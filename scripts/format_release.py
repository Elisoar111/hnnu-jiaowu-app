#!/usr/bin/env python3
"""排版「本次更新说明」：写一份草稿，生成 release-notes/vX.Y.Z.md。

为什么要有这个程序
------------------
每发一版都要写 `release-notes/vX.Y.Z.md`，其中 `## notes` 区块是应用内更新弹窗的
正文，排版有硬约束 —— 手写容易踩一批**"写错了不报错、只是用户看不到"**的坑：

  1. `release-notes/vX.Y.Z.md` 的 `## notes` 区块 —— 应用内更新弹窗的正文。
     `AppUpdateDialog` 现在用 `MarkdownText` 渲染它，所以 `**加粗**` 与 `- ` 列表
     **是给它看的排版**，可以写。仍有两个禁区：行首 `#`（会让 notes 区块在抽取时
     被截断，内容凭空消失且不报错）与反引号（窄弹窗里等宽底纹和中文糊在一起）；
     `notes_violations` 只拦这两类。段与段之间**必须空一行**——Markdown 的单个换行
     只是软换行，没空行就会被并成一大段。
  2. `## changelog` 区块 —— Markdown 随便写，程序不解析它，原样搬进产物。

手写的代价是踩坑时没有任何提示：

  - notes 里混进反引号 → 弹窗里原样显示这个符号，**不会报错**；
  - notes 里出现行首 `#` → 抽取时被截断，后面的内容凭空消失，**不会报错**；
  - 段与段之间漏了空行 → 弹窗里被并成一大段，**不会报错**。

所以草稿只写一份、成品由程序生成，生成后当场用发版链条上真正的那两个校验器
（`release_notes.sh` 的 `notes_violations`、`publish.py` 的 `release_notes_for`）
验一遍再落盘。

草稿格式（`release-notes/drafts/<tag>.md`）
-----------------------------------------
    # v1.2.6

    ## 标题
    课表新增日视图，桌面小组件回归，顶栏更清爽
    （一句话概括这一版，预览页顶部会显示。省略则用各章标题拼）

    ## 概览
    这一版围绕课表做了一次大改版。          ← notes 的开场白，纯文本一段

    ## 章节

    ### 课表日视图
    课表顶栏多了「日 / 周」切换，一天的课按时间排开。   ← 章首段 = bits 的「摘要」
    - 顶栏新增「日 / 周」切换
    - 正在上的课标「正在上课」

    ### 桌面小组件
    桌面小组件回归，提供三种样式。
    - 双课程 / 简洁单课 / 今日时间轴

    ## 结尾
    感谢每一位使用与支持本项目的同学。

    ## 强制更新
    本次更新是必须的：教务端改了登录协议，旧版将无法登录。

    ## 变更

    ### 新增
    - `ScheduleAgenda`（…）

    ### 变更
    - 顶栏动作区重构

五条规则：
  - 每个 `### <章>` 的**第一段**是「摘要」，它进 notes（一行）：缺了它，这一章在
    弹窗里就一个字都没有；`- ` 列表项不进 notes（notes 不允许行首 `-`），现在也
    没有别的去处 —— 保留解析只是让既有草稿照常能过；
  - `## 概览` / `## 结尾` 进 notes 的开头与结尾，不加小标题；
  - `## 强制更新` 可选，只要有这一节，notes 顶部就会写入 `[force-update]` 标记行
    与紧随其后的「为什么强制」（这一行用户在弹窗里**看得到**，要写成人话）；
  - `## 变更` 原样进 `## changelog`（Markdown 随便写）；
  - 其余 `## ` 小节名一律报错，避免把 `## 章节名字` 这种笔误静默吞掉。

用法
----
    python scripts/format_release.py                 # 用 version.properties 的版本
    python scripts/format_release.py 1.2.6           # 指定版本（草稿必须是 drafts/v1.2.6.md）
    python scripts/format_release.py --check         # 只校验，不写盘
    python scripts/format_release.py --dry-run       # 打印将要写入的内容
    python scripts/format_release.py --init          # 没有草稿时生成骨架
    python scripts/format_release.py --preview       # 额外写出排版预览页（HTML）

看排版预览
----------
notes 会被弹窗按 Markdown 渲染，光看源码判断不出"读起来是什么样"。所以：

  - 每次运行都会在终端里把更新弹窗的正文逐行打一遍（`[force-update]` 标记会注明
    应用里会被剥掉）；
  - `--preview`（或实际写盘时自动）再生成 `release-notes/preview/<tag>.html`，
    一份自包含的预览页，三块内容：① 应用内更新弹窗的长相；② 每章摘要分别落在
    notes 的第几行；③ changelog 区块。
"""

