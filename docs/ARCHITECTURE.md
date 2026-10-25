# Android Architecture

```text
MainActivity (permissions, foreground service, STT/TTS)       VoiceForegroundService
        │                                                     ("Hey TruthCore" loop)
        ▼                                                              │
ui/TruthCoreViewModel ── survives rotation                             │
        │                                                              │
        └────────────────────► TruthCoreRuntime.get(context) ◄─────────┘
                               (one per process)
                                      │
     ┌──────────────┬─────────────────┼──────────────────┬───────────────────┐
     ▼              ▼                 ▼                  ▼                   ▼
 command router  ConversationEngine  AgentExecutive  MultiAgentWorkforce  MCP status/list
                     │                    │
                     │               ToolRuntime ── approval codes (6-digit, single-use,
                     │                    │          5-min, bound to call fingerprint)
                     ▼                    ▼
              EvidenceRetriever     TruthCoreRepository ◄── TruthCoreStore (SQLite v2, WAL)
                     │               memory · knowledge · approvals · hash-chained audit
                     ▼
         sanitize → (optional) EvidenceRanker [embeddings]
                     │
                     ▼
         untrusted model draft (OpenAICompatibleProvider)
                     │
                     ▼
         ClaimLock (deterministic) ──► optional DeepVerificationGate [NLI judge]
                     │                  (additive: can only withhold)
           ┌─────────┴──────────┐
           ▼                    ▼
       VERIFIED             ABSTAINED  ── per-claim audit returned to the UI
```

## Invariants

- The model is never the factual authority. Evidence is data, not instruction.
- Retrieved evidence, tool output, and MCP results are sanitized; records with two or more injection signals are quarantined.
- Semantic verification and embeddings are optional and fail closed. NLI can only remove claims ClaimLock released; embeddings only reorder evidence.
- Every non-read-only tool call needs a fresh approval code bound to its exact arguments. Execution, failures, issuance, consumption, and cancellation are audit chained.
- Credentials (model key, MCP bearer) exist only in process memory (`ModelSession`, `VerificationSession`, `McpSession`).

## Packages

| Package | Responsibility |
|---|---|
| `ui` | Compose screens, ViewModel, settings panels |
| `core` | `TruthCoreRuntime` command router, version |
| `core.chat` | `ConversationEngine`, verification options |
| `core.truth` | `ClaimLock` |
| `core.semantic` | NLI/embedding contracts, `ModelJudgeNliProvider`, `OpenAICompatibleEmbeddingProvider`, `EvidenceRanker`, temporal graph, source auth |
| `core.agent` | `ToolRuntime`, `AgentExecutive`, `MultiAgentWorkforce` |
| `core.data` | `TruthCoreRepository`, `TruthCoreStore`, `InMemoryTruthCoreRepository` |
| `core.model` | HTTPS chat/embedding client, process sessions |
| `core.mcp` | Streamable HTTP MCP client (stateless + legacy fallback) |
| `voice` | Push-to-talk controller, hands-free foreground service |
