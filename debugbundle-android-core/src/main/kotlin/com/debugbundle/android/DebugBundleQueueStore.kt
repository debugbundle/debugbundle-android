package com.debugbundle.android

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class DebugBundleQueueLimits(
    val maxEvents: Int,
    val maxBytes: Long,
    val ttlMillis: Long,
)

@Serializable
data class QueuedDebugBundleEvent(
    val envelope: DebugBundleEnvelope,
    @SerialName("queued_at_millis")
    val queuedAtMillis: Long,
)

interface DebugBundleQueueStore {
    fun snapshot(nowMillis: Long, limits: DebugBundleQueueLimits): List<QueuedDebugBundleEvent>

    fun append(
        events: List<DebugBundleEnvelope>,
        nowMillis: Long,
        limits: DebugBundleQueueLimits,
    ): List<QueuedDebugBundleEvent>

    fun removeLeading(
        count: Int,
        nowMillis: Long,
        limits: DebugBundleQueueLimits,
    ): List<QueuedDebugBundleEvent>
}

/**
 * Additive capability used by built-in stores to acknowledge a batch by event
 * index without requeueing events the API already accepted.
 */
interface DebugBundleIndexedAcknowledgementQueueStore : DebugBundleQueueStore {
    fun retainLeadingIndices(
        count: Int,
        retainedIndices: Set<Int>,
        nowMillis: Long,
        limits: DebugBundleQueueLimits,
    ): List<QueuedDebugBundleEvent>
}

class InMemoryDebugBundleQueueStore : DebugBundleIndexedAcknowledgementQueueStore {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val records = ArrayDeque<QueuedDebugBundleEvent>()

    @Synchronized
    override fun snapshot(nowMillis: Long, limits: DebugBundleQueueLimits): List<QueuedDebugBundleEvent> {
        prune(nowMillis, limits)
        return records.toList()
    }

    @Synchronized
    override fun append(
        events: List<DebugBundleEnvelope>,
        nowMillis: Long,
        limits: DebugBundleQueueLimits,
    ): List<QueuedDebugBundleEvent> {
        events.forEach { records.addLast(QueuedDebugBundleEvent(it, nowMillis)) }
        prune(nowMillis, limits)
        return records.toList()
    }

    @Synchronized
    override fun removeLeading(
        count: Int,
        nowMillis: Long,
        limits: DebugBundleQueueLimits,
    ): List<QueuedDebugBundleEvent> {
        repeat(count.coerceAtMost(records.size)) {
            records.removeFirst()
        }
        prune(nowMillis, limits)
        return records.toList()
    }

    @Synchronized
    override fun retainLeadingIndices(
        count: Int,
        retainedIndices: Set<Int>,
        nowMillis: Long,
        limits: DebugBundleQueueLimits,
    ): List<QueuedDebugBundleEvent> {
        val selectedCount = count.coerceAtMost(records.size)
        val leading = List(selectedCount) { records.removeFirst() }
        leading
            .filterIndexed { index, _ -> index in retainedIndices }
            .asReversed()
            .forEach(records::addFirst)
        prune(nowMillis, limits)
        return records.toList()
    }

    private fun prune(nowMillis: Long, limits: DebugBundleQueueLimits) {
        while (records.isNotEmpty() && nowMillis - records.first().queuedAtMillis > limits.ttlMillis) {
            records.removeFirst()
        }
        while (records.size > limits.maxEvents) {
            records.removeFirst()
        }
        while (serializedSize(records.toList()) > limits.maxBytes && records.isNotEmpty()) {
            records.removeFirst()
        }
    }

    private fun serializedSize(events: List<QueuedDebugBundleEvent>): Long {
        return json.encodeToString(StoredQueueState(events = events)).encodeToByteArray().size.toLong()
    }

    @Serializable
    private data class StoredQueueState(
        val version: Int = 1,
        val events: List<QueuedDebugBundleEvent> = emptyList(),
    )
}

