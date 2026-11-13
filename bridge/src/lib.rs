//! 星云 × NeoForge JNI 桥接层。
//!
//! Java 宿主只负责事件源与 `世界` 动作落地；本 crate 静态链接 `nebula` 引擎，负责
//! 扫描 `<gameDir>/nebula/<插件名>/`、编译词库、执行生命周期词条、分发事件。
//!
//! `世界` 方法以全局对象注入词库；动作类方法（提示 / 广播 / 执行命令 / 动作栏）
//! 不直接操作服务端，而是记录到本次调用的动作列表，由 Java 在服务端主线程执行。

use std::cell::{Cell, RefCell};
use std::collections::{BTreeMap, HashMap};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use jni::objects::{GlobalRef, JClass, JString, JValue};
use jni::sys::{jlong, jstring};
use jni::{JNIEnv, JavaVM};
use serde_json::json;

use nebula::imports;
use nebula::inputs::DicInputs;
use nebula::interp::call_func_value;
use nebula::model::{DicClass, NasFunc};
use nebula::runner::{dic_run_event_with_p, NebulaDic};
use nebula::util::any_to_string;
use nebula::value::{set_system_var, DicVal, Val, Value as NValue};

const ENTRY_FILE: &str = "main.n";

// ────────────────────────────── 动作收集 ──────────────────────────────

thread_local! {
    /// 本次 Java 调用期间 `世界` 动作方法产生的待执行动作。
    static ACTIONS: RefCell<Vec<serde_json::Value>> = const { RefCell::new(Vec::new()) };
    /// 本次事件的取消标志（`$世界.取消事件$`）。
    static CANCEL: Cell<bool> = const { Cell::new(false) };
    /// 当前正在执行的插件名。动作带上归属后，宿主才能把界面点击回传给打开界面的插件。
    static CURRENT_PLUGIN: RefCell<Option<String>> = const { RefCell::new(None) };
}

fn push_action(mut action: serde_json::Value) {
    if let Some(name) = CURRENT_PLUGIN.with(|p| p.borrow().clone()) {
        if let Some(obj) = action.as_object_mut() {
            obj.insert("插件".into(), serde_json::Value::String(name));
        }
    }
    ACTIONS.with(|a| a.borrow_mut().push(action));
}

fn set_current_plugin(name: Option<&str>) {
    CURRENT_PLUGIN.with(|p| *p.borrow_mut() = name.map(str::to_string));
}

fn current_plugin_name() -> String {
    CURRENT_PLUGIN.with(|p| p.borrow().clone()).unwrap_or_default()
}

/// 词库侧的界面对象：`$世界.创建界面$` 返回 id，后续 `界面物品` / `打开界面` 都带这个 id。
#[derive(Clone)]
struct GuiState {
    plugin: String,
    player: String,
    title: String,
    /// 点击回调；闭包由 `$打开界面 面 回调$` 传入，签名是 `回调:[函数]`。
    callback: Option<NValue>,
}

static GUIS: OnceLock<Mutex<BTreeMap<u64, GuiState>>> = OnceLock::new();
static NEXT_GUI: AtomicU64 = AtomicU64::new(1);

fn guis() -> &'static Mutex<BTreeMap<u64, GuiState>> {
    GUIS.get_or_init(|| Mutex::new(BTreeMap::new()))
}

fn parse_gui_id(raw: &str) -> u64 {
    raw.trim().parse::<u64>().unwrap_or(0)
}

fn take_actions() -> Vec<serde_json::Value> {
    ACTIONS.with(|a| std::mem::take(&mut *a.borrow_mut()))
}

fn begin_call() {
    let _ = take_actions();
    CANCEL.with(|c| c.set(false));
    set_current_plugin(None);
}

fn take_cancel() -> bool {
    CANCEL.with(|c| c.replace(false))
}

// ────────────────────────────── 全局对象 `世界` ──────────────────────────────

type HookResult = std::result::Result<NValue, nebula::EngineError>;

fn ok_empty() -> HookResult {
    Ok(NValue::Str(String::new()))
}

fn hook(f: impl Fn(&mut DicInputs) -> HookResult + Send + Sync + 'static) -> NasFunc {
    Arc::new(f)
}

/// 宿主 JVM 与查询回调类，`create` 时缓存一次。
static JVM: OnceLock<JavaVM> = OnceLock::new();
static MC_API: OnceLock<GlobalRef> = OnceLock::new();

/// 同步调用 Java `McApi.query`，仍在本线程（服务端主线程）的 JNI 调用帧内。
fn java_query(request: &str) -> Option<String> {
    let vm = JVM.get()?;
    let class_ref = MC_API.get()?;
    let mut env = vm.get_env().ok()?;
    let out: jni::errors::Result<String> = env.with_local_frame(8, |env| {
        let arg = env.new_string(request)?;
        let class = JClass::from(env.new_local_ref(class_ref.as_obj())?);
        let result = env.call_static_method(
            class,
            "query",
            "(Ljava/lang/String;)Ljava/lang/String;",
            &[JValue::Object(&arg)],
        )?;
        let obj = result.l()?;
        if obj.as_raw().is_null() {
            return Ok(String::new());
        }
        let text_obj = JString::from(obj);
        let text = env.get_string(&text_obj)?;
        Ok(text.to_string_lossy().into_owned())
    });
    out.ok()
}

/// 玩家参数既接受名字，也接受 `%玩家%` 注入的 JSON 对象。
fn player_name(raw: &str) -> String {
    let text = raw.trim();
    if text.starts_with('{') {
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(text) {
            for key in ["名称", "name"] {
                if let Some(s) = v.get(key).and_then(|x| x.as_str()) {
                    return s.to_string();
                }
            }
        }
        return String::new();
    }
    text.to_string()
}

/// 指令名清洗：允许中文 / 字母 / 数字 / `_` / `-`，去掉前导 `/`。
/// Minecraft 的 Brigadier 节点名不能含空白或路径分隔符。
fn clean_command(raw: &str) -> Option<String> {
    let name = raw.trim().trim_start_matches('/');
    if name.is_empty() || name.len() > 48 {
        return None;
    }
    if name
        .chars()
        .any(|c| c.is_control() || c.is_whitespace() || matches!(c, '/' | '\\'))
    {
        return None;
    }
    Some(name.to_string())
}

/// `取相邻方块 维度 X Y Z [预算]` → 宿主查询请求 JSON。
fn neighbor_request(d: &DicInputs) -> Option<String> {
    let dim = d.string(1);
    if dim.is_empty() {
        return None;
    }
    let budget = match d.int(5) {
        n if n > 0 => n.min(4096),
        _ => 64,
    };
    Some(
        json!({
            "op": "取相邻方块",
            "维度": dim,
            "x": d.int(2),
            "y": d.int(3),
            "z": d.int(4),
            "预算": budget,
        })
        .to_string(),
    )
}

// ────────────────────── 表驱动 mc 方法（映射表批量落地） ──────────────────────
//
// mod/docs/mc-api-map.md 里每个方法都有固定的参数位次。这里把它声明成一张规格表：
// 查询类 → 构造请求 JSON 同步回调 Java McApi.query；动作类 → 压入动作队列，
// 由宿主在服务端主线程落地。宿主侧的 op 名与方法名一致，避免两边各维护一份别名。

#[derive(Clone, Copy)]
enum ArgKind {
    Str,
    Int,
    Float,
    Bool,
    Text,
    Player,
}

#[derive(Clone, Copy)]
struct Field {
    key: &'static str,
    at: usize,
    kind: ArgKind,
}

#[derive(Clone, Copy)]
struct Spec {
    name: &'static str,
    query: bool,
    /// 查询失败（世界未就绪 / 宿主不支持）时的回退值。
    fallback: &'static str,
    fields: &'static [Field],
}

macro_rules! f {
    ($k:literal, $i:literal, $t:ident) => {
        Field { key: $k, at: $i, kind: ArgKind::$t }
    };
}

macro_rules! q {
    ($n:literal, $d:literal $(, $field:expr)* $(,)?) => {
        Spec { name: $n, query: true, fallback: $d, fields: &[$($field),*] }
    };
}

macro_rules! a {
    ($n:literal $(, $field:expr)* $(,)?) => {
        Spec { name: $n, query: false, fallback: "", fields: &[$($field),*] }
    };
}

/// 按规格把词库实参拼成请求 / 动作 JSON。参数位次与 mod/docs/mc-api-map.md 的参数列一致。
fn build_request(spec: &Spec, d: &mut DicInputs) -> serde_json::Value {
    let mut obj = serde_json::Map::new();
    obj.insert("op".into(), json!(spec.name));
    for field in spec.fields {
        let value = match field.kind {
            ArgKind::Str => json!(d.string(field.at)),
            ArgKind::Int => json!(d.int(field.at)),
            ArgKind::Float => json!(d.float(field.at)),
            ArgKind::Bool => json!(matches!(
                d.string(field.at).as_str(),
                "是" | "true" | "1" | "开" | "真" | "yes" | "on"
            )),
            ArgKind::Text => json!(d.inputs.string_after(field.at)),
            ArgKind::Player => json!(player_name(&d.string(field.at))),
        };
        obj.insert(field.key.into(), value);
    }
    serde_json::Value::Object(obj)
}

