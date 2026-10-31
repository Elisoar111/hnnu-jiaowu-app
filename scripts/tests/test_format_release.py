"""scripts/format_release.py 的单测。

这一层要守的都是"写错了不会报错、只是用户看不到"的东西：
  - 段落与列表被反过来（说明文字落在细节后面，读起来别扭）；
  - 章摘要缺一段 → notes 少一行，用户少知道一件事；
  - notes 里混进 Markdown 标记 → 更新弹窗里会原样出现一串符号。
"""

import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from format_release import (  # noqa: E402
    SKELETON, DraftError, cn_number, parse_draft, render_markdown_html, render_notes,
    render_preview_html, render_release_notes_file, validate, write_text_lf,
)

MINI = """# v9.9.9

## 标题
两件事都修好了

## 概览
这一版解决了两件事。

## 章节

### 第一件事
第一件事的说明。
- 细节 A
- 细节 B

### 第二件事
第二件事的说明，没有列表。

## 结尾
感谢每一位使用与支持本项目的同学。

## 变更

### 新增
- `Foo` 做了 A

### 修复
- `Bar` 修了 B
"""

FORCED = MINI.replace(
    "## 结尾",
    "## 强制更新\n本次更新是必须的：旧版登录会失败。\n\n## 结尾",
)

CODE = 90909
TAG = "v9.9.9"


def draft_of(text=MINI):
    return parse_draft(text)


class DraftParsingTests(unittest.TestCase):
    def test_章节顺序与摘要(self):
        draft = draft_of()
        self.assertEqual(draft.tag, TAG)
        self.assertEqual(draft.version, "9.9.9")
        self.assertEqual(draft.title, "两件事都修好了")
        self.assertEqual(draft.overview, "这一版解决了两件事。")
        self.assertEqual(draft.closing, "感谢每一位使用与支持本项目的同学。")
        self.assertEqual([s.title for s in draft.sections], ["第一件事", "第二件事"])
        self.assertEqual(draft.sections[0].summary, "第一件事的说明。")
        self.assertEqual(draft.sections[0].blocks[1].items, ["细节 A", "细节 B"])

    def test_列表项之前的段落不会被挤到列表后面(self):
        # 曾经的 bug：列表项分支没有先落盘待处理段落，渲染出来列表在前、说明在后。
        blocks = draft_of().sections[0].blocks
        self.assertFalse(blocks[0].is_list, "§第一段必须是说明文字，不是列表")
        self.assertTrue(blocks[1].is_list)

    def test_变更分组按出现顺序保留(self):
        self.assertEqual([name for name, _ in draft_of().changes], ["新增", "修复"])

    def test_注释块整块跳过(self):
        text = MINI.replace(
            "## 变更",
            "<!--\n## 强制更新\n千万别生效。\n-->\n\n## 变更")
        self.assertEqual(draft_of(text).force_reason, "")

    def test_不认识的小节名报错(self):
        with self.assertRaises(DraftError):
            draft_of(MINI.replace("## 概览", "## 概述"))

    def test_没有章节时报错(self):
        with self.assertRaises(DraftError):
            draft_of("# v9.9.9\n\n## 概览\n只有概览。\n")

    def test_章节缺摘要时报错(self):
        # 只有列表、没有摘要 → notes 里这一章没有任何说明文字，等于用户看不到
        with self.assertRaises(DraftError):
            draft_of(MINI.replace("第一件事的说明。\n", ""))

    def test_一级标题必须是版本号(self):
        with self.assertRaises(DraftError):
            draft_of(MINI.replace("# v9.9.9", "# 更新说明"))


