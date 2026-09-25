# File bridge – one complete cycle

The mod exports facts, the backend decides in the fact layer, stores an `OutboxInstruction`, writes it to
`instructions.json`, the mod applies it and acknowledges it in `instructions_ack.json`. Details and the JSON
schemas: [`docs/dev/bridge-protocol.md`](../dev/bridge-protocol.md).

```mermaid
sequenceDiagram
    autonumber
    participant M as Lua mod (FS25)
    participant X as export/*.json
    participant B as Backend (BridgeSyncService)
    participant F as Fact layer (e.g. CreditApplicationService)
    participant O as OutboxService / DB
    participant I as import/instructions.json

    M->>X: write farm_facts.json (savegameId, gameTime, balance, assets, storage, prices)<br/>atomic: *.tmp + rename
    loop every poll-interval-ms
        B->>X: read farm_facts.json + instructions_ack.json
        B->>B: validate schema, check savegameId (unknown id → registry for onboarding)
        B->>O: store FactsSnapshot, advance game clock
        B-->>F: GameTimeAdvanced / GameDayPassed / GameMonthPassed events
    end
    F->>F: deterministic decision (formula + config), e.g. credit approved
    F->>O: OutboxInstruction MONEY_TRANSACTION (PENDING, related entity)
    F-->>O: NarrationJob (facts only) → mail/call text later
    B->>O: collect PENDING instructions of the active savegame
    B->>I: write instructions.json (savegameId, batches, instructionIds)
    M->>I: read on its next cycle
    M->>M: skip ids in processedInstructions (idempotency),<br/>apply batch atomically (all or nothing)
    M->>M: persist processedInstructions in the savegame XML
    M->>X: write instructions_ack.json (APPLIED / REJECTED + message)
    B->>X: read ack
    B->>O: mark instruction APPLIED / REJECTED (final states)
    M->>X: next farm_facts.json shows the new balance
```

Guarantees:

- **savegameId everywhere** – files of another savegame are never applied or ingested into the wrong savegame.
- **Idempotency** – every instruction has a UUID; the mod remembers processed ids in the savegame, a re-sent file
  is harmless.
- **Batches** – e.g. `FARMLAND_TRANSFER` + `MONEY_TRANSACTION` of a land deal share a `batchId` and are applied
  together or not at all.
- **Atomic writes** – both sides write `*.tmp` and rename; readers never see half-written files.