class FileDebugBundleQueueStore(
    queueFile: Path,
    private val json: Json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = true },
) : DebugBundleIndexedAcknowledgementQueueStore {
    private val queueFile = queueFile.toAbsolutePath().normalize()

    init {
        require(this.queueFile.isAbsolute) { "offline queue path must be absolute" }
        this.queueFile.parent?.createDirectories()
    }

    @Synchronized
    override fun snapshot(nowMillis: Long, limits: DebugBundleQueueLimits): List<QueuedDebugBundleEvent> {
        val records = prune(readState().events.toMutableList(), nowMillis, limits)
        writeState(StoredQueueState(events = records))
        return records
    }

    @Synchronized
    override fun append(
        events: List<DebugBundleEnvelope>,
        nowMillis: Long,
        limits: DebugBundleQueueLimits,
    ): List<QueuedDebugBundleEvent> {
        val current = readState().events.toMutableList()
        events.forEach { current.add(QueuedDebugBundleEvent(it, nowMillis)) }
        val records = prune(current, nowMillis, limits)
        writeState(StoredQueueState(events = records))
        return records
    }

    @Synchronized
    override fun removeLeading(
        count: Int,
        nowMillis: Long,
        limits: DebugBundleQueueLimits,
    ): List<QueuedDebugBundleEvent> {
        val current = readState().events.toMutableList()
        repeat(count.coerceAtMost(current.size)) {
            current.removeFirst()
        }
        val records = prune(current, nowMillis, limits)
        writeState(StoredQueueState(events = records))
        return records
    }

    @Synchronized
    override fun retainLeadingIndices(
        count: Int,
        retainedIndices: Set<Int>,
        nowMillis: Long,
        limits: DebugBundleQueueLimits,
    ): List<QueuedDebugBundleEvent> {
        val current = readState().events.toMutableList()
        val selectedCount = count.coerceAtMost(current.size)
        val leading = current.take(selectedCount)
        repeat(selectedCount) {
            current.removeFirst()
        }
        current.addAll(
            0,
            leading.filterIndexed { index, _ -> index in retainedIndices },
        )
        val records = prune(current, nowMillis, limits)
        writeState(StoredQueueState(events = records))
        return records
    }

    private fun readState(): StoredQueueState {
        if (!queueFile.exists()) {
            return StoredQueueState()
        }
        return try {
            queueFile.inputStream().use { input ->
                json.decodeFromString<StoredQueueState>(input.readBytes().decodeToString())
            }
        } catch (_: Throwable) {
            StoredQueueState()
        }
    }

    private fun writeState(state: StoredQueueState) {
        queueFile.parent?.createDirectories()
        val tempFile = Files.createTempFile(queueFile.parent, queueFile.fileName.toString(), ".tmp")
        tempFile.outputStream().use { output ->
            output.write(json.encodeToString(state).encodeToByteArray())
        }
        try {
            Files.move(
                tempFile,
                queueFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Throwable) {
            Files.move(
                tempFile,
                queueFile,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun prune(
        events: MutableList<QueuedDebugBundleEvent>,
        nowMillis: Long,
        limits: DebugBundleQueueLimits,
    ): List<QueuedDebugBundleEvent> {
        events.removeAll { nowMillis - it.queuedAtMillis > limits.ttlMillis }
        while (events.size > limits.maxEvents) {
            events.removeFirst()
        }
        while (serializedSize(events) > limits.maxBytes && events.isNotEmpty()) {
            events.removeFirst()
        }
        return events
    }

    private fun serializedSize(events: List<QueuedDebugBundleEvent>): Long {
        return json.encodeToString(StoredQueueState(events = events)).encodeToByteArray().size.toLong()
    }

    @Serializable
    private data class StoredQueueState(
        val version: Int = 1,
        val events: List<QueuedDebugBundleEvent> = emptyList(),
    )
}
