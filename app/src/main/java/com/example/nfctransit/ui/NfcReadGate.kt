package com.example.nfctransit.ui

/** 整个读取/保存任务互斥；冷却从任务结束计算，避免重复检测积压成队列。 */
internal class NfcReadGate {
    private var activeTag: String? = null
    private var lastTag: String? = null
    private var finishedAt: Long? = null

    private var suppressedTags: Set<String> = emptySet()
    private var suppressedUntil = 0L

    /** 在 [untilMs] 之前忽略这些标签（联名卡读完后恢复全技术轮询时的重复发现）。 */
    @Synchronized
    fun suppress(tagIds: Set<String>, untilMs: Long) {
        suppressedTags = tagIds
        suppressedUntil = untilMs
    }

    /** @param ignoreCooldown 联名卡第二阶段：另一接口的发现紧随第一阶段，不受冷却限制 */
    @Synchronized
    fun tryBegin(tagId: String, nowMs: Long, ignoreCooldown: Boolean = false): Boolean {
        if (activeTag != null) return false
        if (tagId in suppressedTags && nowMs < suppressedUntil) return false
        val elapsed = finishedAt?.let { nowMs - it }
        if (!ignoreCooldown && elapsed != null && (elapsed < 300 || (lastTag == tagId && elapsed < 1500))) {
            return false
        }
        activeTag = tagId
        return true
    }

    @Synchronized
    fun finish(nowMs: Long) {
        lastTag = activeTag ?: return
        activeTag = null
        finishedAt = nowMs
    }
}
