package io.github.nku100.webui.data

import kotlinx.coroutines.sync.Mutex

/** Serializes config and driver registry publications within this process. */
internal object RepositoryMutationGuard {
    private val mutex = Mutex()

    suspend fun <T> mutate(block: suspend () -> T): T {
        mutex.lock()
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}
