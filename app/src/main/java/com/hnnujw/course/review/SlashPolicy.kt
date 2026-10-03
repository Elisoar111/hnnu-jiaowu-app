package com.hnnujw.course.review

/**
 * 「斩题」的语义约束。
 *
 * 斩题 = **把一眼就会的题移出后续练习**，不是删题、也不是"已掌握"。它是刷题时最省时的
 * 一个动作：一套卷子里总有一半是闭着眼都会的，一刀斩掉，后面就只剩值得练的题。
 *
 * 三条必须守住的语义（都做成纯函数/常量，便于单测和复用）：
 *  1. **斩题 ≠ 已掌握**：斩一道错题**不会**把它标记成已掌握，也不会改动错题本的掌握状态。
 *     斩是"我不用练了"（主观跳过），掌握是"我答对了"（客观记录），两者不能混。若混了，
 *     用户会发现"我一斩，它就说我掌握了"，统计立刻失真。
 *  2. **斩是软隐藏，可恢复**：斩掉的题进「斩题本」，随时能放回练习；它仍留在题库里。
 *  3. **斩不删除任何学习记录**：records / wrong_book / favorites 一行都不动。
 *
 * 因此本文件只提供"能不能斩"的判据，不提供任何修改掌握状态的动作 —— 因为没有这种动作。
 */

/** 斩题判据的结果。返回原因对象而非 Boolean，便于界面给出准确文案。 */
sealed interface SlashCheck {
    /** 可以斩。 */
    data object Ok : SlashCheck

    /** 已经斩过了 —— 重复斩是幂等的，不该报错，界面按"已斩"渲染即可。 */
    data object AlreadySlashed : SlashCheck

    /** 题目不在当前题库里（理论上不该发生，防御性留下）。 */
    data object UnknownQuestion : SlashCheck
}

/**
 * 能否斩这一道题。
 *
 * @param known 该题是否确实存在于目标题库
 * @param slashed 该题当前是否已被斩
 */
fun checkSlash(known: Boolean, slashed: Boolean): SlashCheck = when {
    !known -> SlashCheck.UnknownQuestion
    slashed -> SlashCheck.AlreadySlashed
    else -> SlashCheck.Ok
}

/**
 * 斩题**不会**改变掌握状态。
 *
 * 这个函数存在的唯一目的是把这个不变量**显式化并可测**：它恒等返回入参。
 * 如果哪天有人在斩题的代码路径上"顺手"改掌握度，改动要么绕不开这个函数（被 review 看见），
 * 要么就会被 [SlashPolicyTest] 里"斩题不改变掌握状态"那条断言挡下。
 */
fun wrongStatusAfterSlash(current: WrongStatus): WrongStatus = current
