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

/// 插件名 → 插件目录。`$java.eval "xxx.java"` 的相对路径按它解析。
///
/// 单独一张表而不是再加一个 thread-local：`set_current_plugin` 有五六处调用点，
/// 而插件目录在编译期就定了，记一次就够，不必跟着执行上下文走。
static PLUGIN_DIRS: OnceLock<Mutex<HashMap<String, PathBuf>>> = OnceLock::new();

fn plugin_dirs() -> &'static Mutex<HashMap<String, PathBuf>> {
    PLUGIN_DIRS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn remember_plugin_dir(name: &str, dir: &Path) {
    plugin_dirs()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .insert(name.to_string(), dir.to_path_buf());
}

fn current_plugin_dir() -> Option<PathBuf> {
    let name = current_plugin_name();
    plugin_dirs()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(&name)
        .cloned()
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

// ────────────────────────────── 生物对象 ──────────────────────────────

/// 一个生物品种的运行时条目。实例是词库里的 `Value::Class`，这里持有同一份 `Arc`。
struct MobEntry {
    plugin: String,
    instance: Arc<DicClass>,
    /// 是否已调用过 `$怪.生效$`；未生效的在编译结束时自动补提交。
    committed: bool,
    /// 本轮编译代次：同代次重复注册视为幂等，旧代次在编译成功后整体清掉。
    generation: u64,
}

static MOBS: OnceLock<Mutex<BTreeMap<String, MobEntry>>> = OnceLock::new();
static MOB_SEQ: AtomicU64 = AtomicU64::new(1);

thread_local! {
    /// 当前编译轮次；`注册生物` 用它标记对象归属哪一轮。
    static MOB_GENERATION: Cell<u64> = const { Cell::new(0) };
}

fn mobs() -> &'static Mutex<BTreeMap<String, MobEntry>> {
    MOBS.get_or_init(|| Mutex::new(BTreeMap::new()))
}

/// 字段白名单与默认值（顺序即 `生效` 打包顺序）。`名称` 的默认值是品种 id。
const MOB_FIELDS: &[(&str, &str)] = &[
    ("名称", ""),
    ("体型", "normal"),
    ("分类", "creature"),
    ("持久", "是"),
    ("血量", "20"),
    ("速度", "0.22"),
    ("跳跃", "0.42"),
    ("击退", "0.4"),
    ("容器格数", "0"),
    ("容器距离", "8"),
    ("碰撞箱", ""),
    ("死亡掉落", "是"),
    ("行为", ""),
];

fn mob_instance(d: &DicInputs) -> Option<Arc<DicClass>> {
    d.v.scope.self_class()
}

fn mob_str(inst: &DicClass, key: &str) -> String {
    match inst.local_value.get(key) {
        Some(NValue::Str(s)) => s,
        Some(other) => any_to_string(&other),
        None => String::new(),
    }
}

fn mob_error(d: &mut DicInputs, text: impl Into<String>) {
    d.v.scope.set("报错", NValue::Str(text.into()));
}

fn mob_validate(key: &str, value: &str) -> Result<(), String> {
    match key {
        "品种" => Err("品种 只读，创建时通过 $世界.注册生物 指定".into()),
        "动画" => Err("动画 请用 `$怪.动画 键 值$`（弹跳/前倾/开盖/开合）".into()),
        "造型" => Err("造型 请用 `$怪.造型 体|盖 fromx fromy fromz tox toy toz$`".into()),
        "贴图" | "UV" => Err("UV 请用 `$怪.贴图 体|盖 面 u0 v0 u1 v1 [材质名]$`".into()),
        "材质" => Err("材质 请用 `$怪.材质 名字 贴图$`".into()),
        "目标" => Err("目标 请用 `$怪.目标 类型 [对象] [数值1] [数值2]$`".into()),
        "交互" => Err("交互 请用 `$怪.交互 开容器|无|给予 物品 数量$`".into()),
        "掉落" => Err("掉落 请用 `$怪.掉落 物品 [概率] [数量]$`".into()),
        "名称" | "行为" => Ok(()),
        "体型" => match value {
            "tiny" | "small" | "normal" | "large" => Ok(()),
            _ => Err(format!("体型 只能是 tiny/small/normal/large，收到「{value}」")),
        },
        "分类" => Ok(()),
        "持久" | "死亡掉落" => match value {
            "是" | "否" | "true" | "false" => Ok(()),
            _ => Err(format!("{key} 只能是 是/否，收到「{value}」")),
        },
        "血量" | "速度" | "跳跃" | "击退" | "容器距离" => value
            .trim()
            .parse::<f64>()
            .map(|_| ())
            .map_err(|_| format!("{key} 必须是数字，收到「{value}」")),
        // 「自动」= 按 `$怪.造型$` 的体/盖并集算；也可以直接写「宽,高」。
        "碰撞箱" => {
            let text = value.trim();
            if text.is_empty() || text == "自动" || text.eq_ignore_ascii_case("auto") {
                Ok(())
            } else {
                let parts: Vec<&str> = text
                    .split(|c: char| matches!(c, ',' | '，' | ' ' | 'x' | 'X' | '*'))
                    .filter(|s| !s.is_empty())
                    .collect();
                if parts.len() != 2 {
                    return Err("碰撞箱 写「自动」或「宽,高」（例：0.9,1.0）".into());
                }
                for part in parts {
                    match part.parse::<f64>() {
                        Ok(n) if n > 0.05 && n <= 8.0 => {}
                        _ => {
                            return Err(format!("碰撞箱 的宽/高必须是 0.05..8 的数字，收到「{part}」"));
                        }
                    }
                }
                Ok(())
            }
        }
        "容器格数" => value
            .trim()
            .parse::<i64>()
            .map_err(|_| format!("容器格数 必须是整数，收到「{value}」"))
            .and_then(|n| {
                if (0..=54).contains(&n) {
                    Ok(())
                } else {
                    Err(format!("容器格数 必须在 0..=54，收到 {n}"))
                }
            }),
        _ => Err(format!("未知字段「{key}」，可用：{}", MOB_FIELD_NAMES)),
    }
}

const MOB_FIELD_NAMES: &str =
    "名称/体型/分类/持久/血量/速度/跳跃/击退/容器格数/容器距离/碰撞箱/死亡掉落/行为";

/// 把对象当前字段打包成一条 `注册生物` 动作，并标记已生效。
fn mob_commit_instance(inst: &Arc<DicClass>) {
    let id = mob_str(inst, "品种");
    if id.is_empty() {
        return;
    }
    let plugin = mob_str(inst, "插件");
    let mut def = serde_json::Map::new();
    for (key, _) in MOB_FIELDS {
        def.insert((*key).to_string(), json!(mob_str(inst, key)));
    }
    // 外观是一个对象：`$怪.外观$` 写方式/贴图/偏移/阴影，`$怪.造型$` 写 体/盖 两个盒子，
    // `$怪.贴图$` 写 uv。三者都只改对象字段，这里统一并成一份 `外观` 下发。
    let mut look_obj: serde_json::Map<String, serde_json::Value> = inst
        .local_value
        .get("外观")
        .and_then(|v| match v {
            NValue::Str(s) => serde_json::from_str::<serde_json::Value>(&s)
                .ok()
                .and_then(|v| v.as_object().cloned()),
            _ => None,
        })
        .unwrap_or_default();
    if let Some(NValue::Str(shape)) = inst.local_value.get("造型") {
        if let Ok(serde_json::Value::Object(map)) = serde_json::from_str::<serde_json::Value>(&shape) {
            for (part, value) in map {
                look_obj.insert(part, value);
            }
        }
    }
    if let Some(NValue::Str(uv)) = inst.local_value.get("UV") {
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&uv) {
            look_obj.insert("uv".into(), v);
        }
    }
    if let Some(NValue::Str(mats)) = inst.local_value.get("材质") {
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&mats) {
            look_obj.insert("材质".into(), v);
        }
    }
    if let Some(NValue::Str(goals)) = inst.local_value.get("目标") {
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&goals) {
            def.insert("目标".into(), v);
        }
    }
    if let Some(NValue::Str(interact)) = inst.local_value.get("交互") {
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&interact) {
            def.insert("交互".into(), v);
        }
    }
    if let Some(NValue::Str(drops)) = inst.local_value.get("掉落") {
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&drops) {
            def.insert("掉落".into(), v);
        }
    }
    if !look_obj.is_empty() {
        def.insert("外观".into(), serde_json::Value::Object(look_obj));
    }
    if let Some(NValue::Str(anim)) = inst.local_value.get("动画") {
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&anim) {
            def.insert("动画".into(), v);
        }
    }
    if let Some(NValue::Str(items)) = inst.local_value.get("物品") {
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&items) {
            def.insert("物品".into(), v);
        }
    }
    let batch = MOB_GENERATION.with(|g| g.get());
    push_action(json!({
        "op": "注册生物",
        "品种": id,
        "插件": plugin,
        "定义": def,
        "批次": batch,
    }));
    if let Some(entry) = mobs()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get_mut(&id)
    {
        entry.committed = true;
    }
}

/// 编译结束时补提交「创建了但忘了 `生效`」的对象。
fn mob_auto_commit(plugins: &[String], generation: u64) {
    let pending: Vec<String> = {
        let map = mobs().lock().unwrap_or_else(|e| e.into_inner());
        map.iter()
            .filter(|(_, e)| {
                e.generation == generation && !e.committed && plugins.iter().any(|p| p == &e.plugin)
            })
            .map(|(id, _)| id.clone())
            .collect()
    };
    for id in pending {
        let inst = mobs()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&id)
            .map(|e| e.instance.clone());
        if let Some(inst) = inst {
            eprintln!("[星云] 注册生物：{id} 未调用 `生效`，已自动提交");
            mob_commit_instance(&inst);
        }
    }
}

