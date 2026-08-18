# NTPX TruthCore

Android-first, local-first cognitive agent runtime focused on evidence-gated answers, native voice interaction, durable memory, permissioned tools, and fail-closed execution.

## Current milestone

**v0.5 Android Foundation**

- Kotlin + Jetpack Compose native app
- ClaimLock deterministic release gate
- Evidence sanitization / prompt-injection quarantine
- Evidence trust, freshness, provenance hash primitives
- Episodic / semantic / procedural memory model
- Android SpeechRecognizer + TextToSpeech voice layer
- GitHub Actions unit tests and debug APK build
- Official Gradle wrapper generated and validated in CI

## Build

The CI workflow uses JDK 17, Gradle 9.5.0, Android Gradle Plugin 9.3.0, compileSdk 37, and the Compose BOM 2026.06.00.

```bash
gradle wrapper --gradle-version 9.5.0
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is published as the `ntpx-truthcore-debug-apk` GitHub Actions artifact.

## Truth invariant

> Models may propose. Evidence must authorize factual release. Privileged actions require explicit policy authority.

This repository is the canonical source for NTPX TruthCore. The earlier Python/FastAPI implementation remains a reference architecture while features are ported natively to Android.
