# -*- coding: utf-8 -*-
"""用户手册 构建脚本：仓库根目录的 用户手册.md -> app/src/main/assets/users_manual.html

手册在应用内由 ManualActivity 用 WebView 展示；Markdown 在构建期转成带样式的 HTML，
不引第三方 Markdown 渲染库，离线可用、排版可控。

浅色 / 暗色：CSS 变量在 :root（浅色）、@media prefers-color-scheme（跟随系统）
与 body.dark（强制暗色）三处定义；页面末尾的一小段内联脚本读 URL 的
?theme= 参数决定是否加 body.dark（ManualActivity 传入设置里选的主题）。

用法：改完 用户手册.md 后运行一次
    python scripts/build_manual.py
"""
import markdown
from markdown.extensions.toc import slugify_unicode
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "用户手册.md"
OUT = ROOT / "app" / "src" / "main" / "assets" / "users_manual.html"

CSS = """
:root {
  --bg: #f6f7fb; --fg: #1c1e26; --fg-2: #5d6370; --accent: #3b6cf6;
  --border: #e3e6ee; --card: #ffffff; --code-bg: #eef1f7; --quote-bg: #f2f5fb;
  --th-bg: #f0f3fa; --warn-fg: #b4232a; --warn-bg: #fdf0f0;
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #14161c; --fg: #e8eaf0; --fg-2: #9aa1af; --accent: #7aa2ff;
    --border: #2b2f3a; --card: #1c1f27; --code-bg: #232734; --quote-bg: #1e2230;
    --th-bg: #222634; --warn-fg: #ff8a8f; --warn-bg: #2d1f22;
  }
}
body.dark {
  --bg: #14161c; --fg: #e8eaf0; --fg-2: #9aa1af; --accent: #7aa2ff;
  --border: #2b2f3a; --card: #1c1f27; --code-bg: #232734; --quote-bg: #1e2230;
  --th-bg: #222634; --warn-fg: #ff8a8f; --warn-bg: #2d1f22;
}
* { box-sizing: border-box; }
html { -webkit-text-size-adjust: 100%; }
body {
  margin: 0; padding: 24px 18px 56px; background: var(--bg); color: var(--fg);
  font-family: -apple-system, "HarmonyOS Sans SC", "MiSans", "Source Han Sans SC",
    "Noto Sans SC", Roboto, "Segoe UI", sans-serif;
  font-size: 15px; line-height: 1.75; word-break: break-word;
}
main { max-width: 720px; margin: 0 auto; }
h1 { font-size: 24px; line-height: 1.4; text-align: center; margin: 8px 0 6px; letter-spacing: 0.5px; }
h1 + blockquote { margin-top: 10px; }
h2 {
  font-size: 19px; margin: 34px 0 12px; padding-left: 10px; line-height: 1.5;
  border-left: 4px solid var(--accent); border-radius: 2px;
}
h3 { font-size: 16.5px; margin: 24px 0 8px; }
h2, h3, h4 { scroll-margin-top: 12px; }
p { margin: 8px 0; }
strong { color: var(--fg); }
a { color: var(--accent); text-decoration: none; }
ul, ol { margin: 8px 0; padding-left: 22px; }
li { margin: 4px 0; }
blockquote {
  margin: 12px 0; padding: 10px 14px; background: var(--quote-bg);
  border-left: 3px solid var(--accent); border-radius: 0 10px 10px 0;
  color: var(--fg-2); font-size: 13.5px;
}
blockquote p { margin: 2px 0; }
code {
  background: var(--code-bg); border-radius: 6px; padding: 2px 6px;
  font-size: 13px; font-family: "JetBrains Mono", Menlo, Consolas, monospace;
}
pre { background: var(--code-bg); border-radius: 10px; padding: 12px; overflow-x: auto; }
pre code { padding: 0; background: none; }
table {
  width: 100%; border-collapse: collapse; margin: 12px 0; font-size: 13.5px;
  background: var(--card); border-radius: 10px; overflow: hidden;
  border: 1px solid var(--border);
}
th, td { border-bottom: 1px solid var(--border); padding: 8px 10px; text-align: left; vertical-align: top; }
tr:last-child td { border-bottom: none; }
th { background: var(--th-bg); font-weight: 600; white-space: nowrap; }
hr { border: none; border-top: 1px solid var(--border); margin: 26px 0; }
em { color: var(--fg-2); font-style: normal; font-size: 13px; }
"""

SCRIPT = (
    "<script>try{if(new URLSearchParams(location.search).get('theme')==='dark')"
    "document.body.classList.add('dark');}catch(e){}</script>"
)

def main() -> None:
    md_text = SRC.read_text(encoding="utf-8")
    body = markdown.markdown(
        md_text,
        extensions=["tables", "fenced_code", "sane_lists", "nl2br", "toc"],
        # 必须开 toc 扩展：不开的话标题不会生成 id，而手册正文（目录、FAQ 里的
        # 交叉引用）用的是 `#4-课表` 这类锚点，点下去毫无反应。
        # 默认 slugify 会把非 ASCII 字符整段剥掉，中文标题会变成空 id，
        # 所以显式换成保留 Unicode 的那个。
        # 注意：这里必须传**函数对象**。写成 "markdown.extensions.toc:slugify_unicode"
        # 字符串不会被解析，toc 扩展会拿字符串当函数调，报 'str' object is not callable。
        extension_configs={
            "toc": {
                "slugify": slugify_unicode,
                "anchorlink": False,
                "permalink": False,
            }
        },
        output_format="html5",
    )
    page = (
        "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
        f"<title>教务助理 · 用户手册</title><style>{CSS}</style></head>"
        f"<body><main>{body}</main>{SCRIPT}</body></html>"
    )
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(page, encoding="utf-8")
    print(f"written {OUT} ({OUT.stat().st_size} bytes)")

if __name__ == "__main__":
    main()