/// 生物对象的 `fn_map`：六方法，所有实例共享同一份。
fn mob_methods() -> Arc<HashMap<String, NasFunc>> {
    static CACHE: OnceLock<Arc<HashMap<String, NasFunc>>> = OnceLock::new();
    CACHE
        .get_or_init(|| {
            let mut m: HashMap<String, NasFunc> = HashMap::new();

            // 设 键 值
            m.insert(
                "设".into(),
                hook(|d| {
                    let Some(inst) = mob_instance(d) else {
                        return ok_empty();
                    };
                    let key = d.string(1);
                    let value = d.string(2);
                    match mob_validate(&key, &value) {
                        Ok(()) => inst.local_value.set(&key, NValue::Str(value)),
                        Err(e) => mob_error(d, e),
                    }
                    ok_empty()
                }) as NasFunc,
            );

            // 外观 方式 方块模型 贴图 [偏移x 偏移y 偏移z] [阴影]
            m.insert(
                "外观".into(),
                hook(|d| {
                    let Some(inst) = mob_instance(d) else {
                        return ok_empty();
                    };
                    let way = d.string(1);
                    if way != "方块" {
                        mob_error(d, format!("外观方式目前只支持「方块」，收到「{way}」"));
                        return ok_empty();
                    }
                    let model = d.string(2);
                    let texture = d.string(3);
                    if model.is_empty() || texture.is_empty() {
                        mob_error(d, "外观 需要：方式 方块模型 贴图");
                        return ok_empty();
                    }
                    let shadow = d.float(7);
                    let look = json!({
                        "方式": way,
                        "方块模型": model,
                        "贴图": texture,
                        "偏移": [d.float(4), d.float(5), d.float(6)],
                        "阴影": if shadow > 0.0 { shadow } else { 0.45 },
                    });
                    inst.local_value.set("外观", NValue::Str(look.to_string()));
                    ok_empty()
                }) as NasFunc,
            );

            // 造型 体|盖 fromx fromy fromz tox toy toz：覆盖默认「箱体 + 盖子」盒子的几何。
            // 坐标是方块坐标的 1/16 单位（与原版模型 JSON 的 from/to 同一套），0..16，可用小数。
            m.insert(
                "造型".into(),
                hook(|d| {
                    let Some(inst) = mob_instance(d) else {
                        return ok_empty();
                    };
                    let part = d.string(1);
                    if part != "体" && part != "盖" {
                        mob_error(d, format!("造型 第一个参数只能是「体」或「盖」，收到「{part}」"));
                        return ok_empty();
                    }
                    let mut values = [0.0f64; 6];
                    for (index, slot) in values.iter_mut().enumerate() {
                        let raw = d.string(index + 2);
                        if raw.trim().is_empty() {
                            mob_error(d, "造型 需要 6 个坐标：fromx fromy fromz tox toy toz");
                            return ok_empty();
                        }
                        match raw.trim().parse::<f64>() {
                            Ok(v) if (0.0..=16.0).contains(&v) => *slot = v,
                            Ok(v) => {
                                mob_error(d, format!("造型 坐标必须在 0..16（1/16 格），收到 {v}"));
                                return ok_empty();
                            }
                            Err(_) => {
                                mob_error(d, format!("造型 坐标必须是数字，收到「{raw}」"));
                                return ok_empty();
                            }
                        }
                    }
                    if values[0] > values[3] || values[1] > values[4] || values[2] > values[5] {
                        mob_error(d, "造型 的 from 不能大于 to（会得到零体积或反向盒子）");
                        return ok_empty();
                    }
                    let mut shape = inst
                        .local_value
                        .get("造型")
                        .and_then(|v| match v {
                            NValue::Str(s) => serde_json::from_str::<serde_json::Value>(&s)
                                .ok()
                                .and_then(|v| v.as_object().cloned()),
                            _ => None,
                        })
                        .unwrap_or_default();
                    shape.insert(part, json!(values));
                    inst.local_value.set(
                        "造型",
                        NValue::Str(serde_json::Value::Object(shape).to_string()),
                    );
                    ok_empty()
                }) as NasFunc,
            );

            // 贴图 体|盖 面 u0 v0 u1 v1：覆盖单面 UV。面 = 北/南/东/西/上/下/全部。
            // 只影响「几何怎么贴图」，贴图文件仍是 `$怪.外观$` 的第 3 参。
            m.insert(
                "贴图".into(),
                hook(|d| {
                    let Some(inst) = mob_instance(d) else {
                        return ok_empty();
                    };
                    let part = d.string(1);
                    if part != "体" && part != "盖" {
                        mob_error(d, format!("贴图 第一个参数只能是「体」或「盖」，收到「{part}」"));
                        return ok_empty();
                    }
                    let face_cn = d.string(2);
                    let faces: Vec<&str> = match face_cn.as_str() {
                        "北" => vec!["north"],
                        "南" => vec!["south"],
                        "东" => vec!["east"],
                        "西" => vec!["west"],
                        "上" => vec!["up"],
                        "下" => vec!["down"],
                        "全部" | "全" => vec!["north", "south", "east", "west", "up", "down"],
                        _ => {
                            mob_error(
                                d,
                                format!("贴图 的「面」只能是 北/南/东/西/上/下/全部，收到「{face_cn}」"),
                            );
                            return ok_empty();
                        }
                    };
                    let mut quad = [0.0f64; 4];
                    for (index, slot) in quad.iter_mut().enumerate() {
                        let raw = d.string(index + 3);
                        if raw.trim().is_empty() {
                            mob_error(d, "贴图 需要 4 个 UV：u0 v0 u1 v1");
                            return ok_empty();
                        }
                        match raw.trim().parse::<f64>() {
                            Ok(v) if (-32.0..=32.0).contains(&v) => *slot = v,
                            Ok(v) => {
                                mob_error(d, format!("贴图 UV 必须在 -32..32，收到 {v}"));
                                return ok_empty();
                            }
                            Err(_) => {
                                mob_error(d, format!("贴图 UV 必须是数字，收到「{raw}」"));
                                return ok_empty();
                            }
                        }
                    }
                    let mut uv = inst
                        .local_value
                        .get("UV")
                        .and_then(|v| match v {
                            NValue::Str(s) => serde_json::from_str::<serde_json::Value>(&s)
                                .ok()
                                .and_then(|v| v.as_object().cloned()),
                            _ => None,
                        })
                        .unwrap_or_default();
                    let mut part_map = uv
                        .get(&part)
                        .and_then(|v| v.as_object().cloned())
                        .unwrap_or_default();
                    let material = d.string(7);
                    let mut entry = serde_json::Map::new();
                    entry.insert("uv".into(), json!(quad));
                    if !material.is_empty() {
                        entry.insert("贴图".into(), json!(material));
                    }
                    for face in faces {
                        part_map.insert(face.to_string(), serde_json::Value::Object(entry.clone()));
                    }
                    uv.insert(part, serde_json::Value::Object(part_map));
                    inst.local_value
                        .set("UV", NValue::Str(serde_json::Value::Object(uv).to_string()));
                    ok_empty()
                }) as NasFunc,
            );

            // 材质 名字 贴图：额外注册一张贴图，供 `$怪.贴图 … 名字$` 按面引用。
            // 主贴图（`$怪.外观$` 第 3 参）始终可用，名字是「crate」。
            m.insert(
                "材质".into(),
                hook(|d| {
                    let Some(inst) = mob_instance(d) else {
                        return ok_empty();
                    };
                    let name = d.string(1);
                    let texture = d.string(2);
                    if name.is_empty() || texture.is_empty() {
                        mob_error(d, "材质 需要：名字 贴图（例：脸 nebula_bridge:block/face）");
                        return ok_empty();
                    }
                    let mut mats = inst
                        .local_value
                        .get("材质")
                        .and_then(|v| match v {
                            NValue::Str(s) => serde_json::from_str::<serde_json::Value>(&s)
                                .ok()
                                .and_then(|v| v.as_object().cloned()),
                            _ => None,
                        })
                        .unwrap_or_default();
                    mats.insert(name, json!(texture));
                    inst.local_value.set(
                        "材质",
                        NValue::Str(serde_json::Value::Object(mats).to_string()),
                    );
                    ok_empty()
                }) as NasFunc,
            );

            // 目标 类型 [对象] [数值1] [数值2]：声明 AI 目标（原版 Goal 的参数化装配）。
            // 注视 玩家 8 / 攻击 玩家 1.0 16 / 逃跑 玩家 8 1.4 / 恐慌 1.2 / 漂浮
            m.insert(
                "目标".into(),
                hook(|d| {
                    let Some(inst) = mob_instance(d) else {
                        return ok_empty();
                    };
                    let kind = d.string(1);
                    const KINDS: [&str; 5] = ["注视", "攻击", "逃跑", "恐慌", "漂浮"];
                    if !KINDS.contains(&kind.as_str()) {
                        mob_error(
                            d,
                            format!("目标 只支持 注视/攻击/逃跑/恐慌/漂浮，收到「{kind}」"),
                        );
                        return ok_empty();
                    }
                    let mut list: Vec<serde_json::Value> = inst
                        .local_value
                        .get("目标")
                        .and_then(|v| match v {
                            NValue::Str(s) => serde_json::from_str::<serde_json::Value>(&s)
                                .ok()
                                .and_then(|v| v.as_array().cloned()),
                            _ => None,
                        })
                        .unwrap_or_default();
                    let mut one = serde_json::Map::new();
                    one.insert("类型".into(), json!(kind));
                    // 参数位置按类型定：注视/攻击/逃跑 有「对象」，恐慌/漂浮 没有。
                    // 不能用空串占位（词库会把空实参吞掉，后面的参数直接前移）。
                    let has_target = matches!(kind.as_str(), "注视" | "攻击" | "逃跑");
                    if has_target {
                        let target = d.string(2);
                        if !target.is_empty() {
                            one.insert("对象".into(), json!(target));
                        }
                    }
                    let base = if has_target { 3 } else { 2 };
                    for (index, key) in ["数值1", "数值2"].iter().enumerate() {
                        let raw = d.string(base + index);
                        if raw.trim().is_empty() {
                            continue;
                        }
                        match raw.trim().parse::<f64>() {
                            Ok(n) if n > 0.0 => {
                                one.insert((*key).to_string(), json!(n));
                            }
                            _ => {
                                mob_error(
                                    d,
                                    format!("目标 {kind} 的参数必须是正数，收到「{raw}」"),
                                );
                                return ok_empty();
                            }
                        }
                    }
                    list.push(serde_json::Value::Object(one));
                    inst.local_value
                        .set("目标", NValue::Str(json!(list).to_string()));
                    ok_empty()
                }) as NasFunc,
            );

            // 交互 类型 [物品] [数量]：右键这只怪做什么。
            // 开容器（缺省）/ 无（交还给事件）/ 给予 物品 数量
            m.insert(
                "交互".into(),
                hook(|d| {
                    let Some(inst) = mob_instance(d) else {
                        return ok_empty();
                    };
                    let kind = d.string(1);
                    const KINDS: [&str; 3] = ["开容器", "无", "给予"];
                    if !KINDS.contains(&kind.as_str()) {
                        mob_error(d, format!("交互 只支持 开容器/无/给予，收到「{kind}」"));
                        return ok_empty();
                    }
                    let mut one = serde_json::Map::new();
                    one.insert("类型".into(), json!(kind));
                    if kind == "给予" {
                        let item = d.string(2);
                        if item.is_empty() {
                            mob_error(d, "交互 给予 需要物品 id（例：minecraft:apple）");
                            return ok_empty();
                        }
                        one.insert("物品".into(), json!(item));
                        let count = d.int(3);
                        one.insert("数量".into(), json!(if count > 0 { count } else { 1 }));
                    }
                    inst.local_value
                        .set("交互", NValue::Str(serde_json::Value::Object(one).to_string()));
                    ok_empty()
                }) as NasFunc,
            );

            // 掉落 物品 [概率] [数量]：死亡时额外撒的东西（和容器的「死亡掉落」互不影响）。
            m.insert(
                "掉落".into(),
                hook(|d| {
                    let Some(inst) = mob_instance(d) else {
                        return ok_empty();
                    };
                    let item = d.string(1);
                    if item.is_empty() {
                        mob_error(d, "掉落 需要物品 id（例：minecraft:apple）");
                        return ok_empty();
                    }
                    let chance = d.float(2);
                    let count = d.int(3);
                    let mut list: Vec<serde_json::Value> = inst
                        .local_value
                        .get("掉落")
                        .and_then(|v| match v {
                            NValue::Str(s) => serde_json::from_str::<serde_json::Value>(&s)
                                .ok()
                                .and_then(|v| v.as_array().cloned()),
                            _ => None,
                        })
                        .unwrap_or_default();
                    list.push(json!({
                        "物品": item,
                        "概率": if chance > 0.0 { chance.min(1.0) } else { 1.0 },
                        "数量": if count > 0 { count.min(64) } else { 1 },
                    }));
                    inst.local_value
                        .set("掉落", NValue::Str(json!(list).to_string()));
                    ok_empty()
                }) as NasFunc,
            );

            // 加物品 用途 基底 标记 名称 [模型] [贴图] [堆叠] [创造页]
            m.insert(
                "加物品".into(),
                hook(|d| {
                    let Some(inst) = mob_instance(d) else {
                        return ok_empty();
                    };
                    let use_for = d.string(1);
                    let base = d.string(2);
                    let marker = d.string(3);
                    let name = d.string(4);
                    if base.is_empty() || marker.is_empty() || name.is_empty() {
                        mob_error(d, "加物品 需要：用途 基底 标记 名称");
                        return ok_empty();
                    }
                    let mut list: Vec<serde_json::Value> = inst
                        .local_value
                        .get("物品")
                        .and_then(|v| match v {
                            NValue::Str(s) => serde_json::from_str(&s).ok(),
                            _ => None,
                        })
                        .unwrap_or_default();
                    let mut entry = serde_json::Map::new();
                    entry.insert("用途".into(), json!(use_for));
                    entry.insert("基底".into(), json!(base));
                    entry.insert("标记".into(), json!(marker));
                    entry.insert("名称".into(), json!(name));
                    let model = d.string(5);
                    if !model.is_empty() {
                        entry.insert("模型".into(), json!(model));
                    }
                    let texture = d.string(6);
                    if !texture.is_empty() {
                        entry.insert("贴图".into(), json!(texture));
                    }
                    let stack = d.int(7);
                    if stack > 0 {
                        entry.insert("堆叠".into(), json!(stack));
                    }
                    let tab = d.string(8);
                    if !tab.is_empty() {
                        entry.insert("创造页".into(), json!(tab));
                    }
                    let tint = d.string(9);
                    if !tint.is_empty() {
                        entry.insert("颜色".into(), json!(tint));
                    }
                    list.push(serde_json::Value::Object(entry));
                    inst.local_value.set("物品", NValue::Str(json!(list).to_string()));
                    ok_empty()
                }) as NasFunc,
            );

            // 动画 键 值：表现参数，全部透传给宿主派生的客户端声明；
            // 「开合」是唯一的服务端量（开盖速度），其余三个是客户端渲染量。
            m.insert(
                "动画".into(),
                hook(|d| {
                    let Some(inst) = mob_instance(d) else {
                        return ok_empty();
                    };
                    let key = d.string(1);
                    let value = d.string(2);
                    if !matches!(
                        key.as_str(),
                        "弹跳" | "前倾" | "开盖" | "开合" | "摇摆" | "缩放" | "旋转" | "浮空"
                    ) {
                        mob_error(
                            d,
                            format!(
                                "动画 只支持 弹跳/前倾/开盖/开合/摇摆/缩放/旋转/浮空，收到「{key}」"
                            ),
                        );
                        return ok_empty();
                    }
                    let Ok(number) = value.trim().parse::<f64>() else {
                        mob_error(d, format!("动画 {key} 必须是数字，收到「{value}」"));
                        return ok_empty();
                    };
                    let mut anim: serde_json::Map<String, serde_json::Value> = inst
                        .local_value
                        .get("动画")
                        .and_then(|v| match v {
                            NValue::Str(s) => serde_json::from_str::<serde_json::Value>(&s)
                                .ok()
                                .and_then(|v| v.as_object().cloned()),
                            _ => None,
                        })
                        .unwrap_or_default();
                    anim.insert(key, json!(number));
                    inst.local_value
                        .set("动画", NValue::Str(serde_json::Value::Object(anim).to_string()));
                    ok_empty()
                }) as NasFunc,
            );

            // 生效：提交快照（幂等）
            m.insert(
                "生效".into(),
                hook(|d| {
                    if let Some(inst) = mob_instance(d) {
                        mob_commit_instance(&inst);
                    }
                    ok_empty()
                }) as NasFunc,
            );

            // 卸载：单品种即时下线
            m.insert(
                "卸载".into(),
                hook(|d| {
                    let Some(inst) = mob_instance(d) else {
                        return ok_empty();
                    };
                    let id = mob_str(&inst, "品种");
                    if !id.is_empty() {
                        push_action(json!({
                            "op": "注销生物",
                            "品种": id,
                            "批次": MOB_GENERATION.with(|g| g.get()),
                        }));
                        mobs().lock().unwrap_or_else(|e| e.into_inner()).remove(&id);
                    }
                    ok_empty()
                }) as NasFunc,
            );

            // 列表 [上限]
            m.insert(
                "列表".into(),
                hook(|d| {
                    let Some(inst) = mob_instance(d) else {
                        return Ok(NValue::Str("[]".into()));
                    };
                    let id = mob_str(&inst, "品种");
                    let limit = if d.int(1) > 0 { d.int(1) } else { 64 };
                    let req = json!({
                        "op": "取生物列表",
                        "品种": id,
                        "上限": limit,
                    })
                    .to_string();
                    Ok(NValue::Str(
                        java_query(&req).unwrap_or_else(|| "[]".to_string()),
                    ))
                }) as NasFunc,
            );

            Arc::new(m)
        })
        .clone()
}

