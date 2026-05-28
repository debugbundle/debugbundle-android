package com.debugbundle.android.crash

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
        this.crashFile.parent?.createDirectories()
    }

    @Synchronized
    override fun load(): DebugBundleFatalCrashRecord? {
        if (!crashFile.exists()) {
            return null
        }
        return try {
            crashFile.inputStream().use { input ->
                json.decodeFromString<DebugBundleFatalCrashRecord>(input.readBytes().decodeToString())
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
        crashFile.parent?.createDirectories()
        val tempFile = Files.createTempFile(crashFile.parent, crashFile.fileName.toString(), ".tmp")
        tempFile.outputStream().use { output ->
            output.write(json.encodeToString(record).encodeToByteArray())
        }
        try {
            Files.move(
                tempFile,
                crashFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Throwable) {
            Files.move(
                tempFile,
                crashFile,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}

@Serializable
internal data class DebugBundleFatalCrashRecord(
    val version: Int = 1,
    val occurredAt: String,
    val threadName: String,
    val errorType: String,
    val errorMessage: String,
    val stackTrace: List<String>,
)
