# Current status

**v1.1.0** — source-complete on Android and Web, with hardening and real verification backends. See [`CHANGELOG.md`](../CHANGELOG.md).

| Gate | Result |
|---|---|
| Android unit tests (`:app:testDebugUnitTest`) | 61 passing |
| Android debug APK (`:app:assembleDebug`) + lint | passing |
| Web tests (`npm test`) | 65 passing |
| Web syntax checks (`npm run check`) | passing |
| ClaimLock cross-platform conformance (17 shared cases) | Android and Web agree |
| HTTP smoke test / Docker build | in Web CI |

GitHub Actions remains the authoritative gate for Android SDK/Gradle validation and publishes the debug APK artifact.

Still operator-owned (not source gaps): production credentials, hosting/domain/TLS, signing keys, store review, real-device hands-free validation, and optional on-device model assets. See [`FINISHING.md`](FINISHING.md).
