# Call state machine

Incoming calls (`Communication` with channel `CALL`, initiated by a character) follow this state machine
(`CallService`). The ring timeout runs in **game time** – if the game is paused, the phone keeps ringing.

```mermaid
stateDiagram-v2
    [*] --> RINGING: character calls<br/>(NarrationJob with channel CALL)
    RINGING --> ACCEPTED: player accepts<br/>POST /api/calls/{id}/accept
    RINGING --> DECLINED: player declines<br/>POST /api/calls/{id}/decline
    RINGING --> MISSED: ring timeout reached<br/>(ring-timeout-game-minutes, game time)
    ACCEPTED --> ACCEPTED: player speaks / character answers<br/>POST /api/calls/{id}/message
    ACCEPTED --> COMPLETED: player hangs up<br/>POST /api/calls/{id}/complete
    DECLINED --> [*]
    MISSED --> [*]
    COMPLETED --> [*]

    note right of ACCEPTED
        "Soft time window" is UI only:
        a countdown in the frontend,
        no backend timeout, no penalty
    end note
    note right of DECLINED
        Trust malus (call-declined),
        topic stays open (openTopic)
        until the player gets in touch
    end note
    note right of MISSED
        Trust malus (call-missed),
        marked unread, topic stays open
    end note
```

Every transition is published as `CallStatusChanged` and pushed to the browser as SSE event `call`; the frontend
shows the incoming-call overlay for `RINGING` and the conversation view for `ACCEPTED`.
