"""扫描 Compose 界面文件里的中文用户可见文案，做标点 / 术语体检。

为什么要有这个脚本：项目里有 2000+ 行界面代码，文案散在 text= / label= /
title= / subtitle= / message= / placeholder= / contentDescription= 等具名参数里。
靠眼睛通读必然漏，且漏掉的那条通常正好是用户会截图来问的那条。

判据（只对**含中文**的字面量报警）：
  1. 中文句子里混用半角逗号/句号/冒号/问号/叹号（应统一全角）；
  2. 普通 Text 里混进 Markdown 标记 `**粗体**`（Text 不解析 Markdown，
     用户会直接看到星号）—— 需要 MarkdownText 才是故意的；
  3. 结尾用了 `。.` 这类重复标点。

用法：python scripts/scan_ui_text.py <文件或目录> [...]
"""
import re
import sys
from pathlib import Path

# 只扫这些「具名参数 = 字符串」的位置，避免把 KDoc 注释也算进来。
ATTR = re.compile(
    r'\b(text|label|title|subtitle|message|header|footer|placeholder|'
    r'contentDescription|hint|reason)\s*=\s*"([^"\\]*(?:\\.[^"\\]*)*)"'
)
CJK = re.compile(r'[\u4e00-\u9fff]')
# 中文字符紧邻的半角标点（左邻或右邻是汉字才算，避免误伤 "1,000" 或 "v1.2"）。
BAD_PUNCT = re.compile(r'[\u4e00-\u9fff][,;:?!]|[,;:?!][\u4e00-\u9fff]')
MARKDOWN = re.compile(r'\*\*[^*]+\*\*')


def scan(path: Path):
    hits = []
    for i, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
        for m in ATTR.finditer(line):
            attr, lit = m.group(1), m.group(2)
            if not CJK.search(lit):
                continue
            if MARKDOWN.search(lit):
                hits.append((i, attr, 'Markdown 标记会原样显示', lit))
            if BAD_PUNCT.search(lit):
                hits.append((i, attr, '中文里混了半角标点', lit))
    return hits


def main(argv):
    targets = []
    for a in argv[1:]:
        p = Path(a)
        targets.extend(sorted(p.rglob('*.kt')) if p.is_dir() else [p])
    total = 0
    for p in targets:
        for line, attr, why, lit in scan(p):
            total += 1
            shown = lit if len(lit) <= 60 else lit[:57] + '...'
            print(f'{p.name}:{line}  [{attr}] {why}\n    {shown}')
    print(f'\n共 {total} 处待确认（{len(targets)} 个文件）')


if __name__ == '__main__':
    main(sys.argv)
