# -*- coding: utf-8 -*-
"""用户手册 构建脚本：仓库根目录的 用户手册.md -> app/src/main/assets/users_manual.json

手册在应用内由 ManualActivity 用**原生 Compose** 渲染
（见 app/src/main/java/com/hnnujw/course/ui/screen/UserManualScreen.kt），
不再走 WebView + HTML。Markdown 在构建期解析成结构化 JSON：

- 章节（h2）→ chapters[]，每章带 anchor id；
- 小节（h3/h4）、段落、有序/无序列表（支持一层子项）、表格、引用块、分隔线 → blocks[]；
- 行内 **粗体** / `代码` / *斜体* / [文字](链接) → span 片段，交回 Compose 用
  AnnotatedString 渲染。

这样排版完全由 Compose 决定：跟随应用主题（浅色 / 暗色 / 壁纸玻璃），
不需要 CSS、不需要往页面里注入脚本，也没有 WebView 的开销与安全面。
手册正文仍以 用户手册.md 为唯一来源 —— 改完跑一次本脚本即可。

用法：改完 用户手册.md 后运行
    python scripts/build_manual.py
"""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "用户手册.md"
OUT = ROOT / "app" / "src" / "main" / "assets" / "users_manual.json"

# 行内记号：链接 / 粗体 / 行内代码 / 斜体。三者互不嵌套，按出现顺序切分即可。
INLINE = re.compile(r"(\[[^\]]*\]\([^)]*\)|\*\*[^*]+\*\*|`[^`]+`|\*[^*\n]+\*)")
LINK = re.compile(r"\[([^\]]*)\]\(([^)]*)\)")
BULLET = re.compile(r"^(\s*)[-*]\s+(.*)$")
ORDERED = re.compile(r"^(\s*)(\d+)\.\s+(.*)$")
HEADING = re.compile(r"^(#{1,4})\s+(.*)$")


def spans(text: str) -> list:
    """把一行 Markdown 行内文本切成 span 列表。

    span 是紧凑的 dict：{"x": 文本} 必填；"b"=粗体、"c"=行内代码、"i"=斜体、
    "a"=链接目标。字段按需出现，JSON 体积小。
    """
    out = []
    for part in INLINE.split(text):
        if not part:
            continue
        if part.startswith("["):
            m = LINK.match(part)
            if m:
                # 链接文字里可能还带粗体/代码，退一步只留纯文本 —— 手册里的链接
                # 只有目录，文字是章节名，不需要嵌套样式。
                out.append({"x": re.sub(r"[*`]", "", m.group(1)), "a": m.group(2)})
                continue
        if part.startswith("**") and part.endswith("**") and len(part) > 4:
            inner = part[2:-2]
            for sub in re.split(r"(`[^`]+`)", inner):
                if not sub:
                    continue
                if sub.startswith("`") and sub.endswith("`"):
                    out.append({"x": sub[1:-1], "b": 1, "c": 1})
                else:
                    out.append({"x": sub, "b": 1})
            continue
        if part.startswith("`") and part.endswith("`") and len(part) > 2:
            out.append({"x": part[1:-1], "c": 1})
            continue
        if part.startswith("*") and part.endswith("*") and len(part) > 2:
            out.append({"x": part[1:-1], "i": 1})
            continue
        out.append({"x": part})
    return out


def split_row(line: str) -> list:
    """切表格行：去掉首尾空单元，逐格解析行内记号。"""
    cells = [c.strip() for c in line.strip().strip("|").split("|")]
    return [spans(c) for c in cells]


def anchor_of(text: str) -> str:
    """章节锚点：与 Markdown 侧 `#1-安装与首次启动` 一致的形状，便于跨端引用。"""
    slug = re.sub(r"[\s·]+", "-", text.strip()).strip("-")
    # "1. 安装与首次启动" 会先变成 "1.-安装…"，把序号后的点一起吃掉
    return re.sub(r"\.-", "-", slug).lower()


def tidy(blocks: list) -> list:
    """折叠重复分隔线、去掉块尾多余的分隔线（前言那里连着两条 ---）。"""
    out: list = []
    for b in blocks:
        if b["t"] == "hr" and (not out or out[-1]["t"] == "hr"):
            continue
        out.append(b)
    while out and out[-1]["t"] == "hr":
        out.pop()
    return out


