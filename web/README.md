# NTPX TruthCore Web

TruthCore Web is the browser/server sibling of the native Android app. v1.1.0 includes the public site at `/`, the installable PWA at `/app`, browser-local memory/knowledge/audit storage, dynamic RAG evidence, bounded agent tools, multi-agent review, ClaimLock, an optional NLI judge gate and embedding evidence ranking, browser voice controls when available, and the server-side HTTPS model proxy.

## Run locally

```bash
cd web
npm test
npm run check
TRUTHCORE_ALLOWED_PROVIDER_HOSTS=api.example.com npm start
```

Open `http://localhost:8787` for the website or `http://localhost:8787/app` for the application.

## Useful app commands

- `remember that <text>` → proposes a persistent browser-memory write
- `forget <words>` → proposes deleting memories containing every word
- `approve <code>` → executes one pending write (six-digit code, five-minute expiry)
- `deny` / `cancel` → drops all pending approvals
- `memory count`
- `what do you remember about <topic>`
- `search knowledge for <topic>`
- `add knowledge: <label> | <content>` → approval gated
- `agent: <goal>` → bounded registered-tool planning/execution
- `team: <goal>` → planner + critic + reviewer, labeled `TEAM_GENERATED`
- `list tools`
- `audit status`

## Model proxy security

Provider hosts must be listed in `TRUTHCORE_ALLOWED_PROVIDER_HOSTS`. The server rejects cleartext URLs, embedded credentials, localhost/local-network targets, common metadata targets, and destinations resolving to private/reserved addresses — checked again at connect time so DNS rebinding cannot bypass it. See `.env.example` for `TRUTHCORE_TRUST_PROXY` and `TRUTHCORE_HSTS`.

The browser keeps the provider API key only in the current tab's JavaScript runtime. It is not written to localStorage, cookies, IndexedDB, service-worker cache, or the Git repository. IndexedDB stores only user-approved memory/knowledge and the browser-local audit chain.

## Truth boundary

Creative/transformation output is labeled `GENERATED`; multi-agent review is labeled `TEAM_GENERATED`. Factual model drafts remain untrusted and pass through ClaimLock against a bounded evidence packet. Retrieved text is sanitized before model use, and unsupported/contradicted facts are withheld as `ABSTAINED`.

The NLI judge and embedding ranking are optional (Model settings → Verification backends). The judge can only withhold claims ClaimLock already supported; embeddings only reorder evidence. Both fail closed, and "Require semantic pass" withholds claims whenever the judge is unavailable.

## Deployment

`Dockerfile` packages the full Node/PWA runtime. Static hosting alone can display the public shell, but the connected model app needs the Node server (or a deliberately equivalent server-side adapter) so provider routing and host-policy enforcement stay off the public client.
