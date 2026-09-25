# Testing

## Backend end-to-end scenarios (AP-6.4)

`BridgeSimulatorEndToEndTest` drives the complete data flow backend → file bridge → real bridge simulator (Node) →
file bridge → backend, one scenario per core module: onboarding + `STARTING_CAPITAL_ADJUSTMENT`, credit application
→ approval → disbursement, price event (regional), auction + direct negotiation (farmland transfer + re-exported
market context), hiring + resignation escalation over 32 game days, village rotation.

- Requirements: Node.js ≥ 20 and `npm ci` in `tools/bridge-simulator` (otherwise the test is skipped).
- Measured runtime: **≈ 6.4 s** for all six scenarios (4-core container, 2026-09-25).
