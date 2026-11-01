package com.debugbundle.smoke

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EvidenceSmokeTest {
    @Test fun installedNativeCauseOperationRecoveryPrivacyAndCanonicalAcceptance() {
        assertInstalledNativeEvidence()
    }
}