fn register_table_driven(m: &mut HashMap<String, NasFunc>) {
    let specs: Vec<Spec> = vec![
        // 1. 方块
        q!("取方块", "", f!("维度", 1, Str), f!("x", 2, Int), f!("y", 3, Int), f!("z", 4, Int)),
        q!("取方块数据", "", f!("维度", 1, Str), f!("x", 2, Int), f!("y", 3, Int), f!("z", 4, Int)),
        q!("获取方块", "{}", f!("维度", 1, Str), f!("x", 2, Int), f!("y", 3, Int), f!("z", 4, Int)),
        q!("取方块标签", "[]", f!("维度", 1, Str), f!("x", 2, Int), f!("y", 3, Int), f!("z", 4, Int)),
        a!("设方块", f!("维度", 1, Str), f!("x", 2, Int), f!("y", 3, Int), f!("z", 4, Int), f!("方块", 5, Str)),
        a!("设方块状态", f!("维度", 1, Str), f!("x", 2, Int), f!("y", 3, Int), f!("z", 4, Int), f!("名称", 5, Str), f!("状态", 6, Text)),
        a!("设方块数据", f!("维度", 1, Str), f!("x", 2, Int), f!("y", 3, Int), f!("z", 4, Int), f!("数据", 5, Text)),
        q!("取生物群系", "", f!("维度", 1, Str), f!("x", 2, Int), f!("y", 3, Int), f!("z", 4, Int)),
        a!("设生物群系", f!("维度", 1, Str), f!("x1", 2, Int), f!("z1", 3, Int), f!("x2", 4, Int), f!("z2", 5, Int), f!("群系", 6, Str)),
        a!("填充区域", f!("维度", 1, Str), f!("x1", 2, Int), f!("y1", 3, Int), f!("z1", 4, Int), f!("x2", 5, Int), f!("y2", 6, Int), f!("z2", 7, Int), f!("方块", 8, Str)),
        // 2. 坐标
        q!("取玩家坐标", "{}", f!("玩家", 1, Player)),
        q!("取复活点坐标", "{}"),
        a!("设复活点", f!("x", 1, Int), f!("y", 2, Int), f!("z", 3, Int)),
        // 3. 玩家
        q!("取玩家状态", "{}", f!("玩家", 1, Player)),
        q!("取玩家列表", "[]"),
        q!("取玩家数量", "0"),
        a!("传送玩家", f!("玩家", 1, Player), f!("维度", 2, Str), f!("x", 3, Int), f!("y", 4, Int), f!("z", 5, Int)),
        a!("设置模式", f!("模式", 1, Str), f!("玩家", 2, Player)),
        q!("取玩家模式", "", f!("玩家", 1, Player)),
        q!("取玩家血量", "0", f!("玩家", 1, Player)),
        a!("治疗玩家", f!("玩家", 1, Player), f!("数值", 2, Float)),
        a!("伤害玩家", f!("玩家", 1, Player), f!("数值", 2, Float)),
        a!("击杀玩家", f!("玩家", 1, Player)),
        a!("踢出玩家", f!("玩家", 1, Player), f!("原因", 2, Text)),
        a!("设置侧边栏", f!("玩家", 1, Player), f!("文本", 2, Text)),
        a!("清除侧边栏", f!("玩家", 1, Player)),
        a!("设置等级", f!("玩家", 1, Player), f!("数值", 2, Int)),
        a!("加等级", f!("玩家", 1, Player), f!("数值", 2, Int)),
        a!("加经验", f!("玩家", 1, Player), f!("数值", 2, Int)),
        a!("设置经验进度", f!("玩家", 1, Player), f!("数值", 2, Float)),
        a!("设置饥饿", f!("玩家", 1, Player), f!("数值", 2, Int)),
        a!("设置饱和度", f!("玩家", 1, Player), f!("数值", 2, Float)),
        a!("设置消耗度", f!("玩家", 1, Player), f!("数值", 2, Float)),
        q!("取玩家权限", "0", f!("玩家", 1, Player)),
        a!("设置权限", f!("玩家", 1, Player), f!("等级", 2, Int)),
        a!("给予物品", f!("玩家", 1, Player), f!("物品", 2, Str), f!("数量", 3, Int)),
        a!("丢弃物品", f!("玩家", 1, Player), f!("物品", 2, Str), f!("数量", 3, Int)),
        q!("取手持物品", "{}", f!("玩家", 1, Player)),
        q!("取背包物品", "{}", f!("玩家", 1, Player), f!("槽位", 2, Int)),
        a!("设背包物品", f!("玩家", 1, Player), f!("槽位", 2, Int), f!("物品", 3, Str), f!("数量", 4, Int)),
        a!("清空背包", f!("玩家", 1, Player)),
        q!("取副手物品", "{}", f!("玩家", 1, Player)),
        a!("设置副手", f!("玩家", 1, Player), f!("物品", 2, Str), f!("数量", 3, Int)),
        q!("取装备", "[]", f!("玩家", 1, Player)),
        a!("加效果", f!("玩家", 1, Player), f!("效果", 2, Str), f!("秒", 3, Int), f!("等级", 4, Int)),
        a!("移除效果", f!("玩家", 1, Player), f!("效果", 2, Str)),
        a!("设置能力", f!("玩家", 1, Player), f!("能力", 2, Str), f!("值", 3, Bool)),
        a!("设置飞行", f!("玩家", 1, Player), f!("值", 2, Bool)),
        a!("设置无敌", f!("玩家", 1, Player), f!("值", 2, Bool)),
        a!("设置隐身", f!("玩家", 1, Player), f!("值", 2, Bool)),
        a!("设置名牌", f!("玩家", 1, Player), f!("文本", 2, Text)),
        a!("设置名牌可见", f!("玩家", 1, Player), f!("值", 2, Bool)),
        a!("玩家加标签", f!("玩家", 1, Player), f!("标签", 2, Str)),
        a!("玩家移除标签", f!("玩家", 1, Player), f!("标签", 2, Str)),
        q!("玩家有标签", "否", f!("玩家", 1, Player), f!("标签", 2, Str)),
        a!("设置玩家出生点", f!("玩家", 1, Player), f!("维度", 2, Str), f!("x", 3, Int), f!("y", 4, Int), f!("z", 5, Int)),
        a!("设置区块半径", f!("玩家", 1, Player), f!("数值", 2, Int)),
        a!("重发区块", f!("玩家", 1, Player), f!("数值", 2, Int)),
        a!("打开背包", f!("玩家", 1, Player)),
        a!("播放动作", f!("玩家", 1, Player), f!("动作", 2, Str)),
        a!("骑乘", f!("玩家", 1, Player), f!("实体", 2, Str)),
        a!("取消骑乘", f!("玩家", 1, Player)),
        a!("攻击", f!("玩家", 1, Player), f!("实体", 2, Str)),
        a!("交互", f!("玩家", 1, Player), f!("实体", 2, Str)),
        // 4. 世界 / 服务器
        q!("取时间", "0"),
        a!("设时间", f!("时刻", 1, Int)),
        a!("设天气", f!("天气", 1, Str)),
        a!("更新天气", f!("数值", 1, Int)),
        q!("取难度", ""),
        a!("设难度", f!("难度", 1, Str)),
        q!("取种子", "0"),
        q!("取游戏规则", "", f!("名称", 1, Str)),
        a!("设游戏规则", f!("名称", 1, Str), f!("值", 2, Str)),
        a!("保存世界"),
        q!("取睡眠状态", "否"),
        q!("区块已加载", "否", f!("维度", 1, Str), f!("x", 2, Int), f!("z", 3, Int)),
        q!("取TPS", "0"),
        q!("取MSPT", "0"),
        q!("取服务器tick", "0"),
        q!("取协议版本", "0"),
        q!("取服务器版本", ""),
        q!("取环境变量", "", f!("名称", 1, Str)),
        a!("设环境变量", f!("名称", 1, Str), f!("值", 2, Text)),
        q!("取本地时间", ""),
        // 需 Mixin 的四项：注册占位，宿主明确回报「不支持」，而不是让词库报「未知方法」。
        a!("设置tick冻结", f!("值", 1, Bool)),
        a!("步进tick", f!("数值", 1, Int)),
        a!("设置tick加速", f!("数值", 1, Float)),
        q!("取模拟暂停", "否"),
        // 5. 实体
        a!("生成生物", f!("维度", 1, Str), f!("类型", 2, Str), f!("x", 3, Int), f!("y", 4, Int), f!("z", 5, Int)),
        a!("生成实体", f!("维度", 1, Str), f!("类型", 2, Str), f!("x", 3, Int), f!("y", 4, Int), f!("z", 5, Int), f!("数据", 6, Text)),
        a!("生成粒子", f!("维度", 1, Str), f!("效果", 2, Str), f!("x", 3, Int), f!("y", 4, Int), f!("z", 5, Int)),
        q!("取实体列表", "[]", f!("维度", 1, Str), f!("上限", 2, Int)),
        q!("取实体坐标", "{}", f!("实体", 1, Str)),
        q!("取实体数据", "{}", f!("实体", 1, Str)),
        a!("实体传送", f!("实体", 1, Str), f!("维度", 2, Str), f!("x", 3, Int), f!("y", 4, Int), f!("z", 5, Int)),
        a!("实体伤害", f!("实体", 1, Str), f!("数值", 2, Float)),
        a!("实体治疗", f!("实体", 1, Str), f!("数值", 2, Float)),
        a!("实体击杀", f!("实体", 1, Str)),
        a!("实体移除", f!("实体", 1, Str)),
        a!("实体加标签", f!("实体", 1, Str), f!("标签", 2, Str)),
        a!("实体移除标签", f!("实体", 1, Str), f!("标签", 2, Str)),
        q!("实体有标签", "否", f!("实体", 1, Str), f!("标签", 2, Str)),
        q!("取实体属性", "", f!("实体", 1, Str), f!("属性", 2, Str)),
        a!("实体隐身", f!("实体", 1, Str), f!("值", 2, Bool)),
        // 6.2 显示特效
        a!("悬浮字", f!("维度", 1, Str), f!("x", 2, Float), f!("y", 3, Float), f!("z", 4, Float), f!("文本", 5, Str), f!("缩放", 6, Float), f!("上升", 7, Float), f!("时长", 8, Int)),
    ];

    for spec in specs {
        let name = spec.name.to_string();
        let method: NasFunc = if spec.query {
            hook(move |d| {
                let req = build_request(&spec, d).to_string();
                let out = java_query(&req).unwrap_or_else(|| spec.fallback.to_string());
                Ok(NValue::Str(out))
            })
        } else {
            hook(move |d| {
                push_action(build_request(&spec, d));
                ok_empty()
            })
        };
        m.insert(name, method);
    }
}

