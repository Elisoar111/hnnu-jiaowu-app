"""scripts/format_release.py 的单测。

这一层要守的都是"写错了不会报错、只是用户看不到"的东西：
  - 段落与列表被反过来（公告读起来说明在细节后面）；
  - 章摘要缺一段 → notes 少一行，用户少知道一件事；
  - 公告 id 不带 `vX_Y_Z` → AnnouncementLogicTest 找不到本版公告；
  - 版本范围写成本版以外 / 0-不限 → 弹窗不出现，或一直骚扰后面每个版本；
  - 重跑一次多出一条公告 → 同一版出现两条。
"""

import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from format_release import (  # noqa: E402
    ANNOUNCEMENT_JSON, DraftError, announcement_entry, announcement_id, applies_to,
    cn_number, load_announcements, parse_draft, render_announcement_content,
    render_markdown_html, render_notes, render_preview_html, render_release_notes_file,
    save_announcements, upsert_announcement, validate,
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
        text = MINI.replace("## 变更", "<!--\n## 强制更新\n千万别生效。\n-->\n\n## 变更")
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
        # 列表项只进公告：notes 里出现行首 `-` 会被 notes_violations 拒绝发版
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
        notes = render_notes(draft)
        entry = announcement_entry(draft, CODE, "2026-09-23")
        with self.assertRaises(DraftError) as caught:
            validate(draft, CODE, notes, entry, [entry])
        self.assertIn("反引号", str(caught.exception))


class AnnouncementTests(unittest.TestCase):
    def test_正文版式与历史公告一致(self):
        content = render_announcement_content(draft_of())
        self.assertTrue(content.startswith("**一、第一件事**\n"))
        self.assertIn("\n**二、第二件事**\n", content)
        self.assertIn("\n1. 细节 A\n2. 细节 B\n", content)
        self.assertTrue(content.endswith("感谢每一位使用与支持本项目的同学。"))

    def test_章节序号从一而不是从零起(self):
        # 明确要求：不要 0、1、2 这种从 0 起的中文编号，首章就是「一、」。
        content = render_announcement_content(draft_of())
        self.assertNotIn("〇", content)
        self.assertEqual(
            [line for line in content.splitlines() if line.startswith("**")],
            ["**一、第一件事**", "**二、第二件事**"])

    def test_条目字段(self):
        entry = announcement_entry(draft_of(), CODE, "2026-09-23")
        self.assertEqual(entry["id"], "20260923_v9_9_9_release")
        self.assertEqual(entry["title"], "v9.9.9 更新：两件事都修好了")
        self.assertEqual(entry["type"], "important")
        self.assertEqual(entry["contentType"], "markdown")
        self.assertTrue(entry["showOnce"])
        self.assertEqual(entry["created_at"], "2026-09-23")
        self.assertEqual((entry["minVersionCode"], entry["maxVersionCode"]), (CODE, CODE))
        self.assertIn(f"（{CODE}\u2013{CODE}）", entry["note"])
        # 键顺序照抄既有条目，diff 才只落在真正变化的行上
        self.assertEqual(list(entry), [
            "id", "title", "content", "type", "contentType", "showOnce",
            "audience", "created_at", "minVersionCode", "maxVersionCode", "note"])

    def test_没有标题时用章节名拼(self):
        entry = announcement_entry(draft_of(MINI.replace("两件事都修好了", "")),
                                   CODE, "2026-09-23")
        self.assertEqual(entry["title"], "v9.9.9 更新：第一件事、第二件事")

    def test_公告id带版本标记(self):
        # AnnouncementLogicTest 断言"id 里含 vX_Y_Z"且"该条覆盖本版 versionCode"
        entry = announcement_entry(draft_of(), CODE, "2026-09-23")
        self.assertIn("v9_9_9", entry["id"])
        self.assertTrue(applies_to(entry, CODE))

    def test_appliesTo与Kotlin口径一致(self):
        # Announcement.appliesTo：两个版本字段都没写过 = 历史归档，恒不适用
        self.assertFalse(applies_to({"id": "x"}, CODE))
        self.assertFalse(applies_to({"minVersionCode": 0, "maxVersionCode": 91}, CODE))
        self.assertTrue(applies_to({"minVersionCode": 1, "maxVersionCode": 99999}, CODE))

    def test_中文数字编号(self):
        # 函数本身是通用的中文数字（0 → 〇），但章节编号在调用处传 index + 1，
        # 所以公告里不会出现「〇、」。
        self.assertEqual([cn_number(i) for i in range(4)], ["〇", "一", "二", "三"])
        self.assertEqual(cn_number(9), "九")
        self.assertEqual(cn_number(10), "十")
        self.assertEqual(cn_number(11), "十一")
        self.assertEqual(cn_number(20), "二十")
        self.assertEqual(cn_number(21), "二十一")

    def test_产出文件可被发版链条抽取(self):
        body = render_release_notes_file(draft_of(), CODE, "20260923_v9_9_9_release")
        self.assertIn("\n## notes\n\n", body)
        self.assertIn("\n## changelog\n\n", body)
        # 自动补的公告归档条目
        self.assertIn("### 公告\n\n- 随包新增 v9.9.9 公告（`20260923_v9_9_9_release`）"
                      f"，版本范围锁 {CODE}\u2013{CODE}。", body)


class UpsertTests(unittest.TestCase):
    def setUp(self):
        self.other = {"id": "old_thing", "title": "旧公告", "content": "c"}
        self.entry = announcement_entry(draft_of(), CODE, "2026-09-23")

    def test_没有本版条目时追加在末尾(self):
        merged, replaced = upsert_announcement([self.other], self.entry, TAG)
        self.assertFalse(replaced)
        self.assertEqual([e["id"] for e in merged], ["old_thing", self.entry["id"]])

    def test_已有本版条目时原地替换(self):
        stale = {**self.entry, "id": "20260101_v9_9_9_release", "created_at": "2026-01-01"}
        merged, replaced = upsert_announcement([self.other, stale], self.entry, TAG)
        self.assertTrue(replaced)
        # 位置不变、其它条目不被动
        self.assertEqual(merged[0], self.other)
        self.assertEqual(merged[1]["id"], self.entry["id"])
        self.assertEqual(len(merged), 2)

    def test_按tag认领而不是按完整id(self):
        # id 里含日期：改 --date 重跑时完整 id 已经变了，按 id 找会再多出一条公告
        stale = {**self.entry, "id": "20260101_v9_9_9_release"}
        merged, _ = upsert_announcement([stale], {**self.entry, "id": "20261231_v9_9_9_release"}, TAG)
        self.assertEqual(len(merged), 1)

    def test_连跑两次只有一条(self):
        first, _ = upsert_announcement([self.other], self.entry, TAG)
        second, replaced = upsert_announcement(first, self.entry, TAG)
        self.assertTrue(replaced)
        self.assertEqual(second, first)

    def test_json写出后可读回且不破坏其它条目(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "announcement.json"
            save_announcements(path, [self.other, self.entry])
            reloaded = load_announcements(path)
        self.assertEqual(reloaded, [self.other, self.entry])
        self.assertEqual(reloaded[0]["title"], "旧公告")


class ValidationTests(unittest.TestCase):
    def setUp(self):
        self.draft = draft_of()
        self.notes = render_notes(self.draft)
        self.entry = announcement_entry(self.draft, CODE, "2026-09-23")

    def test_通过时不抛异常(self):
        validate(self.draft, CODE, self.notes, self.entry, [self.entry])

    def test_id缺版本标记报错(self):
        entry = {**self.entry, "id": "20260923_release"}
        with self.assertRaises(DraftError) as caught:
            validate(self.draft, CODE, self.notes, entry, [entry])
        self.assertIn("v9_9_9", str(caught.exception))

    def test_版本范围不覆盖本版报错(self):
        # 范围写成本版以外 → 装上新版的人看不到任何更新说明，而且不会报错
        entry = {**self.entry, "maxVersionCode": CODE - 1}
        with self.assertRaises(DraftError):
            validate(self.draft, CODE, self.notes, entry, [entry])

    def test_没有本版公告报错(self):
        stale = {**self.entry, "minVersionCode": 1, "maxVersionCode": 2,
                 "id": "20260101_v9_9_9_release"}
        with self.assertRaises(DraftError):
            validate(self.draft, CODE, self.notes, self.entry, [stale])

    def test_公告id重复报错(self):
        with self.assertRaises(DraftError) as caught:
            validate(self.draft, CODE, self.notes, self.entry, [self.entry, self.entry])
        self.assertIn("重复", str(caught.exception))

    def test_非法type报错(self):
        entry = {**self.entry, "type": "urgent"}
        with self.assertRaises(DraftError):
            validate(self.draft, CODE, self.notes, entry, [entry])


class PreviewTests(unittest.TestCase):
    def test_markdown渲染出加粗与编号列表(self):
        html = render_markdown_html(render_announcement_content(draft_of()))
        self.assertIn("<strong>一、第一件事</strong>", html)
        self.assertIn("<ol><li>细节 A</li><li>细节 B</li></ol>", html)
        self.assertNotIn("**", html)

    def test_预览页含两块版式与摘要对照(self):
        draft = draft_of()
        entry = announcement_entry(draft, CODE, "2026-09-23")
        page = render_preview_html(draft, CODE, entry, render_notes(draft),
                                   render_release_notes_file(draft, CODE, entry["id"]))
        self.assertIn("应用内更新弹窗", page)
        self.assertIn("<strong>一、第一件事</strong>", page)
        self.assertIn("第一件事的说明。", page)
        self.assertIn(entry["id"], page)
        # 对照表按 notes 真实行号数：开场白是第 1 行，第一章摘要是第 2 行
        self.assertIn("notes 第 1 行（开场白）", page)
        self.assertIn("notes 第 2 行 · 公告「一、第一件事」", page)


class RealFileTests(unittest.TestCase):
    """对着仓库里那份真数据跑的回归：排版程序不能把别人写的公告格式改掉。"""

    def test_既有公告文件能原样写回(self):
        # 比字节而不是比字符串：Windows 上 Python 的文本模式会把 \n 翻成 CRLF，
        # 用 text 读写比对会把这个坑盖住，而它足以让一次排版产出"整篇都变了"的 diff。
        original = ANNOUNCEMENT_JSON.read_bytes()
        entries = load_announcements(ANNOUNCEMENT_JSON)
        self.assertTrue(entries, "assets/announcement.json 里一条公告都没有")
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "announcement.json"
            save_announcements(path, entries)
            rewritten = path.read_bytes()
        self.assertEqual(rewritten, original,
                         "写回的字节与原文不一致：说明 save_announcements 的缩进/转义/"
                         "换行与既有文件不同，一次排版会把整个文件重排成一大片 diff")
        self.assertNotIn(b"\r\n", rewritten, "产物必须用 LF，与仓库里既有的那两份一致")

    def test_既有公告id与范围互相自洽(self):
        for entry in load_announcements(ANNOUNCEMENT_JSON):
            low, high = entry.get("minVersionCode"), entry.get("maxVersionCode")
            if low and high:
                self.assertLessEqual(low, high, entry["id"])

    def test_草稿模板能解析(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / "v9.9.9.md"
            from format_release import SKELETON
            path.write_text(SKELETON.format(tag=TAG, version="9.9.9"), encoding="utf-8")
            draft = parse_draft(path.read_text(encoding="utf-8"), "骨架")
            # 骨架里那一节「强制更新」是注释状态，不该生效
            self.assertEqual(draft.force_reason, "")
            self.assertEqual(len(draft.sections), 2)


if __name__ == "__main__":
    unittest.main()
