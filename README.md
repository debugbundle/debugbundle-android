# DebugBundle Android

Kotlin Android SDK for DebugBundle.

Version **3.1.0 is published** with bounded cause/frame evidence, explicit recovery and revocable operation scopes. Read [the 3.1 guide](MIGRATION-3.1.md) for configuration, compatibility, native limitations and qualification. The client and default facade expose the same evidence APIs; `evidenceCapabilities` reports support intersected with current local flags and diagnostic policy.

Version **3.0.0** changes capture and persistence timing. See [the 3.0 migration](MIGRATION-3.0.md) before upgrading from 2.x.

The Android package family provides native Android capture, durable offline delivery, lifecycle breadcrumbs, crash replay, request correlation, and optional UI/network/logging adapters while keeping the host app fail-open.

Android is a mobile client SDK, not a browser relay host. It sends mobile events to the configured ingestion endpoint and uses explicit first-party network instrumentation for trace correlation; browser relay settings such as `transportMode`, `allowedOrigins`, and CORS preflight handling belong to the Browser SDK plus a backend/server SDK relay.

## Installation

Use the BOM so all Android artifacts stay aligned:

```kotlin
dependencies {
    implementation(platform("com.debugbundle:debugbundle-android-bom:3.1.0"))
    implementation("com.debugbundle:debugbundle-android")
    implementation("com.debugbundle:debugbundle-android-okhttp")
}
```

## Quick Start

```kotlin
class CheckoutApp : Application() {
    override fun onCreate() {
        super.onCreate()

        DebugBundle.init(
            application = this,
            config = DebugBundleConfig(
                projectToken = BuildConfig.DEBUGBUNDLE_TOKEN,
                service = "checkout-android",
                environment = BuildConfig.BUILD_TYPE,
                releaseChannel = BuildConfig.FLAVOR.ifBlank { "production" }
            )
        )
    }
}
```

Optional OkHttp instrumentation:

```kotlin
val httpClient = OkHttpClient.Builder()
    .addInterceptor(
        DebugBundleOkHttpInterceptor(
            tracePropagationTargets = listOf(DebugBundleTracePropagationTarget.host("api.example.com"))
        )
    )
    .build()
```

First-event verification:

```kotlin
DebugBundle.captureException(IllegalStateException("android smoke"))
DebugBundle.flush()
```

## Modules

| Module | Purpose |
| --- | --- |
| `debugbundle-android-core` | Core facade, client, event envelope, redaction, buffering, transport abstraction, capture policy, and probe buffers/directives |
| `debugbundle-android` | Android runtime bootstrap, lifecycle/process hooks, device context, ANR replay, and WorkManager flushing |
| `debugbundle-android-bom` | Version-alignment platform for published Android SDK artifacts |
| `debugbundle-android-compose` | Optional Compose helpers for screen recording and Navigation bridging |
| `debugbundle-android-ktor-client` | Optional Ktor client plugin for target-scoped trace injection and request failure promotion |
| `debugbundle-android-navigation` | Optional Navigation listener for screen transitions and previous-screen context |
| `debugbundle-android-okhttp` | Optional OkHttp interceptor for target-scoped trace injection and request failure promotion |
| `debugbundle-android-testkit` | Test helpers for fake transports and batch inspection |
| `debugbundle-android-timber` | Optional Timber tree for structured SDK log capture |

## Build And Verification

Local commands default to a Docker-backed Gradle runner. GitHub Actions uses the Gradle wrapper directly.

```sh
make verify
make smoke
make build
```

Published-artifact smoke:

```sh
make smoke-published VERSION=3.1.0
```

Release publish:

```sh
make publish-central VERSION=3.1.0
```

## Current Scope

