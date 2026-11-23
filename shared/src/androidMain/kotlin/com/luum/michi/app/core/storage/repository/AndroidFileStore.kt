package com.luum.michi.app.core.storage.repository

import android.content.Context
import com.luum.michi.app.core.storage.domain.FileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException

/** One cache folder for every user-scoped file (lists today, profile later). */
private const val CacheDirName = "michi_cache"

/** Durable files that must survive the OS clearing the cache. */
private const val DurableDirName = "michi_durable"

/**
 * [FileStore] over `context.cacheDir`, in its own subfolder. Every call runs on
 * [Dispatchers.IO]; writes go to a temp file then atomically rename, so a killed
 * process never leaves a half-written file.
 */
internal class AndroidFileStore(context: Context) : FileStore by AndroidDirFileStore(
    File(context.applicationContext.cacheDir, CacheDirName),
)

/**
 * [FileStore] over `context.filesDir`, outside the cache the OS may clear. It
 * backs the mirror outbox and is excluded from Android backups in
 * `data_extraction_rules.xml`.
 */
internal class AndroidDurableFileStore(context: Context) : FileStore by AndroidDirFileStore(
    File(context.applicationContext.filesDir, DurableDirName),
)

/** Atomic text files in one directory. */
private class AndroidDirFileStore(
    private val dir: File,
) : FileStore {

    override suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        try {
            File(dir, name).takeIf { it.exists() }?.readText()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Unreadable: treated as absent.
            null
        }
    }

    override suspend fun write(name: String, content: String) {
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val target = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            try {
                tmp.writeText(content)
                try {
                    Files.move(
                        tmp.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: Exception) {
                    // Some filesystems reject ATOMIC_MOVE: fall back to a replace.
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }

    override suspend fun delete(name: String) {
        withContext(Dispatchers.IO) {
            File(dir, name).delete()
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }
}
