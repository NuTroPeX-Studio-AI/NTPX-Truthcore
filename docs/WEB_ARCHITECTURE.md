# TruthCore Web Architecture

```text
Public website (/)
        │
        ├──────────────► Web application (/app)
        │                         │
        │                 text / browser voice
        │                         │
        │                         ▼
        │                 POST /api/chat
        │                         │
        │                 Conversation policy
        │                  ┌──────┴──────┐
        │                  ▼             ▼
        │             local answer   model draft
        │                                │
        │                 provider proxy (rate limited)
        │                                │
        │                  HTTPS allowlist + netguard
        │            (connect-time private/reserved IP block)
        │                                │
        │                                ▼
        │                           model endpoint
        │                                │
        │                                ▼
        │                         untrusted draft
        │                                │
        │          sanitized evidence (optionally embedding-ranked)
        │                                │
        │                     ClaimLock (deterministic)
        │                                │
        │                  optional NLI judge (additive only)
        │                                │
        │                       ┌────────┴────────┐
        │                       ▼                 ▼
        │                   VERIFIED          ABSTAINED
        │
        └──────────────► installable PWA shell
```

## Boundaries

- Android and Web are sibling clients in one repository; neither is treated as the other's wrapper.
- The web model proxy is server-side so a provider key is not compiled into static browser assets.
- BYOK credentials are held only in the active browser tab and transmitted to the same-origin server for the current request.
- The deployment operator must allowlist provider hostnames with `TRUTHCORE_ALLOWED_PROVIDER_HOSTS`.
- Remote endpoints must use HTTPS, must not embed URL credentials, and must not resolve to private/reserved addresses. This is enforced twice: once during validation and again inside the socket's own DNS lookup (`src/netguard.mjs`), so a host cannot pass validation and then re-resolve to an internal address.
- `/api/*` is JSON-only, rate limited per client (set `TRUTHCORE_TRUST_PROXY=1` only behind a trusted proxy), and returns 404 for unknown routes.
- Client-supplied evidence is capped at trust 0.9, cannot reuse built-in evidence keys, and is sanitized before use.
- Model output is not automatically factual authority. Creative output is labeled `GENERATED`; factual output passes through ClaimLock.
- The service worker never caches `/api/*` requests.

## Conformance

The Kotlin and JavaScript truth cores are held to identical decisions by `conformance/claimlock-cases.json`, which both test suites execute. Add a case there whenever ClaimLock behavior changes.

## Modules

| File | Responsibility |
|---|---|
| `server.mjs` | Process entry, signals |
| `src/http.mjs` | Routes, rate limiting, static serving, security headers |
| `src/netguard.mjs` | Guarded HTTPS transport and reserved-address checks |
| `src/provider.mjs` | Chat, embedding, and NLI-judge provider factories |
| `src/claimlock.mjs` | Deterministic ClaimLock + additive semantic gate |
| `src/semantic.mjs` | NLI contract, deep gate, embedding index, reranking |
| `src/conversation.mjs` | Request policy and evidence packet construction |
| `public/store.js` | IndexedDB memory/knowledge and atomic audit chain |