def parse(md_text: str) -> dict:
    lines = md_text.splitlines()
    title = ""
    preface: list = []
    chapters: list = []
    blocks: list = []          # 当前正在累积的块列表
    chapter_title = None
    chapter_anchor = None
    i = 0

    def flush_chapter() -> None:
        """把累积中的块收进当前章节（或前言）。"""
        nonlocal blocks
        if chapter_title is None:
            preface.extend(tidy(blocks))
        else:
            chapters.append({
                "id": chapter_anchor,
                "title": chapter_title,
                "blocks": tidy(blocks),
            })
        blocks = []

    while i < len(lines):
        raw = lines[i]
        line = raw.rstrip()
        stripped = line.strip()

        # 空行：块之间的分隔，忽略
        if not stripped:
            i += 1
            continue

        # 分隔线
        if stripped in ("---", "***", "___"):
            blocks.append({"t": "hr"})
            i += 1
            continue

        # 标题
        m = HEADING.match(stripped)
        if m:
            level = len(m.group(1))
            text = m.group(2).strip()
            if level == 1:
                title = text
            elif level == 2:
                flush_chapter()
                # 「目录」章节由客户端原生生成（可点击跳转），正文里这份跳过
                if text == "目录":
                    chapter_title = None
                    chapter_anchor = None
                    i += 1
                    # 跳过目录列表直到下一条分隔线
                    while i < len(lines) and lines[i].strip() not in ("---",):
                        i += 1
                    continue
                chapter_title = text
                chapter_anchor = anchor_of(text)
            else:
                blocks.append({
                    "t": "h",
                    "level": level,
                    "text": text,
                    "id": anchor_of(text),
                })
            i += 1
            continue

        # 引用块：连续的 > 行，空 > 行分段
        if stripped.startswith(">"):
            paragraphs = [[]]
            while i < len(lines) and lines[i].strip().startswith(">"):
                content = lines[i].strip()[1:].strip()
                if content:
                    paragraphs[-1].extend(spans(content))
                elif paragraphs[-1]:
                    paragraphs.append([])
                i += 1
            blocks.append({"t": "quote", "p": [p for p in paragraphs if p]})
            continue

        # 表格：连续以 | 开头的行；第二行是 --- 分隔行，丢掉
        if stripped.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                row = lines[i].strip()
                if not re.fullmatch(r"\|[\s:|-]+\|?", row):
                    rows.append(split_row(row))
                i += 1
            if rows:
                blocks.append({"t": "table", "head": rows[0], "rows": rows[1:]})
            continue

        # 无序列表（支持一层缩进子项）
        m = BULLET.match(line)
        if m:
            items: list = []
            while i < len(lines):
                cur = lines[i]
                mm = BULLET.match(cur)
                if not mm:
                    break
                indent = len(mm.group(1))
                content = spans(mm.group(2).strip())
                if indent >= 2 and items:
                    items[-1].setdefault("sub", []).append(content)
                else:
                    items.append({"s": content})
                i += 1
            blocks.append({"t": "ul", "items": items})
            continue

        # 有序列表
        m = ORDERED.match(line)
        if m:
            items = []
            start = int(m.group(2))
            while i < len(lines):
                mm = ORDERED.match(lines[i])
                if not mm:
                    break
                items.append({"s": spans(mm.group(3).strip())})
                i += 1
            blocks.append({"t": "ol", "items": items, "start": start})
            continue

        # 普通段落：连续非空、且不是其它块起始的行合并为一段
        buf = [stripped]
        i += 1
        while i < len(lines):
            nxt = lines[i].strip()
            if (not nxt or nxt.startswith("|") or nxt.startswith(">")
                    or nxt in ("---", "***", "___")
                    or HEADING.match(nxt) or BULLET.match(lines[i])
                    or ORDERED.match(lines[i])):
                break
            buf.append(nxt)
            i += 1
        blocks.append({"t": "p", "s": spans(" ".join(buf))})

    flush_chapter()

    return {
        "title": title or "用户手册",
        "preface": preface,
        "chapters": chapters,
    }


def main() -> None:
    data = parse(SRC.read_text(encoding="utf-8"))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    # 紧凑输出（无多余空白）+ 保留中文，包体积最小；不加时间戳，避免无意义 diff
    OUT.write_text(
        json.dumps(data, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
        newline="\n",
    )
    blocks = len(data["preface"]) + sum(len(c["blocks"]) for c in data["chapters"])
    print(
        f"written {OUT} ({OUT.stat().st_size} bytes, "
        f"{len(data['chapters'])} chapters, {blocks} blocks)"
    )


if __name__ == "__main__":
    main()