- Universal Kotlin facade and instance client
- No-throw capture APIs
- Canonical event envelope builder
- Mandatory bounded `telemetry-privacy-v1` protection for credential keys and high-confidence credential text, with `redactFields` adding customer fields. Capture hooks receive protected evidence on the serialized background worker, and their returned event is protected and validated again before persistence.
- Log eligibility is checked against the effective local and remote capture policy before context construction or `beforeSend`; rejected logs do not invoke the hook.
- Batching and bounded retry/backoff state
- Retry delays from custom transports are capped at five minutes before deadline arithmetic, including infinite durations.
- Duplicate suppression and `error_suppressed` aggregate emission
- Session sampling and max-events-per-session enforcement
- File-backed offline queue store with TTL and size bounds; on the startup worker its older records are projected and atomically rewritten before entering the transport buffer. Unsafe records are withheld.
- Breadcrumb ring buffer attached to exceptions by default
- Device-context provider abstraction with JVM-safe default snapshot
- Lifecycle-facing recording APIs for screens, app state, and coarse actions
- Configurable log-level filtering and structured log payload metadata
- Coroutine exception handler helper with coroutine-name capture when available
- Chained uncaught-exception capture with bounded next-launch crash replay
- Allowlisted request/response header capture with bodies still off by default
- One-shot `/v1/sdk/config` fetch with ETag support and explicit refresh hook
- Server-owned capture-policy enforcement for logs, request-event promotion, and standalone breadcrumbs
- Shared propagation-target matcher for native HTTP integrations
- Android runtime bootstrap delegated from the core facade when Android classes are present
- Android-specific config defaults for queue/crash storage and app version metadata
- Android device-context snapshot provider
- `Application.ActivityLifecycleCallbacks` screen capture
- Process foreground/background breadcrumbs with immediate background flush scheduling
- WorkManager-backed deferred flush worker and scheduler
- Next-launch ANR/process-exit capture from `ApplicationExitInfo`
- Published BOM for aligned Android SDK dependency versions
- Optional Ktor client plugin with explicit propagation-target matching
- Optional Navigation listener with previous-screen tracking
- Optional Compose screen recorder helpers
- Optional OkHttp interceptor with explicit propagation-target matching
- Optional Timber tree for structured logging capture
- Automatic network breadcrumb capture for instrumented requests
- Policy-driven request-event promotion with preserved trace IDs
- Always-on probe ring buffers with contract-shaped `payload.probe_data = { version, items }`
- Remote probe directives from `GET /v1/sdk/config`
- Piggybacked `probe_directives` parsing from successful ingestion responses
- Standalone `probe_event` emission for active matching directives
- Heavy-probe dormancy until a matching remote directive or trigger token is active
- HMAC-validated `dbundle_probe_...` trigger tokens through public helper and OkHttp/Ktor header extraction
- Fake transport test utilities

## Release Notes

- Maven Central publishing uses the same Central Portal credentials and in-memory GPG signing secrets already used by the other JVM SDKs.
- CI validates unit and contract coverage, stages artifacts into a temporary Maven repository, verifies the Apache metadata and complete packaged license in every artifact, and runs a Robolectric consumer smoke install from published coordinates.
- Each code module carries the full license at `META-INF/<artifact>/LICENSE` to preserve it through Android packaging and avoid resource collisions between SDK modules.
- Verification inspects emitted Kotlin metadata and fails if a module exceeds the Kotlin 2.1 metadata level supported by the consumer/R8 matrix.
- The release workflow runs connected emulator checks for Android API 23, 36, and 37.0 before its quality, artifact, and Maven Central publication steps. `make smoke-connected GRADLE_RUNNER=wrapper` installs the staged Maven packages into the synthetic consumer and executes real instrumentation; a receipt check rejects missing, skipped or failed native-evidence tests. It then verifies full-family published state, publishes the aligned package family, and retries a clean-install smoke until the artifacts propagate.

### Ingestion retry hints

Retryable partial acknowledgements, malformed acknowledgements and server failures honor bounded `Retry-After` hints, including HTTP-date headers. Delays are capped at five minutes and measured from response receipt. Existing fallback backoff remains in effect when no hint is supplied.

## Probe checkpoint guidance

Application instrumentation supplies probe labels and bounded data. Deploy a new
checkpoint before activating it. Remote activation affects future matching calls
when policy permits; it does not inject code or recover unretained measurements.
Built-in runtime/error/device collectors keep their documented SDK settings.

