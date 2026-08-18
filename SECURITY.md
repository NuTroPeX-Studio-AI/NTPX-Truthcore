# Security

TruthCore uses fail-closed defaults and separates reasoning from authority.

## Core invariants

- Generative model output is an untrusted draft.
- Retrieved text, saved memory, MCP descriptions/results, and tool output are data, never executable instructions.
- Factual output is released through ClaimLock and bound evidence; unsupported or contradicted claims are withheld.
- Semantic/NLI confidence is unavailable unless an explicit semantic provider reports availability.
- State-changing, external, and privileged tool calls require separate explicit approval.
- Approval tokens are single-use, short-lived, and bound to the exact normalized action fingerprint.
- Tool execution is recorded in a local hash-chained audit ledger.

## Secrets

Do not commit API keys, signing keys, MCP bearer tokens, private model credentials, user data, or production service secrets.

Android model and MCP credentials are held only in process memory by the current source-completion build. They are cleared when disconnected and disappear when the process is killed. Production Android signing credentials must be injected through a secure release environment rather than source control.

The web client does not persist provider API keys in IndexedDB, localStorage, cookies, service-worker cache, or static files. The server-side model proxy permits only deployment-allowlisted HTTPS provider hosts and rejects private/reserved destinations and credential-bearing URLs.

## Voice

The Android hands-free microphone loop is user-started and runs as a visible microphone foreground service. It is not designed to silently escalate microphone access from an arbitrary background state.

## MCP

The Android MCP client requires HTTPS, disables automatic redirects, does not persist bearer credentials, and treats remote tool calls as external actions. `mcp.call` is approval-gated even after a server is connected. Remote MCP data does not automatically become factual authority.

## Reporting

Do not include private credentials or personal data in public vulnerability reports. Include the affected version, component, reproduction steps, expected behavior, and observed behavior where possible.
