# NTPX TruthCore

Verified-first cognitive agent platform for Android and the web, focused on evidence-gated answers, voice interaction, secure model routing, durable memory, permissioned tools, and fail-closed execution.

## Current platforms

### Android — v0.5.2 alpha

- Kotlin + Jetpack Compose native app
- Android SpeechRecognizer + TextToSpeech
- configurable HTTPS OpenAI-compatible model runtime
- BYOK API key held in volatile app memory
- ClaimLock deterministic factual release gate
- evidence sanitization / prompt-injection quarantine
- evidence trust, freshness, and provenance primitives
- episodic / semantic / procedural memory model
- GitHub Actions unit tests and debug APK build

### Web — v0.5.3 alpha

- responsive public TruthCore website
- installable PWA-style web app
- text chat + optional browser speech recognition / speech synthesis
- zero-dependency Node server
- server-side OpenAI-compatible HTTPS provider proxy
- deployment-controlled provider hostname allowlist
- browser BYOK credentials kept out of persistent storage
- JavaScript ClaimLock + evidence isolation port
- dedicated Web CI tests, syntax checks, and HTTP smoke tests
- Docker-ready deployment package

## Android build

The Android CI workflow uses JDK 17, Gradle 9.5.0, Android Gradle Plugin 9.3.0, compileSdk 37, and the Compose BOM 2026.06.00.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is published as the `ntpx-truthcore-debug-apk` GitHub Actions artifact.

## Web run

```bash
cd web
npm test
npm run check
TRUTHCORE_ALLOWED_PROVIDER_HOSTS=api.example.com npm start
```

Then open `http://localhost:8787` for the website or `http://localhost:8787/app` for the web app.

## Truth invariant

> Models may propose. Evidence must authorize factual release. Privileged actions require explicit policy authority.

This repository is the canonical source for NTPX TruthCore. The earlier Python/FastAPI implementation remains a reference architecture while TruthCore evolves across native Android and web runtimes.
