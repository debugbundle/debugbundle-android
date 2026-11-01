package com.debugbundle.android.runtime

import com.debugbundle.android.DebugBundleBeforeSend
import com.debugbundle.android.DebugBundleConfig
import com.debugbundle.android.DebugBundleEvidenceCapabilities
import com.debugbundle.android.DebugBundleEvidenceOptions
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class DebugBundleEvidenceConfigStoreTest {
    @Test
    fun `native defaults and background reload preserve local evidence permission and owned pin`() {
        val application = TestApplication(Files.createTempDirectory("debugbundle-evidence-settings").toFile())
        val hook = DebugBundleBeforeSend { it }
        val config = DebugBundleConfig(projectToken = "fixture").withBeforeSend(hook).withEvidenceOptions(DebugBundleEvidenceOptions(captureExceptionEvidence = false, captureRecoveryObservations = true, evidenceCapabilities = DebugBundleEvidenceCapabilities(features = listOf("exception_evidence_v1", "recovery_v1"))))
        val resolved = DebugBundleAndroidDefaults.resolveConfig(application, config)
        assertEquals(hook, resolved.beforeSend); assertFalse(resolved.evidenceOptions.captureExceptionEvidence)
        val store = DebugBundleAndroidConfigStore(application); store.save(resolved); val restored = store.load(); assertNotNull(restored)
        assertFalse(restored!!.evidenceOptions.captureExceptionEvidence); assertEquals(true, restored.evidenceOptions.captureRecoveryObservations)
        assertEquals(listOf("exception_evidence_v1", "recovery_v1"), restored.evidenceOptions.evidenceCapabilities?.features)
    }
}