fn mc_methods() -> HashMap<String, NasFunc> {
    let mut m: HashMap<String, NasFunc> = HashMap::new();

    // 提示 玩家 文本…（私聊）
    m.insert(
        "提示".into(),
        hook(|d| {
            let player = player_name(&d.string(1));
            if !player.is_empty() {
                push_action(json!({
                    "op": "提示",
                    "玩家": player,
                    "文本": d.inputs.string_after(2),
                }));
            }
            ok_empty()
        }) as NasFunc,
    );

    // 点击消息 玩家 段JSON（一行可点击的聊天：段是 [{文,命令,悬停}] 的 JSON 数组）
    m.insert(
        "点击消息".into(),
        hook(|d| {
            let player = player_name(&d.string(1));
            let seg = d.inputs.string_after(2);
            if player.is_empty() || seg.is_empty() {
                return ok_empty();
            }
            push_action(json!({ "op": "点击消息", "玩家": player, "段": seg }));
            ok_empty()
        }) as NasFunc,
    );

    // 广播 文本…
    m.insert(
        "广播".into(),
        hook(|d| {
            push_action(json!({
                "op": "广播",
                "文本": d.inputs.string_after(1),
            }));
            ok_empty()
        }) as NasFunc,
    );

    // 执行命令 命令…
    m.insert(
        "执行命令".into(),
        hook(|d| {
            push_action(json!({
                "op": "执行命令",
                "命令": d.inputs.string_after(1),
            }));
            ok_empty()
        }) as NasFunc,
    );

    // 动作栏 玩家 文本…
    m.insert(
        "动作栏".into(),
        hook(|d| {
            let player = player_name(&d.string(1));
            if !player.is_empty() {
                push_action(json!({
                    "op": "动作栏",
                    "玩家": player,
                    "文本": d.inputs.string_after(2),
                }));
            }
            ok_empty()
        }) as NasFunc,
    );

    // 取相邻方块 维度 X Y Z [预算] → 26 邻域非空气方块 JSON 数组
    m.insert(
        "取相邻方块".into(),
        hook(|d| {
            let out = neighbor_request(d)
                .and_then(|req| java_query(&req))
                .unwrap_or_else(|| "[]".to_string());
            Ok(NValue::Str(out))
        }) as NasFunc,
    );

    // 破坏方块 维度 X Y Z（由游戏按掉落表清空并生成掉落物）
    m.insert(
        "破坏方块".into(),
        hook(|d| {
            let dim = d.string(1);
            if !dim.is_empty() {
                push_action(json!({
                    "op": "破坏方块",
                    "维度": dim,
                    "x": d.int(2),
                    "y": d.int(3),
                    "z": d.int(4),
                }));
            }
            ok_empty()
        }) as NasFunc,
    );

    // 扣耐久 点数 玩家
    m.insert(
        "扣耐久".into(),
        hook(|d| {
            let points = d.int(1);
            let player = player_name(&d.string(2));
            if points > 0 && !player.is_empty() {
                push_action(json!({
                    "op": "扣耐久",
                    "点数": points,
                    "玩家": player,
                }));
            }
            ok_empty()
        }) as NasFunc,
    );

    // 取物品数 玩家 物品 → 背包（含盔甲 / 副手）里的总数
    m.insert(
        "取物品数".into(),
        hook(|d| {
            let player = player_name(&d.string(1));
            let item = d.string(2);
            if player.is_empty() || item.is_empty() {
                return Ok(NValue::Str("0".into()));
            }
            let req = json!({ "op": "取物品数", "玩家": player, "物品": item }).to_string();
            let out = java_query(&req).unwrap_or_else(|| "0".to_string());
            Ok(NValue::Str(out))
        }) as NasFunc,
    );

    // 界面统一走对象写法：创建界面 / 界面物品 / 界面数据 / 打开界面 / 关闭界面。
    // 宿主侧仍以 清空界面 / 设界面物品 / 设界面数据 / 开界面 / 关界面 作为内部动作 op，
    // 但这些名字不再注册成词库可直接调用的 `世界` 方法。

    // 标题 玩家 主标题 [副标题…]
    m.insert(
        "标题".into(),
        hook(|d| {
            let player = player_name(&d.string(1));
            if !player.is_empty() {
                push_action(json!({
                    "op": "标题",
                    "玩家": player,
                    "主标题": d.string(2),
                    "副标题": d.inputs.string_after(3),
                }));
            }
            ok_empty()
        }) as NasFunc,
    );

    // 音效 玩家 音效 [音量] [音调]
    m.insert(
        "音效".into(),
        hook(|d| {
            let player = player_name(&d.string(1));
            let sound = d.string(2);
            if player.is_empty() || sound.is_empty() {
                return ok_empty();
            }
            let volume = match d.float(3) {
                v if v > 0.0 => v.clamp(0.0, 4.0),
                _ => 1.0,
            };
            let pitch = match d.float(4) {
                v if v > 0.0 => v.clamp(0.0, 4.0),
                _ => 1.0,
            };
            push_action(json!({
                "op": "音效",
                "玩家": player,
                "音效": sound,
                "音量": volume,
                "音调": pitch,
            }));
            ok_empty()
        }) as NasFunc,
    );

    // 给物品 玩家 物品 [数量]
    m.insert(
        "给物品".into(),
        hook(|d| {
            let player = player_name(&d.string(1));
            let item = d.string(2);
            if player.is_empty() || item.is_empty() {
                return ok_empty();
            }
            let count = match d.int(3) {
                n if n > 0 => n.min(4096),
                _ => 1,
            };
            push_action(json!({
                "op": "给物品",
                "玩家": player,
                "物品": item,
                "数量": count,
            }));
            ok_empty()
        }) as NasFunc,
    );

    // 扣物品 玩家 物品 [数量]
    m.insert(
        "扣物品".into(),
        hook(|d| {
            let player = player_name(&d.string(1));
            let item = d.string(2);
            if player.is_empty() || item.is_empty() {
                return ok_empty();
            }
            let count = match d.int(3) {
                n if n > 0 => n.min(4096),
                _ => 1,
            };
            push_action(json!({
                "op": "扣物品",
                "玩家": player,
                "物品": item,
                "数量": count,
            }));
            ok_empty()
        }) as NasFunc,
    );

    // 创建界面 玩家 行数 标题… → 返回界面 id；后续操作都带这个 id
    m.insert(
        "创建界面".into(),
        hook(|d| {
            let player = player_name(&d.string(1));
            let plugin = current_plugin_name();
            if player.is_empty() || plugin.is_empty() {
                return ok_empty();
            }
            let rows = match d.int(2) {
                n if (1..=6).contains(&n) => n,
                _ => 3,
            };
            let title = d.inputs.string_after(3);
            let id = NEXT_GUI.fetch_add(1, Ordering::SeqCst);
            guis().lock().unwrap_or_else(|e| e.into_inner()).insert(
                id,
                GuiState {
                    plugin,
                    player: player.clone(),
                    title: if title.is_empty() {
                        "界面".to_string()
                    } else {
                        title
                    },
                    callback: None,
                },
            );
            push_action(json!({ "op": "清空界面", "玩家": player, "行数": rows }));
            Ok(NValue::Str(id.to_string()))
        }) as NasFunc,
    );

    // 界面物品 面 槽位 物品 数量 名称 说明 [发光]
    m.insert(
        "界面物品".into(),
        hook(|d| {
            let id = parse_gui_id(&d.string(1));
            let slot = d.int(2);
            let item = d.string(3);
            if id == 0 || item.is_empty() || !(0..=53).contains(&slot) {
                return ok_empty();
            }
            let player = {
                let map = guis().lock().unwrap_or_else(|e| e.into_inner());
                let Some(gui) = map.get(&id) else {
                    return ok_empty();
                };
                if gui.plugin != current_plugin_name() {
                    return ok_empty();
                }
                gui.player.clone()
            };
            let count = d.int(4).clamp(1, 64);
            let glow = matches!(d.string(7).as_str(), "是" | "true" | "1");
            push_action(json!({
                "op": "设界面物品",
                "玩家": player,
                "槽位": slot,
                "物品": item,
                "数量": count,
                "名称": d.string(5),
                "说明": d.string(6),
                "发光": glow,
            }));
            ok_empty()
        }) as NasFunc,
    );

    // 界面数据 面 数据…
    m.insert(
        "界面数据".into(),
        hook(|d| {
            let id = parse_gui_id(&d.string(1));
            if id == 0 {
                return ok_empty();
            }
            let player = {
                let map = guis().lock().unwrap_or_else(|e| e.into_inner());
                let Some(gui) = map.get(&id) else {
                    return ok_empty();
                };
                if gui.plugin != current_plugin_name() {
                    return ok_empty();
                }
                gui.player.clone()
            };
            push_action(json!({
                "op": "设界面数据",
                "玩家": player,
                "数据": d.inputs.string_after(2),
            }));
            ok_empty()
        }) as NasFunc,
    );

    // 打开界面 面 回调（`回调:[函数]` 的函数值；点击时以 JSON payload 作为 %参数1% 调用）
    m.insert(
        "打开界面".into(),
        hook(|d| {
            let id = parse_gui_id(&d.string(1));
            let callback = d.inputs.get_cloned(2);
            if id == 0 || !matches!(callback, NValue::Func(_)) {
                return ok_empty();
            }
            let plugin = current_plugin_name();
            let info = {
                let mut map = guis().lock().unwrap_or_else(|e| e.into_inner());
                let Some(gui) = map.get_mut(&id) else {
                    return ok_empty();
                };
                if gui.plugin != plugin {
                    return ok_empty();
                }
                gui.callback = Some(callback);
                let player = gui.player.clone();
                let title = gui.title.clone();
                // 同一个玩家只保留当前界面的回调，旧界面被替换后不再触发。
                map.retain(|other, g| *other == id || g.player != player);
                (player, title)
            };
            push_action(json!({
                "op": "开界面",
                "玩家": info.0,
                "标题": info.1,
                "界面ID": id,
            }));
            ok_empty()
        }) as NasFunc,
    );

    // 输入界面 玩家 标题 回调（铁砧输入框：提交时以 {玩家名,文本,动作} 调用闭包）
    m.insert(
        "输入界面".into(),
        hook(|d| {
            let player = player_name(&d.string(1));
            let plugin = current_plugin_name();
            if player.is_empty() || plugin.is_empty() {
                return ok_empty();
            }
            let callback = d.inputs.get_cloned(3);
            if !matches!(callback, NValue::Func(_)) {
                return ok_empty();
            }
            let title = d.string(2);
            let id = NEXT_GUI.fetch_add(1, Ordering::SeqCst);
            {
                let mut map = guis().lock().unwrap_or_else(|e| e.into_inner());
                map.insert(
                    id,
                    GuiState {
                        plugin,
                        player: player.clone(),
                        title: title.clone(),
                        callback: Some(callback),
                    },
                );
                // 同一个玩家只保留最后一个界面对象，和 打开界面 的语义一致。
                map.retain(|other, g| *other == id || g.player != player);
            }
            push_action(json!({
                "op": "输入界面",
                "玩家": player,
                "标题": title,
                "界面ID": id,
            }));
            Ok(NValue::Str(id.to_string()))
        }) as NasFunc,
    );

    // 关闭界面 面
    m.insert(
        "关闭界面".into(),
        hook(|d| {
            let id = parse_gui_id(&d.string(1));
            if id == 0 {
                return ok_empty();
            }
            let plugin = current_plugin_name();
            let player = {
                let mut map = guis().lock().unwrap_or_else(|e| e.into_inner());
                let Some(gui) = map.get(&id) else {
                    return ok_empty();
                };
                if gui.plugin != plugin {
                    return ok_empty();
                }
                let player = gui.player.clone();
                map.remove(&id);
                player
            };
            push_action(json!({ "op": "关界面", "玩家": player }));
            ok_empty()
        }) as NasFunc,
    );

    // 注册指令 指令 [权限] 别名…（权限为单个 0~4 数字；不写默认 0）
    m.insert(
        "注册指令".into(),
        hook(|d| {
            let Some(command) = clean_command(&d.string(1)) else {
                return ok_empty();
            };
            let mut index = 2;
            let mut permission = 0i64;
            let second = d.string(2);
            if second.len() == 1 && second.as_bytes()[0].is_ascii_digit() {
                permission = second.parse::<i64>().unwrap_or(0).clamp(0, 4);
                index = 3;
            }
            let mut aliases: Vec<String> = Vec::new();
            for i in index..=d.inputs.len() {
                if let Some(name) = clean_command(&d.string(i)) {
                    if name != command && !aliases.contains(&name) {
                        aliases.push(name);
                    }
                }
            }
            push_action(json!({
                "op": "注册指令",
                "指令": command,
                "别名": aliases,
                "权限": permission,
            }));
            ok_empty()
        }) as NasFunc,
    );

    // 注销指令 指令 别名…
    m.insert(
        "注销指令".into(),
        hook(|d| {
            let Some(command) = clean_command(&d.string(1)) else {
                return ok_empty();
            };
            let mut aliases: Vec<String> = Vec::new();
            for i in 2..=d.inputs.len() {
                if let Some(name) = clean_command(&d.string(i)) {
                    if name != command && !aliases.contains(&name) {
                        aliases.push(name);
                    }
                }
            }
            push_action(json!({
                "op": "注销指令",
                "指令": command,
                "别名": aliases,
            }));
            ok_empty()
        }) as NasFunc,
    );

    // 取消事件
    m.insert(
        "取消事件".into(),
        hook(|_d| {
            CANCEL.with(|c| c.set(true));
            ok_empty()
        }) as NasFunc,
    );

    // 映射表里其余方法由规格表批量生成；已有的复杂方法（界面对象 / 指令注册）优先，
    // 不会被同名条目覆盖语义。
    register_table_driven(&mut m);
    m
}

