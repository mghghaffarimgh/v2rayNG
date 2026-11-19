package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Append-only diagnostic log of leak checks, one JSON object per line.
 *
 * Written by the VPN service process, the background watcher process and the UI process, so every
 * write holds an exclusive file lock. Addresses are masked by the caller before they reach this
 * file, so it is safe to share for diagnosis.
 *
 * This is a separate owner from [LeakCheckManager] because its lifetime is the file on disk, not
 * a check: the log outlives every check, process and service run that wrote to it.
 */
object LeakLog {

    private const val FILE_NAME = "leak_log.jsonl"

    /** Above this size the oldest half of the entries is dropped. */
    private const val MAX_BYTES = 512 * 1024L

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    /**
     * Appends one entry.
     *
     * @param context Any context.
     * @param jsonLine The entry as single-line JSON.
     */
    fun append(context: Context, jsonLine: String) {
        val target = file(context)
        try {
            RandomAccessFile(target, "rw").use { raf ->
                raf.channel.lock().use {
                    if (raf.length() > MAX_BYTES) trimLocked(raf)
                    raf.seek(raf.length())
                    raf.write((jsonLine.replace('\n', ' ') + "\n").toByteArray(Charsets.UTF_8))
                }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LeakLog: append failed", e)
        }
    }

    /**
     * Reads the newest entries.
     *
     * @param context Any context.
     * @param maxLines How many entries to return at most.
     * @return The entries, oldest first.
     */
    fun readLast(context: Context, maxLines: Int): List<String> {
        val target = file(context)
        if (!target.exists()) return emptyList()
        return try {
            target.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.takeLast(maxLines)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LeakLog: read failed", e)
            emptyList()
        }
    }

    /**
     * Deletes every entry.
     *
     * @param context Any context.
     */
    fun clear(context: Context) {
        val target = file(context)
        try {
            RandomAccessFile(target, "rw").use { raf ->
                raf.channel.lock().use { raf.setLength(0) }
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LeakLog: clear failed", e)
        }
    }

    /**
     * Writes the newest entries to a file in the cache directory for sharing.
     *
     * @param context Any context.
     * @return The exported file, or null when there is nothing to export or writing failed.
     */
    fun exportToCache(context: Context): File? {
        val lines = readLast(context, Int.MAX_VALUE)
        if (lines.isEmpty()) return null
        return try {
            val out = File(context.cacheDir, FILE_NAME)
            FileOutputStream(out).use { it.write(lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)) }
            out
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "LeakLog: export failed", e)
            null
        }
    }

    /** Keeps the newer half of the file. The caller holds the lock. */
    private fun trimLocked(raf: RandomAccessFile) {
        val bytes = ByteArray(raf.length().toInt())
        raf.seek(0)
        raf.readFully(bytes)
        val text = String(bytes, Charsets.UTF_8)
        val lines = text.split('\n').filter { it.isNotBlank() }
        val kept = lines.takeLast(lines.size / 2).joinToString("\n", postfix = "\n")
        raf.setLength(0)
        raf.seek(0)
        raf.write(kept.toByteArray(Charsets.UTF_8))
    }
}
