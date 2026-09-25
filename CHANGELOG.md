# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

## [3.0.0] - 2026-09-25

### Breaking changes

- Capture admits protected events to a bounded memory stage. A single serialized worker runs accepted `beforeSend` callbacks, final validation/privacy/policy/sampling/suppression, offline persistence and delivery. Capture and construction no longer wait for disk or callbacks; events can be lost if the process exits before background persistence. See [MIGRATION-3.0.md](MIGRATION-3.0.md).
- `flush(timeout)` waits only for the current shared worker generation up to its timeout. Custom stores and transports retain their interfaces and run on the worker. Hook drops do not consume the session event allowance; valid schema replacements, including IDs and types, retain their previous semantics.
- Stage ownership includes pending and active entries, hook replacements and disk retries (256 events/2 MiB total). Overflow protects exception/error evidence ahead of ordinary logs. Offline stores retain their configured count, bytes and TTL caps and now evict the oldest lowest-priority record on overflow.


### Changed

- Reconcile delivery acknowledgements using sent event identities so queue overflow during a send cannot delete newer unsent events or shift retryable acknowledgement indices.

- Reject logs below the effective local and remote level before merging context, privacy scanning, or invoking `beforeSend`. The hook is no longer called for logs rejected by policy.
- Gate Maven Central publication on connected emulator checks for Android API 23, 36, and 37.0 in addition to the existing source and artifact checks.

## [2.0.0] - 2026-09-21

### Security

- Enforce the native bounded privacy baseline before and after capture hooks, in the built-in persistent file queue, and before transport or replay. Validated correlation IDs remain useful, while unsafe historical queued records are withheld.

## [1.3.1] - 2026-09-12

### Fixed

- Preserve the full Apache license in Android AARs using module-specific `META-INF/<artifact>/LICENSE` resources; the generic license path was omitted from 1.3.0 AARs.
- Verify license metadata and the exact packaged license in every staged and published artifact before the consumer smoke passes.

## [1.3.0] - 2026-09-12

### Changed

- License first-party SDK code under Apache-2.0 and ship consistent package licensing metadata and license text.

## 1.2.0 - 2026-07-28

- Emit canonical closed mobile event envelopes and reconcile ingestion acknowledgements per event so retryable rejections remain queued and terminal rejections do not produce false delivery health.
- Add the universal `beforeSend` hook, canonical external-event capture for React Native, and object wrapping for scalar/list probe values.
- Add shared-schema wire-contract coverage and minimum/current Android connected-device release lanes.
- Prepare the coordinated `1.2.0` source line, enforce at least 80% coverage across every production source file, emit Kotlin 2.1-compatible metadata, and fail verification if compiled artifacts exceed that consumer-safe metadata level.

## 1.1.0

- Added path-scoped immediate client-error incident promotion support in the mobile remote capture-policy handling so explicitly configured `4xx` routes can emit standalone `request_event` incident signals without widening the status globally.
- Unpromoted client-error request telemetry now remains context-only under repeated traffic, while `5xx` handling and explicitly promoted client-error behavior are preserved.

## 1.0.0

- Declared the Android SDK package family stable at `1.0.0` after release-hardening the Maven Central publish flow, aligned BOM packaging, and published-artifact Robolectric smoke coverage.

## 0.1.1

- Clarified that Android remains a native mobile client SDK and does not implement browser relay host settings such as `transportMode`, `allowedOrigins`, or CORS preflight handling.

- Initialized the standalone Android SDK repository layout and governance files.
- Added Maven Central publishing, release validation, and a published-artifact Robolectric smoke flow.
