# Two-tier principle (fact layer vs. personality layer)

The central security concept: **the AI never produces numbers.** Everything that moves game money, prices,
skills or trust is decided by deterministic services with configurable formulas (`rpsim.formulas.*`). The AI only
receives the finished result and phrases it in the voice of a character.

```mermaid
flowchart TB
    subgraph INPUT["Player input"]
        FORM["Forms (amount, term, bid, salary)<br/>typed numbers + Bean Validation"]
        TEXT["Free text (mail, call, backstory)"]
    end

    subgraph FACT["Fact layer (pure Java, no AI)"]
        SVC["Services: CreditScoringService · LoanService · NegotiationEngine<br/>MarketEventEngine · SatisfactionService · VillageReputationService"]
        TONE["ToneClassifier (lexicon)<br/>→ small, capped trust delta"]
        OUT["OutboxInstruction<br/>(money, prices, farmland)"]
    end

    subgraph NARR["Personality layer"]
        JOB["NarrationJob<br/>eventType + NarrationFacts (whitelisted keys)"]
        AIS["AiNarrationService<br/>(the only class that calls an AI)"]
        PB["PromptBuilder: 4 blocks<br/>character · memory · facts · task<br/>player text wrapped in &lt;spieler_nachricht&gt;"]
        PROV["AI provider<br/>OpenAI · Anthropic · Gemini · Ollama"]
        FB["Fallback templates<br/>fallback-templates/de/*.txt"]
    end

    COMM["Communication (mail / call)<br/>shown in the mailbox"]

    FORM --> SVC
    TEXT --> TONE
    TONE --> SVC
    TEXT -. "only as quoted context" .-> PB
    SVC --> OUT
    SVC --> JOB
    JOB --> AIS
    AIS --> PB --> PROV
    PROV -- "subject + body (text only)" --> AIS
    AIS -- "provider missing / error / invalid output" --> FB
    AIS --> COMM
    FB --> COMM

    classDef guard fill:#1c2621,stroke:#38B000,color:#E6EEE9;
    class SVC,OUT,TONE guard;
```

Guard rails (each is covered by tests):

| Rule | Where |
| --- | --- |
| Forbidden fact keys (score, minAccept, maxBid, trust, weight, threshold, hiddenMax, virtualWealth) can never reach a prompt | `NarrationFacts` |
| Player text is data, not instructions (`<spieler_nachricht>` wrapper, system prompt says so) | `PromptBuilder` |
| AI output is parsed as text only; numbers in it never flow back into the fact layer | `AiOutputParser`, `AiNarrationService` |
| Numbers come exclusively from forms (typed request DTOs), never parsed from free text | `api/Requests` |
| Trust from tone is small and capped per message (`rpsim.formulas.tone.cap`); proactive messages have a pacing cool-down | `ToneTrustService`, `ConversationService` |
| Trust influence on credit (±8) is smaller than the gap between decision thresholds (30) | `CreditFormulaTest.trustCapIsSmallerThanThresholdGap` |
| Trust influence on land prices is capped at ±5 % | `NegotiationFormulaTest` |
| Without an AI provider (`NONE`) the game still works with templates | `FallbackTemplates` |
