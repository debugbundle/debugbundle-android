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
        val retained = pruneDebugBundleQueue(records.toMutableList(), nowMillis, limits, json)
        records.clear()
        records.addAll(retained)
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
    private var privacyTransformer: ((DebugBundleEnvelope) -> DebugBundleEnvelope?)? = null

    /** Installed before startup hydration; old records are rewritten only after safe transformation. */
    @Synchronized
    internal fun installPrivacyTransformer(transform: (DebugBundleEnvelope) -> DebugBundleEnvelope?) {
        privacyTransformer = transform
    }

    init {
        require(this.queueFile.isAbsolute) { "offline queue path must be absolute" }
    }

    @Synchronized
    override fun snapshot(nowMillis: Long, limits: DebugBundleQueueLimits): List<QueuedDebugBundleEvent> {
        val records = prune(readState(limits).events.toMutableList(), nowMillis, limits)
        writeState(StoredQueueState(events = records))
        return records
    }

    @Synchronized
    override fun append(
        events: List<DebugBundleEnvelope>,
        nowMillis: Long,
        limits: DebugBundleQueueLimits,
    ): List<QueuedDebugBundleEvent> {
        val current = readState(limits).events.toMutableList()
        events.mapNotNull { transform(it) }.forEach { current.add(QueuedDebugBundleEvent(it, nowMillis)) }
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
        val current = readState(limits).events.toMutableList()
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
        val current = readState(limits).events.toMutableList()
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

    private fun transform(envelope: DebugBundleEnvelope): DebugBundleEnvelope? =
        runCatching { privacyTransformer?.invoke(envelope) ?: if (privacyTransformer == null) envelope else null }.getOrNull()

    private fun readState(limits: DebugBundleQueueLimits): StoredQueueState {
        if (!queueFile.exists()) {
            return StoredQueueState()
        }
        return try {
            if (Files.size(queueFile) > limits.maxBytes) return StoredQueueState()
            queueFile.inputStream().use { input ->
                val decoded = json.decodeFromString<StoredQueueState>(input.readBytes().decodeToString())
                val protected = ArrayList<QueuedDebugBundleEvent>()
                var retainedBytes = 0L
                for (queued in decoded.events) {
                    val event = transform(queued.envelope) ?: continue
                    val projected = queued.copy(envelope = event)
                    val bytes = json.encodeToString(projected).encodeToByteArray().size.toLong()
                    // Do not retain an expanded hydration batch beyond the configured serialized budget.
                    if (bytes > limits.maxBytes) continue
                    protected.add(projected)
                    retainedBytes += bytes
                    if (protected.size > limits.maxEvents || retainedBytes > limits.maxBytes) {
                        pruneDebugBundleQueue(protected, queued.queuedAtMillis, limits, json)
                        retainedBytes = protected.sumOf { json.encodeToString(it).encodeToByteArray().size.toLong() }
                    }
                }
                StoredQueueState(events = protected)
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
    ): List<QueuedDebugBundleEvent> = pruneDebugBundleQueue(events, nowMillis, limits, json)

    @Serializable
    private data class StoredQueueState(
        val version: Int = 1,
        val events: List<QueuedDebugBundleEvent> = emptyList(),
    )
}

/** Count/bytes overflow evicts the oldest lowest-priority row; ordinary FIFO behavior stays intact. */
private fun pruneDebugBundleQueue(
    events: MutableList<QueuedDebugBundleEvent>,
    nowMillis: Long,
    limits: DebugBundleQueueLimits,
    json: Json,
): List<QueuedDebugBundleEvent> {
    events.removeAll { nowMillis - it.queuedAtMillis > limits.ttlMillis }
    val sizes = events.map { json.encodeToString(it).encodeToByteArray().size.toLong() }.toMutableList()
    // StoredQueueState's version/events wrapper is constant. Charge commas once and update per eviction.
    var bytes = "{\"version\":1,\"events\":[]}".length.toLong() + sizes.sum() + (events.size - 1).coerceAtLeast(0)
    while (events.isNotEmpty() && (events.size > limits.maxEvents || bytes > limits.maxBytes)) {
        val index = events.indices.minBy { DebugBundleCaptureWorker.priority(events[it].envelope) }
        bytes -= sizes.removeAt(index) + if (events.size > 1) 1 else 0
        events.removeAt(index)
    }
    return events
}