/// 把编译期写入的源码指纹留在 .rodata，供构建/回归脚本从 dll 字节里读取。
///
/// `build.rs` 在编译时算出 `src/**/*.rs` 与 `Cargo.toml` 的逐文件 SHA256 并经
/// `cargo:rustc-env` 注入。这个字符串是「产物由哪份源码编出」的一手证据，
/// 不依赖任何事后生成的清单文件；格式 `NEBULA_SOURCES=<路径>:<哈希>;…`。
#[no_mangle]
pub extern "C" fn nebula_embedded_sources() -> *const std::os::raw::c_char {
    concat!("NEBULA_SOURCES=", env!("NEBULA_EMBEDDED_SOURCES"), "\0")
        .as_ptr()
        .cast()
}

/// 把方法表发布成注入类 `世界` 与系统变量 `世界`：词库直接 `$世界.提示 …$` 即可用。
fn publish_world() {
    static ONCE: OnceLock<()> = OnceLock::new();
    if ONCE.set(()).is_err() {
        return;
    }
    let methods = mc_methods();
    let local_value = Val::arc();
    local_value.set_class_name(Some("世界".to_string()));
    let instance = Arc::new(DicClass {
        local_value,
        dic_funcs: Arc::new(HashMap::new()),
        fn_map: Arc::new(methods.clone()),
    });
    imports::register_injected_class("世界", methods);
    set_system_var("世界", NValue::Class(instance));
}

// ────────────────────────────── 插件注册表 ──────────────────────────────

#[derive(Clone)]
struct Plugin {
    name: String,
    entry: PathBuf,
    dic: Arc<NebulaDic>,
    entries: usize,
    state: Arc<Val>,
    /// `/nebula debug` 开启后，词库文件一改就在下次事件前自动重编译。
    debug: bool,
    /// 编译时的词库文件指纹（名字 + 修改时间），用来判断有没有改动。
    stamp: u64,
}

struct Bridge {
    root: PathBuf,
    plugins: Mutex<BTreeMap<String, Plugin>>,
}

impl Bridge {
    fn loaded(&self) -> Vec<Plugin> {
        let reg = self.plugins.lock().unwrap_or_else(|e| e.into_inner());
        reg.values().cloned().collect()
    }
}

// ────────────────────────────── 目录 / 编译 ──────────────────────────────

fn validate_name(name: &str) -> Result<(), String> {
    if name.is_empty() {
        return Err("插件名不能为空".into());
    }
    if name.starts_with('-') {
        return Err(format!("插件名不能以 - 开头（--all 是保留字）：{name}"));
    }
    if name.chars().any(|c| matches!(c, '/' | '\\' | '\0')) || name == ".." || name == "." {
        return Err(format!("非法插件名：{name}（不能含路径分隔符）"));
    }
    Ok(())
}

fn is_all(word: &str) -> bool {
    matches!(word, "--all" | "all" | "*" | "全部" | "所有")
}

/// 挑出插件入口词库：main.n 优先；否则允许目录里唯一的一个 .n 文件。
fn plugin_entry(dir: &Path) -> Result<PathBuf, String> {
    let main = dir.join(ENTRY_FILE);
    if main.is_file() {
        return Ok(main);
    }
    let mut files: Vec<PathBuf> = std::fs::read_dir(dir)
        .map_err(|e| format!("读取 {} 失败：{e}", dir.display()))?
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| {
            p.is_file()
                && p.extension()
                    .is_some_and(|x| x.to_string_lossy().eq_ignore_ascii_case("n"))
        })
        .collect();
    files.sort();
    match files.len() {
        0 => Err(format!("插件目录 {} 没有 main.n 或任何 .n 词库", dir.display())),
        1 => Ok(files.remove(0)),
        _ => Err(format!(
            "插件目录 {} 有多个 .n 文件但缺少 main.n，请补 main.n 作入口",
            dir.display()
        )),
    }
}

/// 扫描插件根目录，返回 (插件名, 目录)，按名字排序。
fn scan_plugins(root: &Path) -> Vec<(String, PathBuf)> {
    let Ok(rd) = std::fs::read_dir(root) else {
        return Vec::new();
    };
    let mut out: Vec<(String, PathBuf)> = rd
        .flatten()
        .filter_map(|e| {
            let path = e.path();
            if !path.is_dir() {
                return None;
            }
            let name = e.file_name().to_string_lossy().into_owned();
            if name.starts_with('.') || name.starts_with('_') {
                return None;
            }
            Some((name, path))
        })
        .collect();
    out.sort_by(|a, b| a.0.cmp(&b.0));
    out
}

/// 只数插件自己声明的词条，排除 `nebula::init()` 注入的内置函数。
fn count_entries(dic: &NebulaDic, dir: &Path) -> usize {
    let declared = dic
        .data
        .dic_funcs
        .values()
        .flatten()
        .filter(|e| Path::new(e.source_path.as_ref()).parent() == Some(dir))
        .count();
    dic.data.dic.len() + declared
}

/// 插件目录里全部 `.n` / `.json` 文件的「文件名 + 修改时间」指纹。
///
/// 调试模式下每次事件前比一次：值变了就重编译。`.json` 也纳入指纹，改配置 / 商品目录
/// 后不用手动 reload。只会读顶层平铺文件，与 `build_dic_from_dir` 的收集范围一致。
fn plugin_stamp(dir: &Path) -> u64 {
    use std::hash::{Hash, Hasher};

    let mut files: Vec<(String, u128)> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(dir) {
        for entry in rd.flatten() {
            let path = entry.path();
            if !path.is_file() {
                continue;
            }
            let is_tracked = path.extension().is_some_and(|x| {
                let ext = x.to_string_lossy();
                ext.eq_ignore_ascii_case("n") || ext.eq_ignore_ascii_case("json")
            });
            if !is_tracked {
                continue;
            }
            let mtime = std::fs::metadata(&path)
                .and_then(|m| m.modified())
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_nanos())
                .unwrap_or(0);
            files.push((entry.file_name().to_string_lossy().into_owned(), mtime));
        }
    }
    files.sort();
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    files.hash(&mut hasher);
    hasher.finish()
}

