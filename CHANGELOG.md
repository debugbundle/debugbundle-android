# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

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
