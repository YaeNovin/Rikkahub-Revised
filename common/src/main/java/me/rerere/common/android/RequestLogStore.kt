package me.rerere.common.android

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.uuid.Uuid

/** Private app files, not cache. One atomic file per request; a bad record cannot erase the rest.
 * Call on IO threads. No exit callback is required to make a finished write durable.
 */
internal class RequestLogStore(
    private val directory: File,
    private val maxEntries: Int = 500,
    private val maxBytes: Long = 32L * 1024 * 1024,
    private val onFailure: (Exception) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private data class Record(val entry: LogEntry, val bytes: Long)
    private val records = linkedMapOf<Uuid, Record>()
    private var loaded = false

    @Synchronized
    fun read(): List<LogEntry> {
        load()
        return records.values.map { it.entry }.sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun save(entry: LogEntry) {
        require(entry is LogEntry.RequestLog || entry is LogEntry.ProviderRequestLog)
        load()
        try {
            val bytes = json.encodeToString<LogEntry>(entry).toByteArray(Charsets.UTF_8)
            require(bytes.size <= maxBytes) { "Request log exceeds storage limit" }
            atomicWrite(File(directory, "${entry.id}.json"), bytes)
            records[entry.id] = Record(entry, bytes.size.toLong())
            pruneDisk()
        } catch (error: Exception) { onFailure(error) }
    }

    @Synchronized
    fun clear() {
        // Never recursively remove a directory. Only files owned by this store are deleted.
        try {
            directory.listFiles().orEmpty().filter { isRecordFile(it) || isTemporaryFile(it) }.forEach {
                check(it.delete() || !it.exists()) { "Unable to clear request log" }
            }
            records.clear()
            loaded = true
        } catch (error: Exception) { onFailure(error) }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        try { pruneDisk() } catch (error: Exception) { onFailure(error) }
        // Limit hydration even if a previous version left too many files.
        var loadedBytes = 0L
        directory.listFiles().orEmpty().filter(::isRecordFile)
            .sortedByDescending { it.lastModified() }.forEach { file ->
                try {
                    val length = file.length()
                    if (records.size >= maxEntries || loadedBytes + length > maxBytes) return@forEach
                    val entry = json.decodeFromString<LogEntry>(file.readText(Charsets.UTF_8))
                    if ((entry is LogEntry.RequestLog || entry is LogEntry.ProviderRequestLog) && file.name == "${entry.id}.json") {
                        records[entry.id] = Record(entry, length)
                        loadedBytes += length
                    }
                } catch (error: Exception) { onFailure(error) }
            }
    }

    private fun pruneDisk() {
        var count = 0
        var bytes = 0L
        directory.listFiles().orEmpty().filter(::isRecordFile).sortedByDescending { it.lastModified() }.forEach { file ->
            if (count >= maxEntries || bytes + file.length() > maxBytes) {
                check(file.delete() || !file.exists()) { "Unable to prune request log" }
                records.remove(Uuid.parse(file.nameWithoutExtension))
            } else { count++; bytes += file.length() }
        }
    }

    private fun isRecordFile(file: File): Boolean = file.isFile && file.extension == "json" &&
        runCatching { Uuid.parse(file.nameWithoutExtension) }.isSuccess

    private fun isTemporaryFile(file: File): Boolean = file.isFile && file.name.endsWith(".json.tmp") &&
        runCatching { Uuid.parse(file.name.removePrefix(".").removeSuffix(".json.tmp")) }.isSuccess
}

/** Sync before atomically replacing the old version. A failed write keeps the old record. */
internal fun atomicWrite(destination: File, bytes: ByteArray) {
    val parent = requireNotNull(destination.parentFile)
    check(parent.isDirectory || parent.mkdirs()) { "Cannot create log directory" }
    val temporary = File(parent, ".${destination.name}.tmp")
    try {
        FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}