/// 编译插件：main.n 存在时合并目录顶层全部 .n；否则编译单文件。
fn load_plugin_dic(dir: &Path, entry: &Path) -> Result<NebulaDic, String> {
    if entry
        .file_name()
        .is_some_and(|n| n.to_string_lossy().eq_ignore_ascii_case(ENTRY_FILE))
    {
        let build = imports::build_dic_from_dir(dir).map_err(|e| e.to_string())?;
        Ok(NebulaDic::from_build_value(&entry.to_string_lossy(), build))
    } else {
        NebulaDic::from_file(&entry.to_string_lossy()).map_err(|e| e.to_string())
    }
}

fn has_trigger(dic: &NebulaDic, trigger: &str) -> bool {
    if dic.data.dic.iter().any(|e| e.trigger == trigger) {
        return true;
    }
    ["函数", "公开函数"].iter().any(|category| {
        dic.data
            .dic_funcs
            .get(*category)
            .is_some_and(|v| v.iter().any(|e| e.owner.is_none() && e.trigger == trigger))
    })
}

fn has_event(dic: &NebulaDic, section: &str, trigger: &str) -> bool {
    dic.data
        .dic_funcs
        .get(section)
        .is_some_and(|v| v.iter().any(|e| e.trigger == trigger))
}

/// 在插件常驻作用域里执行一个 `[f]` / `[g]` 词条。
fn run_plugin_trigger(plugin: &Plugin, trigger: &str) -> Option<String> {
    if !has_trigger(&plugin.dic, trigger) {
        return None;
    }
    for section in ["函数", "公开函数"] {
        if !has_event(&plugin.dic, section, trigger) {
            continue;
        }
        set_current_plugin(Some(&plugin.name));
        let out = dic_run_event_with_p(&plugin.dic, section, trigger, plugin.state.clone());
        set_current_plugin(None);
        return Some(one_line(&out));
    }
    None
}

fn run_hook(plugin: &Plugin, trigger: &str) -> Option<String> {
    let out = run_plugin_trigger(plugin, trigger)?;
    (!out.is_empty()).then_some(out)
}

fn one_line(s: &str) -> String {
    s.replace(['\r', '\n'], " ").trim().to_string()
}

fn compile_plugin(name: &str, dir: &Path) -> Result<Plugin, String> {
    let entry = plugin_entry(dir)?;
    let dic = load_plugin_dic(dir, &entry)
        .map_err(|e| format!("编译插件 {name}（{}）失败：{e}", entry.display()))?;
    let entries = count_entries(&dic, dir);
    let plugin = Plugin {
        name: name.to_string(),
        entry,
        dic: Arc::new(dic),
        entries,
        state: Val::arc(),
        debug: false,
        stamp: plugin_stamp(dir),
    };
    // 词库（含函数帧）从词库 root 读取 `%库目录%`，用来加载同目录下的配置 / 商品 JSON。
    plugin
        .dic
        .val
        .root()
        .set("库目录", NValue::Str(dir.to_string_lossy().into_owned()));
    for trigger in ["@头部", "初始化"] {
        if let Some(out) = run_plugin_trigger(&plugin, trigger) {
            if !out.is_empty() {
                println!("[星云] 插件 {} 的 {trigger}：{out}", plugin.name);
            }
        }
    }
    Ok(plugin)
}

// ────────────────────────────── 插件操作 ──────────────────────────────

fn op_load(state: &Bridge, name: &str) -> Result<String, String> {
    if state
        .plugins
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .contains_key(name)
    {
        return Ok(format!("插件 {name} 已加载，跳过"));
    }
    let dir = scan_plugins(&state.root)
        .into_iter()
        .find(|(n, _)| n == name)
        .map(|(_, d)| d)
        .ok_or_else(|| {
            format!(
                "找不到插件「{name}」：目录 {} 下不存在或被禁用",
                state.root.display()
            )
        })?;
    let plugin = compile_plugin(name, &dir)?;
    let hook = run_hook(&plugin, "加载")
        .map(|s| format!("（{s}）"))
        .unwrap_or_default();
    state
        .plugins
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .insert(name.to_string(), plugin.clone());
    Ok(format!(
        "已加载 {name}（{} 词条，{}）{hook}",
        plugin.entries,
        plugin.entry.display()
    ))
}

fn op_reload(state: &Bridge, name: &str) -> Result<String, String> {
    let (dir, debug) = {
        let reg = state.plugins.lock().unwrap_or_else(|e| e.into_inner());
        let old = reg
            .get(name)
            .ok_or_else(|| format!("插件 {name} 未加载：请先 /nebula load {name}"))?;
        (
            old.entry
                .parent()
                .unwrap_or_else(|| Path::new("."))
                .to_path_buf(),
            old.debug,
        )
    };
    // 先编译成功再替换：词库写坏时旧版本继续可用。
    let mut plugin = compile_plugin(name, &dir)?;
    plugin.debug = debug;
    let hook = run_hook(&plugin, "重载")
        .map(|s| format!("（{s}）"))
        .unwrap_or_default();
    state
        .plugins
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .insert(name.to_string(), plugin.clone());
    Ok(format!("已重载 {name}（{} 词条）{hook}", plugin.entries))
}

/// `/nebula debug` / `undebug`：开关调试自动重编译。
fn op_debug(state: &Bridge, name: &str, on: bool) -> Result<String, String> {
    let mut reg = state.plugins.lock().unwrap_or_else(|e| e.into_inner());
    let plugin = reg
        .get_mut(name)
        .ok_or_else(|| format!("插件 {name} 未加载：请先 /nebula load {name}"))?;
    plugin.debug = on;
    if on {
        // 以当前文件为基线：刚开启时不该立刻触发一次重载。
        if let Some(dir) = plugin.entry.parent() {
            plugin.stamp = plugin_stamp(dir);
        }
        Ok(format!(
            "已开启调试 {name}：改词库文件后，下次事件自动重载（/nebula undebug {name} 关闭）"
        ))
    } else {
        Ok(format!("已关闭调试 {name}：改词库后请手动 /nebula reload {name}"))
    }
}

/// 调试插件的词库文件变了就重编译；失败保留旧版本，并记住这次的指纹避免每次事件都报错。
fn refresh_debug_plugin(state: &Bridge, plugin: &Plugin) -> Plugin {
    if !plugin.debug {
        return plugin.clone();
    }
    let Some(dir) = plugin.entry.parent() else {
        return plugin.clone();
    };
    let stamp = plugin_stamp(dir);
    if stamp == plugin.stamp {
        return plugin.clone();
    }
    let mut reg = state.plugins.lock().unwrap_or_else(|e| e.into_inner());
    let Some(current) = reg.get(&plugin.name).cloned() else {
        return plugin.clone();
    };
    if current.stamp == stamp {
        // 同一次改动已经被别的线程/事件重载过了。
        return current;
    }
    match compile_plugin(&current.name, dir) {
        Ok(mut fresh) => {
            fresh.debug = true;
            fresh.stamp = stamp;
            println!(
                "[星云] 调试重载 {}：检测到词库改动，{} 词条",
                fresh.name, fresh.entries
            );
            reg.insert(fresh.name.clone(), fresh.clone());
            fresh
        }
        Err(e) => {
            eprintln!("[星云] 调试重载 {} 失败，继续用旧版本：{e}", current.name);
            let mut failed = current;
            failed.stamp = stamp;
            reg.insert(failed.name.clone(), failed.clone());
            failed
        }
    }
}

fn op_unload(state: &Bridge, name: &str) -> Result<String, String> {
    let removed = state
        .plugins
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .remove(name);
    match removed {
        None => Ok(format!("插件 {name} 未加载，跳过")),
        Some(p) => {
            let hook = run_hook(&p, "卸载")
                .map(|s| format!("（{s}）"))
                .unwrap_or_default();
            // 插件卸载后它的界面回调不能再触发。
            guis()
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .retain(|_, g| g.plugin != name);
            Ok(format!("已卸载 {name}{hook}"))
        }
    }
}

fn target_names(state: &Bridge, target: &str, op: &str) -> Result<Vec<String>, String> {
    if is_all(target) {
        return Ok(match op {
            "load" => scan_plugins(&state.root).into_iter().map(|(n, _)| n).collect(),
            _ => state
                .plugins
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .keys()
                .cloned()
                .collect(),
        });
    }
    validate_name(target)?;
    Ok(vec![target.to_string()])
}

fn apply_op(state: &Bridge, op: &str, target: &str) -> String {
    let names = match target_names(state, target, op) {
        Ok(n) => n,
        Err(e) => {
            return json!({ "summary": e, "ok": false, "actions": take_actions() }).to_string()
        }
    };
    let mut done: Vec<String> = Vec::new();
    let mut failed: Vec<String> = Vec::new();
    for name in &names {
        let result = match op {
            "load" => op_load(state, name),
            "reload" => op_reload(state, name),
            "debug" => op_debug(state, name, true),
            "undebug" => op_debug(state, name, false),
            _ => op_unload(state, name),
        };
        match result {
            Ok(msg) => done.push(msg),
            Err(e) => {
                eprintln!("[星云] {e}");
                failed.push(e);
            }
        }
    }
    let label = match op {
        "load" => "加载",
        "reload" => "重载",
        "debug" => "开启调试",
        "undebug" => "关闭调试",
        _ => "卸载",
    };
    let mut summary = if done.len() == 1 && names.len() == 1 {
        done.first().cloned().unwrap_or_default()
    } else {
        format!("{label}：成功 {} 个", done.len())
    };
    if !failed.is_empty() {
        summary.push_str(&format!("，失败 {} 个：{}", failed.len(), failed.join("；")));
    }
    json!({
        "summary": one_line(&summary),
        "ok": failed.is_empty(),
        "actions": take_actions(),
    })
    .to_string()
}