from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys
from dataclasses import dataclass, field

ROOT = pathlib.Path(__file__).resolve().parent.parent
VERSION_PROPERTIES = ROOT / "app" / "version.properties"
RELEASE_NOTES_DIR = ROOT / "release-notes"
DRAFT_DIR = RELEASE_NOTES_DIR / "drafts"
RELEASE_NOTES_SH = ROOT / "scripts" / "release_notes.sh"
# 预览页只是给人看的，不属于产物（.gitignore 已忽略这个目录）。
PREVIEW_DIR = RELEASE_NOTES_DIR / "preview"

# 复用发布程序里的版本号公式与 bash 查找：这两个都是"三处必须一致"的东西
# （release.sh / release.yml / publish.py），再抄一份就是等着某天算出不同的 code。
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import publish  # noqa: E402

# `release_notes.sh` 的 notes_violations 是这条规则的唯一权威；本地这份只是为了让
# 报错能指到具体行、且在没有 bash 的机器上也能拦住明显的错误。
LOCAL_NOTES_VIOLATION = re.compile(r"(^[ \t]*#)|`")

# 强制更新标记。唯一权威是 app 侧的 `AppUpdateChecker.FORCE_UPDATE_MARKER`，这里必须
# 与它逐字符一致（改了那边忘了这边，标记就白写：用户看不到强制更新的提示，也不报错）。
# 写法上的两条硬约束见 scripts/release_notes.sh 的文件头：必须是可见的方括号文本、
# 必须独立成行、不能以 `#` 开头。
FORCE_UPDATE_MARKER = "[force-update]"

CN_DIGITS = "〇一二三四五六七八九"


class DraftError(SystemExit):
    """草稿写错了 —— 报错要指到具体位置，而不是抛一句笼统的"解析失败"。"""


def die(message: str) -> None:
    raise DraftError(f"ERROR: {message}")


def cn_number(value: int) -> str:
    """章序号：0 → 〇、1 → 一、10 → 十、11 → 十一。

    章节编号**从「一」开始**（调用处传 index + 1）。早先首章是「〇」，
    但那是编号从 0 起，读起来别扭 —— 已按作者要求改掉，别再退回去。
    """
    if 0 <= value < 10:
        return CN_DIGITS[value]
    if value < 20:
        return "十" + (CN_DIGITS[value - 10] if value > 10 else "")
    if value < 100:
        tens, ones = divmod(value, 10)
        return CN_DIGITS[tens] + "十" + (CN_DIGITS[ones] if ones else "")
    die(f"章序号 {value} 超出中文数字的支持范围")


# ── 草稿模型 ────────────────────────────────────────────────────────────────

@dataclass
class Block:
    """章体内的一块：`text` 与 `items` 至多有一个非空。"""

    text: str = ""
    items: list[str] = field(default_factory=list)

    @property
    def is_list(self) -> bool:
        return bool(self.items)


@dataclass
class Section:
    title: str
    blocks: list[Block] = field(default_factory=list)

    @property
    def summary(self) -> str:
        """章首段。notes 里它独占一行 —— 摘要缺失就没法给用户一行说明，直接拦下。"""
        for block in self.blocks:
            if not block.is_list:
                return block.text
        die(f"章节「{self.title}」缺少摘要：`### {self.title}` 下面要先写一段说明文字，"
            "再写 `- ` 列表项（notes 只收摘要，列表项请写进 `## 变更`）")


