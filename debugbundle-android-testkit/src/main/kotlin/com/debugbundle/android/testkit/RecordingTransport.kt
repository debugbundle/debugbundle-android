package com.debugbundle.android.testkit

import com.debugbundle.android.DebugBundleEnvelope
import com.debugbundle.android.DebugBundleTransport
import com.debugbundle.android.DebugBundleTransportRequest
import com.debugbundle.android.DebugBundleTransportResult
import java.util.concurrent.CopyOnWriteArrayList

class RecordingTransport(
    private val responder: (DebugBundleTransportRequest) -> DebugBundleTransportResult = {
        DebugBundleTransportResult(statusCode = 202)
    },
) : DebugBundleTransport {
    private val _requests = CopyOnWriteArrayList<DebugBundleTransportRequest>()

    val requests: List<DebugBundleTransportRequest>
        get() = _requests.toList()

    val events: List<DebugBundleEnvelope>
        get() = _requests.flatMap { it.events }

    override fun send(request: DebugBundleTransportRequest): DebugBundleTransportResult {
        _requests.add(request)
        return responder(request)
    }
}
