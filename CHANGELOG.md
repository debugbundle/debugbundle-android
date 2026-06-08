# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

## 1.1.0

- Added path-scoped immediate client-error incident promotion support in the mobile remote capture-policy handling so explicitly configured `4xx` routes can emit standalone `request_event` incident signals without widening the status globally.
- Unpromoted client-error request telemetry now remains context-only under repeated traffic, while `5xx` handling and explicitly promoted client-error behavior are preserved.

## 1.0.0

- Declared the Android SDK package family stable at `1.0.0` after release-hardening the Maven Central publish flow, aligned BOM packaging, and published-artifact Robolectric smoke coverage.

## 0.1.1

- Clarified that Android remains a native mobile client SDK and does not implement browser relay host settings such as `transportMode`, `allowedOrigins`, or CORS preflight handling.

- Initialized the standalone Android SDK repository layout and governance files.
- Added Maven Central publishing, release validation, and a published-artifact Robolectric smoke flow.