/// 清掉某插件（或全部插件）的旧代次生物对象：编译成功后调用。
///
/// 语义是「本批没声明就下线」：指定插件时只动该插件的条目，其他插件一律保留；
/// `None`（`loadAll`）时所有插件都必须在本轮重新声明，旧代次全部清掉。
fn mob_retain(plugin: Option<&str>, generation: u64) {
    let mut removed: Vec<String> = Vec::new();
    {
        let mut map = mobs().lock().unwrap_or_else(|e| e.into_inner());
        map.retain(|id, entry| {
            let in_scope = match plugin {
                Some(name) => entry.plugin == name,
                None => true,
            };
            let keep = !in_scope || entry.generation == generation;
            if !keep {
                removed.push(id.clone());
            }
            keep
        });
    }
    // Rust 侧清掉还不够：宿主 `MobRegistry` 也有一份。逐个发注销动作，
    // 否则重新 load 删掉定义 / unload 之后，服务端的品种表与资源包都会残留。
    for id in removed {
        push_action(json!({
            "op": "注销生物",
            "品种": id,
            "批次": MOB_GENERATION.with(|g| g.get()),
        }));
    }
}

// ────────────────────────────── 物品对象 ──────────────────────────────
//
// `$世界.注册物品 <id>$` 返回一个有状态对象，宿主按它派生资源包条目（item definition /
// 模型 / 创造页）并在 `给物品` 时构造带组件的 ItemStack。和生物对象同一套机制：
// `self_class()` 取自己、`local_value` 存字段、`生效` 才把快照推给宿主。

/// 注册表里的一件物品。`generation` 与生物共用同一编译轮次标号。
struct ItemEntry {
    plugin: String,
    instance: Arc<DicClass>,
    committed: bool,
    generation: u64,
}

static ITEMS: OnceLock<Mutex<BTreeMap<String, ItemEntry>>> = OnceLock::new();

fn items() -> &'static Mutex<BTreeMap<String, ItemEntry>> {
    ITEMS.get_or_init(|| Mutex::new(BTreeMap::new()))
}

/// 字段白名单（顺序即 `生效` 打包顺序）。`模型` 由 `$剑.外观$` 写，不对外开放。
const ITEM_FIELDS: &[(&str, &str)] = &[
    ("名称", ""),
    ("基底", "minecraft:paper"),
    ("颜色", "white"),
    ("稀有度", "common"),
    ("堆叠", "1"),
    ("耐久", "0"),
    ("创造页", ""),
    ("标记", ""),
    ("模型", ""),
];

const ITEM_FIELD_NAMES: &str = "名称/基底/颜色/稀有度/堆叠/耐久/创造页/标记";

fn item_instance(d: &DicInputs) -> Option<Arc<DicClass>> {
    d.v.scope.self_class()
}

fn item_str(inst: &DicClass, key: &str) -> String {
    match inst.local_value.get(key) {
        Some(NValue::Str(s)) => s,
        Some(other) => any_to_string(&other),
        None => String::new(),
    }
}

fn item_error(d: &mut DicInputs, text: impl Into<String>) {
    d.v.scope.set("报错", NValue::Str(text.into()));
}

fn item_validate(key: &str, value: &str) -> Result<(), String> {
    match key {
        "物品" | "模型" => Err(format!("{key} 由宿主维护，不能直接设")),
        "名称" | "基底" | "颜色" | "稀有度" | "创造页" | "标记" => Ok(()),
        "堆叠" => value
            .trim()
            .parse::<i64>()
            .map_err(|_| format!("堆叠 必须是整数，收到「{value}」"))
            .and_then(|n| {
                if (1..=99).contains(&n) {
                    Ok(())
                } else {
                    Err(format!("堆叠 必须在 1..=99，收到 {n}"))
                }
            }),
        "耐久" => value
            .trim()
            .parse::<i64>()
            .map_err(|_| format!("耐久 必须是整数，收到「{value}」"))
            .and_then(|n| {
                if (0..=100_000).contains(&n) {
                    Ok(())
                } else {
                    Err(format!("耐久 必须在 0..=100000，收到 {n}"))
                }
            }),
        _ => Err(format!("未知字段「{key}」，可用：{ITEM_FIELD_NAMES}")),
    }
}

/// 取一个累加型字段（`描述` / `属性`）的 JSON 数组，解析失败就从空开始。
fn item_list(inst: &DicClass, key: &str) -> Vec<serde_json::Value> {
    inst.local_value
        .get(key)
        .and_then(|v| match v {
            NValue::Str(s) => serde_json::from_str(&s).ok(),
            _ => None,
        })
        .unwrap_or_default()
}

fn item_push(inst: &DicClass, key: &str, entry: serde_json::Value) {
    let mut list = item_list(inst, key);
    list.push(entry);
    inst.local_value.set(key, NValue::Str(json!(list).to_string()));
}

/// 把对象当前字段打包成一条 `注册物品` 动作，并标记已生效。
fn item_commit_instance(inst: &Arc<DicClass>) {
    let id = item_str(inst, "物品");
    if id.is_empty() {
        return;
    }
    let plugin = item_str(inst, "插件");
    let mut def = serde_json::Map::new();
    for (key, _) in ITEM_FIELDS {
        def.insert((*key).to_string(), json!(item_str(inst, key)));
    }
    for extra in ["描述", "属性", "食物"] {
        if let Some(NValue::Str(s)) = inst.local_value.get(extra) {
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(&s) {
                def.insert(extra.into(), v);
            }
        }
    }
    let batch = MOB_GENERATION.with(|g| g.get());
    push_action(json!({
        "op": "注册物品",
        "物品": id,
        "插件": plugin,
        "定义": def,
        "批次": batch,
    }));
    if let Some(entry) = items()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get_mut(&id)
    {
        entry.committed = true;
    }
}

/// 编译结束时补提交「创建了但忘了 `生效`」的物品对象。
fn item_auto_commit(plugins: &[String], generation: u64) {
    let pending: Vec<String> = {
        let map = items().lock().unwrap_or_else(|e| e.into_inner());
        map.iter()
            .filter(|(_, e)| {
                e.generation == generation && !e.committed && plugins.iter().any(|p| p == &e.plugin)
            })
            .map(|(id, _)| id.clone())
            .collect()
    };
    for id in pending {
        let inst = items()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&id)
            .map(|e| e.instance.clone());
        if let Some(inst) = inst {
            eprintln!("[星云] 注册物品：{id} 未调用 `生效`，已自动提交");
            item_commit_instance(&inst);
        }
    }
}

/// 清掉某插件（或全部插件）的旧代次物品对象，并让宿主同步下线。
fn item_retain(plugin: Option<&str>, generation: u64) {
    let mut removed: Vec<String> = Vec::new();
    {
        let mut map = items().lock().unwrap_or_else(|e| e.into_inner());
        map.retain(|id, entry| {
            let in_scope = match plugin {
                Some(name) => entry.plugin == name,
                None => true,
            };
            let keep = !in_scope || entry.generation == generation;
            if !keep {
                removed.push(id.clone());
            }
            keep
        });
    }
    for id in removed {
        push_action(json!({
            "op": "注销物品",
            "物品": id,
            "批次": MOB_GENERATION.with(|g| g.get()),
        }));
    }
}

