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
        │                         provider proxy
        │                                │
        │                       HTTPS allowlist
        │                                │
        │                                ▼
        │                           model endpoint
        │                                │
        │                                ▼
        │                         untrusted draft
        │                                │
        │                     evidence + ClaimLock
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
- Remote endpoints must use HTTPS, must not embed URL credentials, and must not resolve to private/reserved addresses.
- Model output is not automatically factual authority. Creative output is labeled `GENERATED`; factual output passes through ClaimLock.
- The service worker never caches `/api/*` requests.

## Near-term convergence

The current Kotlin and JavaScript truth cores intentionally mirror the same invariants. A later shared conformance suite should feed identical evidence/draft fixtures into Android and Web and require identical ClaimLock decisions before either implementation is considered release-ready.