fn load_all(state: &Bridge) -> String {
    let mut map = BTreeMap::new();
    let mut done: Vec<String> = Vec::new();
    let mut failed: Vec<String> = Vec::new();
    for (name, dir) in scan_plugins(&state.root) {
        match compile_plugin(&name, &dir) {
            Ok(plugin) => {
                let hook = run_hook(&plugin, "加载")
                    .map(|s| format!("（{s}）"))
                    .unwrap_or_default();
                done.push(format!("{name}（{} 词条）{hook}", plugin.entries));
                map.insert(name, plugin);
            }
            Err(e) => {
                eprintln!("[星云] {e}");
                failed.push(name);
            }
        }
    }
    *state.plugins.lock().unwrap_or_else(|e| e.into_inner()) = map;
    let mut summary = if done.is_empty() {
        format!(
            "插件目录 {} 下没有可加载的插件",
            state.root.display()
        )
    } else {
        format!("已加载 {} 个插件：{}", done.len(), done.join("，"))
    };
    if !failed.is_empty() {
        summary.push_str(&format!("；失败：{}", failed.join("，")));
    }
    json!({
        "summary": one_line(&summary),
        "ok": failed.is_empty(),
        "actions": take_actions(),
    })
    .to_string()
}

fn list_plugins(state: &Bridge) -> String {
    let found = scan_plugins(&state.root);
    let reg = state.plugins.lock().unwrap_or_else(|e| e.into_inner());
    let mut parts = Vec::new();
    for (name, dir) in &found {
        match reg.get(name) {
            Some(p) => {
                let mut tag = format!("{name}[已加载·{}词条", p.entries);
                if p.debug {
                    tag.push_str("·调试中");
                }
                tag.push(']');
                parts.push(tag);
            }
            None => match plugin_entry(dir) {
                Ok(_) => parts.push(format!("{name}[未加载]")),
                Err(e) => parts.push(format!("{name}[不可用：{e}]")),
            },
        }
    }
    for name in reg.keys() {
        if !found.iter().any(|(n, _)| n == name) {
            parts.push(format!("{name}[已加载·目录缺失]"));
        }
    }
    json!({
        "summary": format!("插件 已加载{}/共{}：{}", reg.len(), found.len(), parts.join("，")),
        "ok": true,
        "actions": take_actions(),
    })
    .to_string()
}

fn dispatch_event(state: &Bridge, trigger: &str, payload: &str) -> String {
    let vars = parse_payload(payload);
    let mut outputs = Vec::new();
    for plugin in state.loaded() {
        // 调试插件：词库文件改过就先重编译，事件用的永远是最新版本。
        let plugin = refresh_debug_plugin(state, &plugin);
        if !has_event(&plugin.dic, "事件", trigger) {
            continue;
        }
        // 事件用独立调用帧，父作用域是插件常驻 state。
        let scope = Val::arc();
        scope.set_parent(Some(plugin.state.clone()));
        scope.set_call_frame(true);
        scope.set("插件", NValue::Str(plugin.name.clone()));
        for (k, v) in &vars {
            scope.set(k, NValue::Str(v.clone()));
        }
        set_current_plugin(Some(&plugin.name));
        let raw = one_line(&dic_run_event_with_p(&plugin.dic, "事件", trigger, scope));
        set_current_plugin(None);
        if !raw.is_empty() {
            outputs.push(json!({ "插件": plugin.name, "文本": raw }));
        }
    }
    json!({
        "outputs": outputs,
        "actions": take_actions(),
        "cancel": take_cancel(),
    })
    .to_string()
}

/// 按名字取出插件快照（指令定向分发用）。
fn find_plugin(state: &Bridge, name: &str) -> Option<Plugin> {
    state
        .plugins
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(name)
        .cloned()
}

/// 在一个插件里按「词条类别 + 触发词」跑一段词条；没有该词条返回 None。
fn run_section(
    plugin: &Plugin,
    section: &str,
    trigger: &str,
    vars: &[(String, String)],
) -> Option<String> {
    if !has_event(&plugin.dic, section, trigger) {
        return None;
    }
    let scope = Val::arc();
    scope.set_parent(Some(plugin.state.clone()));
    scope.set_call_frame(true);
    scope.set("插件", NValue::Str(plugin.name.clone()));
    for (k, v) in vars {
        scope.set(k, NValue::Str(v.clone()));
    }
    set_current_plugin(Some(&plugin.name));
    let raw = one_line(&dic_run_event_with_p(&plugin.dic, section, trigger, scope));
    set_current_plugin(None);
    Some(raw)
}

fn outputs_json(plugin: &Plugin, raw: &str) -> Vec<serde_json::Value> {
    if raw.is_empty() {
        Vec::new()
    } else {
        vec![json!({ "插件": plugin.name, "文本": raw })]
    }
}

/// 按指令名分发：只匹配 `[指令]商店|shop`（`|` / `,` 分隔别名）。
/// 插件没有对应 `[指令]` 词条时直接返回 ok=false，不再回退旧的统一事件。
fn dispatch_command(state: &Bridge, plugin_name: &str, command: &str, payload: &str) -> String {
    let vars = parse_payload(payload);
    let Some(plugin) = find_plugin(state, plugin_name) else {
        return json!({
            "ok": false,
            "summary": format!("插件「{plugin_name}」未加载"),
            "outputs": [],
            "actions": take_actions(),
            "cancel": false,
        })
        .to_string();
    };
    let plugin = refresh_debug_plugin(state, &plugin);
    let hit = plugin.dic.data.dic_funcs.get("指令").and_then(|list| {
        list.iter()
            .find(|entry| {
                entry
                    .trigger
                    .split(|c: char| c == '|' || c == ',' || c == '，')
                    .any(|name| name.trim().trim_start_matches('/') == command)
            })
            .map(|entry| entry.trigger.clone())
    });
    let Some(trigger) = hit else {
        return json!({
            "ok": false,
            "summary": format!(
                "插件「{}」没有处理指令 /{}：请写 [指令]{}",
                plugin.name, command, command
            ),
            "outputs": [],
            "actions": take_actions(),
            "cancel": false,
        })
        .to_string();
    };
    let Some(raw) = run_section(&plugin, "指令", &trigger, &vars) else {
        return json!({
            "ok": false,
            "summary": format!(
                "插件「{}」没有处理指令 /{}：请写 [指令]{}",
                plugin.name, command, command
            ),
            "outputs": [],
            "actions": take_actions(),
            "cancel": false,
        })
        .to_string();
    };
    json!({
        "ok": true,
        "outputs": outputs_json(&plugin, &raw),
        "actions": take_actions(),
        "cancel": take_cancel(),
    })
    .to_string()
}

/// 点击界面对象：调用 `$世界.打开界面` 存下的闭包，payload 作为 `%参数1%` 传入。
fn click_gui(state: &Bridge, gui_id: u64, payload: &str) -> String {
    let gui = guis()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(&gui_id)
        .cloned();
    let Some(gui) = gui else {
        return json!({ "ok": false, "summary": "界面已关闭", "outputs": [], "actions": take_actions() })
            .to_string();
    };
    let Some(NValue::Func(callback)) = gui.callback.clone() else {
        return json!({ "ok": false, "summary": "界面没有点击回调", "outputs": [], "actions": take_actions() })
            .to_string();
    };
    let Some(plugin) = find_plugin(state, &gui.plugin) else {
        return json!({
            "ok": false,
            "summary": format!("插件「{}」未加载", gui.plugin),
            "outputs": [],
            "actions": take_actions(),
        })
        .to_string();
    };
    let plugin = refresh_debug_plugin(state, &plugin);
    let scope = DicVal {
        scope: plugin.state.clone(),
    };
    set_current_plugin(Some(&plugin.name));
    let res = call_func_value(
        &plugin.dic.data,
        &scope,
        &callback,
        vec![NValue::Str(payload.to_string())],
    );
    set_current_plugin(None);
    let (text, ok, summary) = match res {
        Ok(value) => (any_to_string(&value), true, String::new()),
        Err(e) => (String::new(), false, e),
    };
    let outputs = if text.is_empty() {
        Vec::new()
    } else {
        vec![json!({ "插件": plugin.name, "文本": text })]
    };
    json!({
        "ok": ok,
        "summary": summary,
        "outputs": outputs,
        "actions": take_actions(),
        "cancel": false,
    })
    .to_string()
}

fn close_gui(gui_id: u64) {
    guis().lock().unwrap_or_else(|e| e.into_inner()).remove(&gui_id);
}

fn parse_payload(payload: &str) -> Vec<(String, String)> {
    let Ok(value) = serde_json::from_str::<serde_json::Value>(payload) else {
        return Vec::new();
    };
    let Some(obj) = value.as_object() else {
        return Vec::new();
    };
    obj.iter()
        .map(|(k, v)| {
            let text = match v {
                serde_json::Value::String(s) => s.clone(),
                other => other.to_string(),
            };
            (k.clone(), text)
        })
        .collect()
}

// ────────────────────────────── JNI 入口 ──────────────────────────────

fn jstring_to_string(env: &mut JNIEnv<'_>, s: &JString<'_>) -> String {
    match env.get_string(s) {
        Ok(v) => v.to_string_lossy().into_owned(),
        Err(_) => String::new(),
    }
}

fn ret(env: &mut JNIEnv<'_>, out: String) -> jstring {
    match env.new_string(out) {
        Ok(v) => v.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn guard(f: impl FnOnce() -> String) -> String {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f))
        .unwrap_or_else(|_| json!({ "ok": false, "summary": "桥接层内部异常（panic 已捕获）" }).to_string())
}

unsafe fn state<'a>(handle: jlong) -> Option<&'a Bridge> {
    if handle == 0 {
        return None;
    }
    let ptr = handle as *mut Bridge;
    if ptr.is_null() {
        None
    } else {
        Some(&*ptr)
    }
}

