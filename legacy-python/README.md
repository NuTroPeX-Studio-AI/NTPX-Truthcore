# Legacy Python reference

TruthCore v0.1–v0.4 was prototyped in Python/FastAPI. That prototype is **not included in this repository**; this folder only records its role.

Every invariant from the prototype has since been ported to native Kotlin (Android) and JavaScript (Web), and both ports are now held to the same behavior by the shared ClaimLock conformance fixtures in [`conformance/claimlock-cases.json`](../conformance/claimlock-cases.json). Treat the Android and Web sources as canonical; there is no remaining behavior that depends on the Python code.
