package com.luum.michi.app.core.storage.domain

/**
 * Suspending text-file store for payloads too big for `SharedPreferences`
 * (which loads the whole file into memory at startup). The platform wires a
 * **cache** store (the OS may wipe it, so a missing file is normal) and a
 * **durable** store (survives cache clears; the mirror outbox). Names are flat
 * (no path separators) and writes are atomic, so a process kill mid-write never
 * leaves a broken file.
 */
internal interface FileStore {
    /** File contents, or null when absent/unreadable. */
    suspend fun read(name: String): String?

    /** Writes [content] to [name], replacing it atomically. */
    suspend fun write(name: String, content: String)

    suspend fun delete(name: String)

    /** Removes every file in the store's folder (privacy: logout / 401). */
    suspend fun clear()
}