/// 物品对象的 `fn_map`：七个方法，所有实例共享同一份。
fn item_methods() -> Arc<HashMap<String, NasFunc>> {
    static CACHE: OnceLock<Arc<HashMap<String, NasFunc>>> = OnceLock::new();
    CACHE
        .get_or_init(|| {
            let mut m: HashMap<String, NasFunc> = HashMap::new();

            // 载入 <配置JSON文本> —— 批量设字段；文本一般由词库 `$读文件$` 读进来
            m.insert(
                "载入".into(),
                hook(|d| {
                    let Some(inst) = item_instance(d) else {
                        return ok_empty();
                    };
                    let text = d.string(1);
                    if text.trim().is_empty() {
                        item_error(d, "载入 需要：配置 JSON 文本（通常来自 $读文件$）");
                        return ok_empty();
                    }
                    if let Err(e) = apply_item_def(&inst, &text) {
                        item_error(d, e);
                    }
                    ok_empty()
                }) as NasFunc,
            );

            // 设 键 值
            m.insert(
                "设".into(),
                hook(|d| {
                    let Some(inst) = item_instance(d) else {
                        return ok_empty();
                    };
                    let key = d.string(1);
                    let value = d.string(2);
                    match item_validate(&key, &value) {
                        Ok(()) => inst.local_value.set(&key, NValue::Str(value)),
                        Err(e) => item_error(d, e),
                    }
                    ok_empty()
                }) as NasFunc,
            );

            // 外观 贴图名 —— 派生 item definition / 模型，贴图取 资源/物品材质/<名>.png
            m.insert(
                "外观".into(),
                hook(|d| {
                    let Some(inst) = item_instance(d) else {
                        return ok_empty();
                    };
                    let raw = d.string(1).trim().to_string();
                    if raw.is_empty() {
                        item_error(d, "外观 需要：贴图名（可只写名字，也可写 nebula:item/xxx）");
                        return ok_empty();
                    }
                    let model = if raw.contains(':') {
                        raw.clone()
                    } else {
                        format!("nebula:item/{raw}")
                    };
                    let leaf = model.rsplit('/').next().unwrap_or(&model).to_string();
                    inst.local_value.set("模型", NValue::Str(model));
                    if item_str(&inst, "标记").is_empty() {
                        inst.local_value.set("标记", NValue::Str(leaf));
                    }
                    ok_empty()
                }) as NasFunc,
            );

            // 加描述 文本 [颜色]
            m.insert(
                "加描述".into(),
                hook(|d| {
                    let Some(inst) = item_instance(d) else {
                        return ok_empty();
                    };
                    let text = d.string(1);
                    if text.is_empty() {
                        item_error(d, "加描述 需要：文本 [颜色]");
                        return ok_empty();
                    }
                    let mut entry = serde_json::Map::new();
                    entry.insert("文本".into(), json!(text));
                    let color = d.string(2);
                    if !color.is_empty() {
                        entry.insert("颜色".into(), json!(color));
                    }
                    item_push(&inst, "描述", serde_json::Value::Object(entry));
                    ok_empty()
                }) as NasFunc,
            );

            // 加属性 属性id 数值 [运算] [槽位]
            m.insert(
                "加属性".into(),
                hook(|d| {
                    let Some(inst) = item_instance(d) else {
                        return ok_empty();
                    };
                    let attribute = d.string(1);
                    let amount = d.float(2);
                    if attribute.is_empty() || amount == 0.0 {
                        item_error(d, "加属性 需要：属性id 非零数值 [运算] [槽位]");
                        return ok_empty();
                    }
                    let operation = {
                        let v = d.string(3);
                        if v.is_empty() {
                            "add_value".to_string()
                        } else {
                            v
                        }
                    };
                    if !matches!(
                        operation.as_str(),
                        "add_value" | "add_multiplied_base" | "add_multiplied_total"
                    ) {
                        item_error(
                            d,
                            format!(
                                "运算 只能是 add_value/add_multiplied_base/add_multiplied_total，收到「{operation}」"
                            ),
                        );
                        return ok_empty();
                    }
                    let slot = {
                        let v = d.string(4);
                        if v.is_empty() {
                            "mainhand".to_string()
                        } else {
                            v
                        }
                    };
                    let known = item_list(&inst, "属性").len();
                    let marker = {
                        let m = item_str(&inst, "标记");
                        if m.is_empty() {
                            item_str(&inst, "物品")
                        } else {
                            m
                        }
                    };
                    let mut entry = serde_json::Map::new();
                    entry.insert("属性".into(), json!(attribute));
                    entry.insert("数值".into(), json!(amount));
                    entry.insert("运算".into(), json!(operation));
                    entry.insert("槽位".into(), json!(slot));
                    entry.insert("id".into(), json!(format!("nebula:{marker}_{known}")));
                    item_push(&inst, "属性", serde_json::Value::Object(entry));
                    ok_empty()
                }) as NasFunc,
            );

            // 加食物 饥饿 [饱和] [总能吃]
            m.insert(
                "加食物".into(),
                hook(|d| {
                    let Some(inst) = item_instance(d) else {
                        return ok_empty();
                    };
                    let hunger = d.int(1);
                    if hunger <= 0 {
                        item_error(d, "加食物 需要：饥饿值(>0) [饱和] [总能吃 是/否]");
                        return ok_empty();
                    }
                    let always = matches!(d.string(3).as_str(), "是" | "true");
                    let food = json!({
                        "饥饿": hunger,
                        "饱和": d.float(2),
                        "总能吃": always,
                    });
                    inst.local_value.set("食物", NValue::Str(food.to_string()));
                    ok_empty()
                }) as NasFunc,
            );

            // 生效：提交快照（幂等）
            m.insert(
                "生效".into(),
                hook(|d| {
                    if let Some(inst) = item_instance(d) {
                        item_commit_instance(&inst);
                    }
                    ok_empty()
                }) as NasFunc,
            );

            // 卸载：单件物品即时下线
            m.insert(
                "卸载".into(),
                hook(|d| {
                    let Some(inst) = item_instance(d) else {
                        return ok_empty();
                    };
                    let id = item_str(&inst, "物品");
                    if !id.is_empty() {
                        push_action(json!({
                            "op": "注销物品",
                            "物品": id,
                            "批次": MOB_GENERATION.with(|g| g.get()),
                        }));
                        items().lock().unwrap_or_else(|e| e.into_inner()).remove(&id);
                    }
                    ok_empty()
                }) as NasFunc,
            );

            Arc::new(m)
        })
        .clone()
}