@dataclass
class Draft:
    tag: str
    title: str = ""
    overview: str = ""
    sections: list[Section] = field(default_factory=list)
    closing: str = ""
    force_reason: str = ""
    changes: list[tuple[str, list[str]]] = field(default_factory=list)

    @property
    def version(self) -> str:
        return self.tag.lstrip("vV")


TOP_LEVEL = ("标题", "概览", "章节", "结尾", "强制更新", "变更")


def parse_draft(text: str, path_for_error: str = "<草稿>") -> Draft:
    """把草稿解析成 [Draft]。不认识的 `## ` 小节名一律报错（笔误不该被静默吞掉）。"""
    draft: Draft | None = None
    section_kind: str | None = None
    current: Section | None = None
    change_group: str | None = None
    paragraph: list[str] = []
    in_comment = False

    def flush_paragraph() -> None:
        nonlocal paragraph
        if not paragraph:
            return
        # 连续的纯文本行合并成一段：中文按行硬折行是写稿时的常态，合并后才是
        # 一段连贯的话（换行处不补空格，中文里补空格反而错）。
        body = "".join(paragraph).strip()
        paragraph = []
        if not body:
            return
        if section_kind == "章节" and current is not None:
            current.blocks.append(Block(text=body))
        elif section_kind == "变更":
            # 变更区只认 `- ` 列表项，混进普通段落说明写错了地方。
            die(f"{path_for_error}: `## 变更` 里只能写 `- ` 列表项，"
                f"`{change_group or '未分组'}` 下出现了普通段落：{body[:40]}")
        elif section_kind == "标题":
            draft.title = body if draft is not None else body
        elif section_kind == "概览":
            if draft is not None:
                draft.overview = body
        elif section_kind == "结尾":
            if draft is not None:
                draft.closing = body
        elif section_kind == "强制更新":
            if draft is not None:
                draft.force_reason = body
        else:
            die(f"{path_for_error}: 正文出现在 `## ` 小节之外（{body[:40]}）")

    for raw in text.splitlines():
        line = raw.rstrip()
        # HTML 注释整块跳过 —— release_notes.sh 的 extract_notes 也会把注释剥掉，
        # 两边口径一致；顺带让草稿可以把"这一版暂不启用"的可选小节（比如
        # `## 强制更新`）留成注释，取消注释即生效。
        if in_comment:
            if "-->" in line:
                in_comment = False
            continue
        if "<!--" in line:
            flush_paragraph()
            if "-->" not in line:
                in_comment = True
            continue
        heading = re.match(r"^##[ \t]+(\S.*?)[ \t]*$", line)
        if line.startswith("# ") and not line.startswith("## "):
            flush_paragraph()
            if draft is not None:
                die(f"{path_for_error}: 出现了第二个 `# ` 标题（{line}）")
            tag = line[2:].strip()
            if not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
                die(f"{path_for_error}: 一级标题必须是 `# vX.Y.Z`，现在是 `{line}`")
            draft = Draft(tag=tag)
            section_kind = None
            continue
        if heading:
            flush_paragraph()
            if draft is None:
                die(f"{path_for_error}: `## {heading.group(1)}` 出现在 `# vX.Y.Z` 之前")
            name = heading.group(1).strip()
            if name not in TOP_LEVEL:
                die(f"{path_for_error}: 不认识的小节 `## {name}`，"
                    f"只允许 {'/'.join(TOP_LEVEL)}")
            section_kind = name
            current = None
            change_group = None
            continue
        if line.startswith("### ") and not line.startswith("#### "):
            flush_paragraph()
            sub = line[4:].strip()
            if section_kind == "章节":
                current = Section(title=sub)
                draft.sections.append(current)
            elif section_kind == "变更":
                change_group = sub
                draft.changes.append((sub, []))
            else:
                die(f"{path_for_error}: `### {sub}` 只能出现在 `## 章节` / `## 变更` 里面")
            continue
        if not line.strip():
            flush_paragraph()
            continue
        bullet = re.match(r"^[ \t]*[-*][ \t]+(.*)$", line)
        if bullet:
            # 先把攒着的段落落盘，否则「段落 + 列表」的顺序会被反过来 —— 渲染出来
            # 变成列表在前、说明在后，notes 读起来很别扭。
            flush_paragraph()
            if section_kind == "章节" and current is not None:
                if current.blocks and current.blocks[-1].is_list:
                    current.blocks[-1].items.append(bullet.group(1).strip())
                else:
                    current.blocks.append(Block(items=[bullet.group(1).strip()]))
            elif section_kind == "变更" and draft.changes:
                draft.changes[-1][1].append(bullet.group(1).strip())
            else:
                die(f"{path_for_error}: 列表项出现在不该出现的地方：{line.strip()[:40]}")
            continue
        paragraph.append(line.strip())

    flush_paragraph()
    if draft is None:
        die(f"{path_for_error}: 找不到 `# vX.Y.Z` 一级标题")
    if not draft.sections:
        die(f"{path_for_error}: `## 章节` 下至少要有 `### 一章`（notes 以章节为主体）")
    for section in draft.sections:
        _ = section.summary  # 触发摘要缺失的校验
    return draft