Use fixed labels and allowlisted technical states: `media.preview.checkpoint`
(stage/input kind/attempt), `auth.session.checkpoint` (observed session presence
and result), `job.dependency.checkpoint` (named dependency/attempt/result), or
`cache.preview.checkpoint` (hit/miss/coarse age bucket). Omit customer identifiers,
tokens/cookies, cache keys, URLs and raw payloads. HTTP 200 or attempt completion
alone does not establish semantic recovery.

Empty probe data means no matching retained observation for the selected sample
window. It does not confirm activation delivery to every SDK or execution of a
label. Check the deployed SDK/wrapper version, instrumentation, scope, expiry,
policy, sampling and delivery receipts. See the [probe recipes](https://debugbundle.com/docs/probes#bounded-checkpoint-recipes).

## Evidence foundation

Current local source uses the packaged accepting schema plus semantic graph,
reference/scope, resource and UTF-8 checks for complete hook results. Hooks may
replace any schema-allowed field, including ID/type. Invalid/throwing hooks keep
the protected original; exhausted validation is withheld. Accepted JSON values are
copied independently with 8MiB/4096-value/depth16 ownership ceilings. Receiving
metadata byte counts are separate from actual native serializer bytes. Absent
optional envelope context/correlation is omitted, rather than sent as invalid null.
Repeated ordinary/empty containers remain values; actual cycles stay bounded.
This foundation does not establish native evidence/recovery/reader-proof parity;
those producer, bridge/offline and qualification paths are still in progress.

Native context projection admits standard bounded primitive containers and uses
`[Unsupported value]` for foreign collection implementations, custom number
formatters and arbitrary objects. Correlation fields require strings; logging
labels use bounded native scalars. These paths do not enumerate foreign context
maps or call arbitrary formatters on capture callers. Privacy runs before SDK
retention and after accepted hooks. Optional evidence that becomes invalid after
privacy is reduced with a limit marker; the latest accepted primary stays intact.

Evidence options use `config.withEvidenceOptions(DebugBundleEvidenceOptions(...))`
so the existing Kotlin constructors and generated copy descriptors stay compatible.
`captureExceptionEvidence` defaults true, `captureRecoveryObservations` false, and
`evidenceCapabilities` null/unknown. Both this builder and `withBeforeSend` preserve
and independently own each other's settings. Like the existing hook body property,
evidence options are outside the data-class constructor: use the SDK builders after
ordinary `copy(...)` calls. A pin asserts the complete delivery/reader path.
Descriptor normalization accepts only v1, unique known features and the common
bounded intersection. Native discovery/production integration is still active work.

For the local evidence candidate, `make evidence-smoke` stages all Maven publications, validates packaged licenses and exercises actual installed native evidence in the fresh consumer. Artifacts remain in `.tmp/evidence-maven`; `make evidence-manifest` records their SHA256 and the uncommitted source/receipt identities. These local receipts do not replace connected-device or publication gates.

`make evidence-performance` compares 20 identical native exception/recovery pairs
with evidence disabled/enabled and an empty/full 256-event staging queue. The
standalone JVM harness reports caller time/allocation, deferred worker CPU/allocation
and retained staging bytes, and checks original failure priority and native causes.
Initialization, seeding and error creation are excluded; a held worker makes pressure
deterministic. Configuration, storage and event delivery use local in-memory fakes.
Measurements are HotSpot/JDK qualification, with one warm-up and three reported
repetitions; they do not establish Android ART/device performance. Benchmark classes
are excluded from the published SDK.

The installed consumer also contains an ART off/on/queue-pressure comparison. It
reports caller time, deferred worker CPU, bounded retained bytes and approximate
process-wide allocation deltas from Android's `Debug.getRuntimeStat`. Those counters
include runtime/test background allocation and are not exclusive SDK-thread bytes;
missing statistics remain null. The same20 native error/recovery pairs, one warm-up
and three measured repetitions exercise both modes. No network call is used in
this performance fixture; the separate installed evidence test uses native HTTP.
See [Android runtime statistics](https://developer.android.com/reference/android/os/Debug#getRuntimeStat(java.lang.String)).
Emulator measurements do not predict physical-device timings.