class NotesTests(unittest.TestCase):
    def test_顺序是概览到各章摘要到结尾(self):
        lines = render_notes(draft_of()).splitlines()
        self.assertEqual(lines[0], "这一版解决了两件事。")
        self.assertEqual(lines[-1], "感谢每一位使用与支持本项目的同学。")
        self.assertIn("第一件事的说明。", lines)
        self.assertIn("第二件事的说明，没有列表。", lines)
        # 列表项不进 notes：行首 `-` 会被 notes_violations 拒绝发版
        self.assertNotIn("细节 A", render_notes(draft_of()))

    def test_段与段之间空一行(self):
        text = render_notes(draft_of())
        self.assertIn("这一版解决了两件事。\n\n第一件事的说明。", text)

    def test_强制更新标记独立成行且在人话说明之前(self):
        # 文件里段间有空行，extract_notes 会把空行去掉 —— 应用拿到的是相邻的两行。
        lines = [line for line in render_notes(draft_of(FORCED)).splitlines() if line.strip()]
        self.assertEqual(lines[0], "[force-update]")
        self.assertEqual(lines[1], "本次更新是必须的：旧版登录会失败。")

    def test_没有强制更新小节时不出现标记(self):
        self.assertNotIn("[force-update]", render_notes(draft_of()))

    def test_notes里出现反引号会被拦(self):
        draft = draft_of(MINI.replace("第一件事的说明。", "改了 `Foo` 这个类。"))
        with self.assertRaises(DraftError) as caught:
            validate(draft, render_notes(draft))
        self.assertIn("反引号", str(caught.exception))


class NumberingTests(unittest.TestCase):
    def test_中文数字编号(self):
        # 函数本身是通用的中文数字（0 → 〇），但调用处传 index + 1，
        # 所以对照表里不会出现「第〇章」。
        self.assertEqual([cn_number(i) for i in range(4)], ["〇", "一", "二", "三"])
        self.assertEqual(cn_number(9), "九")
        self.assertEqual(cn_number(10), "十")
        self.assertEqual(cn_number(11), "十一")
        self.assertEqual(cn_number(20), "二十")
        self.assertEqual(cn_number(21), "二十一")


class ReleaseNotesFileTests(unittest.TestCase):
    def test_产出文件可被发版链条抽取(self):
        body = render_release_notes_file(draft_of())
        self.assertIn("\n## notes\n\n", body)
        self.assertIn("\n## changelog\n\n", body)
        self.assertIn("### 新增\n\n- `Foo` 做了 A", body)

    def test_版本号与来源写在文件里(self):
        body = render_release_notes_file(draft_of())
        self.assertTrue(body.startswith("# v9.9.9 更新说明"))
        self.assertIn("release-notes/drafts/v9.9.9.md", body)


class ValidationTests(unittest.TestCase):
    def test_通过时不抛异常(self):
        draft = draft_of()
        validate(draft, render_notes(draft))


class PreviewTests(unittest.TestCase):
    def test_markdown渲染出加粗与编号列表(self):
        html = render_markdown_html(
            "**第一件事**\n说明文字。\n\n1. 细节 A\n2. 细节 B")
        self.assertIn("<strong>第一件事</strong>", html)
        self.assertIn("<ol><li>细节 A</li><li>细节 B</li></ol>", html)
        self.assertNotIn("**", html)

    def test_预览页含弹窗与摘要对照(self):
        draft = draft_of()
        page = render_preview_html(draft, CODE, render_notes(draft),
                                   render_release_notes_file(draft))
        self.assertIn("应用内更新弹窗", page)
        self.assertIn("发现新版本", page)
        self.assertIn("第一件事的说明。", page)
        # 对照表按 notes 真实行号数：开场白是第 1 行，第一章摘要是第 2 行
        self.assertIn("notes 第 1 行（开场白）", page)
        self.assertIn("notes 第 2 行 · 第一章「第一件事」", page)
        # 公告模块已经整块移除，预览页不该再出现公告卡片
        self.assertNotIn("announcement", page)


class SkeletonTests(unittest.TestCase):
    def test_草稿模板能解析(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "v9.9.9.md"
            path.write_text(SKELETON.format(tag=TAG, version="9.9.9"), encoding="utf-8")
            draft = parse_draft(path.read_text(encoding="utf-8"), "骨架")
            # 骨架里那一节「强制更新」是注释状态，不该生效
            self.assertEqual(draft.force_reason, "")
            self.assertEqual(len(draft.sections), 2)


class WriteTests(unittest.TestCase):
    def test_写盘一律用LF(self):
        # Windows 上文本模式会把换行翻成 CRLF，产物就变成"整篇都改了"的 diff
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "notes.md"
            write_text_lf(path, "# 标题\n\n正文\n")
            self.assertNotIn(b"\r\n", path.read_bytes())


if __name__ == "__main__":
    unittest.main()