# ── 渲染 ────────────────────────────────────────────────────────────────────

def render_notes(draft: Draft) -> str:
    """`## notes` 区块的正文：段与段之间空一行。

    顺序是 [force-update] → 概览 → 各章摘要 → 结尾。

    空行必须写进产物：应用内的更新弹窗按 Markdown 渲染，单个换行只是软换行，
    段与段之间少了空行就会被并成一大段（`extract_notes` 现在也改成保留空行了）。
    每一段仍写成**一行**——换行即分段，段内不要自己折行。
    """
    paragraphs: list[str] = []
    if draft.force_reason:
        # 标记必须独立成行、且是可见文本（不能写成 HTML 注释 —— extract_notes
        # 会把注释整行剥掉，标记就到不了 Release 正文）；下一行的「为什么强制」
        # 用户看得到，所以它是人话。
        paragraphs.append(FORCE_UPDATE_MARKER)
        paragraphs.append(draft.force_reason)
    if draft.overview:
        paragraphs.append(draft.overview)
    paragraphs.extend(section.summary for section in draft.sections)
    if draft.closing:
        paragraphs.append(draft.closing)
    return "\n\n".join(paragraphs) + "\n"


def render_changelog(draft: Draft) -> str:
    """`## changelog` 区块：变更分组原样保留。"""
    parts = []
    for name, bullets in draft.changes:
        body = "\n".join(f"- {item}" for item in bullets)
        parts.append(f"### {name}\n\n{body}")
    return "\n\n".join(parts) + "\n"


def render_release_notes_file(draft: Draft) -> str:
    return (
        f"# v{draft.version} 更新说明\n\n"
        f"<!-- 由 scripts/format_release.py 从 release-notes/drafts/{draft.tag}.md 生成，"
        f"改内容请改草稿后重跑，别直接改这里 -->\n\n"
        f"## notes\n\n"
        f"{render_notes(draft)}"
        f"\n## changelog\n\n"
        f"{render_changelog(draft)}"
    )


# ── 预览（把成品按应用里的样子显示出来）────────────────────────────────────
#
# 更新弹窗里的 notes 会被按 Markdown 渲染，光看源码判断不出"读起来是什么样"。
# `render_preview_html` 生成一份自包含的预览页，把弹窗长相与摘要对照表摆在一起。
# 页面用到的 Markdown 子集（段落 / `**加粗**` / 编号列表）交给
# `render_markdown_html` 渲染，不引第三方库。

MD_BOLD = re.compile(r"\*\*([^*]+)\*\*")
MD_ORDERED = re.compile(r"^\s*(\d+)\.\s+(.*)$")
MD_HEADING = re.compile(r"\*\*[^*]+\*\*")


