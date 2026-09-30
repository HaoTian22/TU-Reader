package com.example.nfctransit.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 等待启动恢复成功，再串行处理会修改卡片镜像及持久层的操作。 */
internal class CardStateCoordinator {
    private val restored = CompletableDeferred<Unit>()
    private val mutex = Mutex()

    suspend fun initialize(restore: suspend () -> Unit) {
        mutex.withLock {
            try {
                restore()
                restored.complete(Unit)
            } catch (error: Throwable) {
                restored.completeExceptionally(error)
                throw error
            }
        }
    }

    suspend fun <T> withRestoredState(operation: suspend () -> T): T {
        restored.await()
        return mutex.withLock { operation() }
    }
}
