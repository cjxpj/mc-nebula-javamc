#!/usr/bin/env python3
"""从 NebulaLoadMod/docs/*.md 重建 docs/index.html 里内嵌的 `const DOCS`。

背景：文档站是一个纯静态单页，所有 markdown 全文内嵌在 index.html 的 `const DOCS`
里。build.ps1 只把 docs/ 目录**拷贝**进 jar，不会重新生成这份内嵌数据 —— 所以改完
任何 .md 之后必须跑本脚本，否则网页内容会停在旧版本。

2026-11-15 修：此前内嵌 JSON 是手工拼的，markdown 里 ```json 代码块的双引号没有转义，
`const DOCS = {...}` 本身就是非法 JS（整站白屏）。本脚本用 json.dumps 生成，保证转义。

用法：python NebulaLoadMod/tools/gen_docs.py
"""

import io
import json
import os
import sys
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
MOD_DIR = os.path.dirname(HERE)
DOCS_DIR = os.path.join(MOD_DIR, "docs")
INDEX = os.path.join(DOCS_DIR, "index.html")

# 顺序 = 网页左侧目录顺序；key 是锚点 id，改动会让旧链接失效。
SECTIONS = [
    ("guide", "plugin-guide.md", "词库插件开发指南", "快速开始"),
    ("apiindex", "apis/README.md", "API 写在前面 · 约定", "API 参考"),
    ("events", "apis/事件.md", "事件清单", "API 参考"),
    ("block", "apis/方块.md", "方块", "API 参考"),
    ("coord", "apis/坐标.md", "坐标", "API 参考"),
    ("player", "apis/玩家.md", "玩家", "API 参考"),
    ("world", "apis/世界与服务器.md", "世界与服务器", "API 参考"),
    ("entity", "apis/实体.md", "实体", "API 参考"),
    ("interact", "apis/交互与事件.md", "交互与事件", "API 参考"),
    ("guicmd", "apis/指令与界面.md", "指令与界面", "API 参考"),
    ("neoforge", "neoforge.md", "设计与实现现状", "设计与实现"),
    ("api", "mc-api-map.md", "世界 方法映射（契约）", "设计与实现"),
    ("choice", "platform-choice.md", "平台取舍（归档）", "设计与实现"),
    ("index", "README.md", "文档索引 · 维护约定", "总览"),
]

MARK_START = "const DOCS = "
MARK_END = "};\n"


def build_docs():
    docs = OrderedDict()
    for key, rel, title, group in SECTIONS:
        path = os.path.join(DOCS_DIR, rel.replace("/", os.sep))
        if not os.path.isfile(path):
            raise SystemExit("缺少文档：%s" % path)
        with io.open(path, encoding="utf-8") as f:
            text = f.read()
        docs[key] = OrderedDict(
            [("file", rel), ("title", title), ("group", group), ("text", text)]
        )
    return docs


def main():
    with io.open(INDEX, encoding="utf-8") as f:
        html = f.read()

    start = html.find(MARK_START)
    if start < 0:
        raise SystemExit("index.html 里找不到 `%s`" % MARK_START)
    start += len(MARK_START)
    end = html.find(MARK_END, start)
    if end < 0:
        raise SystemExit("index.html 里找不到 DOCS 结尾 `%s`" % MARK_END.strip())
    end += 1  # 保留右花括号

    payload = json.dumps(build_docs(), ensure_ascii=False)
    out = html[:start] + payload + html[end:]

    with io.open(INDEX, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)

    # 自检：写回去的这段必须真的是合法 JSON。
    i = out.find(MARK_START) + len(MARK_START)
    j = out.find(MARK_END, i) + 1
    parsed = json.loads(out[i:j])
    print(
        "DOCS 已重建：%d 篇，%d 字符（%s）"
        % (len(parsed), len(payload), ", ".join(parsed.keys()))
    )


if __name__ == "__main__":
    sys.exit(main())