def _md_inline_html(text: str) -> str:
    parts: list[str] = []
    pos = 0
    for match in MD_BOLD.finditer(text):
        parts.append(escape_html(text[pos:match.start()]))
        parts.append(f"<strong>{escape_html(match.group(1))}</strong>")
        pos = match.end()
    parts.append(escape_html(text[pos:]))
    return "".join(parts)


def escape_html(text: str) -> str:
    return (text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace('"', "&quot;"))


def render_markdown_html(markdown: str) -> str:
    """把 Markdown 子集（段落 / 加粗 / 编号列表）渲染成 HTML。

    主流程已经不再用它（更新弹窗自己渲染），留着是因为它是一份通用的小工具，
    单测也还在覆盖它。
    """
    html: list[str] = []
    paragraph: list[str] = []
    ordered: list[str] = []

    def flush_paragraph() -> None:
        if paragraph:
            html.append("<p>" + "<br>".join(_md_inline_html(x) for x in paragraph) + "</p>")
            paragraph.clear()

    def flush_ordered() -> None:
        if ordered:
            html.append("<ol>" + "".join(f"<li>{_md_inline_html(x)}</li>" for x in ordered)
                        + "</ol>")
            ordered.clear()

    for line in markdown.splitlines():
        numbered = MD_ORDERED.match(line)
        stripped = line.strip()
        if not stripped:
            flush_paragraph()
            flush_ordered()
        elif numbered:
            flush_paragraph()
            ordered.append(numbered.group(2))
        elif MD_HEADING.fullmatch(stripped):
            # 整行只有一个加粗片段 = 小标题，它不是段落的一部分，独占一行才像应用里的样子。
            flush_paragraph()
            flush_ordered()
            html.append(f'<p class="h">{_md_inline_html(stripped)}</p>')
        else:
            flush_ordered()
            paragraph.append(stripped)
    flush_paragraph()
    flush_ordered()
    return "\n".join(html)


PREVIEW_CSS = """
:root { color-scheme: light dark; }
* { box-sizing: border-box; }
body { margin: 0; padding: 28px 20px 56px; font-family: -apple-system, "PingFang SC",
       "Microsoft YaHei", "Segoe UI", sans-serif; line-height: 1.7;
       background: #f2f3f5; color: #1c1c1e; }
.wrap { max-width: 760px; margin: 0 auto; }
h1 { font-size: 21px; margin: 0 0 4px; }
.sub { color: #8a8a8e; font-size: 13px; margin-bottom: 22px; }
.card { background: #fff; border: 1px solid rgba(0,0,0,.07); border-radius: 18px;
        padding: 18px 20px; margin-bottom: 18px; box-shadow: 0 1px 3px rgba(0,0,0,.05); }
.card > h2 { font-size: 13px; font-weight: 600; letter-spacing: .06em; color: #6c6c70;
             margin: 0 0 14px; text-transform: uppercase; }
dialog { display: block; border: none; padding: 0; margin: 0; background: none; }
.dialog { border-radius: 16px; background: #fff; border: 1px solid rgba(0,0,0,.08);
          padding: 18px; }
.dialog h3 { margin: 0 0 2px; font-size: 16px; }
.dialog .meta { color: #8a8a8e; font-size: 12px; margin-bottom: 12px; }
.dialog p { margin: 0 0 12px; font-size: 14px; }
.dialog p:last-child { margin-bottom: 0; }
.dialog .force { color: #c0392b; font-weight: 600; }
table { width: 100%; border-collapse: collapse; font-size: 13.5px; }
td { padding: 7px 10px; border-bottom: 1px solid rgba(0,0,0,.06); vertical-align: top; }
td:first-child { width: 42%; color: #6c6c70; }
code, pre { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
pre { background: #f6f6f8; border-radius: 10px; padding: 12px 14px; overflow-x: auto;
      font-size: 12.5px; line-height: 1.6; margin: 0; white-space: pre-wrap; }
.err { color: #c0392b; }
@media (prefers-color-scheme: dark) {
  body { background: #0e0e10; color: #f2f2f4; }
  .card, .dialog { background: #1c1c1f; border-color: rgba(255,255,255,.08);
                   box-shadow: none; }
  .card > h2, .sub, .dialog .meta { color: #9a9aa0; }
  td { border-color: rgba(255,255,255,.08); }
  td:first-child { color: #9a9aa0; }
  pre { background: #232326; }
  .dialog .force { color: #ff6b6b; }
}
"""


