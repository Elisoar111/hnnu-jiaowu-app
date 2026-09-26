package com.hnnujw.course.ykt

/**
 * 挂失/解挂的**二次确认**判据（纯函数，无 Android 依赖，可直接单测）。
 *
 * ## 为什么要"输入指定文字"这一步
 *
 * 挂失是**不可逆**的：一旦提交，卡立即冻结、无法消费，且没有任何"撤销"按钮
 * （解挂同样要再走一次密码流程，且现实中往往要跑一趟卡务中心）。
 * 一次误触的代价是用户当天吃不上饭。
 *
 * 点一下按钮就执行的确认框，在真机上很容易被"手滑连点"穿透；
 * 要求用户**逐字输入**一句明确的话，能确保：
 * 1. 用户确实读到了那句话（把后果写进要输入的内容里）；
 * 2. 必须发生一次完整、有意图的键盘输入，误触与脚本化操作都无法完成；
 * 3. 复制的文本也无法直接通过（见 [matches] 的归一规则——仍要求手打）。
 *
 * ## 为什么判据要做成纯函数
 *
 * "输入什么才算通过"涉及大小写、空白、全角/半角等一堆琐碎规则，
 * 且一旦判错就是**真把用户的卡挂了**，属于必须被单测钉死的逻辑。
 */
object YktLostConfirm {

    /**
     * 挂失要求输入的文字。
     *
     * 刻意写成**第一人称的声明句**而不是"确认挂失"这类祈使短语：
     * 用户要打出来的这句话本身就是一个承诺——"我确认挂失这张校园卡"。
     * 打完整句比打四个字多花几秒，这几秒里用户必须逐个字看过去，
     * 也就必然读到了"挂失"这个后果本身。
     *
     * 要求输入的内容**不能改成语义中性的词**（如"我同意"/"继续"），
     * 否则这一步退化成机械输入，失去"让用户读到后果"的意义。
     */
    const val LOST_PHRASE: String = "我确认挂失这张校园卡"

    /**
     * 解挂要求输入的文字。
     *
     * 与 [LOST_PHRASE] 必须**明显不同**：若两者相同，用户在已挂失的卡上
     * 点解挂时输入的文字与刚才挂失时一模一样，等于无门槛，
     * 而解挂会让冻结的卡重新可刷，误操作成本同样很高。
     */
    const val UNLOST_PHRASE: String = "我确认解挂这张校园卡"

    /** 按操作类型取要求的确认文字。 */
    fun phraseFor(isLost: Boolean): String = if (isLost) LOST_PHRASE else UNLOST_PHRASE

    /**
     * 输入的确认文字是否通过。
     *
     * 归一规则（**只做不改变语义的宽容**）：
     * - 去首尾空白；
     * - 去掉所有**内部空白**（手机输入法常自动在词间插空格）；
     * - 全角转半角（用户可能误切全角）。
     *
     * **不做**的宽容（这些会削弱"必须看清并手打"的意义）：
     * - 不忽略大小写之外的内容差异；
     * - 不做拼音/近义词匹配；
     * - 不接受空串或只输一半。
     *
     * @param input 用户输入
     * @param phrase 要求输入的文字（默认按挂失）
     */
    fun matches(input: String, phrase: String = LOST_PHRASE): Boolean =
        normalize(input) == normalize(phrase) && normalize(phrase).isNotEmpty()

    /**
     * 归一化：去除全部空白 + 全角转半角。
     *
     * 全角映射覆盖 ASCII 可见区（`\uFF01`..`\uFF5E` → `\u0021`..`\u007E`）
     * 与全角空格（`\u3000`）。
     */
    internal fun normalize(text: String): String {
        val builder = StringBuilder(text.length)
        for (ch in text) {
            val mapped = when {
                ch == '\u3000' -> ' '
                ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                else -> ch
            }
            if (!mapped.isWhitespace()) builder.append(mapped)
        }
        return builder.toString()
    }
}
