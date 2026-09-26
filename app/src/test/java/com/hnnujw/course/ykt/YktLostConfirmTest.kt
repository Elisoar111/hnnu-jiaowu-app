package com.hnnujw.course.ykt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 挂失/解挂二次确认判据的单测。
 *
 * ## 为什么这组测试必须存在
 *
 * [YktLostConfirm.matches] 的返回值**直接决定会不会真的把用户的卡挂掉**。
 * 判错的两个方向都不只是"体验问题"：
 *
 * - **过宽**（不该通过却通过）：用户手滑/粘贴就完成挂失，卡立即冻结、无法自助撤销。
 * - **过严**（该通过却不通过）：用户卡真丢了却挂不上，余额可能被别人刷走。
 *
 * 所以这里把"什么算通过、什么算不通过"逐条钉死，尤其是那些**看起来只是
 * 格式差异**的输入（空格、全角、大小写）与那些**看起来很像但语义不同**的输入
 * （少一个字、多一个字、近义词）。
 *
 * ## 关于"改坏重跑"
 *
 * 本轮的变异注入点选在 [YktLostConfirm.normalize] 的空白过滤上：
 * 把 `if (!mapped.isWhitespace())` 改成恒真，`内部空格被忽略` 等用例应立即 FAILED。
 * 只改阈值或调整 `when` 分支顺序会因等价路径吸收变异而不报错——教训见项目备忘。
 */
class YktLostConfirmTest {

    // ── 文案本身 ─────────────────────────────────────────────────────────

    /**
     * 两个短语都必须是**第一人称声明句**，且句子里写明操作类型。
     *
     * 用户要打出来的内容就是他的承诺（"我确认挂失这张校园卡"）。
     * 若改成语义中性的词（如"继续"/"我同意"），这一步就退化成纯粹的机械输入，
     * 失去"让用户读到后果"的意义。
     */
    @Test
    fun `确认短语是第一人称声明且写明操作`() {
        assertTrue("应以'我'开头，构成第一人称承诺", YktLostConfirm.LOST_PHRASE.startsWith("我"))
        assertTrue("应写明'挂失'", YktLostConfirm.LOST_PHRASE.contains("挂失"))
        assertTrue("应以'我'开头，构成第一人称承诺", YktLostConfirm.UNLOST_PHRASE.startsWith("我"))
        assertTrue("应写明'解挂'", YktLostConfirm.UNLOST_PHRASE.contains("解挂"))
        assertTrue("应明确操作对象", YktLostConfirm.LOST_PHRASE.contains("校园卡"))
    }

    /**
     * 短语要有**足够长度**。
     *
     * 这是"输入文字"这道闸门的意义所在：句子越长，用户越不可能不看内容
     * 而机械敲完。若短语只有两三个字，它就退化成"再点一次确认"。
     */
    @Test
    fun `确认短语足够长以强制用户阅读`() {
        assertTrue("挂失短语不应短于 6 字", YktLostConfirm.LOST_PHRASE.length >= 6)
        assertTrue("解挂短语不应短于 6 字", YktLostConfirm.UNLOST_PHRASE.length >= 6)
    }

    /**
     * 挂失与解挂的短语必须**不同**。
     *
     * 若两者相同，用户在"已挂失"的卡上点解挂时，输入的文字与他刚才挂失时
     * 输入的一模一样，心智负担为零但误操作成本极高（解挂后卡又能被刷）。
     * 文案不同才能确保用户读的是"当前这次操作"的后果。
     */
    @Test
    fun `挂失与解挂的短语不同`() {
        assertTrue(
            "两个短语相同会让解挂变成无门槛操作",
            YktLostConfirm.LOST_PHRASE != YktLostConfirm.UNLOST_PHRASE,
        )
    }

    /** [YktLostConfirm.phraseFor] 按操作类型取短语，不能取反。 */
    @Test
    fun `phraseFor 按操作类型取对应短语`() {
        assertEquals(YktLostConfirm.LOST_PHRASE, YktLostConfirm.phraseFor(isLost = true))
        assertEquals(YktLostConfirm.UNLOST_PHRASE, YktLostConfirm.phraseFor(isLost = false))
    }