def render_preview_html(draft: Draft, code: int, notes_text: str,
                        release_notes_body: str) -> str:
    """自包含的排版预览页：更新弹窗里读起来是什么样 + 每章摘要落在哪一行。"""
    notes_lines = [line for line in notes_text.splitlines() if line.strip()]
    dialog_body = []
    for line in notes_lines:
        css = " class=\"force\"" if line == FORCE_UPDATE_MARKER else ""
        # [force-update] 只是给机器看的标记，应用里会被 stripMarkdown 摘掉。
        if line == FORCE_UPDATE_MARKER:
            dialog_body.append(
                '<p style="color:#8a8a8e;font-size:12px">'
                f'（下一行是不升级的具体说明；<code>{escape_html(line)}</code> '
                '标记会在应用里被剥掉，仅在 Gitee Release 页可见）</p>')
            continue
        dialog_body.append(f"<p{css}>{escape_html(line)}</p>")

    # 对照表要说清"这段摘要在 notes 里是第几行"，所以行号得按 notes 的真实顺序数，
    # 不能用表格的行数凑 —— notes 顶部有 [force-update] 两行时就会整体错位。
    rows: list[tuple[str, str]] = []
    cursor = 0
    if draft.force_reason:
        cursor += 1
        rows.append((f"notes 第 {cursor} 行（机器标记，应用里看不到）",
                     f"<code>{escape_html(FORCE_UPDATE_MARKER)}</code>"))
        cursor += 1
        rows.append((f"notes 第 {cursor} 行（不升级的原因，弹窗里可见）", draft.force_reason))
    if draft.overview:
        cursor += 1
        rows.append((f"notes 第 {cursor} 行（开场白）", escape_html(draft.overview)))
    for index, section in enumerate(draft.sections):
        cursor += 1
        rows.append((f"notes 第 {cursor} 行 · 第{cn_number(index + 1)}章「"
                     f"{escape_html(section.title)}」", escape_html(section.summary)))
    if draft.closing:
        cursor += 1
        rows.append((f"notes 第 {cursor} 行（结尾）", escape_html(draft.closing)))

    table = "\n".join(
        f"<tr><td>{label}</td><td>{text}</td></tr>" for label, text in rows)

    return f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{escape_html(draft.tag)} · 排版预览</title>
<style>{PREVIEW_CSS}</style>
</head>
<body>
<div class="wrap">
  <h1>{escape_html(draft.tag)} 排版预览</h1>
  <div class="sub">versionCode {code} · 仅预览，不是产物</div>

  <div class="card">
    <h2>① 应用内更新弹窗（notes 就是弹窗正文）</h2>
    <div class="dialog">
      <h3>发现新版本</h3>
      <div class="meta">版本 v{escape_html(draft.version)} · 本次更新须知</div>
      {''.join(dialog_body)}
    </div>
  </div>

  <div class="card">
    <h2>② 一段摘要进两个地方（章摘要 = notes 一行 = changelog 一条）</h2>
    <table>{table}</table>
  </div>

  <div class="card">
    <h2>③ release-notes/{escape_html(draft.tag)}.md 的 changelog 区块</h2>
    <pre>{escape_html(release_notes_body.split('## changelog', 1)[-1].strip())}</pre>
  </div>