fn no_handle() -> String {
    json!({ "ok": false, "summary": "桥接层未初始化" }).to_string()
}

/// 词库订阅的全部事件名（去重、排序）：宿主据此按需注册 NeoForge 监听器。
/// 没有插件写 `[事件]Xxx` 时返回空数组，宿主不会挂任何额外监听器。
fn event_triggers(state: &Bridge) -> String {
    let mut names = std::collections::BTreeSet::new();
    for plugin in state.loaded() {
        if let Some(entries) = plugin.dic.data.dic_funcs.get("事件") {
            for entry in entries {
                if !entry.trigger.is_empty() {
                    names.insert(entry.trigger.clone());
                }
            }
        }
    }
    serde_json::to_string(&names.into_iter().collect::<Vec<String>>())
        .unwrap_or_else(|_| "[]".to_string())
}

/// 供宿主刷新「事件名 → 监听器」订阅表：只在 load / reload / unload / debug 后调用。
#[no_mangle]
pub extern "system" fn Java_nebula_neoforge_NativeBridge_eventTriggers(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let out = guard(|| unsafe {
        begin_call();
        match state(handle) {
            Some(s) => event_triggers(s),
            None => "[]".to_string(),
        }
    });
    ret(&mut env, out)
}

/// 按指令名分发（只匹配 `[指令]名|别名`）。
#[no_mangle]
pub extern "system" fn Java_nebula_neoforge_NativeBridge_dispatchCommand(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    plugin: JString<'_>,
    command: JString<'_>,
    payload: JString<'_>,
) -> jstring {
    let plugin = jstring_to_string(&mut env, &plugin);
    let command = jstring_to_string(&mut env, &command);
    let payload = jstring_to_string(&mut env, &payload);
    let out = guard(|| unsafe {
        begin_call();
        match state(handle) {
            Some(s) => dispatch_command(s, &plugin, &command, &payload),
            None => no_handle(),
        }
    });
    ret(&mut env, out)
}

/// 点击界面对象：调用 `$世界.打开界面` 存下的闭包。
#[no_mangle]
pub extern "system" fn Java_nebula_neoforge_NativeBridge_clickGui(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    gui_id: jlong,
    payload: JString<'_>,
) -> jstring {
    let payload = jstring_to_string(&mut env, &payload);
    let out = guard(|| unsafe {
        begin_call();
        match state(handle) {
            Some(s) => click_gui(s, gui_id.max(0) as u64, &payload),
            None => no_handle(),
        }
    });
    ret(&mut env, out)
}

/// 界面关闭：丢掉回调，避免闭包和捕获变量一直留在内存里。
#[no_mangle]
pub extern "system" fn Java_nebula_neoforge_NativeBridge_closeGui(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    _handle: jlong,
    gui_id: jlong,
) {
    close_gui(gui_id.max(0) as u64);
}