    // ── 正确输入必须通过 ─────────────────────────────────────────────────

    /** 逐字输入，最常见的路径。 */
    @Test
    fun `完全一致时通过`() {
        assertTrue(
            YktLostConfirm.matches("我确认挂失这张校园卡", "我确认挂失这张校园卡")
        )
        assertTrue(
            YktLostConfirm.matches("我确认解挂这张校园卡", "我确认解挂这张校园卡")
        )
        // 同时验证真实常量本身可被通过——防止常量里混入看不见的字符
        assertTrue(YktLostConfirm.matches(YktLostConfirm.LOST_PHRASE))
        assertTrue(
            YktLostConfirm.matches(
                YktLostConfirm.UNLOST_PHRASE,
                YktLostConfirm.UNLOST_PHRASE,
            )
        )
    }

    /**
     * 手机输入法常在词与词之间自动插空格（中文候选栏"我确认 挂失"）。
     *
     * 这种输入**语义上完全一致**，用户也确实逐字打了，拒绝它只会让人
     * 反复怀疑自己输入法坏了——属于应当宽容的格式差异。
     */
    @Test
    fun `内部空格被忽略`() {
        val phrase = "我确认挂失这张校园卡"
        assertTrue("词间空格应宽容", YktLostConfirm.matches("我确认 挂失这张校园卡", phrase))
        assertTrue("多个空格应宽容", YktLostConfirm.matches("我确认  挂失 这张校园卡", phrase))
        assertTrue("制表符应宽容", YktLostConfirm.matches("我确认\t挂失这张校园卡", phrase))
    }

    /** 首尾空白同理，属于纯格式差异。 */
    @Test
    fun `首尾空白被忽略`() {
        val phrase = "我确认挂失这张校园卡"
        assertTrue(YktLostConfirm.matches("  我确认挂失这张校园卡  ", phrase))
        assertTrue(YktLostConfirm.matches("\n我确认挂失这张校园卡\n", phrase))
    }

    /**
     * 全角空格（`\u3000`）在中文输入法下极常见，且肉眼几乎与半角看不出区别。
     * 它与半角空格一样只是空白，应当宽容。
     */
    @Test
    fun `全角空格被忽略`() {
        val phrase = "我确认挂失这张校园卡"
        assertTrue(YktLostConfirm.matches("我确认\u3000挂失这张校园卡", phrase))
    }

    /**
     * 用户误切全角时，中文字符不变，但**其中的标点/ASCII** 会变成全角。
     * 这里用一个含 ASCII 的短语验证归一规则本身生效
     *（`\uFF01`..`\uFF5E` → `\u0021`..`\u007E`）。
     */
    @Test
    fun `全角 ASCII 归一为半角`() {
        assertEquals("ABC123", YktLostConfirm.normalize("ＡＢＣ１２３"))
    }

    // ── 错误输入必须被拒 ─────────────────────────────────────────────────

    /**
     * **空输入绝不能通过**——这是最关键的一条。
     *
     * 归一化会把纯空白串变成空串；若判据写成 `normalize(input) == normalize(phrase)`
     * 而 phrase 也为空（或漏了非空校验），一个空输入就能完成挂失。
     * 这里同时锁住"空串"与"纯空白"两种形态。
     */
    @Test
    fun `空输入不通过`() {
        val phrase = "我确认挂失这张校园卡"
        assertFalse("空串必须拒绝", YktLostConfirm.matches("", phrase))
        assertFalse("纯空格必须拒绝", YktLostConfirm.matches("     ", phrase))
        assertFalse("全角空格必须拒绝", YktLostConfirm.matches("\u3000", phrase))
    }