</div>
</body>
</html>
"""


# ── 落盘 ────────────────────────────────────────────────────────────────────

def write_text_lf(path: pathlib.Path, text: str) -> None:
    """一律写 LF。

    Python 在 Windows 上默认把 `\\n` 翻成 CRLF，而仓库里的产物都是 LF；不显式指定
    就会留下一个"全文件每一行都变了"的 diff，还会让 CI 的 sed/awk 多带一个 `\\r`。
    """
    path.write_bytes(text.encode("utf-8"))


# ── 校验 ────────────────────────────────────────────────────────────────────

def notes_violations(text: str) -> list[str]:
    """notes 里的 Markdown 标记。先本地扫一遍（能报行号），再请 bash 复核。

    真正的闸门是 `release_notes.sh` 的 `notes_violations` —— 发版时拦人的就是它；
    这里调同一份实现，是为了让"本地排版过了、发版被拒"这种事不发生。
    """
    found = [
        f"{number}: {line}" for number, line in enumerate(text.splitlines(), start=1)
        if LOCAL_NOTES_VIOLATION.search(line)
    ]
    if found:
        return found
    bash = publish.find_bash()
    if not bash:
        return []
    script = RELEASE_NOTES_SH if RELEASE_NOTES_SH.is_file() else publish.RELEASE_NOTES_SH
    proc = subprocess.run(
        [bash, "-c", 'source "$1"; notes_violations', "_", script.as_posix()],
        input=text, capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    return [line for line in proc.stdout.splitlines() if line.strip()]


def validate(draft: Draft, notes_text: str) -> None:
    """生成物的自检。这里的每一条都对应一个"写错了不会报错、只是用户看不到"的坑。"""
    if draft.tag != f"v{draft.version}":
        die(f"草稿的一级标题 `{draft.tag}` 与版本号对不上")

    violations = notes_violations(notes_text)
    if violations:
        die("notes 里有 Markdown 标记（行首 # * - 或反引号）。这段文字会被应用当纯文本"
            "逐行显示，符号会原样出现在更新弹窗里。技术细节请写进 `## 变更`：\n       "
            + "\n       ".join(violations))


# ── 草稿骨架 ────────────────────────────────────────────────────────────────

SKELETON = """# {tag}

## 标题
一句话概括这一版（只进 changelog 的标题行，用户看不到）

## 概览
这一版围绕……（notes 的开场白，纯文本一段）

## 章节

### 第一件事
这一章的一句话说明（进 notes 一行，同时进 changelog 一条）

### 第二件事
这一章的一句话说明。

## 结尾
感谢每一位使用与支持本项目的同学。

<!-- 需要"不升级就没法用"时取消注释：
## 强制更新
本次更新是必须的：请写清原因（这一行用户在更新弹窗里看得到）。
-->

## 变更

### 新增
- `类名`（做了什么）

### 变更
- 改了什么

