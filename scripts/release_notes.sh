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
# ——UpdateDialog 用 MarkdownText 渲染它。release.sh 里原先那句
# "两边写法必须一致，否则本地过了 CI 还会挂" 说的就是这个坑。
#
# 所以：bash 侧的实现在这里，release.sh 与 publish.py 都调它，不再各写一份。
# 用法（被 source，不是直接执行）：
#     source scripts/release_notes.sh
#     NOTES="$(extract_notes release-notes/v1.2.2.md)"
#     BODY="$(extract_changelog release-notes/v1.2.2.md)"
#
# ## notes 里可以用一行 [force-update] 声明「强制更新」
# ------------------------------------------------------
# 应用侧（network/AppUpdateChecker）会在 Release 正文里找这一行 —— 独立成行、大小写
# 不敏感。命中后更新弹窗变成**不可关闭**，并无视用户此前点过的「以后再说」。
# 用途只有一个：不升级就没法继续用的版本（例如教务端改了协议，旧版登录直接失败）。
#
# 两条写法上的硬约束，踩了就不生效：
#   1. 必须写成**可见的方括号文本**，不能写成 HTML 注释 —— extract_notes 会把
#      `<!-- -->` 整行剥掉，注释形式的标记根本到不了 Release 正文，应用看不到；
#   2. 不能写成 `#` 开头 —— 行首的 `#` 会让 block_body 以为 notes 区块结束了
#      （notes_violations 见文件末，会直接拒绝发版）。
#
# 它不会漏进用户看到的说明：AppUpdateChecker.notesForDisplay 会先把这一行摘掉。
# 但 Gitee 的 Release 页面上仍然看得见，所以把「为什么强制」写在它下一行。

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

# notes：段与段之间留一个空行。这一份会被原样放进 Release 正文，应用读的就是它。
#
# 空行必须留着（以前是 sed 删掉的）：应用内的更新弹窗现在按 Markdown 渲染，
# 而 Markdown 里单个换行只是"软换行"，段与段之间少了空行就会被并成一大坨，
# 反而比纯文本时期更难扫读。站点上的 Release 页同理。只掐首尾空行。
extract_notes() {
    block_body notes "$1" | awk '
        { line[NR] = $0; if ($0 ~ /[^[:space:]]/) { if (!first) first = NR; last = NR } }
        END { for (i = first; i <= last; i++) print line[i] }
    '
}

# changelog：要进 CHANGELOG.md，是 Markdown，内部空行必须留着（小标题与列表之间
# 少一行空行，归档出来的段落就和手写的历史版本长得不一样）。只掐掉首尾空行。
extract_changelog() {
    block_body changelog "$1" | awk '
        { line[NR] = $0; if ($0 ~ /[^[:space:]]/) { if (!first) first = NR; last = NR } }
        END { for (i = first; i <= last; i++) print line[i] }
    '
}

# notes 现在由 UpdateDialog 用 MarkdownText 渲染，`**加粗**` 与 `- ` 列表都是
# **有意写上去的排版**，不该再拦。仍然拦两类：
#   1. 行首 `#` —— block_body 靠 `^## <段名>` 切区块，notes 里出现 `## xxx` /
#      `### xxx` 会被当成下一个区块的开头，从那一行起 notes 被截断，
#      写进去的内容凭空消失，而且**不报错**；
#   2. 反引号 —— 行内代码的等宽底纹在窄弹窗里会和中文正文糊成一片；中文说明里
#      又常随手写一个反引号，误伤的概率大于收益。
# 从 stdin 读文本，把出问题的行（带行号）打到 stdout；有输出即非法。
# 调用方：printf '%s\n' "$NOTES" | notes_violations
notes_violations() {
    grep -nE '^[[:space:]]*#|`' || true
}
