# Security Policy

## Reporting a Vulnerability

Do not report security issues in public issues, discussions, or pull requests.

Report vulnerabilities through GitHub's private vulnerability reporting flow for `debugbundle/debugbundle-android`:

- https://github.com/debugbundle/debugbundle-android/security/advisories/new

Include a clear description, impact assessment, affected version or commit, reproduction steps, and any mitigations you have already validated.

## Supported Versions

DebugBundle is pre-production. Security fixes are applied against the current `main` branch and the latest unreleased code in this repository.

## Response Expectations

- Initial triage within 3 business days.
- A follow-up status update after reproduction and impact assessment.
- Coordinated disclosure after a fix or mitigation is available.

## Scope

Security concerns include:

- Secret, token, or credential leakage in SDK transport or offline persistence.
- Redaction failures for secrets, PII, or regulated data.
- Trace propagation crossing unintended hosts.
- Trigger-token validation or probe activation crossing request boundaries incorrectly.
