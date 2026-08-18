# Roadmap

## Source milestones

- [x] v0.1 Core conversational runtime (reference implementation)
- [x] v0.2 ClaimLock (reference implementation)
- [x] v0.3 Agentic runtime (reference implementation)
- [x] v0.4 Evidence Intelligence + Cognitive Voice (reference implementation)
- [x] v0.5 Android-first native foundation
- [x] v0.5.1 Android conversation + voice hardening
- [x] v0.5.2 Configurable HTTPS model runtime
- [x] v0.5.3 Web platform foundation
- [x] v0.6 Deep Semantic Verification framework
  - [x] fail-closed NLI/semantic provider contract
  - [x] deep entailment/contradiction gate
  - [x] semantic gate integrated into Android ClaimLock
  - [x] embedding index/provider contract
  - [x] source authentication
  - [x] temporal knowledge graph
  - [x] contradiction resolution
  - [x] Web semantic/embedding adapter contract
- [x] v0.7 Cognitive Executive
  - [x] bounded planner
  - [x] registered-tool allowlist
  - [x] tool-call budget
  - [x] approval-gated state changes
  - [x] single-use action-bound approval tokens
  - [x] hash-chained audit ledger
  - [x] persistent memory + local knowledge RAG
- [x] v0.8 Multi-Agent Cognitive Workforce
  - [x] planner specialist
  - [x] critic specialist
  - [x] reviewer specialist
  - [x] stage-output sanitization
  - [x] advisory/generated status separated from factual verification
- [x] v0.9 Advanced Android services + interoperability
  - [x] user-started microphone foreground service
  - [x] `Hey TruthCore` wake/listen/respond loop
  - [x] process-memory model session for hands-free mode
  - [x] HTTPS MCP 2026-07-28 client
  - [x] MCP tool discovery
  - [x] approval-gated MCP tool calls
  - [x] process-memory MCP credentials
- [x] v1.0.0-rc1 source-completion candidate
  - [x] Android CI debug APK
  - [x] Web CI + Docker validation
  - [x] release-candidate workflow for unsigned Android APK/AAB + web deployment bundle
  - [x] Android and Web version alignment
  - [x] explicit deployment/security boundary documentation

## External deployment / runtime validation gates

These are intentionally **not** marked complete by source code alone:

- [ ] live Android model-provider validation with operator-supplied endpoint/model/credentials
- [ ] live Android MCP-server validation with operator-supplied endpoint/credentials
- [ ] on-device hands-free behavioral validation across real Android lifecycle/power states
- [ ] production website/web-app hosting
- [ ] custom domain + production HTTPS/DNS
- [ ] live web-provider validation on the production HTTPS origin
- [ ] optional on-device generative model adapter + model asset selected and installed
- [ ] optional on-device embedding/NLI model adapter + model assets selected and installed
- [ ] production Android keystore / signing secrets configured outside Git
- [ ] signed production AAB/APK generated
- [ ] Play Store / distribution review and release

## Post-v1 product evolution

These are future product expansions rather than blockers to the v1 source-completion candidate:

- cross-device encrypted sync/accounts
- broader Android intent/device-action tool catalog
- additional MCP transports/adapters when justified
- richer evidence connectors and authenticated source providers
- model benchmarking and device-specific local-inference profiles
- multi-device / desktop clients
