package com.debugbundle.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Real ART, native HTTP and installed Maven bytes; no SDK source overlay. */
@RunWith(AndroidJUnit4::class)
class InstalledEvidenceDeviceTest {
    @Test fun nativeCauseRecoveryPrivacyAndCapabilityDelivery() {
        assertInstalledNativeEvidence()
    }
}