/// 把一份物品配置（JSON 文本）写进对象：词库负责读文件，这里只负责解析与设值。
///
/// 规范化三件事：`外观` 翻成 `模型` + `标记`（缺省）；`属性` 缺 `id` 时按 `<标记>_<序号>` 补；
/// `物品` / `外观` 这两个只在这里用到的键不进对象字段。
fn apply_item_def(inst: &Arc<DicClass>, text: &str) -> Result<(), String> {
    let parsed: serde_json::Value =
        serde_json::from_str(text).map_err(|e| format!("配置 JSON 解析失败（{e}）"))?;
    let Some(object) = parsed.as_object() else {
        return Err("配置顶层必须是对象".to_string());
    };
    let id = item_str(inst, "物品");
    let mut def = object.clone();

    if let Some(appearance) = object.get("外观").and_then(|v| v.as_str()) {
        let appearance = appearance.trim();
        if !appearance.is_empty() {
            let model = if appearance.contains(':') {
                appearance.to_string()
            } else {
                format!("nebula:item/{appearance}")
            };
            let leaf = model.rsplit('/').next().unwrap_or(&model).to_string();
            def.insert("模型".into(), json!(model));
            if !object.contains_key("标记") {
                def.insert("标记".into(), json!(leaf));
            }
        }
    }
    def.remove("外观");

    let marker = def
        .get("标记")
        .and_then(|v| v.as_str())
        .map(str::to_string)
        .or_else(|| {
            def.get("模型")
                .and_then(|v| v.as_str())
                .and_then(|m| m.rsplit('/').next())
                .map(str::to_string)
        })
        .unwrap_or_else(|| id.rsplit(':').next().unwrap_or("item").to_string());
    if let Some(attrs) = def.get_mut("属性").and_then(|v| v.as_array_mut()) {
        for (index, one) in attrs.iter_mut().enumerate() {
            if let Some(attr) = one.as_object_mut() {
                if !attr.contains_key("id") {
                    attr.insert("id".into(), json!(format!("nebula:{marker}_{index}")));
                }
            }
        }
    }
    def.remove("物品");

    for (key, _) in ITEM_FIELDS {
        if let Some(v) = def.get(*key) {
            let text = match v {
                serde_json::Value::String(s) => s.clone(),
                other => other.to_string(),
            };
            inst.local_value.set(*key, NValue::Str(text));
        }
    }
    for extra in ["描述", "属性", "食物"] {
        if let Some(v) = def.get(extra) {
            inst.local_value.set(extra, NValue::Str(v.to_string()));
        }
    }
    Ok(())
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

/// `参数=帮助,余额` 的值：按常见分隔符切开，逐个过 [`clean_command`]（含空白即丢弃）。
///
/// 只认逗号类分隔符，**不认空格** —— 词库里空格就是实参边界，若在这里认空格，
/// `参数=帮助 余额` 会先把 `余额` 当别的实参，反而漏掉。
fn push_sub_names(raw: &str, out: &mut Vec<String>) {
    for part in raw.split([',', '，', '、', '|', '／']) {
        if let Some(name) = clean_command(part) {
            if !out.contains(&name) {
                out.push(name);
            }
        }
    }
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

// ────────────────────── 表驱动 mc 方法（规格来自 Java 侧唯一来源） ──────────────────────
//
// 方法清单、参数位次 / 类型 / 尾参默认值全部在
// NebulaLoadMod/src/main/resources/nebula/world-specs.json 里维护。本层只把这份数据翻译成
// 引擎的注入类函数：查询类构造请求 JSON 同步回调 Java McApi.query，动作类压入动作队列。
// 宿主侧的 op 名与方法名一致，避免两边各维护一份别名。

#[derive(Clone, Copy, Debug)]
enum ArgKind {
    Str,
    Int,
    Float,
    Bool,
    Text,
    Player,
}

impl ArgKind {
    fn parse(raw: &str) -> Result<Self, String> {
        Ok(match raw {
            "Str" => Self::Str,
            "Int" => Self::Int,
            "Float" => Self::Float,
            "Bool" => Self::Bool,
            "Text" => Self::Text,
            "Player" => Self::Player,
            _ => return Err(format!("未知参数类型 {raw}")),
        })
    }
}

#[derive(Clone, Debug)]
struct Field {
    key: String,
    at: usize,
    kind: ArgKind,
    /// 仅尾部参数可设：实参不足时用这个默认值，而不是 d.int() 返回的 0。
    default: Option<String>,
}

#[derive(Clone, Debug)]
struct Spec {
    name: String,
    query: bool,
    /// 查询失败（世界未就绪 / 宿主不支持）时的回退值。
    fallback: String,
    fields: Vec<Field>,
}

/// 编译期副本只用于单测与「create 没带规格」的兜底；生产路径由 Java 传入的 JSON 覆盖。
const EMBEDDED_SPECS: &str =
    include_str!("../../NebulaLoadMod/src/main/resources/nebula/world-specs.json");
static WORLD_SPECS: OnceLock<Vec<Spec>> = OnceLock::new();

fn parse_specs(json: &str) -> Result<Vec<Spec>, String> {
    let root: serde_json::Value =
        serde_json::from_str(json).map_err(|e| format!("world-specs.json 解析失败: {e}"))?;
    let methods = root
        .get("methods")
        .and_then(|v| v.as_array())
        .ok_or_else(|| "world-specs.json 缺少 methods 数组".to_string())?;
    let mut specs = Vec::with_capacity(methods.len());
    for (i, raw) in methods.iter().enumerate() {
        let name = raw
            .get("name")
            .and_then(|v| v.as_str())
            .ok_or_else(|| format!("第 {i} 条规格缺少 name"))?
            .to_string();
        let fields = raw
            .get("fields")
            .and_then(|v| v.as_array())
            .cloned()
            .unwrap_or_default();
        let mut parsed_fields = Vec::with_capacity(fields.len());
        for (j, f) in fields.iter().enumerate() {
            let key = f
                .get("key")
                .and_then(|v| v.as_str())
                .ok_or_else(|| format!("{name} 第 {j} 个字段缺少 key"))?
                .to_string();
            let at = f
                .get("at")
                .and_then(|v| v.as_u64())
                .ok_or_else(|| format!("{name}/{key} 缺少 at"))? as usize;
            let kind = ArgKind::parse(
                f.get("kind")
                    .and_then(|v| v.as_str())
                    .ok_or_else(|| format!("{name}/{key} 缺少 kind"))?,
            )?;
            let default = f.get("default").and_then(|v| v.as_str()).map(str::to_string);
            parsed_fields.push(Field { key, at, kind, default });
        }
        specs.push(Spec {
            name,
            query: raw.get("query").and_then(|v| v.as_bool()).unwrap_or(false),
            fallback: raw
                .get("fallback")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            fields: parsed_fields,
        });
    }
    Ok(specs)
}

/// create 时把 jar 内的规格装进来；重复调用（热重启）直接复用第一份。
fn init_world_specs(json: &str) -> Result<(), String> {
    if json.trim().is_empty() {
        return Ok(());
    }
    let specs = parse_specs(json)?;
    let _ = WORLD_SPECS.set(specs);
    Ok(())
}

fn world_specs() -> &'static [Spec] {
    WORLD_SPECS
        .get_or_init(|| parse_specs(EMBEDDED_SPECS).expect("内置 world-specs.json 无效"))
        .as_slice()
}

fn default_value(field: &Field) -> serde_json::Value {
    let raw = field.default.as_deref().unwrap_or("");
    match field.kind {
        ArgKind::Int => json!(raw.parse::<i64>().unwrap_or(0)),
        ArgKind::Float => json!(raw.parse::<f64>().unwrap_or(0.0)),
        ArgKind::Bool => json!(matches!(raw, "是" | "true" | "1" | "开" | "真" | "yes" | "on")),
        _ => json!(raw),
    }
}

/// 按规格把词库实参拼成请求 / 动作 JSON。参数位次与 NebulaLoadMod/docs/mc-api-map.md 的参数列一致。
fn build_request(spec: &Spec, d: &mut DicInputs) -> serde_json::Value {
    let mut obj = serde_json::Map::new();
    obj.insert("op".into(), json!(spec.name));
    for field in &spec.fields {
        let missing = d.inputs.len() < field.at;
        let value = if missing && field.default.is_some() {
            default_value(field)
        } else {
            match field.kind {
                ArgKind::Str => json!(d.string(field.at)),
                ArgKind::Int => json!(d.int(field.at)),
                ArgKind::Float => json!(d.float(field.at)),
                ArgKind::Bool => json!(matches!(
                    d.string(field.at).as_str(),
                    "是" | "true" | "1" | "开" | "真" | "yes" | "on"
                )),
                ArgKind::Text => json!(d.inputs.string_after(field.at)),
                ArgKind::Player => json!(player_name(&d.string(field.at))),
            }
        };
        obj.insert(field.key.clone(), value);
    }
    serde_json::Value::Object(obj)
}

fn register_table_driven(m: &mut HashMap<String, NasFunc>, specs: &[Spec]) {
    for spec in specs {
        let spec = spec.clone();
        let name = spec.name.clone();
        let method: NasFunc = if spec.query {
            hook(move |d| {
                let req = build_request(&spec, d).to_string();
                let out = java_query(&req).unwrap_or_else(|| spec.fallback.clone());
                Ok(NValue::Str(out))
            })
        } else {
            hook(move |d| {
                push_action(build_request(&spec, d));
                ok_empty()
            })
        };
        // P0-1：手写 hook 优先。以前是无条件 `insert`，只是因为规格表里恰好没有同名条目
        // 才没出事；一旦有人往 world-specs.json 加了 `注册生物` 这类名字，手写实现会被
        // 静默换成表驱动空壳。`or_insert` 让「先注册的赢」成为可依赖的约定。
        m.entry(name).or_insert(method);
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

    // 注册指令 指令 [权限] 别名… [参数=值,值…]（权限为单个 0~4 数字；不写默认 0）
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
            let mut subs: Vec<String> = Vec::new();
            // `参数=` 之后一律算子参数：宿主把它注册成 Brigadier 的 literal 子节点，
            // 客户端 Tab 才看得见「/商店 帮助」「/商店 余额」。写成别名会被当成根指令。
            let mut in_subs = false;
            for i in index..=d.inputs.len() {
                let raw = d.string(i);
                let raw = raw.trim();
                if raw.is_empty() {
                    continue;
                }
                let head = raw
                    .strip_prefix("参数=")
                    .or_else(|| raw.strip_prefix("参数＝"));
                match head {
                    Some(rest) => {
                        in_subs = true;
                        push_sub_names(rest, &mut subs);
                    }
                    None if in_subs => push_sub_names(raw, &mut subs),
                    None => {
                        if let Some(name) = clean_command(raw) {
                            if name != command && !aliases.contains(&name) {
                                aliases.push(name);
                            }
                        }
                    }
                }
            }
            push_action(json!({
                "op": "注册指令",
                "指令": command,
                "别名": aliases,
                "子参数": subs,
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

    // 注册生物 品种 → 返回生物对象（`Value::Class`）。
    // 字段与方法都挂在对象上：`$怪.设 血量 20$` / `%怪.血量%` / `$怪.生效$`。
    m.insert(
        "注册生物".into(),
        hook(|d| {
            let id = d.string(1).trim().to_string();
            if id.is_empty() {
                mob_error(d, "注册生物：缺少品种 id");
                return ok_empty();
            }
            let plugin = current_plugin_name();
            let generation = MOB_GENERATION.with(|g| g.get());
            {
                let map = mobs().lock().unwrap_or_else(|e| e.into_inner());
                if let Some(existing) = map.get(&id) {
                    if existing.generation == generation && existing.plugin == plugin {
                        return Ok(NValue::Class(existing.instance.clone()));
                    }
                }
            }
            let value = Val::arc();
            value.set_class_name(Some("生物".to_string()));
            value.set("品种", NValue::Str(id.clone()));
            value.set("插件", NValue::Str(plugin.clone()));
            for (key, def) in MOB_FIELDS {
                let fallback = if *key == "名称" { id.clone() } else { (*def).to_string() };
                value.set(key, NValue::Str(fallback));
            }
            value.set("物品", NValue::Str("[]".to_string()));
            let instance = Arc::new(DicClass {
                local_value: value,
                dic_funcs: Arc::new(HashMap::new()),
                fn_map: mob_methods(),
            });
            mobs()
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .insert(
                    id,
                    MobEntry {
                        plugin,
                        instance: instance.clone(),
                        committed: false,
                        generation,
                    },
                );
            Ok(NValue::Class(instance))
        }) as NasFunc,
    );

    // 注册物品 物品id → 返回物品对象（`Value::Class`）。
    //
    // 只建空白对象，字段由词库填：`$剑.载入 %配置文本%$` 批量写，或 `$剑.设 键 值$` 逐条写。
    // 读文件这一步留给词库（`$读文件$`），宿主不碰插件目录。
    m.insert(
        "注册物品".into(),
        hook(|d| {
            let raw = d.string(1).trim().to_string();
            if raw.is_empty() {
                item_error(d, "注册物品：缺少物品 id");
                return ok_empty();
            }
            // 命名空间默认 nebula：写 `star_sword` 和写 `nebula:star_sword` 等价。
            let id = if raw.contains(':') {
                raw
            } else {
                format!("nebula:{raw}")
            };
            let plugin = current_plugin_name();
            let generation = MOB_GENERATION.with(|g| g.get());
            {
                let map = items().lock().unwrap_or_else(|e| e.into_inner());
                if let Some(existing) = map.get(&id) {
                    if existing.generation == generation && existing.plugin == plugin {
                        return Ok(NValue::Class(existing.instance.clone()));
                    }
                }
            }
            let value = Val::arc();
            value.set_class_name(Some("物品".to_string()));
            value.set("物品", NValue::Str(id.clone()));
            value.set("插件", NValue::Str(plugin.clone()));
            for (key, fallback) in ITEM_FIELDS {
                let text = if *key == "名称" {
                    id.clone()
                } else {
                    (*fallback).to_string()
                };
                value.set(key, NValue::Str(text));
            }
            let instance = Arc::new(DicClass {
                local_value: value,
                dic_funcs: Arc::new(HashMap::new()),
                fn_map: item_methods(),
            });
            items()
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .insert(
                    id,
                    ItemEntry {
                        plugin,
                        instance: instance.clone(),
                        committed: false,
                        generation,
                    },
                );
            Ok(NValue::Class(instance))
        }) as NasFunc,
    );

    // 取物品列表 → 已注册物品的展示名串，供插件打印菜单
    m.insert(
        "取物品列表".into(),
        hook(|_d| {
            let req = json!({ "op": "取物品列表" }).to_string();
            Ok(NValue::Str(
                java_query(&req).unwrap_or_else(|| "[]".to_string()),
            ))
        }) as NasFunc,
    );

    // 映射表里其余方法由规格表批量生成；已有的复杂方法（界面对象 / 指令注册）优先，
    // 不会被同名条目覆盖语义。
    register_table_driven(&mut m, world_specs());
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

// ──────────────────────── 全局对象 `java`（$java.eval） ────────────────────────

/// `$java.eval` 的宿主入口。
///
/// 词库这一侧只做转发：代码（或 `.java` 文件）连同插件归属、参数一起交给 Java 的
/// `JavaSnippet`，编译、缓存、实例化、执行都在那边。为什么不放 Rust 侧：编译要用
/// `jdk.compiler`，而类路径必须是<b>运行时类加载器</b>里的那份 Minecraft 定义，
/// 只有 JVM 内才拿得到。
fn java_methods() -> HashMap<String, NasFunc> {
    let mut m = HashMap::new();
    m.insert(
        "eval".into(),
        hook(|d| {
            let raw = d.string(1);
            let trimmed = raw.trim();
            // 两种来源：`.java` 结尾按插件目录里的文件读，否则当内联源码。
            let (code, source, read_error) = if trimmed.to_ascii_lowercase().ends_with(".java") {
                match current_plugin_dir() {
                    Some(dir) => {
                        let path = Path::new(trimmed);
                        let path = if path.is_absolute() {
                            path.to_path_buf()
                        } else {
                            dir.join(path)
                        };
                        match std::fs::read_to_string(&path) {
                            Ok(text) => (text, path.display().to_string(), None),
                            Err(e) => (
                                String::new(),
                                path.display().to_string(),
                                Some(format!("读取 {trimmed} 失败：{e}")),
                            ),
                        }
                    }
                    None => (
                        String::new(),
                        trimmed.to_string(),
                        Some(format!("找不到插件目录，无法定位 {trimmed}")),
                    ),
                }
            } else {
                (raw.clone(), "内联".to_string(), None)
            };
            let mut request = json!({
                "op": "java.eval",
                "插件": current_plugin_name(),
                "来源": source,
                "代码": code,
                "参数": d.string(2),
            });
            if let Some(error) = read_error {
                request["错误"] = serde_json::Value::String(error);
            }
            Ok(NValue::Str(
                java_query(&request.to_string()).unwrap_or_default(),
            ))
        }) as NasFunc,
    );
    m
}

/// 发布注入类 `java`：词库写 `$java.eval <代码>$` 即可用。
fn publish_java() {
    let methods = java_methods();
    let local_value = Val::arc();
    local_value.set_class_name(Some("java".to_string()));
    let instance = Arc::new(DicClass {
        local_value,
        dic_funcs: Arc::new(HashMap::new()),
        fn_map: Arc::new(methods.clone()),
    });
    imports::register_injected_class("java", methods);
    set_system_var("java", NValue::Class(instance));
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
    // `java` 跟着一起发：两者都是词库的注入根，少一个就整批 `$java.eval` 变文本。
    publish_java();
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
/// 后不用手动 load。只会读顶层平铺文件，与 `build_dic_from_dir` 的收集范围一致。
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

/// 插件场景里的「误报」诊断。
///
/// - `UNDEFINED_VAR`：`%玩家名%` / `%参数%` / `%entity.name%` 由宿主在事件/指令帧
///   里注入，逐文件静态检查看不见，插件里几乎每条事件都会命中。
/// - `UNDEFINED_FUNC`：目录库是多文件合并的，而该检查逐文件跑，`界面.n` 调 `main.n`
///   的函数会被判成未知 —— 只要这个名字在本插件里确实有定义就放过；真有笔误仍会报。
fn warning_is_noise(plugin: &Plugin, w: &nebula::model::BuildWarning) -> bool {
    use nebula::diagnostic::code;
    if w.code == code::UNDEFINED_VAR {
        return true;
    }
    if w.code == code::UNDEFINED_FUNC {
        if let Some(name) = first_dollar_name(&w.text) {
            return has_trigger(&plugin.dic, name);
        }
    }
    false
}

/// 从诊断文本里取第一个 `$名字$`（引擎文案固定以它开头）。
fn first_dollar_name(text: &str) -> Option<&str> {
    let start = text.find('$')? + 1;
    let rest = &text[start..];
    let end = rest.find('$')?;
    (!rest[..end].is_empty()).then(|| &rest[..end])
}

/// 编译期诊断的可读文本（引擎 `BuildValue.warnings`）。
///
/// 引擎会把「`$...$` 未闭合」「未知函数」「未定义变量」这类问题记进
/// `warnings`，但宿主以前整批丢弃 —— 词库里漏一个结尾 `$` 只会静默降级成
/// 文本输出，日志里一个字都没有。这里把它摊平成 `[编译错误] main.n:119 …`。
fn plugin_warnings(plugin: &Plugin) -> Vec<String> {
    plugin
        .dic
        .data
        .warnings
        .iter()
        .filter(|w| !warning_is_noise(plugin, w))
        .map(|w| {
            let level = if w.level == "error" {
                "编译错误"
            } else {
                "编译警告"
            };
            let file = Path::new(&w.file)
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_else(|| w.file.clone());
            format!("[{level}] {file}:{} {}", w.line, w.text)
        })
        .collect()
}

/// 加载/重载消息尾巴：有诊断时提示条数，细节在控制台。
fn warning_suffix(plugin: &Plugin) -> String {
    match plugin_warnings(plugin).len() {
        0 => String::new(),
        n => format!(" · {n} 条编译诊断（详见控制台）"),
    }
}

/// 把指定插件当前的编译诊断摊平成「插件 名 [编译错误] main.n:6 …」。
///
/// 走 `diagnostics` 字段回给 Java 宿主记 WARN：Rust 侧 `println!` 只到 stdout，
/// dedicated server 的 `logs/latest.log` 里看不到，而宿主 logger 会写进去。
fn plugin_diagnostics(state: &Bridge, names: &[String]) -> Vec<String> {
    let reg = state.plugins.lock().unwrap_or_else(|e| e.into_inner());
    names
        .iter()
        .filter_map(|name| reg.get(name))
        .flat_map(|plugin| {
            plugin_warnings(plugin)
                .into_iter()
                .map(|line| format!("插件 {} {line}", plugin.name))
                .collect::<Vec<_>>()
        })
        .collect()
}

/// 编译插件目录；`with_hooks=false` 时只编译、不跑 `@头部` / `[f]初始化`。
///
/// 「预编译检查」用这个：钩子有副作用（注册指令、写文件、发动作），
/// 只想看诊断时绝不能跑。
fn build_plugin(name: &str, dir: &Path, with_hooks: bool) -> Result<Plugin, String> {
    // 记下目录：`$java.eval` 读 `.java` 文件时要用（详见 java_methods）。
    remember_plugin_dir(name, dir);
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
    if !with_hooks {
        return Ok(plugin);
    }
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

fn compile_plugin(name: &str, dir: &Path) -> Result<Plugin, String> {
    build_plugin(name, dir, true)
}

/// 只编译不落地：给「先检查再上」用（测试里的随服词库预检）。
#[cfg(test)]
fn check_plugin(name: &str, dir: &Path) -> Result<Plugin, String> {
    build_plugin(name, dir, false)
}

// ────────────────────────────── 插件操作 ──────────────────────────────

/// 开始一轮编译：生物对象用代次区分「本轮声明」与「上一轮残留」。
fn begin_mob_generation() -> u64 {
    let generation = MOB_SEQ.fetch_add(1, Ordering::SeqCst);
    MOB_GENERATION.with(|g| g.set(generation));
    generation
}

/// `/nebula load`：唯一入口。没加载过就编译 + 跑 `[f]加载`；已加载就原地重编译，
/// 等价于旧的 reload（保留调试开关，不重复「首次加载」提示）。两种情况都会重跑
/// `[f]初始化`，本批没声明的东西（指令 / 生物）靠批次对账与代次回收自动下线。
fn op_load(state: &Bridge, name: &str) -> Result<String, String> {
    let old = state
        .plugins
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(name)
        .cloned();
    let (dir, debug, first) = match old {
        Some(p) => (
            p.entry
                .parent()
                .unwrap_or_else(|| Path::new("."))
                .to_path_buf(),
            p.debug,
            false,
        ),
        None => {
            let d = scan_plugins(&state.root)
                .into_iter()
                .find(|(n, _)| n == name)
                .map(|(_, d)| d)
                .ok_or_else(|| {
                    format!(
                        "找不到插件「{name}」：目录 {} 下不存在或被禁用",
                        state.root.display()
                    )
                })?;
            (d, false, true)
        }
    };
    // 先编译成功再替换：词库写坏时旧版本继续可用。
    let mut plugin = compile_plugin(name, &dir)?;
    plugin.debug = debug;
    let hook = if first {
        run_hook(&plugin, "加载")
            .map(|s| format!("（{s}）"))
            .unwrap_or_default()
    } else {
        String::new()
    };
    state
        .plugins
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .insert(name.to_string(), plugin.clone());
    // 编译成功才清旧代次：本批没声明的品种自动下线；失败时保留旧对象集。
    mob_retain(Some(name), MOB_GENERATION.with(|g| g.get()));
    item_retain(Some(name), MOB_GENERATION.with(|g| g.get()));
    Ok(if first {
        format!(
            "已加载 {name}（{} 词条，{}）{hook}{}",
            plugin.entries,
            plugin.entry.display(),
            warning_suffix(&plugin)
        )
    } else {
        format!(
            "已加载 {name}（{} 词条）{}",
            plugin.entries,
            warning_suffix(&plugin)
        )
    })
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
        Ok(format!("已关闭调试 {name}：改词库后请手动 /nebula load {name}"))
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
    // 调试重编译也开启新代次：`[f]初始化` 里的注册对象属于这一轮，
    // 否则它们会和上一轮混在一起，删掉的定义不回收（§4.3 / P1-2）。
    let generation = begin_mob_generation();
    match compile_plugin(&current.name, dir) {
        Ok(mut fresh) => {
            fresh.debug = true;
            fresh.stamp = stamp;
            println!(
                "[星云] 调试重载 {}：检测到词库改动，{} 词条",
                fresh.name, fresh.entries
            );
            reg.insert(fresh.name.clone(), fresh.clone());
            mob_retain(Some(&fresh.name), generation);
            mob_auto_commit(std::slice::from_ref(&fresh.name), generation);
            item_retain(Some(&fresh.name), generation);
            item_auto_commit(std::slice::from_ref(&fresh.name), generation);
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
        Some(_p) => {
            // 卸载完全由加载器接管：界面、生物对象、事件订阅（`EventBridge.refresh`）、
            // 插件指令（宿主按归属批量注销）都由加载器回收。
            // `[f]卸载` 钩子已移除 —— 插件不再需要写任何清理代码。
            guis()
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .retain(|_, g| g.plugin != name);
            // `0` 代次保证该插件全部生物 / 物品下线，并对宿主补发注销动作。
            mob_retain(Some(name), 0);
            item_retain(Some(name), 0);
            Ok(format!("已卸载 {name}"))
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
    // 整轮共用同一个代次：`--all` 时所有插件都属于「这一批」。
    let generation = begin_mob_generation();
    let names = match target_names(state, target, op) {
        Ok(n) => n,
        Err(e) => {
            return json!({ "summary": e, "ok": false, "actions": take_actions(), "批次": generation })
                .to_string()
        }
    };
    let mut done: Vec<String> = Vec::new();
    let mut failed: Vec<String> = Vec::new();
    let mut failed_names: Vec<String> = Vec::new();
    for name in &names {
        let result = match op {
            "load" => op_load(state, name),
            "debug" => op_debug(state, name, true),
            "undebug" => op_debug(state, name, false),
            _ => op_unload(state, name),
        };
        match result {
            Ok(msg) => done.push(msg),
            Err(e) => {
                eprintln!("[星云] {e}");
                failed.push(e);
                failed_names.push(name.clone());
            }
        }
    }
    // 未调用 `生效` 的对象在这里补提交（编译成功的那部分）。
    mob_auto_commit(&names, generation);
    item_auto_commit(&names, generation);
    let label = match op {
        "load" => "加载",
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
    // P0-3：编译失败的插件，本轮产生的动作（含 `注册生物`）一律丢弃，
    // 不能让坏词库注册出半成品；`--all` 时成功的插件照常提交。
    let mut actions = take_actions();
    if !failed_names.is_empty() {
        actions.retain(|action| {
            let owner = action.get("插件").and_then(|v| v.as_str()).unwrap_or("");
            !failed_names.iter().any(|f| f == owner)
        });
    }
    json!({
        "summary": one_line(&summary),
        "ok": failed.is_empty(),
        "actions": actions,
        "批次": generation,
        "失败插件": failed_names,
        "diagnostics": plugin_diagnostics(state, &names),
    })
    .to_string()
}

fn load_all(state: &Bridge) -> String {
    let generation = begin_mob_generation();
    let mut map = BTreeMap::new();
    let mut done: Vec<String> = Vec::new();
    let mut failed: Vec<String> = Vec::new();
    let mut loaded_names: Vec<String> = Vec::new();
    for (name, dir) in scan_plugins(&state.root) {
        match compile_plugin(&name, &dir) {
            Ok(plugin) => {
                let hook = run_hook(&plugin, "加载")
                    .map(|s| format!("（{s}）"))
                    .unwrap_or_default();
                done.push(format!(
                    "{name}（{} 词条）{hook}{}",
                    plugin.entries,
                    warning_suffix(&plugin)
                ));
                loaded_names.push(name.clone());
                map.insert(name, plugin);
            }
            Err(e) => {
                eprintln!("[星云] {e}");
                failed.push(name);
            }
        }
    }
    *state.plugins.lock().unwrap_or_else(|e| e.into_inner()) = map;
    // 开服全量加载：成功加载的插件按「本批声明」收口，失败插件不动（本来也没有旧对象）。
    for name in &loaded_names {
        mob_retain(Some(name), generation);
        item_retain(Some(name), generation);
    }
    mob_auto_commit(&loaded_names, generation);
    item_auto_commit(&loaded_names, generation);
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
    let loaded: Vec<String> = state
        .plugins
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .keys()
        .cloned()
        .collect();
    // P0-3：失败插件本轮的动作一律丢弃。
    let mut actions = take_actions();
    if !failed.is_empty() {
        actions.retain(|action| {
            let owner = action.get("插件").and_then(|v| v.as_str()).unwrap_or("");
            !failed.iter().any(|f| f == owner)
        });
    }
    json!({
        "summary": one_line(&summary),
        "ok": failed.is_empty(),
        "actions": actions,
        "批次": generation,
        "失败插件": failed,
        "diagnostics": plugin_diagnostics(state, &loaded),
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
    // 上一轮如果是在事件帧外（`[f]加载` / 指令）调过 `ctx.cancel()`，脏标志不能带到这一轮。
    CANCEL.with(|c| c.set(false));
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
        // 调试自动重编译会在事件帧前开启新代次并产生 `注册生物` 动作，
        // 宿主靠这个字段做「按批次收口」；普通事件里 BATCH_PLUGINS 为空，commit 是空操作。
        "批次": MOB_GENERATION.with(|g| g.get()),
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

/// 供宿主刷新「事件名 → 监听器」订阅表：只在 load / unload / debug 后调用。
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

/// `$java.eval` 片段取消事件：Java 的 `Ctx.cancel()` 直接写回本次分发的取消标志。
///
/// 只在事件帧内有效：`dispatch_event` 结尾的 `take_cancel()` 会消费掉它。标志按线程保存，
/// 片段本来就跑在服务端主线程，跟词库的 `$世界.取消事件$` 共用同一个 Cell。
#[no_mangle]
pub extern "system" fn Java_nebula_neoforge_NativeBridge_markCancel(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    CANCEL.with(|c| c.set(true));
}

#[no_mangle]
pub extern "system" fn Java_nebula_neoforge_NativeBridge_create(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    root: JString<'_>,
    specs_json: JString<'_>,
) -> jlong {
    let root = jstring_to_string(&mut env, &root);
    let specs_json = jstring_to_string(&mut env, &specs_json);
    // 方法规格由 jar 内的 world-specs.json 提供；解析失败时回退编译期副本，
    // 保证旧 jar / 坏资源不会让服务端起不来。
    if let Err(e) = init_world_specs(&specs_json) {
        eprintln!("[星云] world-specs.json 无效，回退内置副本：{e}");
    }
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
    mobs().lock().unwrap_or_else(|e| e.into_inner()).clear();
    items().lock().unwrap_or_else(|e| e.into_inner()).clear();
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
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// `$...$` 漏结尾 `$` 必须能在编译期被看见：引擎记 NEB0004，宿主摊平成日志行。
    /// 这条是 2026-11-14 Tab列表 事故的回归锁：漏 `$` 不报错、只静默变文本，
    /// 以前宿主把 warnings 丢掉，作者只能靠肉眼发现。
    #[test]
    fn compile_warnings_surface_unclosed_dollar() {
        let dir = temp_dir("unclosed-dollar");
        std::fs::write(
            dir.join("main.n"),
            "\n[f]初始化\n$世界.注册指令 tab 0 tab$\n\n[指令]tab\n$世界.提示 %玩家名% \"坏行\"\n",
        )
        .unwrap();
        let plugin = compile_plugin("漏美元符", &dir).unwrap();

        let lines = plugin_warnings(&plugin);
        assert!(
            lines.iter().any(|l| l.contains("未闭合")),
            "漏结尾 $ 必须产生诊断，实际：{lines:?}"
        );
        assert!(
            lines.iter().any(|l| l.contains("main.n:6")),
            "诊断要指到出问题的行，实际：{lines:?}"
        );
        assert!(
            lines.iter().all(|l| l.starts_with("[编译")),
            "诊断要带级别前缀，实际：{lines:?}"
        );
        assert!(warning_suffix(&plugin).contains("编译诊断"));

        // 写对的行不该再报
        std::fs::write(
            dir.join("main.n"),
            "\n[f]初始化\n$世界.注册指令 tab 0 tab$\n\n[指令]tab\n$世界.提示 %玩家名% \"好行\"$\n",
        )
        .unwrap();
        let ok = compile_plugin("正确", &dir).unwrap();
        assert!(
            plugin_warnings(&ok).is_empty(),
            "正确的结尾 $ 不该有诊断：{:?}",
            plugin_warnings(&ok)
        );
        assert!(warning_suffix(&ok).is_empty());
    }

    /// 掉落物堆叠的白名单是 JSON 数组，靠实参位置交给宿主 `聚拢掉落物`。
    /// 实参路径要保住 `[` / `"` 原样（旧版担心它被当算术或引号处理掉），
    /// 否则宿主解析出空白名单 → 一颗掉落物都不聚，而且**完全没有报错**。
    #[test]
    fn gather_dropstack_passes_whitelist_json() {
        nebula::init();
        publish_world();
        let _ = take_actions();
        let dir = temp_dir("gather-drops");
        std::fs::write(
            dir.join("main.n"),
            "\n[f]初始化\n文::{\"物品\":[\"minecraft:cobblestone\",\"minecraft:dirt\"],\"半径\":3}\n表:$JSON键读 %文% 物品$\n径:$JSON键读 %文% 半径$\n$世界.聚拢掉落物 minecraft:overworld %表% %径% 是 否$\n",
        )
        .unwrap();
        compile_plugin("聚拢", &dir).unwrap();

        let actions = take_actions();
        let gather = actions
            .iter()
            .find(|a| a["op"] == "聚拢掉落物")
            .unwrap_or_else(|| panic!("没有聚拢动作：{actions:?}"));
        assert_eq!(gather["维度"], "minecraft:overworld");
        assert_eq!(
            gather["白名单"], "[\"minecraft:cobblestone\",\"minecraft:dirt\"]",
            "白名单 JSON 必须原样到达宿主"
        );
        assert_eq!(gather["半径"], json!(3.0));
        assert_eq!(gather["保护"], json!(true));
        assert_eq!(gather["无限"], json!(false));
        std::fs::remove_dir_all(&dir).ok();
    }

    /// 目录库的跨文件调用不该被报成「未知函数」：逐文件静态检查看不到别的文件，
    /// 宿主这边按本插件已有的函数表放过，只留真正的笔误。
    #[test]
    fn cross_file_calls_are_not_reported_as_unknown() {
        let dir = temp_dir("cross-file");
        std::fs::write(
            dir.join("main.n"),
            "\n[f]初始化\n模式:1\n\n[函数]工具 值\n%值%\n",
        )
        .unwrap();
        std::fs::write(
            dir.join("界面.n"),
            "\n[事件]t\n结果:$工具 3$\n结果=%结果%\n",
        )
        .unwrap();
        let plugin = compile_plugin("跨文件", &dir).unwrap();
        let lines = plugin_warnings(&plugin);
        assert!(
            !lines.iter().any(|l| l.contains("工具")),
            "跨文件调用不该报未知函数：{lines:?}"
        );

        // 过滤只对「本插件确实有这个函数」生效，真笔误仍然保留
        use nebula::diagnostic::code;
        let known = nebula::model::BuildWarning::new(
            code::UNDEFINED_FUNC,
            "warning",
            "",
            1,
            1,
            "`$工具$` 既不是本词库函数，也不是内置函数：运行期会原样输出 `$...$`",
        );
        assert!(
            warning_is_noise(&plugin, &known),
            "本插件有 `工具` 时应按跨文件误报放过"
        );
        let typo = nebula::model::BuildWarning::new(
            code::UNDEFINED_FUNC,
            "warning",
            "",
            1,
            1,
            "`$工具x$` 既不是本词库函数，也不是内置函数：运行期会原样输出 `$...$`",
        );
        assert!(!warning_is_noise(&plugin, &typo), "真笔误不能被吞掉");

        // 事件帧里的 `%玩家名%` 由宿主注入，不该报未定义变量
        let injected = nebula::model::BuildWarning::new(
            code::UNDEFINED_VAR,
            "warning",
            "",
            1,
            1,
            "`%玩家名%` 在本词库内没有声明或赋值",
        );
        assert!(warning_is_noise(&plugin, &injected));
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
    fn debug_toggle_survives_reload() {
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

        // 再次 load（等价旧 reload）不能把调试开关冲掉
        let reloaded = op_load(&bridge, "test-plugin").unwrap();
        assert!(reloaded.contains("已加载"));
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

    /// `NebulaLoadMod/docs/mc-api-map.md` 是契约，`mc_methods()` 是实现：两边必须一一对应。
    /// 这样以后往映射表加方法时，忘了写函数会直接被测试拦住。
    #[test]
    fn every_mapped_mc_method_has_a_function() {
        let doc = include_str!("../../NebulaLoadMod/docs/mc-api-map.md");
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

    /// `注册指令` 的 `参数=` 段必须落成「子参数」数组：宿主靠它注册 literal 子节点，
    /// 客户端 Tab 才看得见 `/商店 帮助`。顺带锁住两件事 —— 别名不能把 `参数=` 吞进去、
    /// `参数=` 之后的零散实参也算子参数（词库作者很容易写成空格分隔）。
    #[test]
    fn register_command_parses_subs() {
        let methods = mc_methods();
        let _ = take_actions();

        let mut d = fake_inputs("注册指令", &[
            "商店",
            "0",
            "shop",
            "泥土商店",
            "参数=帮助,余额",
        ]);
        (methods.get("注册指令").unwrap())(&mut d).unwrap();
        let actions = take_actions();
        assert_eq!(actions.len(), 1, "注册指令应只压入一个动作");
        assert_eq!(actions[0]["指令"], "商店");
        assert_eq!(actions[0]["别名"], json!(["shop", "泥土商店"]));
        assert_eq!(actions[0]["权限"], 0);
        assert_eq!(actions[0]["子参数"], json!(["帮助", "余额"]));

        // 中文逗号、顿号都认；`参数=` 之后另起的实参继续算子参数。
        let _ = take_actions();
        let mut loose = fake_inputs("注册指令", &[
            "商店",
            "参数=帮助，余额",
            "设置",
        ]);
        (methods.get("注册指令").unwrap())(&mut loose).unwrap();
        let actions = take_actions();
        assert!(actions[0]["别名"].as_array().unwrap().is_empty());
        assert_eq!(actions[0]["子参数"], json!(["帮助", "余额", "设置"]));

        // 不写 `参数=` 的老写法保持原样：别名照旧、子参数为空。
        let _ = take_actions();
        let mut plain = fake_inputs("注册指令", &["商店", "0", "shop"]);
        (methods.get("注册指令").unwrap())(&mut plain).unwrap();
        let actions = take_actions();
        assert_eq!(actions[0]["别名"], json!(["shop"]));
        assert!(actions[0]["子参数"].as_array().unwrap().is_empty());
    }

    /// 规格表拼出来的 JSON 必须与 NebulaLoadMod/docs/mc-api-map.md 的参数顺序一致。
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

    /// 尾部可选参数省略时应落到声明的默认值，而不是 d.int() 的 0 / 空串。
    /// 只测动作类：查询类在单测里走 fallback，拿不到请求 JSON。
    #[test]
    fn optional_tail_params_use_defaults() {
        let methods = mc_methods();
        let cases: &[(&str, &[&str], &str, serde_json::Value)] = &[
            ("给予物品", &["Steve", "minecraft:diamond"], "数量", json!(1)),
            ("设背包物品", &["Steve", "1", "minecraft:diamond"], "数量", json!(1)),
            ("加效果", &["Steve", "minecraft:speed"], "秒", json!(30)),
            ("加效果", &["Steve", "minecraft:speed"], "等级", json!(1)),
            ("治疗玩家", &["Steve"], "数值", json!(1.0)),
            ("设置饥饿", &["Steve"], "数值", json!(20)),
            ("设置饱和度", &["Steve"], "数值", json!(5.0)),
            ("设置飞行", &["Steve"], "值", json!(true)),
            ("设置能力", &["Steve", "飞行"], "值", json!(true)),
            ("实体隐身", &["cow"], "值", json!(true)),
            ("设置区块半径", &["Steve"], "数值", json!(8)),
            ("悬浮字", &["minecraft:overworld", "0", "100", "0", "hi"], "缩放", json!(1.0)),
            ("悬浮字", &["minecraft:overworld", "0", "100", "0", "hi"], "时长", json!(20)),
            ("踢出玩家", &["Steve"], "原因", json!("")),
        ];
        for (name, args, key, expected) in cases {
            let _ = take_actions();
            let mut d = fake_inputs(name, args);
            (methods.get(*name).unwrap())(&mut d).unwrap();
            let actions = take_actions();
            assert_eq!(actions.len(), 1, "{name} 应压入一个动作");
            assert_eq!(actions[0][*key], *expected,
                "{name} 省略尾参 {key} 时应取默认值");
        }
    }

    /// `$怪.造型$` / `$怪.贴图$` 必须真的并进 `定义.外观`。
    ///
    /// <p>0.7.0 之前外观几何写死在宿主里，词库写的方块模型参数被静默丢弃 —— 作者只能靠
    /// 肉眼看「造型没变」才发现。这条测试把「声明 → 下发 JSON」这一段锁住：体/盖 两组盒子和
    /// 逐面 UV 都要能原样读到，且不能踩掉 `外观` 自己的贴图字段。</p>
    #[test]
    fn mob_shape_and_uv_merge_into_look() {
        nebula::init();
        let dir = temp_dir("mob-shape");
        std::fs::write(
            dir.join("main.n"),
            "\n[f]初始化\n\
             怪:$世界.注册生物 test:boxy$\n\
             $怪.外观 方块 nebula_bridge:mob_body_0 nebula_bridge:block/x -0.5 0 -0.5 0.45$\n\
             $怪.造型 体 2 0 2 14 10 14$\n\
             $怪.造型 盖 2 10 2 14 12 14$\n\
             $怪.贴图 体 上 0 0 8 8$\n\
             $怪.生效$\n",
        )
        .unwrap();
        let _ = compile_plugin("造型测试", &dir).unwrap();

        let actions = take_actions();
        let def = actions
            .iter()
            .find(|a| a.get("op").and_then(|v| v.as_str()) == Some("注册生物"))
            .and_then(|a| a.get("定义"))
            .expect("$怪.生效$ 必须压入一条 注册生物 动作");
        let look = &def["外观"];
        assert_eq!(look["体"], json!([2.0, 0.0, 2.0, 14.0, 10.0, 14.0]), "{look}");
        assert_eq!(look["盖"], json!([2.0, 10.0, 2.0, 14.0, 12.0, 14.0]), "{look}");
        assert_eq!(look["uv"]["体"]["up"]["uv"], json!([0.0, 0.0, 8.0, 8.0]), "{look}");
        assert_eq!(look["贴图"], json!("nebula_bridge:block/x"), "造型不能踩掉贴图");
        assert_eq!(look["偏移"], json!([-0.5, 0.0, -0.5]), "造型不能踩掉偏移");
    }

    /// `$怪.材质$` + `$怪.贴图$` 第 7 参 + `$怪.设 碰撞箱$` 必须原样落到定义里。
    ///
    /// <p>这三样都是「词库写了、宿主没读」的高危区（0.6.x 的三个空转参数就是这么来的），
    /// 所以直接锁下发 JSON 的形状：材质进 `外观.材质`，面的材质名进 `uv.<部件>.<面>.贴图`，
    /// 碰撞箱按声明原样下发（解析宽高在 Java 侧）。</p>
    #[test]
    fn mob_material_and_collision_land_in_definition() {
        nebula::init();
        let dir = temp_dir("mob-material");
        std::fs::write(
            dir.join("main.n"),
            "\n[f]初始化\n\
             怪:$世界.注册生物 test:eye$\n\
             $怪.外观 方块 nebula_bridge:mob_body_0 nebula_bridge:block/x -0.5 0 -0.5 0.45$\n\
             $怪.材质 眼睛 nebula_bridge:block/eye$\n\
             $怪.贴图 体 北 0 0 16 16 眼睛$\n\
             $怪.贴图 盖 上 0 0 16 3$\n\
             $怪.设 碰撞箱 自动$\n\
             $怪.生效$\n",
        )
        .unwrap();
        let _ = compile_plugin("材质测试", &dir).unwrap();

        let actions = take_actions();
        let def = actions
            .iter()
            .find(|a| a.get("op").and_then(|v| v.as_str()) == Some("注册生物"))
            .and_then(|a| a.get("定义"))
            .expect("要有 注册生物 动作");
        let look = &def["外观"];
        assert_eq!(look["材质"]["眼睛"], json!("nebula_bridge:block/eye"), "{look}");
        assert_eq!(look["uv"]["体"]["north"]["贴图"], json!("眼睛"), "{look}");
        assert_eq!(look["uv"]["体"]["north"]["uv"], json!([0.0, 0.0, 16.0, 16.0]), "{look}");
        // 没写第 7 参的面不该带「贴图」键（否则会去查一个空材质名）
        assert!(look["uv"]["盖"]["up"].get("贴图").is_none(), "{look}");
        assert_eq!(def["碰撞箱"], json!("自动"));
    }

    /// `$怪.目标 …$` 必须按声明顺序落成 `目标` 数组，参数按位置进 `对象` / `数值1` / `数值2`。
    ///
    /// <p>Java 侧只会认得这几种「类型」，写错一个字符串就静默没有 AI —— 所以形状在测试里锁死。</p>
    #[test]
    fn mob_goals_land_in_definition() {
        nebula::init();
        let dir = temp_dir("mob-goals");
        std::fs::write(
            dir.join("main.n"),
            "\n[f]初始化\n\
             怪:$世界.注册生物 test:hunter$\n\
             $怪.目标 注视 玩家 12$\n\
             $怪.目标 攻击 玩家 1.1 16$\n\
             $怪.目标 恐慌 1.2$\n\
             $怪.生效$\n",
        )
        .unwrap();
        let _ = compile_plugin("目标测试", &dir).unwrap();

        let actions = take_actions();
        let def = actions
            .iter()
            .find(|a| a.get("op").and_then(|v| v.as_str()) == Some("注册生物"))
            .and_then(|a| a.get("定义"))
            .expect("要有 注册生物 动作");
        let goals = def["目标"].as_array().expect("目标 必须是数组");
        assert_eq!(goals.len(), 3, "{goals:?}");
        assert_eq!(goals[0]["类型"], json!("注视"));
        assert_eq!(goals[0]["对象"], json!("玩家"));
        assert_eq!(goals[0]["数值1"], json!(12.0));
        assert_eq!(goals[1]["类型"], json!("攻击"));
        assert_eq!(goals[1]["数值2"], json!(16.0));
        // 恐慌只给一个速度，不该凭空多出「对象」
        assert!(goals[2].get("对象").is_none(), "{goals:?}");
        assert_eq!(goals[2]["数值1"], json!(1.2));
    }

    /// `$怪.交互$` 与 `$怪.掉落$` 的打包形状。
    ///
    /// <p>这两个是「玩法决策下放到词库」的第一批：右键给东西、死亡按概率掉东西。
    /// 概率缺省必须是 1.0（不写概率 = 必掉），数量缺省 1 —— 这两条写错会让「明明声明了却掉不出来」。</p>
    #[test]
    fn mob_interact_and_loot_land_in_definition() {
        nebula::init();
        let dir = temp_dir("mob-interact");
        std::fs::write(
            dir.join("main.n"),
            "\n[f]初始化\n\
             怪:$世界.注册生物 test:giver$\n\
             $怪.交互 给予 minecraft:apple 3$\n\
             $怪.掉落 minecraft:gold_nugget 0.5 2$\n\
             $怪.掉落 minecraft:stick$\n\
             $怪.生效$\n",
        )
        .unwrap();
        let _ = compile_plugin("交互测试", &dir).unwrap();

        let actions = take_actions();
        let def = actions
            .iter()
            .find(|a| a.get("op").and_then(|v| v.as_str()) == Some("注册生物"))
            .and_then(|a| a.get("定义"))
            .expect("要有 注册生物 动作");
        assert_eq!(def["交互"]["类型"], json!("给予"));
        assert_eq!(def["交互"]["物品"], json!("minecraft:apple"));
        assert_eq!(def["交互"]["数量"], json!(3));
        let drops = def["掉落"].as_array().expect("掉落 必须是数组");
        assert_eq!(drops.len(), 2, "{drops:?}");
        assert_eq!(drops[0]["物品"], json!("minecraft:gold_nugget"));
        assert_eq!(drops[0]["概率"], json!(0.5));
        assert_eq!(drops[0]["数量"], json!(2));
        assert_eq!(drops[1]["概率"], json!(1.0), "不写概率必须等于必掉");
        assert_eq!(drops[1]["数量"], json!(1));
    }

    /// 随服发行的词库必须能编译：语法错误（缩进、`如果尾`、漏 `$`）在测试里就炸出来，
    /// 不必等进游戏 `/nebula load` 才发现。
    #[test]
    fn shipped_wordlibs_compile() {        // 内置函数要先注册，否则 `$查找字$`/`$JSON键读$` 会被误判成未知函数。
        // 生产路径由 JNI create 里的 nebula::init() 完成；测试里手动补一次（幂等）。
        nebula::init();
        let javamc = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("..");
        let root = javamc.join("server-1.21.11").join("nebula");
        if !root.is_dir() {
            panic!("找不到随服词库目录：{}", root.display());
        }
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
        // 预编译检查：随服插件不仅「能编译」，还不许带任何编译诊断。
        // 漏结尾 `$`（NEB0004）这类问题编译期就能看见、运行期只会静默变文本，
        // 所以直接把它做成红灯，别等进游戏才发现「没反应」。
        let mut noisy: Vec<String> = Vec::new();
        for dir in dirs {
            // 没有 .n 的目录（纯数据目录）跳过；有词库的必须过编译。
            if plugin_entry(&dir).is_err() {
                continue;
            }
            let name = dir.file_name().unwrap().to_string_lossy().into_owned();
            let plugin = check_plugin(&name, &dir).unwrap_or_else(|e| panic!("词库 {name} 编译失败：{e}"));
            for line in plugin_warnings(&plugin) {
                noisy.push(format!("{name} {line}"));
            }
        }
        assert!(
            noisy.is_empty(),
            "随服词库有编译诊断，先修再上线（/nebula load 也能看到同样的行）：\n  {}",
            noisy.join("\n  ")
        );
    }

    /// 光有 `世界` 函数还不够：宿主必须真的有处理分支，否则方法调用会石沉大海。
    /// 这条测试把「规格表 ↔ McApi/McActions/NebulaMod 的 switch」锁在一起。
    #[test]
    fn every_method_has_a_host_handler() {
        let api = include_str!("../../NebulaLoadMod/src/main/java/nebula/neoforge/McApi.java");
        let actions = include_str!("../../NebulaLoadMod/src/main/java/nebula/neoforge/McActions.java");
        let host = include_str!("../../NebulaLoadMod/src/main/java/nebula/neoforge/NebulaMod.java");

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

        // 规格的唯一来源是 Java 侧资源；单测读同一份 JSON，锁住「规格 ↔ 宿主分支」。
        let specs = parse_specs(EMBEDDED_SPECS).unwrap();
        let queries: Vec<String> = specs.iter().filter(|s| s.query).map(|s| s.name.clone()).collect();
        let actions_spec: Vec<String> = specs.iter().filter(|s| !s.query).map(|s| s.name.clone()).collect();
        // 规格表只增不减：这里锁下限，避免"整表被覆盖"这类灾难，也免得每加一个原语就改一次数字。
        assert!(specs.len() >= 107, "world-specs.json 条目数异常：只有 {}", specs.len());
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
