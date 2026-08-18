# NTPX TruthCore

Verified-first cognitive agent platform for Android and the web. TruthCore treats generative models, retrieved content, tool output, and remote services as untrusted inputs until they pass the appropriate evidence, permission, and execution gates.

## v1.0.0-rc1 source-completion build

### Android

- native Kotlin + Jetpack Compose application
- Android SpeechRecognizer + TextToSpeech
- user-started foreground hands-free session with the wake phrase **Hey TruthCore**
- configurable HTTPS OpenAI-compatible model runtime
- API keys held in volatile process memory only
- persistent SQLite episodic/semantic/procedural memory records
- persistent local knowledge store + query-time evidence retrieval
- ClaimLock deterministic factual release gate
- optional fail-closed semantic/NLI deep-verification adapter contract
- optional embedding index adapter contract
- temporal knowledge graph + conservative contradiction resolution
- evidence sanitization / prompt-injection quarantine
- bounded agent executive with a maximum tool-call budget
- read-only and approval-gated local tools
- single-use action-bound approval tokens
- tamper-evident hash-chained local audit ledger
- bounded planner / critic / reviewer multi-agent workflow
- HTTPS MCP 2026-07-28 client, process-memory bearer credentials, and approval-gated remote `mcp.call`
- GitHub Actions debug APK and release-candidate APK/AAB builds

### Web

- responsive public website
- installable PWA-style application
- text chat + browser speech recognition / speech synthesis when supported
- browser-local IndexedDB memory, knowledge, and audit storage
- dynamic RAG evidence packets sent only for the current request
- JavaScript ClaimLock + evidence isolation
- fail-closed semantic/NLI and embedding adapter contracts
- bounded browser agent tools with approval-gated local writes
- planner / critic / reviewer team mode
- zero-dependency Node server
- server-side OpenAI-compatible HTTPS provider proxy
- deployment-controlled provider hostname allowlist and private-network protections
- BYOK credentials excluded from persistent browser storage and service-worker cache
- Web CI tests, syntax checks, HTTP smoke tests, and Docker image validation

## Truth invariant

> **Models may propose. Evidence must authorize factual release. Privileged or external actions require explicit policy authority and user approval.**

## Android build

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The normal Android CI workflow publishes `ntpx-truthcore-debug-apk`.

The release-candidate workflow additionally builds unsigned release APK/AAB artifacts. Production signing keys are intentionally not committed to the repository.

## Web run

```bash
cd web
npm test
npm run check
TRUTHCORE_ALLOWED_PROVIDER_HOSTS=api.example.com npm start
```

Then open `http://localhost:8787` for the website or `http://localhost:8787/app` for the web application.

## Operator-owned release inputs

The source tree intentionally does **not** pretend these external deployment inputs already exist:

- production model/MCP credentials
- a deployed HTTPS host and custom domain
- Android production keystore / Play signing configuration
- optional downloadable on-device generative, embedding, or NLI model assets
- Play Store or other distribution-account approval

Those are deployment and runtime configuration steps, not missing source-code placeholders.

This GitHub repository is the canonical source for NTPX TruthCore. The earlier Python/FastAPI implementation remains under `legacy-python/` as a reference architecture.