### 文档
- 用户手册：改了什么
"""


# ── 入口 ────────────────────────────────────────────────────────────────────

def parse_args(argv):
    parser = argparse.ArgumentParser(
        prog="format_release.py",
        description="把 release-notes/drafts/<tag>.md 排版成 release-notes/<tag>.md。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__.split("用法\n----\n", 1)[-1],
    )
    parser.add_argument("version", nargs="?",
                        help="版本号（如 1.2.6）。省略则用 app/version.properties 里的版本")
    parser.add_argument("--check", action="store_true", help="只校验，不写盘")
    parser.add_argument("--dry-run", action="store_true", help="打印将要写入的内容，不写盘")
    parser.add_argument("--init", action="store_true",
                        help="草稿不存在时生成骨架（不覆盖已有草稿）")
    parser.add_argument("--preview", action="store_true",
                        help="写出 release-notes/preview/<tag>.html 排版预览页")
    args = parser.parse_args(argv)
    if args.version and re.fullmatch(r"\d+\.\d+\.\d+", args.version) is None:
        parser.error("版本号形如 X.Y.Z（不带 v 前缀）")
    return args


def main(argv=None):
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:  # noqa: BLE001 - 老终端不支持就算了
            pass

    args = parse_args(sys.argv[1:] if argv is None else argv)
    version, code = publish.read_version()
    if args.version:
        version = args.version
        code = publish.version_code_of(version)
        file_version, file_code = publish.read_version()
        if (file_version, file_code) != (version, code):
            print(f"   提示 草稿版本 {version}（code {code}）与 app/version.properties 的 "
                  f"{file_version}（code {file_code}）不同 —— 发版前 release.sh 会对齐",
                  flush=True)
    tag = f"v{version}"
    draft_path = DRAFT_DIR / f"{tag}.md"
    notes_path = RELEASE_NOTES_DIR / f"{tag}.md"

    print(f"=== 排版 {tag}（versionCode={code}）===", flush=True)

    if not draft_path.is_file():
        if args.init:
            DRAFT_DIR.mkdir(parents=True, exist_ok=True)
            draft_path.write_text(
                SKELETON.format(tag=tag, version=version), encoding="utf-8")
            die(f"已生成草稿 {draft_path.relative_to(ROOT)}，"
                "填好内容（尤其是每章的第一段摘要）后重新运行本程序")
        die(f"缺少草稿 {draft_path.relative_to(ROOT)}。加 --init 生成骨架，"
            f"或先写好内容")

    draft = parse_draft(draft_path.read_text(encoding="utf-8"),
                        str(draft_path.relative_to(ROOT)))
    if draft.tag != tag:
        die(f"草稿里写的是 `# {draft.tag}`，与本次版本 `{tag}` 不一致"
            f"（草稿文件名也必须叫 {tag}.md）")

    notes_text = render_notes(draft)
    release_notes_body = render_release_notes_file(draft)
    validate(draft, notes_text)

    print(f"   草稿   {draft_path.relative_to(ROOT)}"
          f"（{len(draft.sections)} 章，notes {len(notes_text.splitlines())} 行）", flush=True)
    print(f"\n--- 更新弹窗正文（notes 逐行当纯文本显示，共 {len(notes_text.splitlines())} 行）---",
          flush=True)

    if args.dry_run or args.check:
        print(f"\n--- {notes_path.relative_to(ROOT)} ---\n{release_notes_body}", flush=True)
        if args.preview:
            PREVIEW_DIR.mkdir(parents=True, exist_ok=True)
            preview_path = PREVIEW_DIR / f"{tag}.html"
            write_text_lf(preview_path,
                          render_preview_html(draft, code, notes_text, release_notes_body))
            print(f"   预览   {preview_path.relative_to(ROOT)}（--preview，其余未写盘）",
                  flush=True)
        if args.check:
            print("=== 校验通过（--check，未写盘）===", flush=True)
        else:
            print("=== dry-run 结束，未写盘 ===", flush=True)
        return

    notes_path.parent.mkdir(parents=True, exist_ok=True)
    write_text_lf(notes_path, release_notes_body)
    print(f"   写出   {notes_path.relative_to(ROOT)}", flush=True)

    # 预览页默认就写：改完稿最想确认的是"读起来是什么样"，不该还要多记一个开关。
    PREVIEW_DIR.mkdir(parents=True, exist_ok=True)
    preview_path = PREVIEW_DIR / f"{tag}.html"
    write_text_lf(preview_path,
                  render_preview_html(draft, code, notes_text, release_notes_body))
    print(f"   预览   {preview_path.relative_to(ROOT)}", flush=True)

    # 端到端复核：发版链条读的就是 publish.release_notes_for(tag)，它内部再调
    # release_notes.sh 抽 notes 并跑 notes_violations。它过了，发版就不会在这里挂。
    # 比较的是**抽取后**的形态：extract_notes 只掐首尾空行，段间空行保留（弹窗按
    # Markdown 渲染，没空行就会被并成一段），所以这里只掐首尾。
    published = publish.release_notes_for(tag)
    expected = notes_text.strip("\n")
    if published != expected:
        die("抽取出来的 Release 正文与这里渲染的 notes 不一致 —— 说明 notes 区块的"
            "排版不符合 release_notes.sh 的抽取规则（例如多了一层缩进或标题）")
    print(f"   复核   release_notes_for({tag}) 抽取一致，"
          f"{len(published.splitlines())} 行", flush=True)
    print(f"=== 完成 ===", flush=True)


if __name__ == "__main__":
    main()
