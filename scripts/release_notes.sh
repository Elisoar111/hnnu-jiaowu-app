#!/usr/bin/env bash
#
# 「## notes / ## changelog 区块抽取」的唯一实现（bash 侧）。
#
# 为什么单独一个文件
# ------------------
# 同一套抽取规则原先在三个地方各写一份：
#   - scripts/release.sh                 （本地发版：写提交信息、归档 CHANGELOG）
#   - .github/workflows/release.yml      （CI：Resolve Release Notes）
#   - scripts/publish.py                 （发布程序：写 Release 正文）
# 三份一旦不一致，后果很具体：本地校验通过的文案，到了 CI 或发布程序里变成另一段
# （多一行、少一行、HTML 注释没剥干净），而这段文案会**原样**进应用内的更新弹窗
# ——UpdateDialog 用纯 Text 渲染，不解析 Markdown。release.sh 里原先那句
# "两边写法必须一致，否则本地过了 CI 还会挂" 说的就是这个坑。
#
# 所以：bash 侧的实现在这里，release.sh 与 publish.py 都调它，不再各写一份。
# 用法（被 source，不是直接执行）：
#     source scripts/release_notes.sh
#     NOTES="$(extract_notes release-notes/v1.2.2.md)"
#     BODY="$(extract_changelog release-notes/v1.2.2.md)"

# 取出 <file> 里某个 "## <section>" 区块，剥掉 HTML 注释。
#
# 注释剥离要分两步。只写 `/^<!--/,/-->$/d` 会把单行注释 `<!-- x -->` 当成区间开头，
# 而 sed 的区间结束模式只从下一行开始找，于是一路删到文件末尾——'## changelog'
# 区块正好以这样一行开头，整段就没了。先删单行注释，再删跨行区间。
block_body() {
    awk -v want="$1" '
        $0 ~ "^## " want "[[:space:]]*$" { inside = 1; next }
        /^## / { inside = 0 }
        inside { print }
    ' "$2" \
        | sed -e '/^[[:space:]]*<!--.*-->[[:space:]]*$/d' -e '/^[[:space:]]*<!--/,/-->/d'
}

# notes：每行一条，去掉空行。这一份会被原样放进 Release 正文，应用读的就是它。
extract_notes() {
    block_body notes "$1" | sed '/^[[:space:]]*$/d'
}

# changelog：要进 CHANGELOG.md，是 Markdown，内部空行必须留着（小标题与列表之间
# 少一行空行，归档出来的段落就和手写的历史版本长得不一样）。只掐掉首尾空行。
extract_changelog() {
    block_body changelog "$1" | awk '
        { line[NR] = $0; if ($0 ~ /[^[:space:]]/) { if (!first) first = NR; last = NR } }
        END { for (i = first; i <= last; i++) print line[i] }
    '
}

# notes 会被 UpdateDialog 当纯文本渲染，Markdown 标记会原样显示给用户。
# 从 stdin 读文本，把出问题的行（带行号）打到 stdout；有输出即非法。
# 调用方：printf '%s\n' "$NOTES" | notes_violations
notes_violations() {
    grep -nE '^[[:space:]]*[#*-]|`' || true
}
