# TruthCore v1.0 completion contract

TruthCore is considered **source-complete** when Android and Web can run the core product behaviors under explicit trust boundaries: persistent memory, evidence retrieval, deterministic and optional semantic verification, model-backed conversation, bounded agent planning, permissioned tools, approval/audit controls, and repeatable CI/release packaging.

## v1.0.0-rc1 source status

| Capability | Android | Web |
|---|---|---|
| Conversational UI | complete | complete |
| Voice input/output | native | browser-capability dependent |
| User-started hands-free wake loop | complete | not applicable to background browser runtime |
| HTTPS model-provider adapter | complete | complete |
| Persistent memory | SQLite | IndexedDB |
| Local knowledge retrieval / RAG | complete | complete |
| ClaimLock deterministic gate | complete | complete |
| Semantic/NLI adapter contract | complete | complete |
| Embedding adapter contract | complete | complete |
| Temporal/contradiction primitives | complete | framework available on Android |
| Bounded agent planning | complete | complete |
| Approval-gated writes | complete | complete |
| Hash-chained audit | complete | complete |
| Multi-agent planner/critic/reviewer | complete | complete |
| MCP | HTTPS client + approval-gated calls | not part of the v1 browser trust boundary |
| Debug/release CI | complete | complete |
| Docker deployment package | n/a | complete |

## What source-complete does not mean

A repository cannot manufacture operator-owned production assets or validate hardware/services it cannot access. The following remain explicit deployment/runtime gates:

- production model and MCP credentials
- production website/web-app host, DNS, domain, and TLS
- live provider calls from the final production origins/devices
- Android production keystore / signing secrets
- signed release artifacts and store review
- real-device hands-free behavior under vendor battery/lifecycle policies
- optional downloadable on-device generative/embedding/NLI model assets and their device-specific performance validation

Those items must remain uncompleted until they are actually supplied and verified. They are not treated as missing source-code placeholders.
