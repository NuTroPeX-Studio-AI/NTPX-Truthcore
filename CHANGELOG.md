# Changelog

## v1.1.0 — hardening + verification backends

### Security fixes
- **Web SSRF / DNS rebinding:** provider traffic now goes through a guarded HTTPS client whose socket-level DNS lookup rejects private, loopback, link-local, metadata, CGNAT, multicast, IPv4-mapped/NAT64/6to4 and other reserved addresses at connect time. Validation-then-fetch could previously be bypassed by a host that re-resolved to an internal address.
- **Web server crash:** rejecting a literal private-IP destination no longer emits an unhandled socket error.
- **Web rate limiting:** per-client token bucket on `/api/*` (health excluded), JSON-only API, explicit 404 for unknown API routes, request/header timeouts, graceful shutdown, opt-in HSTS, COOP/CORP headers.
- **Client evidence identity:** browser-supplied evidence can no longer claim the `independentKey` of built-in server evidence; labels are newline-stripped.
- **Android prompt injection:** retrieved evidence is now sanitized before it is placed in the model's evidence packet (the web client already did this); quarantined records are dropped.
- **MCP:** tool listings and call results are sanitized before display or use; response bodies are capped; bearer tokens are redacted from errors.
- **Response caps:** model/MCP responses are size-limited on both platforms.

### ClaimLock correctness (both platforms, now identical)
- Stopword-only claims (e.g. `It is. [S1]`) no longer pass with full support.
- Citations emitted after the period (`Claim. [S1]`) are re-attached instead of becoming an empty statement.
- Polarity and quantity contradictions are judged against the best-matching source sentence, so an unrelated "not" elsewhere in a document no longer flags a contradiction.
- Thousands separators are normalized (`12,000` = `12000`); percentages must match exactly.
- Abbreviations (`e.g.`, `i.e.`, `vs.` …) no longer split statements.
- The semantic gate is **strictly additive**: it can withhold or contradict a deterministically supported claim, but can never release one ClaimLock rejected. (Android previously let a semantic pass replace lexical support.)
- Replies now carry a per-claim audit (status, cited sources, reason, method) shown in both UIs.

### Real verification backends (previously contract-only)
- **NLI gate:** a chat model acting as an entailment judge (`{"entailment","contradiction"}` JSON verdicts, fail-closed on anything unparseable). Can use a separate judge model for independence. Optional "require semantic pass" mode.
- **Embedding ranking:** OpenAI-compatible `/embeddings` backend with LRU caching ranks retrieved evidence before it reaches the model. Ranking never affects release.
- Both are configurable from the Android **Verify** panel and the web settings dialog.

### Android
- `MainActivity` split into `ui/` (`TruthCoreViewModel`, `TruthCoreApp`, `Components`, `SettingsPanels`); conversation and settings survive rotation.
- One process-wide `TruthCoreRuntime` shared by the activity and the hands-free service: a single SQLite connection, shared pending approvals, and an unforked audit chain (previously each had its own).
- `TruthCoreRepository` interface with SQLite (`TruthCoreStore`) and in-memory implementations; schema v2 adds indexes, WAL, and approval cleanup. Memory search no longer ignores everything past the 200 most important rows.
- Audit appends and approval consumption run in synchronized write transactions.
- **Speakable approval codes:** six-digit, single-use, five-minute, action-bound codes replace UUIDs, so approvals work hands-free ("approve one two three four five six").
- Hands-free: saying just "Hey TruthCore" opens an 8-second follow-up window for the command.
- New commands: `forget <words>`, `forget knowledge <words>` (approval gated, all words must match), `deny`/`cancel`, `memory count`.
- `POST_NOTIFICATIONS` requested on Android 13+ for the hands-free notification; edge-to-edge layout; claim audit in message bubbles; selectable text; dark theme.
- MCP client falls back once to the legacy `initialize` handshake (with `Mcp-Session-Id`) for servers that predate the stateless 2026-07-28 protocol; SSE parsing takes the final JSON-RPC response after notifications; `isError` results surface as failures.
- Fixed `org.json` returning the literal string `"null"` for null message content.

### Web
- `server.mjs` split: `src/http.mjs` builds the server (testable), `src/netguard.mjs` guards outbound traffic.
- Browser audit append is now atomic (tip re-checked inside the write transaction), so concurrent tabs cannot fork the chain.
- `forget`, `deny`, `memory count`, six-digit approval codes, tool execution audited.
- Service worker is network-first for the app shell (deploys roll out immediately) and caches only successful responses.

### Quality
- Shared `conformance/claimlock-cases.json` run by both the Kotlin and JavaScript test suites; CI fails if the platforms disagree.
- Android unit tests: 29 → 61. Web tests: 17 → 65.
- `docs/STATUS.md`, architecture docs, and the legacy-python note brought up to date.
