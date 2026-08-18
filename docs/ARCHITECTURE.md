# Android Architecture

```text
Compose UI / Voice
        |
        v
Cognitive Controller
        |
        +--> Memory Engine
        +--> Evidence Retrieval
        +--> Tool Policy (next milestone)
        |
        v
Untrusted model draft
        |
        v
ClaimLock
        |
        +--> supported -> release
        +--> unsupported/contradicted -> withhold
```

The model is never the factual authority. Evidence is data, not executable instruction.
