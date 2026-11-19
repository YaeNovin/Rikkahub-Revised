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
    private val acceptEvents: Boolean = false,
    private val retentionMillis: Long? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private data class Record(val entry: LogEntry, val bytes: Long)
    private val records = linkedMapOf<Uuid, Record>()
    private var loaded = false

    @Synchronized
    fun read(): List<LogEntry> {
        return summaries().mapNotNull { detail(it.id) }
    }

    @Synchronized
    fun summaries(): List<LogEntry> {
        load()
        return records.values.map { it.entry }.sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun detail(id: Uuid): LogEntry? {
        load()
        if (id !in records) return null
        return try { decode(File(directory, "$id.json")) } catch (error: Exception) { onFailure(error); null }
    }

    @Synchronized
    fun save(entry: LogEntry) {
        require(acceptEvents || entry is LogEntry.RequestLog || entry is LogEntry.ProviderRequestLog)
        load()
        try {
            val bytes = json.encodeToString<LogEntry>(entry).toByteArray(Charsets.UTF_8)
            require(bytes.size <= maxBytes) { "Request log exceeds storage limit" }
            atomicWrite(File(directory, "${entry.id}.json"), bytes)
            val summary = entry.summaryOnly()
            records[entry.id] = Record(summary, bytes.size.toLong())
            atomicWrite(File(directory, "${entry.id}.index"), json.encodeToString<LogEntry>(summary).toByteArray(Charsets.UTF_8))
            pruneDisk()
        } catch (error: Exception) { onFailure(error) }
    }

    @Synchronized
    fun clear() {
        // Never recursively remove a directory. Only files owned by this store are deleted.
        try {
            directory.listFiles().orEmpty().filter { isRecordFile(it) || isTemporaryFile(it) || isIndexFile(it) }.forEach {
                check(it.delete() || !it.exists()) { "Unable to clear request log" }
            }
            records.clear()
            loaded = true
        } catch (error: Exception) { onFailure(error) }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        directory.listFiles().orEmpty().filter { isTemporaryFile(it) }.forEach { it.delete() }
        try { pruneDisk() } catch (error: Exception) { onFailure(error) }
        // Limit hydration even if a previous version left too many files.
        var loadedBytes = 0L
        directory.listFiles().orEmpty().filter(::isRecordFile)
            .sortedByDescending { it.lastModified() }.forEach { file ->
                try {
                    val length = file.length()
                    if (records.size >= maxEntries || loadedBytes + length > maxBytes) return@forEach
                    val index = File(directory, "${file.nameWithoutExtension}.index")
                    val entry = if (index.isFile && index.lastModified() >= file.lastModified()) {
                        runCatching { decode(index).summaryOnly() }.getOrElse { decode(file).summaryOnly() }
                    } else decode(file).summaryOnly().also {
                        atomicWrite(index, json.encodeToString<LogEntry>(it).toByteArray(Charsets.UTF_8))
                    }
                    if ((acceptEvents || entry is LogEntry.RequestLog || entry is LogEntry.ProviderRequestLog) && file.name == "${entry.id}.json") {
                        records[entry.id] = Record(entry, length)
                        loadedBytes += length
                    }
                } catch (error: Exception) { onFailure(error) }
            }
    }

    private fun decode(file: File): LogEntry {
        require(file.length() <= maxBytes) { "Oversized log" }
        return json.decodeFromString<LogEntry>(file.readText(Charsets.UTF_8))
    }

    private fun pruneDisk() {
        var count = 0
        var bytes = 0L
        directory.listFiles().orEmpty().filter(::isRecordFile).sortedByDescending { it.lastModified() }.forEach { file ->
            val index = File(directory, "${file.nameWithoutExtension}.index")
            val weight = file.length() + index.length()
            if (count >= maxEntries || bytes + weight > maxBytes ||
                retentionMillis?.let { file.lastModified() < System.currentTimeMillis() - it } == true) {
                check(file.delete() || !file.exists()) { "Unable to prune request log" }
                records.remove(Uuid.parse(file.nameWithoutExtension))
                index.delete()
            } else { count++; bytes += weight }
        }
        directory.listFiles().orEmpty().filter { isIndexFile(it) && !File(directory, "${it.nameWithoutExtension}.json").exists() }.forEach { it.delete() }
    }

    private fun isRecordFile(file: File): Boolean = file.isFile && file.extension == "json" &&
        runCatching { Uuid.parse(file.nameWithoutExtension) }.isSuccess

    private fun isTemporaryFile(file: File): Boolean = file.isFile &&
        (file.name.endsWith(".json.tmp") || file.name.endsWith(".index.tmp")) &&
        runCatching { Uuid.parse(file.name.removePrefix(".").removeSuffix(".tmp").substringBeforeLast('.')) }.isSuccess

    private fun isIndexFile(file: File): Boolean = file.isFile && file.extension == "index" &&
        runCatching { Uuid.parse(file.nameWithoutExtension) }.isSuccess
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
