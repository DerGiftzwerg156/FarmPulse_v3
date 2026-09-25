# Acceptance checklist (AP-11.3)

Final check of section 14 of the work plan (`docs/concept/Arbeitspakete.md`) against the functional concept
(`Fachliches_Konzept_V3.md`) and the technical concept (`Technisches_Konzept_V6.md`). Status of 2026-09-25,
version 1.0.0.

- ✅ = implemented; evidence = code, test and/or documentation.
- ⛔ **bewusst nicht umgesetzt (V1-Scope)** = conscious decision of the concept, documented instead of implemented.

Path abbreviations: `B/` = `backend/src/main/java/de/farmpulse/rpsim/`, `BT/` = `backend/src/test/java/de/farmpulse/rpsim/`,
`R/` = `backend/src/main/resources/`, `F/` = `frontend/src/app/`, `M/` = `mod/FS25_RPSim/src/`, `MT/` = `mod/tests/`.

| # | Feature (concept) | Status | Evidence (code · tests · docs) |
| --- | --- | --- | --- |
| 1 | Two-tier model (facts deterministic / personality AI) | ✅ | All decisions in `B/credit`, `B/market`, `B/negotiation`, `B/employee`, `B/village`, `B/trust`; the AI is only called by `B/narration/AiNarrationService`; `NarrationFacts` blocks formula internals · `BT/narration/PromptBuilderTest.formulaInternalsCanNeverEnterThePrompt` · `docs/architecture/two-tier-principle.md` |
| 2 | Mails (asynchronous) | ✅ | `B/api/MailController`, `B/communication/ConversationService` (reply via narration job in game time) · `F/features/mailbox` · `BT/api/ApiIntegrationTest.mailsListDetailReply`, E2E "answer a mail" |
| 3 | Calls (accept / decline / soft time window) | ✅ | `B/communication/CallService`, `B/api/CallController` · `F/features/calls/call-overlay.ts`, `calls.ts` (`SOFT_WINDOW_SECONDS`, UI only) · `BT/communication/CallStateMachineTest`, `F/features/calls/*.spec.ts`, E2E "accept and decline incoming calls" |
| 4 | Onboarding: backstory blocks + free text | ✅ | `B/onboarding/OnboardingService` (`FarmOrigin`, `VillageRelation`, capital, legacy loan, tone) · `F/features/onboarding` step 1 · `BT/onboarding/OnboardingFlowIntegrationTest.completeOnboardingFlow` |
| 5 | Onboarding: initial staff | ✅ | `OnboardingRequest.initialEmployees` → generated employees · wizard step 2 · `onboarding-wizard.spec.ts` "step 2" |
| 6 | Onboarding: start package + staggered story hooks | ✅ | `B/character/CharacterGeneratorService`, `B/onboarding/StoryHookCatalog`, `StoryHookScheduler` (`onboarding.story-hook-spread-days-*`) · `OnboardingFlowIntegrationTest` |
| 7 | Onboarding: preview with reroll (single / all) | ✅ | `POST /api/onboarding/{id}/reroll` · wizard step 3 · `onboarding-wizard.spec.ts` "step 3", E2E onboarding |
| 8 | Onboarding: confirm & link with the savegame | ✅ | `DetectedSavegameRegistry`, `POST /api/onboarding/{id}/confirm`, `GET /api/onboarding/unlinked-savegames` · wizard steps 4–5 · `BT/bridge/BridgeSyncIntegrationTest.unlinkedSavegameIsRegisteredForOnboarding`, E2E onboarding |
| 9 | Free text never affects numbers, light moderation, empty field = default | ✅ | `B/ai/LexiconFreeTextModerator` (`R/moderation/de.json`), free text only goes into generation prompts · `BT/ai/FreeTextModerationTest`, `OnboardingFlowIntegrationTest.emptyFreeTextStillProducesStandardCastAndProfilesAreNotTemplates` · UI hint in step 1 |
| 10 | Backstory profiles not stored as templates | ✅ | No template entity/endpoint; every onboarding creates a fresh draft · `OnboardingFlowIntegrationTest.emptyFreeTextStillProducesStandardCastAndProfilesAreNotTemplates` |
| 11 | Mandatory roles (never vacant) + absence (delay / substitute) | ✅ | `B/village/MandatoryRoleAbsenceService`, `VillageRotationService.replaceMandatory`, absence note in `NarrationRequestService` · `BT/village/VillageSystemTest` (absence tests) |
| 12 | Dynamic characters: rotation, max. 1–2 per year | ✅ | `B/village/VillageRotationService` (`rotation.max-per-year: 2`) · `VillageSystemTest.rotationBudgetIsNeverExceededAndCountsArrivalsAndDeparturesTogether` |
| 13 | Role change: fact file stays, relationship restarts | ✅ | Loans/credit history belong to the savegame, not the person; successor starts with base trust and empty memory · `VillageSystemTest.roleChangeKeepsFactFileButRestartsRelationship` |
| 14 | Character data model (fact + personality layer) | ✅ | `B/domain/Character` (role, status, trust, hidden wealth / name, traits, speech style, backstory, relationships), Flyway `R/db/migration` · `docs/architecture/domain-model.md` |
| 15 | Trust: capped, event-driven, used in credit & negotiation | ✅ | `B/trust/TrustScoreService` (event log, caps, decay), `CreditFormula.trustBonus`, `NegotiationFormula.trustAdjustment` · `BT/trust/TrustScoreServiceTest`, `CreditFormulaTest`, `NegotiationFormulaTest` |
| 16 | Memory over time (condensed short facts) | ✅ | `B/narration/MemoryService` (prompt block "memory", `memory.max-facts`) · `BT/narration/MemoryServiceTest` |
| 17 | Credit check: complete data basis incl. silo stock | ✅ | `B/credit/CreditScoringService.inputs` (assets incl. `FactsService.storageValue`, liquidity, cash-flow window, debts incl. vanilla loan, payment history, trust) · `BT/credit/CreditScoringServiceTest`, `BT/bridge/StorageValuationTest` |
| 18 | Three result levels | ✅ | `CreditFormula.decide` (APPROVED / COUNTER_OFFER / REJECTED, coarse reason category only) · `CreditFormulaTest.defaultThresholdsAreExact` · `F/features/bank` |
| 19 | Application flow + artificial processing time | ✅ | `B/credit/CreditApplicationService` (`processing-days-min/max`, decision visible later) · `CreditApplicationServiceTest.decisionIsComputedImmediatelyButOnlyVisibleAfterProcessingTime`, E2E credit flow |
| 20 | Default escalation complete (reminder → penalty → trust → callback / block) | ✅ | `B/credit/LoanService` · `BT/credit/LoanLifecycleTest.completeEscalationChainStepByStepEndsWithCallback`, `finalStageCanBlockInsteadOfCallBack`, `escalationStagesSwitchExactlyAtTheConfiguredDays` |
| 21 | Deferral (Stundung) | ✅ | `LoanService` deferral check, `POST /api/loans/{id}/stundung` · bank page form · `LoanLifecycleTest.deferralGrantedOnceThenDenied`, `bank.spec.ts` |
| 22 | Collateral / pledges | ⛔ **bewusst nicht umgesetzt (V1-Scope)** | Functional concept, bank chapter: „Bewusst nicht in Version 1: Sicherheiten/Pfand als Voraussetzung für große Kredite – als spätere Erweiterung vorgemerkt.“ Large requests are limited by `loanToFarmSize` instead. |
| 23 | Event engine: types, regionality, character binding, timing / rumours | ✅ | `B/market/MarketEventEngine` (bands, advance notice, `rumor-accurate-probability`, one sell point per event, announcing character) · `BT/market/MarketEventEngineTest` (`priceEventIsRegionalOneSellPointOnly`, `rumorsAreAboutSeventyPercentAccurate`, `plannedEventWithAdvanceNoticeActivatesAtStart`, …) |
| 24 | Player reaction to events (special contracts) | ✅ | `POST /api/market-events/{id}/participation` → `PRICE_EVENT` FIXED · market page "Kontrakt annehmen" · `MarketEventEngineTest.specialOfferNeedsParticipationDecisionThenBecomesFixedContract`, `market.spec.ts` |
| 25 | Silo stock export (classic silos only) | ✅ | `M/export/Storage.lua` (`isClassicSilo`), `TODO(offene-frage)` #8 · `MT/test_storage.lua` |
| 26 | Current sell prices export | ✅ | `M/game/GameAdapter.lua` `collectFarmFacts` → `prices[]` · `MT/test_farm_facts.lua` |
| 27 | Market overview + price history chart | ✅ | `GET /api/storage`, `/api/prices/current`, `/api/prices/history` · `F/features/market`, `F/shared/ui/chart-wrapper.ts` · `market.spec.ts`, E2E "price history chart loads data", `docs/screenshots/15-warenbestand-preise.png` |
| 28 | Stock affects credit (storageValue) | ✅ | `FactsService.storageValue` in `totalAssetValue` · `StorageValuationTest.storageCountsIntoTheAssetSumLikeMachines` |
| 29 | Stock affects event target selection | ✅ | `MarketEventEngine` (`target-base-weight + target-stock-weight × share`) · `MarketEventEngineTest.fillTypesInStockAreChosenStatisticallyMoreOften` |
| 30 | Staff: job market, applicant pool, interview | ✅ | `B/employee/HiringService` · `F/features/employees` · `BT/employee/EmployeeSystemTest.postingGeneratesThreeToFiveCandidatesWithApplications`, `interviewKeepsSkillAndSalaryFixed`, E2E calls/hiring |
| 31 | Role-specific skills (pure tool values) | ✅ | `Employee.skill`, `JobRole`; no FS parameter is touched (architecture decision) · `EmployeeSystemTest.salaryFollowsSkillDeterministically` |
| 32 | Salary, monthly deduction | ✅ | `B/payroll/PayrollScheduler` (`MONEY_TRANSACTION` per game month) · `EmployeeSystemTest` |
| 33 | Four need categories | ✅ | `SatisfactionService.needs` (pay fairness, workload, appreciation, working conditions live from vehicle condition) · `SatisfactionFormulaTest` · category bars on the staff page |
| 34 | Satisfaction → skill malus / bonus (deterministic) | ✅ | `SatisfactionFormula.effectMultiplier`, `effectiveSkill`, monthly `EMPLOYEE_EFFECT` · `SatisfactionFormulaTest.effectiveSkillIsToolInternalMalus`, `EmployeeSystemTest.monthlyEffectInstructionReflectsSatisfaction` |
| 35 | Resignation escalation with warning | ✅ | `SatisfactionService.checkEscalation` (≥ 14 days warning, ≥ 30 days resignation) · `EmployeeSystemTest.resignationEscalationWarnsAt14AndResignsAt30Days`, `warningThresholdIsStrict` |
| 36 | Salary overdue → satisfaction | ✅ | `PayrollScheduler` → negative `payFairness` event · `EmployeeSystemTest.salaryOverdueTriggersNegativePayFairnessEvent` |
| 37 | Auction (system-initiated) | ✅ | `NegotiationEngine.startAuction` + daily spawn · `NegotiationEngineTest.auctionWonWithBidAboveAllNpcLimits`, `auctionLostAfterThreeLowBidsAndNpcBecomesOwner` · farmland page |
| 38 | Direct negotiation (player-initiated) | ✅ | `POST /api/negotiations/direct` · farmland page + character detail (`F/features/village`) · `NegotiationEngineTest.directNegotiationOnlyWithOwner`, `village.spec.ts`, E2E negotiation |
| 39 | Form bid, formula check, max. 3 rounds | ✅ | `POST /api/negotiations/{id}/offer` (typed `OfferRequest`) · `NegotiationEngineTest.directNegotiationEndsAfterThreeRounds` · `farmland.spec.ts` |
| 40 | Price finding incl. trust cap | ✅ | `NegotiationFormula` (`minAccept`, `trustAdjustment ±5 %`, counter band 0.9) · `NegotiationFormulaTest.purchaseThresholdsAreExact`, `trustNeverDistortsBasePriceBeyondCap` |
| 41 | NPC bids in auctions | ✅ | `NegotiationFormula.npcMaxBid` (0.9–1.15, seeded, no AI) · `NegotiationFormulaTest.npcMaxBidIsDeterministicAndWithinBand` |
| 42 | Selling own fields | ✅ | `POST /api/farmlands/{id}/sell-offer`, interest check on hidden wealth · `NegotiationEngineTest.saleOfOwnFieldWithInterestedBuyer`, `saleWithoutInterestedBuyers` · farmland page |
| 43 | One field, one negotiation | ✅ | `NegotiationEngine.requireFree` (`FIELD_IN_NEGOTIATION`) · `NegotiationEngineTest.oneFieldOneNegotiation` |
| 44 | Ownership reconciliation against vanilla purchases | ✅ | `B/negotiation/FarmlandOwnershipService.reconcile` on every export; mod exports ownership (`M/export/MarketContext.lua`) · `NegotiationEngineTest.vanillaPurchaseAndSaleAreFollowedUpSilently`, `pendingTransferIsNotReconciledAway` |
| 45 | Leasing | ⛔ **bewusst nicht umgesetzt (V1-Scope)** | Functional concept V2 note and negotiation chapter: purchase/sale of farmland „bewusst ohne Verpachtung“. |
| 46 | Generic negotiation engine (extensibility) | ✅ | `Negotiation.assetType` / `assetId` with `AssetType` (V1: `FARMLAND`), kind/direction enums, `offer` dispatch in `NegotiationEngine.placeOffer` |
| 47 | Free replies + tone classifier | ✅ | `B/tone/ToneClassifier` (`R/tone-lexicon/de.json`), `ToneTrustService` (capped) · `BT/tone/ToneClassifierTest` |
| 48 | Mechanical free-text wishes → polite redirection | ✅ | `PromptBuilder` system rules ("lenke freundlich auf den offiziellen Prozess zurück") + task hints · `PromptBuilderTest.mechanicalRequestAddsRedirectHint`; numbers from text are never parsed (`ApiIntegrationTest.creditFormRejectsNumbersAsText`) |
| 49 | Village reputation (tier only) | ✅ | `B/village/VillageReputationService`, `GET /api/village-reputation` returns tier + label · village page, dashboard · `VillageReputationFormulaTest`, `village.spec.ts` |
| 50 | Proactive message + pacing limit | ✅ | `POST /api/characters/{id}/messages`, `ConversationService.proactive` (`messages.pacing-cooldown-days`) · pacing hint in the village page · `village.spec.ts` |
| 51 | Diary (automatic + free notes) | ✅ | `B/diary/DiaryService` (backstory as first entry, automatic entries), `POST /api/diary/entries` · `F/features/diary` (marked as purely narrative) · `diary.spec.ts` |
| 52 | Village life: congratulations / invitations / gossip | ✅ | `B/village/VillageLifeService` · `BT/village/VillageLifeServiceTest` · badge "Dorfleben" in the mailbox |
| 53 | Tone / genre guard rails (onboarding + bank profile) | ✅ | `TonePreset` in onboarding, prompt block, `CreditConfigResolver` (HART profile) · `CreditApplicationServiceTest.hardToneUsesStricterBank`, `ToneClassifierTest.harshToneSwitchesOnlyTheBankProfile` · read-only on the settings page |
| 54 | `farm_facts.json` complete | ✅ | `M/export/FarmFacts.lua`, schema `tools/bridge-simulator/schema/farm_facts.schema.json` · `MT/test_farm_facts.lua`, `docs/dev/bridge-protocol.md` |
| 55 | `market_context.json` complete | ✅ | `M/export/MarketContext.lua` · `MT/test_market_context.lua` |
| 56 | Import envelope, three instruction types | ✅ | `M/import/Instructions.lua`, `PriceEvents.lua`, `Processor.lua` · `MT/test_instructions.lua`, `test_price_events.lua`, `test_farmland_transfer.lua` · `B/bridge/OutboxService` |
| 57 | Ack & idempotency | ✅ | `processedInstructions` persisted in the savegame (`M/import/Persistence.lua`), `instructions_ack.json` · `MT/test_instructions.lua`, `test_persistence.lua`, `BridgeSyncIntegrationTest.outboxIsWrittenWithEnvelopeAndAckUpdatesStatus` |
| 58 | `savegameId` protection | ✅ | mod guard + backend `SavegameContext` / ack check · `MT/test_savegame_guard.lua`, `BridgeSyncIntegrationTest.ackOfForeignSavegameIsIgnored` |
| 59 | Security principle verified by tests (trust cap < threshold gap) | ✅ | `CreditFormulaTest.trustCapIsSmallerThanThresholdGap` (default + HART, full sweep), `NegotiationFormulaTest.trustNeverDistortsBasePriceBeyondCap` · `docs/dev/testing.md` |
| 60 | Four-block prompt + `<spieler_nachricht>` injection protection | ✅ | `B/narration/PromptBuilder` · `PromptBuilderTest.containsTheFourBuildingBlocks`, `everyFreeTextChannelIsWrappedInTheTag`, `injectionAttemptStaysInsideTheTag` |
| 61 | `NarrationJob` decoupling + fallback texts | ✅ | `NarrationRequestService`, `NarrationWorker`, `FallbackTemplates` (`R/fallback-templates/de`) · `NarrationPipelineTest.providerFailureFallsBackForEveryEventType`, `noProviderConfiguredUsesFallbackWithoutError` |
| 62 | Four `AiProvider` implementations | ✅ | `B/ai/OpenAiProvider`, `AnthropicProvider`, `GeminiProvider`, `OllamaProvider` (+ `NoAiProvider`, `FakeAiProvider`) · `BT/ai/*ProviderTest`, `AiProviderRegistryTest` · `docs/dev/ai-providers.md` |
| 63 | All REST endpoints of the concept table | ✅ | Every endpoint of „Angular-REST-Endpunkte“ exists in `B/api/*Controller` (plus counter-offer, market events, direct negotiation, settings, savegame header) · `ApiIntegrationTest` (one test per module), OpenAPI via springdoc |
| 64 | SSE live updates | ✅ | `B/sse/SseHub`, `EventStreamController` (`/api/events/stream`: hello, mail, call, diary, state, ping) · `F/core/live/live-events.service.ts` (reconnect) · `SseStreamIntegrationTest`, `live-events.service.spec.ts` |
| 65 | Call state machine incl. game-time timeout | ✅ | `CallService` (`calls.ring-timeout-game-minutes`) · `CallStateMachineTest.missedAfterRingTimeoutInGameTime`, `pausedGameKeepsItRinging` · `docs/architecture/call-state-machine.md` |
| 66 | Formula values as configuration, not code constants | ✅ | `B/config/RpsimProperties` ↔ `R/application.yml` (`RpsimPropertiesDefaultsTest`), complete reference `docs/dev/configuration-reference.md` (`ConfigurationReferenceDocTest`) |
| 67 | i18n preparation (only `de` filled) | ✅ | Frontend: `F/core/i18n` (`TranslationService`, `de.json`, no hard-coded texts, `I18nTitleStrategy`); backend: `rpsim.ai.locale`, `R/fallback-templates/de`, `R/prompt-tasks/de.properties`, `R/character-pools/de.json`, `R/moderation/de.json`, `R/tone-lexicon/de.json` |
| 68 | Multiplayer farms | ⛔ **bewusst nicht umgesetzt (V1-Scope)** | Functional concept (single player); `modDesc.xml` `<multiplayer supported="false"/>`, mod uses the single farm id · `mod/README.md` |
| 69 | Design based on `Dashboard.html` | ✅ | `frontend/tailwind.config.js` (tokens from the reference), `frontend/src/styles.css` (`fp-*` utilities), shell with icon rail · `docs/design-reference/Dashboard.html`, `docs/screenshots/*.png` |
| 70 | Complete user and developer documentation with images | ✅ | `docs/user-guide/*` (German, screenshots), `docs/dev/*`, `docs/architecture/*` (Mermaid), `README.md`, `tools/screenshot-generator` |
| 71 | Regular commit/push throughout | ✅ | One conventional commit per work package (`… [AP-x.y]`), each pushed after green checks; 71 commits on the working branch |

## Summary

68 of 71 items implemented, 3 items consciously not implemented as decided by the concept (#22 collateral, #45
leasing, #68 multiplayer). Remaining assumptions that can only be verified in the real game are listed in
[`offene-technische-punkte.md`](offene-technische-punkte.md); open product questions in [`QUESTIONS.md`](../../QUESTIONS.md).