#[no_mangle]
pub extern "system" fn Java_nebula_neoforge_NativeBridge_create(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    root: JString<'_>,
) -> jlong {
    let root = jstring_to_string(&mut env, &root);
    // 缓存 JVM 与查询回调类：查询类 `世界` 方法在事件分发期间同步回调 Java。
    if let Ok(vm) = env.get_java_vm() {
        let _ = JVM.set(vm);
    }
    if let Ok(class) = env.find_class("nebula/neoforge/McApi") {
        if let Ok(global) = env.new_global_ref(&class) {
            let _ = MC_API.set(global);
        }
    }
    let result = std::panic::catch_unwind(|| {
        static INIT: OnceLock<()> = OnceLock::new();
        if INIT.set(()).is_err() {
            return;
        }
        nebula::init();
        publish_world();
    });
    if result.is_err() {
        eprintln!("[星云] 引擎初始化失败（panic 已捕获）");
        return 0;
    }
    let bridge = Bridge {
        root: PathBuf::from(root),
        plugins: Mutex::new(BTreeMap::new()),
    };
    Box::into_raw(Box::new(bridge)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_nebula_neoforge_NativeBridge_destroy(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    guis().lock().unwrap_or_else(|e| e.into_inner()).clear();
    let _ = std::panic::catch_unwind(|| unsafe {
        drop(Box::from_raw(handle as *mut Bridge));
    });
}

#[no_mangle]
pub extern "system" fn Java_nebula_neoforge_NativeBridge_loadAll(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let out = guard(|| unsafe {
        begin_call();
        match state(handle) {
            Some(s) => load_all(s),
            None => no_handle(),
        }
    });
    ret(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_nebula_neoforge_NativeBridge_load(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    target: JString<'_>,
) -> jstring {
    let target = jstring_to_string(&mut env, &target);
    let out = guard(|| unsafe {
        begin_call();
        match state(handle) {
            Some(s) => apply_op(s, "load", &target),
            None => no_handle(),
        }
    });
    ret(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_nebula_neoforge_NativeBridge_reload(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    target: JString<'_>,
) -> jstring {
    let target = jstring_to_string(&mut env, &target);
    let out = guard(|| unsafe {
        begin_call();
        match state(handle) {
            Some(s) => apply_op(s, "reload", &target),
            None => no_handle(),
        }
    });
    ret(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_nebula_neoforge_NativeBridge_debug(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    target: JString<'_>,
) -> jstring {
    let target = jstring_to_string(&mut env, &target);
    let out = guard(|| unsafe {
        begin_call();
        match state(handle) {
            Some(s) => apply_op(s, "debug", &target),
            None => no_handle(),
        }
    });
    ret(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_nebula_neoforge_NativeBridge_undebug(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    target: JString<'_>,
) -> jstring {
    let target = jstring_to_string(&mut env, &target);
    let out = guard(|| unsafe {
        begin_call();
        match state(handle) {
            Some(s) => apply_op(s, "undebug", &target),
            None => no_handle(),
        }
    });
    ret(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_nebula_neoforge_NativeBridge_unload(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    target: JString<'_>,
) -> jstring {
    let target = jstring_to_string(&mut env, &target);
    let out = guard(|| unsafe {
        begin_call();
        match state(handle) {
            Some(s) => apply_op(s, "unload", &target),
            None => no_handle(),
        }
    });
    ret(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_nebula_neoforge_NativeBridge_list(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let out = guard(|| unsafe {
        begin_call();
        match state(handle) {
            Some(s) => list_plugins(s),
            None => no_handle(),
        }
    });
    ret(&mut env, out)
}

#[no_mangle]
pub extern "system" fn Java_nebula_neoforge_NativeBridge_dispatch(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    event: JString<'_>,
    payload: JString<'_>,
) -> jstring {
    let event = jstring_to_string(&mut env, &event);
    let payload = jstring_to_string(&mut env, &payload);
    let out = guard(|| unsafe {
        begin_call();
        match state(handle) {
            Some(s) => dispatch_event(s, &event, &payload),
            None => no_handle(),
        }
    });
    ret(&mut env, out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("nebula-{tag}-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn stamp_reacts_to_wordlib_edits() {
        let dir = temp_dir("stamp");
        let main = dir.join("main.n");
        std::fs::write(&main, "\n[f]初始化\n上限:1\n").unwrap();
        let first = plugin_stamp(&dir);

        // 同内容不动 → 指纹不变
        assert_eq!(first, plugin_stamp(&dir));

        std::thread::sleep(std::time::Duration::from_millis(30));
        std::fs::write(&main, "\n[f]初始化\n上限:2\n[f]加载\n就绪\n").unwrap();
        let second = plugin_stamp(&dir);
        assert_ne!(first, second, "改文件后指纹必须变化");

        // 新增一个 .n 也要算进去
        std::thread::sleep(std::time::Duration::from_millis(30));
        std::fs::write(dir.join("判定.n"), "\n[函数]判定\n是\n").unwrap();
        let third = plugin_stamp(&dir);
        assert_ne!(second, third);

        // 配置 JSON 也要算进去：debug 模式下改配置能自动重编译
        std::thread::sleep(std::time::Duration::from_millis(30));
        std::fs::write(dir.join("配置.json"), "{\"货币\":\"minecraft:dirt\"}").unwrap();
        assert_ne!(third, plugin_stamp(&dir), "改 json 后指纹必须变化");

        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn debug_toggle_keeps_reload_state() {
        let dir = temp_dir("debug");
        std::fs::write(dir.join("main.n"), "\n[f]初始化\n上限:8\n").unwrap();
        let plugin = compile_plugin("test-plugin", &dir).unwrap();
        assert!(!plugin.debug);
        let bridge = Bridge {
            root: dir.clone(),
            plugins: Mutex::new(BTreeMap::from([("test-plugin".to_string(), plugin.clone())])),
        };

        let msg = op_debug(&bridge, "test-plugin", true).unwrap();
        assert!(msg.contains("已开启调试"));
        let marked = bridge.plugins.lock().unwrap().get("test-plugin").cloned().unwrap();
        assert!(marked.debug);

        // 手动 reload 不能把调试开关冲掉
        let reloaded = op_reload(&bridge, "test-plugin").unwrap();
        assert!(reloaded.contains("已重载"));
        assert!(bridge.plugins.lock().unwrap().get("test-plugin").unwrap().debug);

        let msg = op_debug(&bridge, "test-plugin", false).unwrap();
        assert!(msg.contains("已关闭调试"));
        assert!(!bridge.plugins.lock().unwrap().get("test-plugin").unwrap().debug);

        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn debug_plugin_hot_reloads_on_change() {
        let dir = temp_dir("hot");
        std::fs::write(dir.join("main.n"), "\n[f]初始化\n上限:8\n\n[f]加载\n第一版\n").unwrap();
        let plugin = compile_plugin("hot", &dir).unwrap();
        let bridge = Bridge {
            root: dir.clone(),
            plugins: Mutex::new(BTreeMap::from([("hot".to_string(), plugin)])),
        };
        op_debug(&bridge, "hot", true).unwrap();

        let current = || bridge.plugins.lock().unwrap().get("hot").cloned().unwrap();

        // 未改动：不重编译，指纹保持
        let before = current();
        let same = refresh_debug_plugin(&bridge, &before);
        assert_eq!(same.stamp, before.stamp);

        std::thread::sleep(std::time::Duration::from_millis(30));
        std::fs::write(dir.join("main.n"), "\n[f]初始化\n上限:9\n\n[f]加载\n第二版\n").unwrap();
        let fresh = refresh_debug_plugin(&bridge, &before);
        assert_ne!(fresh.stamp, before.stamp, "改动后应重编译并换新指纹");
        assert!(fresh.debug, "热重载后调试开关要保持");
        assert_eq!(current().stamp, fresh.stamp, "新实例要写回注册表");

        // 写坏：保留旧版本，但指纹更新，避免每次事件重复报错
        let good = fresh.entries;
        std::thread::sleep(std::time::Duration::from_millis(30));
        std::fs::write(dir.join("main.n"), "\n[f]初始化\n\n[函数]坏 1\n%参数1%\n").unwrap();
        let kept = refresh_debug_plugin(&bridge, &fresh);
        assert_eq!(kept.entries, good, "编译失败应继续用旧版本");
        assert_ne!(kept.stamp, fresh.stamp);
        assert!(kept.debug);

        std::fs::remove_dir_all(&dir).ok();
    }

    /// `mod/docs/mc-api-map.md` 是契约，`mc_methods()` 是实现：两边必须一一对应。
    /// 这样以后往映射表加方法时，忘了写函数会直接被测试拦住。
    #[test]
    fn every_mapped_mc_method_has_a_function() {
        let doc = include_str!("../../mod/docs/mc-api-map.md");
        let methods = mc_methods();
        let mut mapped: Vec<String> = Vec::new();
        let mut in_event_table = false;
        // 第 0 节是说明与参数规约表，不是方法映射；从「1. 方块」开始解析。
        let mut in_mapping_table = false;

        for line in doc.lines() {
            if line.starts_with("## 1. 方块") {
                in_mapping_table = true;
            }
            if line.starts_with("## 7.") {
                break;
            }
            // 只在映射区里认这个标记：第 0 节的说明文字里提到它也不会提前触发跳过。
            if in_mapping_table && line.starts_with("指令与点击事件") {
                in_event_table = true;
            }
            if !in_mapping_table || in_event_table || !line.starts_with('|') {
                continue;
            }
            let Some(first) = line.split('|').nth(1) else {
                continue;
            };
            // 反引号里的内容才是方法名；一行可能并列多个。
            for (index, part) in first.split('`').enumerate() {
                if index % 2 == 0 {
                    continue;
                }
                for name in part.split(['/', ',', '，']) {
                    let name = name.trim();
                    // 表头里的对象名（旧 mc / 现 世界）不是方法，跳过。
                    if name.is_empty()
                        || name == "世界"
                        || name.contains(' ')
                        || name.contains('[')
                        || name.contains('$')
                    {
                        continue;
                    }
                    if !mapped.iter().any(|item| item == name) {
                        mapped.push(name.to_string());
                    }
                }
            }
        }

        let missing: Vec<&String> = mapped
            .iter()
            .filter(|name| !methods.contains_key(name.as_str()))
            .collect();
        assert!(
            missing.is_empty(),
            "映射文档里这些方法没有对应函数：{missing:?}"
        );

        let undocumented: Vec<&String> = methods
            .keys()
            .filter(|name| !mapped.iter().any(|item| item == *name))
            .collect();
        assert!(
            undocumented.is_empty(),
            "这些已实现的 世界 方法没写进映射文档：{undocumented:?}"
        );
        assert!(
            methods.len() >= 120,
            "世界 方法数量异常，只有 {} 个",
            methods.len()
        );
    }

    /// 造一个只有参数、没有引擎上下文的 DicInputs（Args 的第 0 位是方法名）。
    fn fake_inputs(name: &str, args: &[&str]) -> DicInputs {
        let mut list = vec![name.to_string()];
        list.extend(args.iter().map(|s| s.to_string()));
        DicInputs::new(
            Arc::new(nebula::model::BuildValue::new()),
            DicVal::new(),
            nebula::inputs::Args::from_strings(list),
            Arc::new(nebula::model::SingleValue::new()),
        )
    }

    /// 规格表拼出来的 JSON 必须与 mod/docs/mc-api-map.md 的参数顺序一致。
    #[test]
    fn table_driven_request_shape() {
        let methods = mc_methods();
        let _ = take_actions();

        let mut set_block = fake_inputs("设方块", &[
            "minecraft:overworld", "1", "64", "2", "minecraft:stone",
        ]);
        (methods.get("设方块").unwrap())(&mut set_block).unwrap();
        let actions = take_actions();
        assert_eq!(actions.len(), 1, "动作类方法应只压入一个动作");
        assert_eq!(actions[0]["op"], "设方块");
        assert_eq!(actions[0]["维度"], "minecraft:overworld");
        assert_eq!(actions[0]["x"], 1);
        assert_eq!(actions[0]["y"], 64);
        assert_eq!(actions[0]["z"], 2);
        assert_eq!(actions[0]["方块"], "minecraft:stone");

        // 布尔参数与「剩余文本」参数各走一条转换路径。
        let _ = take_actions();
        let mut fly = fake_inputs("设置飞行", &["Steve", "是"]);
        (methods.get("设置飞行").unwrap())(&mut fly).unwrap();
        let actions = take_actions();
        assert_eq!(actions[0]["玩家"], "Steve");
        assert_eq!(actions[0]["值"], true);

        let mut title = fake_inputs("踢出玩家", &["Steve", "维护", "重启"]);
        (methods.get("踢出玩家").unwrap())(&mut title).unwrap();
        let actions = take_actions();
        assert_eq!(actions[0]["原因"], "维护 重启");

        // 宿主未就绪时查询类走 fallback，而不是返回空串把词库卡住。
        let mut list = fake_inputs("取玩家列表", &[]);
        let out = (methods.get("取玩家列表").unwrap())(&mut list).unwrap();
        assert_eq!(any_to_string(&out), "[]");
        assert!(take_actions().is_empty(), "查询不应产生动作");
    }

    /// 随服发行的词库必须能编译：语法错误（缩进、`如果尾`、漏 `$`）在测试里就炸出来，
    /// 不必等进游戏 `/nebula load` 才发现。
    #[test]
    fn shipped_wordlibs_compile() {
        let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../server/nebula");
        let mut dirs: Vec<PathBuf> = std::fs::read_dir(&root)
            .unwrap_or_else(|e| panic!("读不到词库目录 {}：{e}", root.display()))
            .filter_map(|entry| entry.ok().map(|e| e.path()))
            .filter(|path| {
                path.is_dir()
                    && !path
                        .file_name()
                        .is_some_and(|n| n.to_string_lossy().starts_with(['_', '.']))
            })
            .collect();
        dirs.sort();
        assert!(!dirs.is_empty(), "{} 下没有任何插件目录", root.display());
        for dir in dirs {
            // 没有 .n 的目录（纯数据目录）跳过；有词库的必须过编译。
            if plugin_entry(&dir).is_err() {
                continue;
            }
            let name = dir.file_name().unwrap().to_string_lossy().into_owned();
            let entry = plugin_entry(&dir).unwrap();
            load_plugin_dic(&dir, &entry)
                .unwrap_or_else(|e| panic!("词库 {name} 编译失败：{e}"));
        }
    }

    /// 光有 `世界` 函数还不够：宿主必须真的有处理分支，否则方法调用会石沉大海。
    /// 这条测试把「规格表 ↔ McApi/McActions/NebulaMod 的 switch」锁在一起。
    #[test]
    fn every_method_has_a_host_handler() {
        // 只解析测试模块之前的实现部分，避免把测试里的字符串字面量当成规格。
        let rust = include_str!("lib.rs").split("#[cfg(test)]").next().unwrap();
        let api = include_str!("../../mod/src/main/java/nebula/neoforge/McApi.java");
        let actions = include_str!("../../mod/src/main/java/nebula/neoforge/McActions.java");
        let host = include_str!("../../mod/src/main/java/nebula/neoforge/NebulaMod.java");

        fn names(src: &str, marker: &str) -> Vec<String> {
            let mut out = Vec::new();
            let mut rest = src;
            while let Some(pos) = rest.find(marker) {
                let after = &rest[pos + marker.len()..];
                let Some(end) = after.find('"') else { break };
                out.push(after[..end].to_string());
                rest = &after[end + 1..];
            }
            out
        }

        fn cases(src: &str) -> Vec<String> {
            src.lines()
                .filter(|line| line.contains("case ") && line.contains("->"))
                .flat_map(|line| {
                    let head = line.split("->").next().unwrap_or("");
                    head.split('"')
                        .enumerate()
                        .filter(|(index, _)| index % 2 == 1)
                        .map(|(_, text)| text.to_string())
                        .collect::<Vec<_>>()
                })
                .collect()
        }

        let queries = names(rust, "q!(\"");
        let actions_spec = names(rust, "a!(\"");
        assert!(!queries.is_empty() && !actions_spec.is_empty(), "没解析到规格表");

        let query_cases: std::collections::HashSet<String> = cases(api).into_iter().collect();
        let mut action_cases: std::collections::HashSet<String> =
            cases(actions).into_iter().collect();
        action_cases.extend(cases(host));

        let missing_queries: Vec<&String> = queries
            .iter()
            .filter(|name| !query_cases.contains(*name))
            .collect();
        let missing_actions: Vec<&String> = actions_spec
            .iter()
            .filter(|name| !action_cases.contains(*name))
            .collect();
        assert!(
            missing_queries.is_empty(),
            "这些查询没有 McApi 处理分支：{missing_queries:?}"
        );
        assert!(
            missing_actions.is_empty(),
            "这些动作没有宿主处理分支：{missing_actions:?}"
        );
    }
}
