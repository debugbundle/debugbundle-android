# Smoke Tests

The release flow uses an application-driven Robolectric consumer project that installs the Android SDK from either:

- a temporary staged Maven repository produced during CI, or
- Maven Central after publication.

Run the local staged smoke:

```sh
make smoke
```

Run the published-artifact smoke:

```sh
make smoke-published VERSION=0.1.1
```
