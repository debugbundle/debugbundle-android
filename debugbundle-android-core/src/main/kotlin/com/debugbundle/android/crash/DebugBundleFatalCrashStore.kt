package com.debugbundle.android.crash

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal interface DebugBundleFatalCrashStore {
    fun load(): DebugBundleFatalCrashRecord?

    fun persist(record: DebugBundleFatalCrashRecord)

    fun clear()
}

internal class NoopDebugBundleFatalCrashStore : DebugBundleFatalCrashStore {
    override fun load(): DebugBundleFatalCrashRecord? = null

    override fun persist(record: DebugBundleFatalCrashRecord) = Unit

    override fun clear() = Unit
}

internal class FileDebugBundleFatalCrashStore(
    crashFile: Path,
    private val json: Json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = true },
) : DebugBundleFatalCrashStore {
    private val crashFile = crashFile.toAbsolutePath().normalize()

    init {
        require(this.crashFile.isAbsolute) { "fatal crash path must be absolute" }
    }

    @Synchronized
    override fun load(): DebugBundleFatalCrashRecord? {
        if (!crashFile.exists()) {
            return null
        }
        return try {
            crashFile.inputStream().use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer, 0, minOf(buffer.size, MAX_FILE_BYTES + 1 - bytes.size()))
                    if (count < 0) break
                    if (count == 0) return null
                    bytes.write(buffer, 0, count)
                    if (bytes.size() > MAX_FILE_BYTES) return null
                }
                val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
                json.decodeFromString<DebugBundleFatalCrashRecord>(text)
            }
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    override fun persist(record: DebugBundleFatalCrashRecord) {
        writeRecord(record)
    }

    @Synchronized
    override fun clear() {
        try {
            Files.deleteIfExists(crashFile)
        } catch (_: Throwable) {
            // Preserve host crash and startup behavior.
        }
    }

    private fun writeRecord(record: DebugBundleFatalCrashRecord) {
        val bytes = json.encodeToString(record).encodeToByteArray()
        if (bytes.size > MAX_FILE_BYTES) return
        crashFile.parent?.createDirectories()
        val tempFile = Files.createTempFile(crashFile.parent, crashFile.fileName.toString(), ".tmp")
        try {
            tempFile.outputStream().use { output -> output.write(bytes) }
            try { Files.move(tempFile, crashFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: Throwable) { Files.move(tempFile, crashFile, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(tempFile) }
    }
    private companion object { const val MAX_FILE_BYTES = 256 * 1024 }
}

@Serializable
internal data class DebugBundleFatalCrashRecord(
    val version: Int = 1,
    val occurredAt: String,
    val threadName: String,
    val errorType: String,
    val errorMessage: String,
    val stackTrace: List<String>,
    val traceOrigin: String = "unknown",
)
