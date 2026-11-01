# Android 3.1 candidate migration

This is an unpublished additive candidate. Retain 3.0.1 as the rollback artifact until the full release gates pass. No consumer project is updated by this change.

## Configuration and complete-path support

`DebugBundleConfig().withEvidenceOptions(DebugBundleEvidenceOptions(...))` adds `captureExceptionEvidence` (default true), `captureRecoveryObservations` (default false) and optional `evidenceCapabilities`. SDK-owned configuration copies and Android background preferences retain these settings. Existing constructor/default/copy descriptors remain compatible. Apply the builders after an application-owned ordinary data-class `copy`; like the existing BeforeSend setting, these options are body properties. Both builders preserve each other's values and independently own the capability list.

New evidence requires a closed version1 descriptor for the actual delivery and reader path. Native config/ingestion use their existing opt-in header; missing, malformed or unknown support disables new production. An explicit pin asserts the entire endpoint/relay/reader path, including custom transports. An opaque config wrapper does not discover support for a different transport. No capture-time request or new polling loop is introduced. Ingestion rejection after support loss can retry the same accepted original without optional evidence, with no second hook invocation.

## Causes and locations

The existing worker inspects one live weak Throwable at a time. New graphs contain at most eight nodes, depth four, five children, 32 frames per node and 32KiB of combined additional metadata. Causes and suppressed errors remain distinct; cycles, shared references, native array copies, Unicode and queue limits are bounded or explicitly marked. No frame arguments, local variables, source lookup or symbolication are captured. Native accessors can stall that one worker; there are no replacement workers and capture does not wait for them. Collected or unsafe details retain the existing protected fallback. Legacy primary stacks, propagation and suppression inputs remain intact.

## Explicit recovery and operation ownership

Use `beginOperation().use { ... }`, `withOperationContext { ... }` or `withOperationContextSuspending { ... }`. Each operation has a fresh opaque UUID. Nested and copied coroutine owners revoke when the owner completes or the client closes. Callback results, exceptions and cancellation remain application behavior.

Pass `DebugBundleEvidenceContext.forFailure(failureRef, optionalScope)` to exception context; `failureRef` is a new UUID per failure. `captureRecovery(DebugBundleRecoveryObservation(...))` independently queues an explicit observation with a fresh UUID per attempt and matching operation or explicit page scope. Page scope requires the same nonempty session; trace/session identities must match. States are `attempt_started`, `attempt_completed`, `attempt_failed`, `recovery_observed` and `failure_persisted`. An HTTP200 or completed attempt does not establish semantic recovery. Only report `recovery_observed` when the application confirms its own recovery condition. Technical operation labels are bounded; do not use user/resource identifiers or signed URLs as references.

Local opt-in, current policy, sampling, actual-path proof and queue ownership all apply. Limits are 50 windows, five attempts, 20 observations, five minutes and 64KiB including metadata and queued payloads. ACK/drop releases pending bytes without refunding allowances; offline pending receipts cannot renew an expired window. The existing offline count/bytes/TTL remain authoritative. A missing startup policy withholds previously persisted context until configuration is available; ordinary errors continue. A known denial or unsupported path drops context rather than creating an incident. Context follows existing metering and has no probe/analytics entitlement exemption.

## Native crash and ANR evidence

Fatal records are protected before persistence. The uncaught handler preserves the previous handler and its original Throwable. An allowlisted set of known built-in roots retains its existing message/stack behavior; other Throwable classes use a fixed safe fallback without invoking their accessors. Built-in JVM accessors/native array copies and filesystem I/O are best effort and do not provide hard deadlines against native monitors or storage. No additional cause/suppressed traversal or application hook runs on the fatal caller. Next-launch graphs parse retained native strings under a smaller ceiling of two nodes, eight total frames and 4KiB; absent cause detail is explicitly unavailable. Legacy records retain unknown origins. New supplied uncaught frames have `native_fatal` origin.

ANR reports retain their OS occurrence time. Supplied OS frames use `runtime_reported`; a constructed fallback is labelled `capture`, never a native ANR throw site. Trace reads stop at 4KiB. The report receipt advances only after the configured store retains the event; in-flight deduplication prevents repeat admission while persistence is pending. `captureRuntimeException` is the synthetic integration surface used by runtime adapters; its persistence callback runs on the existing worker and fatal replay bypasses application hooks.

## Delivery and bridges

Native requests count actual UTF8 serializer bytes, wrapper and commas under 256KiB, with finalized tails kept in the existing queue. A shared monotonic flush budget spans chunks. URLConnection response reads require identity encoding, strict UTF8 and at most256KiB under a shared connect/header/body-read budget. Native DNS/write/disconnect cancellation remains an Android/JVM limitation; explicit flush/close waits may finish before the worker's underlying operation. Opaque transports preserve their existing custom behavior.

React Native bridge input owns bounded plain native maps/lists/scalars before validation. Foreign containers/formatters are not invoked. New graph/resource/recovery fields are checked as a complete authored DTO before legacy migration can discard unknown fields; current native privacy/policy/proof applies again. Legacy bridge admission booleans still mean local admission, not cloud acceptance. There is no passive browser collection on Android itself.

## Qualification

Run `make verify` and `make smoke`; use `make evidence-regression` for focused core/runtime evidence checks. Publication additionally requires the existing API23/36/37.0 connected-device lanes and exact Maven artifacts/licenses. Local JVM/Robolectric/consumer results do not substitute for device qualification.
