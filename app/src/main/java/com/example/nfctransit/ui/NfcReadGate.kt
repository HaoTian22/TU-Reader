package com.example.nfctransit.ui

/** 整个读取/保存任务互斥；冷却从任务结束计算，避免重复检测积压成队列。 */
internal class NfcReadGate {
    private var activeTag: String? = null
    private var lastTag: String? = null
    private var finishedAt: Long? = null

    @Synchronized
    fun tryBegin(tagId: String, nowMs: Long): Boolean {
        if (activeTag != null) return false
        val elapsed = finishedAt?.let { nowMs - it }
        if (elapsed != null && (elapsed < 300 || (lastTag == tagId && elapsed < 1500))) {
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