    /** 只输一半不能通过：否则"逐字读后果"的约束就失效了。 */
    @Test
    fun `部分输入不通过`() {
        val phrase = "我确认挂失这张校园卡"
        assertFalse(YktLostConfirm.matches("我确认", phrase))
        assertFalse(YktLostConfirm.matches("我确认挂失", phrase))
        assertFalse(YktLostConfirm.matches("我确认挂失这张", phrase))
        assertFalse("漏掉最后的'卡'也不通过", YktLostConfirm.matches("我确认挂失这张校园", phrase))
    }

    /** 多输字符同样不通过。 */
    @Test
    fun `多余字符不通过`() {
        val phrase = "我确认挂失这张校园卡"
        assertFalse(YktLostConfirm.matches("我确认挂失这张校园卡啊", phrase))
        assertFalse(YktLostConfirm.matches("好的我确认挂失这张校园卡", phrase))
    }

    /**
     * **挂失与解挂不能互相通用**。
     *
     * 在已挂失的卡上点"解挂"，却输入了挂失的句子，说明用户没有读当前提示，
     * 必须拒绝——否则解挂变成了无脑通过的操作。
     */
    @Test
    fun `挂失与解挂的短语互不通用`() {
        assertFalse(
            "解挂时应拒绝挂失短语",
            YktLostConfirm.matches(YktLostConfirm.LOST_PHRASE, YktLostConfirm.UNLOST_PHRASE),
        )
        assertFalse(
            "挂失时应拒绝解挂短语",
            YktLostConfirm.matches(YktLostConfirm.UNLOST_PHRASE, YktLostConfirm.LOST_PHRASE),
        )
    }

    /**
     * 不做近义词/语义匹配：只认字面。
     *
     * 一旦放宽成"意思差不多就行"，判据就要引入模型或词表，
     * 从"可被单测钉死的纯函数"退化成"无法验证的启发式"，
     * 而它守护的是不可逆操作——不值得为省几个字冒这个险。
     */
    @Test
    fun `近义表达不通过`() {
        val phrase = "我确认挂失这张校园卡"
        assertFalse(YktLostConfirm.matches("我确定挂失这张校园卡", phrase))
        assertFalse(YktLostConfirm.matches("我要挂失这张校园卡", phrase))
        assertFalse(YktLostConfirm.matches("我确认挂失该校园卡", phrase))
        assertFalse(YktLostConfirm.matches("我同意挂失这张校园卡", phrase))
    }

    /** 标点不能替代文字。 */
    @Test
    fun `带标点不通过`() {
        val phrase = "我确认挂失这张校园卡"
        assertFalse(YktLostConfirm.matches("我确认挂失这张校园卡。", phrase))
        assertFalse(YktLostConfirm.matches("我确认挂失这张校园卡！", phrase))
    }

    // ── 归一化本身 ───────────────────────────────────────────────────────

    /**
     * [YktLostConfirm.normalize] 是纯函数，直接测它的边界。
     *
     * 它是 `matches` 的唯一实现细节，且是变异注入的首选点——
     * 把它单独钉死，"宽容到什么程度"这件事就不会被悄悄改动。
     */
    @Test
    fun `normalize 的边界行为`() {
        assertEquals("", YktLostConfirm.normalize(""))
        assertEquals("", YktLostConfirm.normalize(" \t\n\u3000 "))
        assertEquals("我确认挂失", YktLostConfirm.normalize(" 我 确 认 挂 失 "))
        assertEquals("ABC", YktLostConfirm.normalize("ＡＢＣ"))
        // 中文本身不在全角 ASCII 区间内，必须原样保留
        assertEquals("我确认挂失这张校园卡", YktLostConfirm.normalize("我确认挂失这张校园卡"))
    }

    /**
     * 默认参数必须指向**挂失**短语。
     *
     * 挂失是更危险的那个方向：如果默认值被误改成解挂短语，
     * 任何忘记显式传参的调用点都会用错判据。
     */
    @Test
    fun `matches 默认短语为挂失`() {
        assertTrue(YktLostConfirm.matches(YktLostConfirm.LOST_PHRASE))
        assertFalse("默认不应接受解挂短语", YktLostConfirm.matches(YktLostConfirm.UNLOST_PHRASE))
    }
}
