package com.hnnujw.course.ykt

/**
 * 一卡通「上次选的级联项」与学校当前配置的**对账**。
 *
 * ## 为什么要对账
 *
 * 一卡通的费项（电费/水费）可能需要逐级选择：楼栋 → 单元 → 房间。
 * 应用把用户上次的选择记忆在本地（`YktSelectionStore`），下次进来直接带出。
 *
 * 原实现只比**级数**：`picks.size > sceneLevels.size` 才丢弃。这挡不住更常见的一种
 * 变化 —— 学校**改了级的含义、级数没变**：
 *
 *  改前：楼栋(1) → 单元(2) → 房间(3)
 *  改后：楼栋(1) → 房间(2)              ← 级数从 3 变 2，会被旧逻辑判掉
 *  改前：楼栋(1) → 单元(2) → 房间(3)
 *  改后：园区(1) → 楼栋(2) → 房间(3)    ← 级数仍是 3，旧逻辑放行 → **错位**
 *
 * 第二种就是问题所在：`picks` 里存的是 `(code, value)` 对，`code` 是级的标识。
 * 级数没变、但 code 变了，把它们原样传给接口，服务端要么报错要么返回**别人房间**的数据。
 * 「看到别人家的电费」不是夸张，是这类错位的典型后果。
 *
 * ## 口径
 *
 * 逐级比对 `code`：前缀对得上的部分保留，遇到第一个对不上的就**从那里截断**。
 * 不整条丢弃是有意的 —— 楼栋没变、只是后面多/少一级时，保留楼栋选择对用户更友好。
 *
 * 纯函数，不碰存储与网络，可直接单测。
 */
object YktPickReconcile {

    /**
     * 把记忆里的 [remembered] 与学校当前的 [currentLevelCodes] 对齐。
     *
     * @param currentLevelCodes 当前费项需要的级标识，按级顺序排列（可能为空 = 不需要选）。
     * @return 可以安全复用的 picks（逐级前缀匹配的结果，长度不会超过 `currentLevelCodes`）。
     */
    fun reconcile(remembered: List<ScenePick>, currentLevelCodes: List<String>): List<ScenePick> {
        if (currentLevelCodes.isEmpty()) return emptyList()
        val kept = ArrayList<ScenePick>(minOf(remembered.size, currentLevelCodes.size))
        for (i in currentLevelCodes.indices) {
            val rememberedAtLevel = remembered.getOrNull(i) ?: break
            // 逐级比 code：级语义变了就从这里截断，绝不把旧值硬套到新级上。
            if (rememberedAtLevel.code != currentLevelCodes[i]) break
            kept.add(rememberedAtLevel)
        }
        return kept
    }

    /**
     * 是否发生了**语义错位**（级数相同但 code 不同）。
     *
     * 用于决定要不要顺手清掉本地记忆：这种情况旧数据一定用不上了，
     * 留着只会每次进来都做一次无意义的对账。
     */
    fun hasSemanticDrift(remembered: List<ScenePick>, currentLevelCodes: List<String>): Boolean {
        if (remembered.isEmpty() || currentLevelCodes.isEmpty()) return false
        // 只在"级数没变"时才有"漂移"可言；级数变了本身就是另一条规则。
        if (remembered.size != currentLevelCodes.size) return false
        return remembered.indices.any { remembered[it].code != currentLevelCodes[it] }
    }
}
