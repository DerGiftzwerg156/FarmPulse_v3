# Configuration reference

Every tunable value of the backend (`rpsim.*`). Source of truth: `backend/src/main/resources/application.yml`;
the Java defaults in `RpsimProperties` mirror it (`RpsimPropertiesDefaultsTest`) and this page lists every key
(`ConfigurationReferenceDocTest` fails when a key is missing here).

All formula values are **placeholders from the technical concept** (chapter „Formeln der Fakten-Ebene“) - balancing
happens in playtesting. Override any value without rebuilding in `application-local.yml` next to the backend
(git-ignored) or on the command line, e.g. `--rpsim.formulas.credit.approve-threshold=80`. The column *Concept* names
the section of `docs/concept/Technisches_Konzept_V6.md` (or the functional concept) the value belongs to.

## `rpsim.bridge` – File bridge

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.bridge.path` | `../tools/bridge-simulator/runtime/modSettings/FS25_RPSim` | Folder `modSettings/FS25_RPSim` (contains `export/` and `import/`). `dev`: simulator runtime folder; `prod`: `~/Documents/My Games/FarmingSimulator2025/modSettings/FS25_RPSim`. | Datei-Bridge |
| `rpsim.bridge.poll-interval-ms` | `2000` | Real-time interval (ms) in which the backend reads the bridge files. The only real-time timer - all game logic runs on game time. | Datei-Bridge |
| `rpsim.bridge.enabled` | `true` | Runs the bridge scheduler; `false` in unit tests. | Datei-Bridge |
| `rpsim.bridge.rewind-auto-resend-max-hours` | `24` | Savegame reloaded without saving (game time jumps back): bookings lost by a rewind up to this many game hours are re-sent automatically; deeper rewinds show a decision card on the dashboard ("nachbuchen" / "Tool-Stand beibehalten"). | TODO T-02 |
| `rpsim.bridge.rewind-lookback-hours` | `24` | Bookings acknowledged up to this many game hours before the reloaded point are checked as well (the first export after loading happens slightly after the saved point). Must stay below the mod's `processedRetentionGameDays`. | TODO T-02 |
| `rpsim.bridge.ingame-notifications` | `true` | New mails and incoming calls are shown in the game (`NOTIFICATION` instruction → `addIngameNotification`). Only for savegames linked to FS25. | TODO T-21 |
| `rpsim.bridge.notification-max-age-hours` | `2` | The mod acknowledges a notification without showing it (`message: EXPIRED`) when it is processed more than this many game hours after it was created, e.g. after loading an older savegame. | TODO T-21 |
| `rpsim.bridge.ingame-prompts` | `true` | Roadmap V2 R2-F2: open decisions of the occasions switched on per savegame are asked in the game as a yes/no question (`PROMPT` instruction); off = every open question is withdrawn. | Roadmap V2 R2-F2 |
| `rpsim.bridge.prompt-default-kinds` | `[CALL]` | Occasions asked in the game until the player chooses on the settings page: `CALL`, `CONTRACT_OFFER` (lease, maintenance, insurance offers and the lease renewal), `WILDLIFE_OFFER`, `CREDIT_COUNTER`, `INVITATION`, `COMPENSATION_CLAIM`, `TAX_BILL`, `TAX_ADVISOR`. | Roadmap V2 R2-F2 |
| `rpsim.bridge.prompt-max-age-hours` | `48` | A question without its own deadline (the counter offer of the bank; a tax bill past its deadline) expires after this many game hours; the others expire with the deadline of their decision (ring timeout, offer validity, end of the lease). | Roadmap V2 R2-F2 |

## `rpsim.web` – Web

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.web.static-dir` | `""` | Folder of the built Angular app. When set (release: `web/`) the backend serves it on `/` with an SPA fallback. Empty = API only. | – |
| `rpsim.web.lan.pin-min-length` | `4` | Shortest PIN for devices in the home network (digits only). | Roadmap V3 R3-N2 |
| `rpsim.web.lan.pin-max-length` | `8` | Longest PIN (digits only). | Roadmap V3 R3-N2 |
| `rpsim.web.lan.max-failed-attempts` | `5` | Wrong PINs of one sender address before it is locked. | Roadmap V3 R3-N2 |
| `rpsim.web.lan.lockout-minutes` | `5` | Lock of a sender address after too many wrong PINs (real minutes). | Roadmap V3 R3-N2 |
| `rpsim.web.lan.session-days` | `30` | Validity of the session cookie of a device (real days). Only the SHA-256 of the cookie value is stored, so a session survives a restart of the backend; a new PIN, removing the PIN or switching the home-network access off ends every session. | Roadmap V3 R3-N2 |
| `rpsim.web.lan.pbkdf2-iterations` | `600000` | Iterations of the PIN hash (`PBKDF2WithHmacSHA256` from the JDK, OWASP Password Storage Cheat Sheet). A PIN keeps the count it was hashed with. | Roadmap V3 R3-N2 |
| `rpsim.web.lan.cookie-name` | `FP_LAN_SESSION` | Name of the session cookie (`HttpOnly`, `SameSite=Strict`, sent by `EventSource` too). | Roadmap V3 R3-N2 |

## Game month = FS25 period

There is no `rpsim.time` configuration any more (TODO T-08): the game month is the FS25 period of the savegame.
The mod exports the calendar (`farm_facts.json` → `calendar`: period, day in period, days per period, year); the
backend counts months from it. Installments and salaries are due at the start of each period; if the player changes
"days per period" in FS25, scheduled dates keep their month. Without a calendar export (older mod) one day per
period is assumed (FS25 default).

## `rpsim.ai` – AI providers

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.ai.provider` | `NONE` | Default AI provider when the settings page stored nothing: `FAKE` (tests) · `OPENAI` · `ANTHROPIC` · `GEMINI` · `OLLAMA` · `NONE` (templates). | KI-Adapter |
| `rpsim.ai.timeout-seconds` | `30` | HTTP timeout of one AI call. | Entkopplung & Resilienz |
| `rpsim.ai.max-attempts` | `2` | Attempts per narration job before the fallback template is used. | Entkopplung & Resilienz |
| `rpsim.ai.local-config-file` | `./data/local-config/ai-provider.properties` | Git-ignored file where the settings page stores provider, model and API key. | KI-Adapter |
| `rpsim.ai.locale` | `de` | Locale of prompts and fallback templates (V1: `de` only). | KI-Adapter |
| `rpsim.ai.worker-interval-ms` | `1500` | Real-time polling interval of the narration worker. | Entkopplung & Resilienz |
| `rpsim.ai.openai.base-url` | `https://api.openai.com/v1` | OpenAI API base URL (Chat Completions). | KI-Adapter |
| `rpsim.ai.openai.model` | `gpt-4o-mini` | Default OpenAI model. | KI-Adapter |
| `rpsim.ai.anthropic.base-url` | `https://api.anthropic.com` | Anthropic API base URL (official Java SDK). | KI-Adapter |
| `rpsim.ai.anthropic.model` | `claude-opus-5` | Default Anthropic model. | KI-Adapter |
| `rpsim.ai.gemini.base-url` | `https://generativelanguage.googleapis.com/v1beta` | Google Gemini API base URL. | KI-Adapter |
| `rpsim.ai.gemini.model` | `gemini-2.5-flash` | Default Gemini model - Google renames free-tier models; see `ai-providers.md`. | KI-Adapter |
| `rpsim.ai.ollama.base-url` | `http://localhost:11434` | Address of a local Ollama server. | KI-Adapter |
| `rpsim.ai.ollama.model` | `llama3.1` | Default Ollama model (must be pulled locally). | KI-Adapter |
| `rpsim.ai.<provider>.api-key` | *(empty)* | API key per provider (`openai`, `anthropic`, `gemini`). **Only** in the git-ignored `application-local.yml` or via the settings page - never in `application.yml`. | KI-Adapter |
| `rpsim.ai.worker-enabled` | `true` | Runs the narration worker; `false` in unit tests (jobs are processed explicitly). | Entkopplung & Resilienz |

## `rpsim.formulas.trust`

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.trust.min` | `-100` | Lower cap of every trust score. | TrustScoreService |
| `rpsim.formulas.trust.max` | `100` | Upper cap of every trust score. | TrustScoreService |
| `rpsim.formulas.trust.neutral` | `0` | Neutral value new characters start with and decay moves towards. | TrustScoreService |
| `rpsim.formulas.trust.decay-start-days` | `10` | Game days without a trust event before decay starts. | TrustScoreService |
| `rpsim.formulas.trust.decay-per-day` | `0.5` | Points per game day the score moves towards neutral after `decay-start-days`. | TrustScoreService |
| `rpsim.formulas.trust.on-time-payment` | `2` | Trust event: installment paid on time (bank advisor). | TrustScoreService |
| `rpsim.formulas.trust.missed-payment` | `-5` | Trust event: installment missed. | TrustScoreService |
| `rpsim.formulas.trust.promise-kept` | `3` | Trust event: promise kept (e.g. special contract delivered). | TrustScoreService |
| `rpsim.formulas.trust.promise-broken` | `-6` | Trust event: promise broken. | TrustScoreService |
| `rpsim.formulas.trust.call-declined` | `-3` | Trust event: player declined a call. | Anruf-Zustandsautomat |
| `rpsim.formulas.trust.call-missed` | `-1` | Trust event: call rang out. | Anruf-Zustandsautomat |
| `rpsim.formulas.trust.negotiation-deal` | `3` | Trust event: agreement in a negotiation. | Verhandlungssystem |
| `rpsim.formulas.trust.village-relation-strained` | `-10` | Start trust of village characters for the backstory choice *belastet*. | Onboarding & Zwei-Phasen-Verknüpfung |
| `rpsim.formulas.trust.village-relation-connected` | `10` | Start trust of village characters for the backstory choice *gut vernetzt*. | Onboarding & Zwei-Phasen-Verknüpfung |
| `rpsim.formulas.trust.display-very-good` | `50` | UI abstraction: score ≥ value → level *sehr vertraut* (raw score is never shown). | TrustScoreService |
| `rpsim.formulas.trust.display-good` | `15` | UI abstraction: score ≥ value → *vertraut*. | TrustScoreService |
| `rpsim.formulas.trust.display-strained` | `-15` | UI abstraction: score ≤ value → *angespannt*. | TrustScoreService |
| `rpsim.formulas.trust.display-bad` | `-50` | UI abstraction: score ≤ value → *zerrüttet*. | TrustScoreService |

## `rpsim.formulas.credit`

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.credit.weight-debt-service-coverage` | `0.30` | Weight of `debtServiceCoverage` in `coreScore` (0.30). | Bonitäts-Score |
| `rpsim.formulas.credit.weight-equity-ratio` | `0.25` | Weight of `equityRatio` (0.25). | Bonitäts-Score |
| `rpsim.formulas.credit.weight-liquidity-buffer` | `0.15` | Weight of `liquidityBuffer` (0.15). | Bonitäts-Score |
| `rpsim.formulas.credit.weight-loan-to-farm-size` | `0.15` | Weight of `loanToFarmSize` (0.15). | Bonitäts-Score |
| `rpsim.formulas.credit.weight-payment-history` | `0.15` | Weight of `paymentHistoryScore` (0.15). | Bonitäts-Score |
| `rpsim.formulas.credit.payment-history-neutral` | `70` | `paymentHistoryScore` without history ("neutral ~70"). | Bonitäts-Score |
| `rpsim.formulas.credit.trust-divisor` | `10` | `trustBonus = clamp(trustScore / trust-divisor, ±trust-cap)`. | Bonitäts-Score |
| `rpsim.formulas.credit.trust-cap` | `8` | Cap of the trust bonus (±8). Must stay smaller than the gap between the thresholds (security principle). | Bonitäts-Score |
| `rpsim.formulas.credit.approve-threshold` | `75` | `finalScore` ≥ value → fully approved. | Bonitäts-Score |
| `rpsim.formulas.credit.counter-threshold` | `45` | `finalScore` ≥ value (and below approve) → counter offer; below → rejected. | Bonitäts-Score |
| `rpsim.formulas.credit.debt-service-coverage-full` | `2.0` | Saturation: coverage (monthly operating cash flow / monthly installments) that yields 100 points. | Bonitäts-Score |
| `rpsim.formulas.credit.debt-service-coverage-no-history` | `50` | `debtServiceCoverage` points while there is no cash-flow history yet. | Bonitäts-Score |
| `rpsim.formulas.credit.cashflow-min-history-days` | `1` | Minimum snapshot history (game days) before the cash-flow trend is used. | Bonitäts-Score |
| `rpsim.formulas.credit.liquidity-full-ratio` | `0.5` | Saturation: balance / requested amount that yields 100 points. | Bonitäts-Score |
| `rpsim.formulas.credit.loan-to-farm-size-full-ratio` | `3.0` | Saturation: asset value / (debt + requested) that yields 100 points. | Bonitäts-Score |
| `rpsim.formulas.credit.cashflow-window-days` | `30` | Moving window (game days) of the cash-flow trend from the `FactsSnapshot` series. | Bonitäts-Score |
| `rpsim.formulas.credit.base-interest-rate` | `0.05` | Annual interest rate of an approved loan. | Bonitäts-Score |
| `rpsim.formulas.credit.counter-max-amount-reduction` | `0.5` | Counter offer: max. share the amount is reduced by at the counter threshold (scaled by the gap to approve). | Bonitäts-Score |
| `rpsim.formulas.credit.counter-max-interest-surcharge` | `0.04` | Counter offer: max. interest surcharge (scaled by the gap). | Bonitäts-Score |
| `rpsim.formulas.credit.counter-max-term-reduction-months` | `12` | Counter offer: max. term reduction in months (scaled by the gap). | Bonitäts-Score |
| `rpsim.formulas.credit.min-term-months` | `6` | Shortest allowed term. | Kreditantrag & Bearbeitungszeit |
| `rpsim.formulas.credit.max-term-months` | `240` | Longest allowed term. | Kreditantrag & Bearbeitungszeit |
| `rpsim.formulas.credit.processing-days-min` | `1` | Artificial processing time, lower bound (game days). | Kreditantrag & Bearbeitungszeit |
| `rpsim.formulas.credit.processing-days-max` | `2` | Artificial processing time, upper bound (game days). | Kreditantrag & Bearbeitungszeit |
| `rpsim.formulas.credit.office-clerk-reduction-hours` | `6` | Hours an employed office clerk shortens the processing time (× skill/100). | Kreditantrag & Bearbeitungszeit |
| `rpsim.formulas.credit.office-clerk-max-reduction-share` | `0.5` | Upper bound of that reduction as share of the rolled processing time. | Kreditantrag & Bearbeitungszeit |
| `rpsim.formulas.credit.reminder-after-days` | `1` | Days overdue until the reminder (stage 1, no money). | Zahlungsausfall-Eskalation |
| `rpsim.formulas.credit.penalty-after-days` | `3` | Days overdue until the late fee (stage 2, `CREDIT_PENALTY`). | Zahlungsausfall-Eskalation |
| `rpsim.formulas.credit.trust-loss-after-days` | `5` | Days overdue until the trust loss (stage 3). | Zahlungsausfall-Eskalation |
| `rpsim.formulas.credit.final-stage-after-missed-installments` | `3` | Missed installments that count as repeated default (final stage). | Zahlungsausfall-Eskalation |
| `rpsim.formulas.credit.final-stage` | `CALLBACK` | Final stage: `CALLBACK` (remaining debt due at once, public → village reputation) or `BLOCK` (no new credit). | Zahlungsausfall-Eskalation |
| `rpsim.formulas.credit.penalty-rate` | `0.05` | Late fee as share of the installment. | Zahlungsausfall-Eskalation |
| `rpsim.formulas.credit.trust-loss-delta` | `-10` | Trust event at stage 3. | Zahlungsausfall-Eskalation |
| `rpsim.formulas.credit.public-default-delta` | `-30` | `PublicActionEvent(PUBLIC_DEFAULT)` delta of a call-back. | Dorf-Ansehen |
| `rpsim.formulas.credit.deferral-months` | `2` | Months an installment is postponed by a granted deferral (Stundung). | Zahlungsausfall-Eskalation |
| `rpsim.formulas.credit.max-deferrals-per-loan` | `1` | Deferrals allowed per loan. | Zahlungsausfall-Eskalation |
| `rpsim.formulas.credit.deferral-min-payment-history` | `50` | Minimum `paymentHistoryScore` for a deferral. | Zahlungsausfall-Eskalation |
| `rpsim.formulas.credit.deferral-max-escalation-level` | `1` | Highest escalation level at which a deferral is still possible. | Zahlungsausfall-Eskalation |
| `rpsim.formulas.credit.legacy-interest-rate` | `0.06` | Interest of the legacy loan from the onboarding (no scoring, no disbursement). | Onboarding & Zwei-Phasen-Verknüpfung |
| `rpsim.formulas.credit.legacy-term-months` | `60` | Term of the legacy loan. | Onboarding & Zwei-Phasen-Verknüpfung |
| `rpsim.formulas.credit.payment-history-missed-penalty` | `15` | `paymentHistoryScore` points lost per missed installment. | Bonitäts-Score |
| `rpsim.formulas.credit.payment-history-on-time-gain` | `2` | `paymentHistoryScore` points gained per on-time installment. | Bonitäts-Score |
| `rpsim.formulas.credit.standing-crop-discount` | `0.5` | Standing crops count as asset in the credit check: harvest value (area × yield × best price) × growth progress × this discount; only with the field export. | Roadmap V2 R2-C5 |
| `rpsim.formulas.credit.special-repayment-free-share` | `0.10` | Sondertilgung: share of the original principal that can be repaid early per FS25 year without a fee. | Sondertilgung |
| `rpsim.formulas.credit.special-repayment-fee-rate` | `0.01` | Sondertilgung: fee (Vorfälligkeitsentschädigung) on the part above the free share, booked on top as `CREDIT_PREPAYMENT_FEE`. | Sondertilgung |
| `rpsim.formulas.credit.special-repayment-trust-delta` | `3` | Sondertilgung: trust bonus at the bank advisor ... | Sondertilgung |
| `rpsim.formulas.credit.special-repayment-trust-min-share` | `0.05` | ... when the Sondertilgung is at least this share of the remaining debt. | Sondertilgung |

## `rpsim.formulas.credit-hard`

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.credit-hard.weight-debt-service-coverage` | `0.30` | HART profile (tone preset *Hart*): same as `formulas.credit.weight-debt-service-coverage`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.weight-equity-ratio` | `0.25` | HART profile (tone preset *Hart*): same as `formulas.credit.weight-equity-ratio`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.weight-liquidity-buffer` | `0.15` | HART profile (tone preset *Hart*): same as `formulas.credit.weight-liquidity-buffer`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.weight-loan-to-farm-size` | `0.15` | HART profile (tone preset *Hart*): same as `formulas.credit.weight-loan-to-farm-size`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.weight-payment-history` | `0.15` | HART profile (tone preset *Hart*): same as `formulas.credit.weight-payment-history`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.payment-history-neutral` | `70` | HART profile (tone preset *Hart*): same as `formulas.credit.payment-history-neutral`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.trust-divisor` | `10` | HART profile (tone preset *Hart*): same as `formulas.credit.trust-divisor`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.trust-cap` | `5` | HART profile (tone preset *Hart*): same as `formulas.credit.trust-cap`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.approve-threshold` | `85` | HART profile (tone preset *Hart*): same as `formulas.credit.approve-threshold`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.counter-threshold` | `55` | HART profile (tone preset *Hart*): same as `formulas.credit.counter-threshold`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.debt-service-coverage-full` | `2.0` | HART profile (tone preset *Hart*): same as `formulas.credit.debt-service-coverage-full`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.debt-service-coverage-no-history` | `50` | HART profile (tone preset *Hart*): same as `formulas.credit.debt-service-coverage-no-history`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.cashflow-min-history-days` | `1` | HART profile (tone preset *Hart*): same as `formulas.credit.cashflow-min-history-days`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.liquidity-full-ratio` | `0.5` | HART profile (tone preset *Hart*): same as `formulas.credit.liquidity-full-ratio`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.loan-to-farm-size-full-ratio` | `3.0` | HART profile (tone preset *Hart*): same as `formulas.credit.loan-to-farm-size-full-ratio`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.cashflow-window-days` | `30` | HART profile (tone preset *Hart*): same as `formulas.credit.cashflow-window-days`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.base-interest-rate` | `0.05` | HART profile (tone preset *Hart*): same as `formulas.credit.base-interest-rate`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.counter-max-amount-reduction` | `0.5` | HART profile (tone preset *Hart*): same as `formulas.credit.counter-max-amount-reduction`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.counter-max-interest-surcharge` | `0.04` | HART profile (tone preset *Hart*): same as `formulas.credit.counter-max-interest-surcharge`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.counter-max-term-reduction-months` | `12` | HART profile (tone preset *Hart*): same as `formulas.credit.counter-max-term-reduction-months`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.min-term-months` | `6` | HART profile (tone preset *Hart*): same as `formulas.credit.min-term-months`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.max-term-months` | `240` | HART profile (tone preset *Hart*): same as `formulas.credit.max-term-months`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.processing-days-min` | `1` | HART profile (tone preset *Hart*): same as `formulas.credit.processing-days-min`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.processing-days-max` | `2` | HART profile (tone preset *Hart*): same as `formulas.credit.processing-days-max`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.office-clerk-reduction-hours` | `6` | HART profile (tone preset *Hart*): same as `formulas.credit.office-clerk-reduction-hours`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.office-clerk-max-reduction-share` | `0.5` | HART profile (tone preset *Hart*): same as `formulas.credit.office-clerk-max-reduction-share`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.reminder-after-days` | `1` | HART profile (tone preset *Hart*): same as `formulas.credit.reminder-after-days`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.penalty-after-days` | `3` | HART profile (tone preset *Hart*): same as `formulas.credit.penalty-after-days`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.trust-loss-after-days` | `5` | HART profile (tone preset *Hart*): same as `formulas.credit.trust-loss-after-days`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.final-stage-after-missed-installments` | `3` | HART profile (tone preset *Hart*): same as `formulas.credit.final-stage-after-missed-installments`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.final-stage` | `CALLBACK` | HART profile (tone preset *Hart*): same as `formulas.credit.final-stage`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.penalty-rate` | `0.05` | HART profile (tone preset *Hart*): same as `formulas.credit.penalty-rate`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.trust-loss-delta` | `-10` | HART profile (tone preset *Hart*): same as `formulas.credit.trust-loss-delta`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.public-default-delta` | `-30` | HART profile (tone preset *Hart*): same as `formulas.credit.public-default-delta`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.deferral-months` | `2` | HART profile (tone preset *Hart*): same as `formulas.credit.deferral-months`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.max-deferrals-per-loan` | `1` | HART profile (tone preset *Hart*): same as `formulas.credit.max-deferrals-per-loan`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.deferral-min-payment-history` | `50` | HART profile (tone preset *Hart*): same as `formulas.credit.deferral-min-payment-history`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.deferral-max-escalation-level` | `1` | HART profile (tone preset *Hart*): same as `formulas.credit.deferral-max-escalation-level`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.legacy-interest-rate` | `0.06` | HART profile (tone preset *Hart*): same as `formulas.credit.legacy-interest-rate`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.legacy-term-months` | `60` | HART profile (tone preset *Hart*): same as `formulas.credit.legacy-term-months`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.payment-history-missed-penalty` | `15` | HART profile (tone preset *Hart*): same as `formulas.credit.payment-history-missed-penalty`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.payment-history-on-time-gain` | `2` | HART profile (tone preset *Hart*): same as `formulas.credit.payment-history-on-time-gain`. | Ton-/Genre-Konfigurationsprofile |
| `rpsim.formulas.credit-hard.standing-crop-discount` | `0.5` | HART profile: same as `formulas.credit.standing-crop-discount`. | Roadmap V2 R2-C5 |
| `rpsim.formulas.credit-hard.special-repayment-free-share` | `0.10` | HART profile: same as `formulas.credit.special-repayment-free-share`. | Sondertilgung |
| `rpsim.formulas.credit-hard.special-repayment-fee-rate` | `0.01` | HART profile: same as `formulas.credit.special-repayment-fee-rate`. | Sondertilgung |
| `rpsim.formulas.credit-hard.special-repayment-trust-delta` | `3` | HART profile: same as `formulas.credit.special-repayment-trust-delta`. | Sondertilgung |
| `rpsim.formulas.credit-hard.special-repayment-trust-min-share` | `0.05` | HART profile: same as `formulas.credit.special-repayment-trust-min-share`. | Sondertilgung |

## `rpsim.formulas.market`

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.market.daily-spawn-probability` | `0.15` | Daily probability roll for a new market event. | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.max-active-events` | `3` | Cap of simultaneously active events. | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.advance-notice-probability` | `0.15` | Share of events announced in advance (most come as a surprise). | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.advance-notice-days-min` | `2` | Advance notice, lower bound (game days). | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.advance-notice-days-max` | `4` | Advance notice, upper bound (game days). | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.rumor-accurate-probability` | `0.7` | Share of rumours that refer to a real planned event (`isAccurate`); the rest is invented (~70/30). | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.target-base-weight` | `1.0` | Target selection weight every fill type gets. | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.target-stock-weight` | `4.0` | Additional weight × share of the fill type in the silo stock (stored crops are hit more often). | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.type-weights.DEMAND_SPIKE` | `3.0` | Relative weight of event type `DEMAND_SPIKE` in the spawn roll. | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.type-weights.DEMAND_SLUMP` | `2.0` | Relative weight of event type `DEMAND_SLUMP` in the spawn roll. | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.type-weights.HARVEST_FAILURE` | `1.0` | Relative weight of event type `HARVEST_FAILURE` in the spawn roll. | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.type-weights.HARVEST_SURPLUS` | `1.0` | Relative weight of event type `HARVEST_SURPLUS` in the spawn roll. | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.type-weights.SPECIAL_OFFER` | `1.0` | Relative weight of event type `SPECIAL_OFFER` in the spawn roll. | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.type-weights.SUBSIDY` | `0.5` | Relative weight of event type `SUBSIDY` in the spawn roll. | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.type-weights.RUMOR` | `1.5` | Relative weight of event type `RUMOR` in the spawn roll. | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.bands.DEMAND_SPIKE` | multiplier-min: 1.08<br>multiplier-max: 1.25<br>ramp-hours-min: 12<br>ramp-hours-max: 36<br>hold-hours-min: 72<br>hold-hours-max: 168<br>decay-hours-min: 48<br>decay-hours-max: 120 | Strength/duration band of `DEMAND_SPIKE`: price multiplier and ramp-up/hold/decay hours (random within the bounds). | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.bands.DEMAND_SLUMP` | multiplier-min: 0.75<br>multiplier-max: 0.92<br>ramp-hours-min: 12<br>ramp-hours-max: 36<br>hold-hours-min: 72<br>hold-hours-max: 168<br>decay-hours-min: 48<br>decay-hours-max: 120 | Strength/duration band of `DEMAND_SLUMP`: price multiplier and ramp-up/hold/decay hours (random within the bounds). | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.bands.HARVEST_FAILURE` | multiplier-min: 1.10<br>multiplier-max: 1.30<br>ramp-hours-min: 24<br>ramp-hours-max: 48<br>hold-hours-min: 96<br>hold-hours-max: 240<br>decay-hours-min: 72<br>decay-hours-max: 168 | Strength/duration band of `HARVEST_FAILURE`: price multiplier and ramp-up/hold/decay hours (random within the bounds). | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.bands.HARVEST_SURPLUS` | multiplier-min: 0.70<br>multiplier-max: 0.90<br>ramp-hours-min: 24<br>ramp-hours-max: 48<br>hold-hours-min: 96<br>hold-hours-max: 240<br>decay-hours-min: 72<br>decay-hours-max: 168 | Strength/duration band of `HARVEST_SURPLUS`: price multiplier and ramp-up/hold/decay hours (random within the bounds). | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.special-offer-premium-min` | `1.10` | Special contract: fixed price = current price × premium, lower bound. | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.special-offer-premium-max` | `1.30` | Special contract premium, upper bound. | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.special-offer-quantity-min` | `5000` | Special contract max. quantity (l), lower bound. | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.special-offer-quantity-max` | `30000` | Special contract max. quantity (l), upper bound. | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.special-offer-days-min` | `3` | Special contract delivery window (game days), lower bound. | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.special-offer-days-max` | `7` | Special contract delivery window, upper bound. | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.subsidy-amount-min` | `2000` | Subsidy amount (€), lower bound. | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.subsidy-amount-max` | `15000` | Subsidy amount (€), upper bound. | Preis-Events & Sonderkontrakte |
| `rpsim.formulas.market.event-call-probability` | `0.2` | Share of event messages that arrive as a phone call instead of a mail. | Preis-Events & Sonderkontrakte |

## `rpsim.formulas.negotiation`

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.negotiation.max-rounds` | `3` | Maximum negotiation rounds (3). | Verhandlungs-Preisfindung |
| `rpsim.formulas.negotiation.counter-band` | `0.9` | Offer ≥ counter-band × `effectiveMinAccept` → counter offer (0.9). | Verhandlungs-Preisfindung |
| `rpsim.formulas.negotiation.trust-divisor` | `20` | `trustAdjustment = clamp(trustScore / trust-divisor, ±trust-cap)`. | Verhandlungs-Preisfindung |
| `rpsim.formulas.negotiation.trust-cap` | `0.05` | Cap of the trust adjustment (±5 %) - security principle. | Verhandlungs-Preisfindung |
| `rpsim.formulas.negotiation.stubbornness-discount.NEGOTIABLE` | `0.20` | `stubbornnessDiscount` of trait `NEGOTIABLE`: `minAccept = basePrice × (1 − discount)`, range 0.05–0.20. | Verhandlungs-Preisfindung |
| `rpsim.formulas.negotiation.stubbornness-discount.NEUTRAL` | `0.12` | `stubbornnessDiscount` of trait `NEUTRAL`: `minAccept = basePrice × (1 − discount)`, range 0.05–0.20. | Verhandlungs-Preisfindung |
| `rpsim.formulas.negotiation.stubbornness-discount.STUBBORN` | `0.05` | `stubbornnessDiscount` of trait `STUBBORN`: `minAccept = basePrice × (1 − discount)`, range 0.05–0.20. | Verhandlungs-Preisfindung |
| `rpsim.formulas.negotiation.npc-bid-min` | `0.90` | Auction: `npcMaxBid = basePrice × random(min, max)`, lower bound. | Verhandlungs-Preisfindung |
| `rpsim.formulas.negotiation.npc-bid-max` | `1.15` | Auction NPC max bid, upper bound. | Verhandlungs-Preisfindung |
| `rpsim.formulas.negotiation.npc-counter-offer-min` | `0.85` | Selling: `npcCounterOffer = askingPrice × random(min, max)`, lower bound. | Verhandlungs-Preisfindung |
| `rpsim.formulas.negotiation.npc-counter-offer-max` | `1.0` | Selling: NPC first offer, upper bound. | Verhandlungs-Preisfindung |
| `rpsim.formulas.negotiation.interest-wealth-factor` | `1.0` | A character is interested in buying if `virtualWealth ≥ askingPrice × factor`. | Verhandlungs-Preisfindung |
| `rpsim.formulas.negotiation.max-interested-buyers` | `3` | Maximum interested buyers per sale offer. | Verhandlungs-Preisfindung |
| `rpsim.formulas.negotiation.auction-daily-spawn-probability` | `0.05` | Daily probability roll for a new auction. | Verhandlungssystem |
| `rpsim.formulas.negotiation.auction-duration-days` | `3` | Game days an auction stays open. | Verhandlungssystem |
| `rpsim.formulas.negotiation.auction-bidders-min` | `1` | NPC bidders per auction, lower bound. | Verhandlungs-Preisfindung |
| `rpsim.formulas.negotiation.auction-bidders-max` | `3` | NPC bidders per auction, upper bound. | Verhandlungs-Preisfindung |
| `rpsim.formulas.negotiation.auction-bid-increment-ratio` | `0.01` | Displayed leading NPC bid = min(npcMaxBid, player bid + ratio × basePrice). | Verhandlungs-Preisfindung |
| `rpsim.formulas.negotiation.auction-tie-trust-threshold` | `0` | Tie: the player wins if trust towards the announcing character ≥ value. | Verhandlungs-Preisfindung |
| `rpsim.formulas.negotiation.sale-offer-valid-days` | `5` | Game days a sale offer of the player stays open. | Verhandlungssystem |
| `rpsim.formulas.negotiation.virtual-wealth-min` | `20000` | Hidden `virtualWealth` of generated characters, lower bound. | Verhandlungs-Preisfindung |
| `rpsim.formulas.negotiation.virtual-wealth-max` | `400000` | Hidden `virtualWealth`, upper bound. | Verhandlungs-Preisfindung |
| `rpsim.formulas.negotiation.sell-willing-probability` | `0.3` | Share of land-owning characters willing to sell in a direct negotiation. | Verhandlungssystem |
| `rpsim.formulas.negotiation.npc-owned-share` | `0.4` | Share of unowned map fields assigned to village characters when the ownership table is first built. | Verhandlungssystem |
| `rpsim.formulas.negotiation.use-game-npc-owners` | `true` | TODO T-21: such a field belongs to the FS25 NPC of the farmland (`farmlands[].npc` in `market_context.json`), created once as a village character with the name the game shows; they never move away. `false` or no NPC in the export: a random village character. | Verhandlungssystem |

## `rpsim.formulas.satisfaction`

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.satisfaction.category-weight` | `0.25` | `satisfactionScore = weight × Σ(4 categories)` (0.25). | Satisfaction-Formel |
| `rpsim.formulas.satisfaction.category-max` | `100` | Upper bound of each category (100). `TODO(offene-frage)`: > 100 would make the 1.2 bonus reachable, see `QUESTIONS.md`. | Satisfaction-Formel |
| `rpsim.formulas.satisfaction.multiplier-min` | `0.5` | `effectMultiplier = clamp(score / 100, min, max)`, lower bound. | Satisfaction-Formel |
| `rpsim.formulas.satisfaction.multiplier-max` | `1.2` | `effectMultiplier`, upper bound. | Satisfaction-Formel |
| `rpsim.formulas.satisfaction.baseline-output-value` | `1500` | `employeeEffectAmount = baseline × (effectMultiplier − 1)` per month. | Satisfaction-Formel |
| `rpsim.formulas.satisfaction.start-value` | `70` | Start value of the event categories after hiring. | Satisfaction-Formel |
| `rpsim.formulas.satisfaction.pay-fairness-decay-per-day` | `0.5` | Decay of `payFairness` per game day. | Satisfaction-Formel |
| `rpsim.formulas.satisfaction.workload-decay-per-day` | `0.7` | Decay of `workload` per game day. | Satisfaction-Formel |
| `rpsim.formulas.satisfaction.appreciation-decay-per-day` | `1.0` | Decay of `appreciation` per game day. | Satisfaction-Formel |
| `rpsim.formulas.satisfaction.raise-points-per-percent` | `2` | `payFairness` points per 1 % raise. | Satisfaction-Formel |
| `rpsim.formulas.satisfaction.raise-max-points` | `30` | Cap of the points of one raise. | Satisfaction-Formel |
| `rpsim.formulas.satisfaction.time-off-points-per-day` | `15` | `workload` points per day off. | Satisfaction-Formel |
| `rpsim.formulas.satisfaction.conversation-points` | `8` | `appreciation` points per conversation (mail/call with the employee). | Satisfaction-Formel |
| `rpsim.formulas.satisfaction.conversation-cooldown-days` | `1` | Cool-down between counted conversations. | Satisfaction-Formel |
| `rpsim.formulas.satisfaction.salary-overdue-delta` | `-15` | `payFairness` event when the salary could not be paid. | Satisfaction-Formel |
| `rpsim.formulas.satisfaction.warning-threshold` | `30` | Score below this value counts as dissatisfied (strict "< 30"). | Kündigung & Bewerbung |
| `rpsim.formulas.satisfaction.warning-after-days` | `14` | Days dissatisfied in a row until the warning mail (≥ 14). | Kündigung & Bewerbung |
| `rpsim.formulas.satisfaction.termination-after-days` | `30` | Days dissatisfied in a row until the resignation (≥ 30). | Kündigung & Bewerbung |
| `rpsim.formulas.satisfaction.strike-threshold` | `30` | Roadmap V2 R2-A5: below this satisfaction score for `strike-after-days` the employee goes on strike (the FS25 helper stops, the salary keeps running); back at the threshold the strike ends. | Roadmap V2 R2-A5 |
| `rpsim.formulas.satisfaction.strike-after-days` | `21` | Days dissatisfied in a row until the strike (between the warning after 14 and the resignation after 30 days). | Roadmap V2 R2-A5 |
| `rpsim.formulas.satisfaction.workload.target-hours-per-day` | `8` | Roadmap V2 R2-A4: target hours of a machine operator per game day; with worked time from the mod the workload follows the real hours instead of `workload-decay-per-day` (evaluated every game day). The target of a game month = hours × days per period. | Roadmap V2 R2-A4 |
| `rpsim.formulas.satisfaction.workload.overtime-penalty-per-hour` | `2` | Workload points lost per hour driven above the daily target. | Roadmap V2 R2-A4 |
| `rpsim.formulas.satisfaction.workload.recovery-per-hour` | `0.5` | Workload points regained per hour below the daily target (light recovery). | Roadmap V2 R2-A4 |
| `rpsim.formulas.satisfaction.workload.effect-scales-with-hours` | `true` | The positive monthly `EMPLOYEE_EFFECT` of a machine operator × min(1, hours driven / target hours of the month); a malus stays unchanged. | Roadmap V2 R2-A4 |
| `rpsim.formulas.satisfaction.workload.animals-per-keeper` | `80` | Roadmap V2 R2-A7: animals one keeper handles; with husbandry values from the mod the keeper's workload follows the animals per keeper (daily). | Roadmap V2 R2-A7 |
| `rpsim.formulas.satisfaction.workload.keeper-overload-penalty-per-day` | `3` | Workload points lost per day and per 100 % overload above `animals-per-keeper`. | Roadmap V2 R2-A7 |
| `rpsim.formulas.satisfaction.workload.keeper-recovery-per-day` | `0.5` | Workload points regained per day at or below `animals-per-keeper`. | Roadmap V2 R2-A7 |

## `rpsim.formulas.hiring`

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.hiring.candidates-min` | `3` | Applicants per job posting, lower bound. | Kündigung & Bewerbung |
| `rpsim.formulas.hiring.candidates-max` | `5` | Applicants per job posting, upper bound. | Kündigung & Bewerbung |
| `rpsim.formulas.hiring.skill-min` | `30` | Applicant skill, lower bound. | Kündigung & Bewerbung |
| `rpsim.formulas.hiring.skill-max` | `95` | Applicant skill, upper bound. | Kündigung & Bewerbung |
| `rpsim.formulas.hiring.base-salary.MACHINE_OPERATOR` | `2400.0` | Monthly base salary (€) of job role `MACHINE_OPERATOR`. | Kündigung & Bewerbung |
| `rpsim.formulas.hiring.base-salary.MECHANIC` | `2700.0` | Monthly base salary (€) of job role `MECHANIC`. | Kündigung & Bewerbung |
| `rpsim.formulas.hiring.base-salary.ANIMAL_KEEPER` | `2200.0` | Monthly base salary (€) of job role `ANIMAL_KEEPER`. | Kündigung & Bewerbung |
| `rpsim.formulas.hiring.base-salary.OFFICE_CLERK` | `2300.0` | Monthly base salary (€) of job role `OFFICE_CLERK`. | Kündigung & Bewerbung |
| `rpsim.formulas.hiring.salary-skill-factor` | `0.3` | Salary expectation = base × (1 + factor × (skill − 50) / 50), rounded to 10 €. | Kündigung & Bewerbung |

## `rpsim.formulas.training`

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.training.duration-days` | `1` | Game days a machine operator is away at a training (ON_LEAVE for the mod, no helper); the qualification counts afterwards. | Schulungen |
| `rpsim.formulas.training.appreciation-points` | `8` | Appreciation points a booked training brings. | Schulungen |
| `rpsim.formulas.training.cost.LARGE_TRACTOR` | `4500` | Price (€) of the training `LARGE_TRACTOR`, booked as `TRAINING`. | Schulungen |
| `rpsim.formulas.training.cost.SELF_PROPELLED` | `6000` | Price (€) of the training `SELF_PROPELLED`, booked as `TRAINING`. | Schulungen |
| `rpsim.formulas.training.cost.SPECIAL_HARVESTER` | `7500` | Price (€) of the training `SPECIAL_HARVESTER`, booked as `TRAINING`. | Schulungen |
| `rpsim.formulas.training.cost.COMBINE` | `9000` | Price (€) of the training `COMBINE`, booked as `TRAINING`. | Schulungen |
| `rpsim.formulas.training.cost.FORAGE_HARVESTER` | `9000` | Price (€) of the training `FORAGE_HARVESTER`, booked as `TRAINING`. | Schulungen |
| `rpsim.formulas.training.cost.TRUCK` | `12000` | Price (€) of the training `TRUCK`, booked as `TRAINING`. | Schulungen |
| `rpsim.formulas.training.applicant-chance` | `0.3` | Probability that a machine operator applicant brings one random training along. | Schulungen |
| `rpsim.formulas.training.applicant-salary-premium` | `0.08` | Extra salary expectation of an applicant with a training (+8 %, rounded to 10 €). | Schulungen |
| `rpsim.formulas.training.categories.LARGE_TRACTOR` | `[TRACTORSL]` | FS25 shop categories (`StoreItem.categoryName`, upper case) that need the training `LARGE_TRACTOR` for a helper; sent to the mod with `EMPLOYEE_ROSTER`. Categories no training lists (small/medium tractors, cars, forklifts, forestry, mod categories) need none. | Schulungen |
| `rpsim.formulas.training.categories.COMBINE` | `[HARVESTERS]` | FS25 shop categories (`StoreItem.categoryName`, upper case) that need the training `COMBINE` for a helper; sent to the mod with `EMPLOYEE_ROSTER`. Categories no training lists (small/medium tractors, cars, forklifts, forestry, mod categories) need none. | Schulungen |
| `rpsim.formulas.training.categories.FORAGE_HARVESTER` | `[FORAGEHARVESTERS]` | FS25 shop categories (`StoreItem.categoryName`, upper case) that need the training `FORAGE_HARVESTER` for a helper; sent to the mod with `EMPLOYEE_ROSTER`. Categories no training lists (small/medium tractors, cars, forklifts, forestry, mod categories) need none. | Schulungen |
| `rpsim.formulas.training.categories.SPECIAL_HARVESTER` | `[BEETVEHICLES, POTATOVEHICLES, VEGETABLEVEHICLES, COTTONVEHICLES, SUGARCANEVEHICLES, GRAPEVEHICLES, OLIVEVEHICLES]` | FS25 shop categories (`StoreItem.categoryName`, upper case) that need the training `SPECIAL_HARVESTER` for a helper; sent to the mod with `EMPLOYEE_ROSTER`. Categories no training lists (small/medium tractors, cars, forklifts, forestry, mod categories) need none. | Schulungen |
| `rpsim.formulas.training.categories.TRUCK` | `[TRUCKS]` | FS25 shop categories (`StoreItem.categoryName`, upper case) that need the training `TRUCK` for a helper; sent to the mod with `EMPLOYEE_ROSTER`. Categories no training lists (small/medium tractors, cars, forklifts, forestry, mod categories) need none. | Schulungen |
| `rpsim.formulas.training.categories.SELF_PROPELLED` | `[SPRAYERVEHICLES, MOWERVEHICLES, FRONTLOADERVEHICLES, TELELOADERVEHICLES, SKIDSTEERVEHICLES, WHEELLOADERVEHICLES]` | FS25 shop categories (`StoreItem.categoryName`, upper case) that need the training `SELF_PROPELLED` for a helper; sent to the mod with `EMPLOYEE_ROSTER`. Categories no training lists (small/medium tractors, cars, forklifts, forestry, mod categories) need none. | Schulungen |

## `rpsim.formulas.reputation`

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.reputation.trust-weight` | `0.6` | `villageReputationScore = 0.6 × trustAverage + 0.4 × publicActionSum`. | Dorf-Ansehen |
| `rpsim.formulas.reputation.public-weight` | `0.4` | Weight of the decaying public action sum. | Dorf-Ansehen |
| `rpsim.formulas.reputation.public-half-life-days` | `60` | Half-life (game days) of public actions. | Dorf-Ansehen |
| `rpsim.formulas.reputation.new-character-factor` | `0.2` | `baseTrustForNewCharacter = clamp(score × factor, ±cap)`. | Dorf-Ansehen |
| `rpsim.formulas.reputation.new-character-cap` | `15` | Cap of the base trust of new characters (±15). | Dorf-Ansehen |
| `rpsim.formulas.reputation.good-threshold` | `25` | Score ≥ value → *gut angesehen*. | Dorf-Ansehen |
| `rpsim.formulas.reputation.controversial-threshold` | `-25` | Score ≤ value → *umstritten*. | Dorf-Ansehen |

## `rpsim.formulas.rotation`

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.rotation.max-per-year` | `2` | Maximum arrivals + departures per game year (1–2). | Dynamische-Charaktere-Rotation |
| `rpsim.formulas.rotation.daily-probability` | `0.02` | Daily roll for a rotation (within the yearly budget). | Dynamische-Charaktere-Rotation |
| `rpsim.formulas.rotation.move-away-share` | `0.5` | Probability that a rotation is a departure (else an arrival). | Dynamische-Charaktere-Rotation |
| `rpsim.formulas.rotation.retirement-share` | `0.4` | Share of departures that are retirements (else moving away). | Dynamische-Charaktere-Rotation |
| `rpsim.formulas.rotation.min-dynamic-characters` | `3` | Never fewer dynamic characters (no departure below). | Dynamische-Charaktere-Rotation |
| `rpsim.formulas.rotation.max-dynamic-characters` | `8` | Never more dynamic characters (forces a departure). | Dynamische-Charaktere-Rotation |

## `rpsim.formulas.absence`

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.absence.daily-probability` | `0.01` | Daily roll for an absence (holiday/illness) of a mandatory role. | Pflichtrollen-Abwesenheit |
| `rpsim.formulas.absence.duration-days-min` | `2` | Absence duration, lower bound (game days). | Pflichtrollen-Abwesenheit |
| `rpsim.formulas.absence.duration-days-max` | `6` | Absence duration, upper bound. | Pflichtrollen-Abwesenheit |
| `rpsim.formulas.absence.substitute-probability` | `0.5` | Share of absences covered by a substitute character (else delayed replies with absence note). | Pflichtrollen-Abwesenheit |
| `rpsim.formulas.absence.delay-hours` | `24` | Extra delay of replies during an absence without substitute. | Pflichtrollen-Abwesenheit |

## `rpsim.formulas.village-life`

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.village-life.congratulation-trend-ratio` | `1.25` | Congratulation when the cash-flow trend exceeds the previous window by this ratio. | Dorfleben-Modul |
| `rpsim.formulas.village-life.congratulation-min-cashflow` | `1000` | …and the monthly cash flow is at least this amount. | Dorfleben-Modul |
| `rpsim.formulas.village-life.congratulation-cooldown-days` | `20` | Cool-down between congratulations. | Dorfleben-Modul |
| `rpsim.formulas.village-life.gossip-daily-probability` | `0.05` | Daily roll for village gossip. | Dorfleben-Modul |
| `rpsim.formulas.village-life.gossip-cooldown-days` | `3` | Cool-down between gossip messages. | Dorfleben-Modul |

## `rpsim.formulas.onboarding`

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.onboarding.dynamic-characters` | `5` | Dynamic village characters generated in the onboarding. | Onboarding & Zwei-Phasen-Verknüpfung |
| `rpsim.formulas.onboarding.story-hooks-min` | `1` | Story hooks from the backstory, lower bound (1–2). | Onboarding & Zwei-Phasen-Verknüpfung |
| `rpsim.formulas.onboarding.story-hooks-max` | `2` | Story hooks, upper bound. | Onboarding & Zwei-Phasen-Verknüpfung |
| `rpsim.formulas.onboarding.story-hook-spread-days-min` | `3` | Hooks arrive staggered: earliest game day. | Onboarding & Zwei-Phasen-Verknüpfung |
| `rpsim.formulas.onboarding.story-hook-spread-days-max` | `21` | Latest game day of a story hook. | Onboarding & Zwei-Phasen-Verknüpfung |
| `rpsim.formulas.onboarding.max-free-text-length` | `2000` | Maximum length of the backstory free text. | Moderation |

## `rpsim.formulas.calls`

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.calls.ring-timeout-game-minutes` | `120` | Ring timeout in game minutes (RINGING → MISSED). | Anruf-Zustandsautomat |

## `rpsim.formulas.messages`

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.messages.pacing-cooldown-days` | `1` | Pacing: per character only one proactive message per n game days counts for trust (the answer always comes). | Proaktive Nachricht |

## `rpsim.formulas.tone`

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.tone.friendly-delta` | `1` | Trust delta of a friendly player message (tone lexicon). | Proaktive Nachricht |
| `rpsim.formulas.tone.rude-delta` | `-2` | Trust delta of a rude player message. | Proaktive Nachricht |
| `rpsim.formulas.tone.cap` | `2` | Cap of the tone delta per message. | Proaktive Nachricht |

## `rpsim.formulas.memory`

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.memory.max-facts` | `8` | Condensed core facts per character in the memory prompt block. | Vier Bausteine im Prompt |

## `rpsim.formulas.storage`

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.storage.price-unit-liters` | `1000` | Price unit of the exported prices (€ per 1000 l). | Warenbestand-Bewertung |
| `rpsim.formulas.storage.history-max-points` | `500` | Max. points per series of `GET /api/prices/history` (down-sampling). | Silo-Warenbestand |

## `rpsim.formulas.insurance` (TODO T-20)

Storms and hail are simulated (no game event is read). A damage always costs money (`DAMAGE`); with an active,
paid-up insurance the player reports it within the deadline and receives `round(damage × coverage-rate) − deductible`
(`INSURANCE_PAYOUT`). Monthly premium = insured value × `premium-rate` (at least `min-premium`); insured value =
reference prices of the own fields + value of the own buildings.

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.insurance.storm-probability-per-month` | `0.15` | Chance per game month (FS25 period) of a storm damage, only in `storm-periods`. | TODO T-20 |
| `rpsim.formulas.insurance.storm-periods` | `[7, 8, 9, 10, 11, 12]` | FS25 periods with storms (1 = March): September to February. | TODO T-20 |
| `rpsim.formulas.insurance.storm-damage-share-min` | `0.005` | Storm damage as share of the building value (lower bound). | TODO T-20 |
| `rpsim.formulas.insurance.storm-damage-share-max` | `0.03` | Upper bound. | TODO T-20 |
| `rpsim.formulas.insurance.hail-probability-per-month` | `0.2` | Chance per game month of hail on one own field, only in `hail-periods`. | TODO T-20 |
| `rpsim.formulas.insurance.hail-periods` | `[3, 4, 5, 6]` | FS25 periods with hail: May to August. | TODO T-20 |
| `rpsim.formulas.insurance.hail-damage-per-hectare-min` | `200` | Hail damage in € per hectare of the hit field (lower bound). | TODO T-20 |
| `rpsim.formulas.insurance.hail-damage-per-hectare-max` | `900` | Upper bound. | TODO T-20 |
| `rpsim.formulas.insurance.hail-damage-share-min` | `0.05` | With the field export hail hits only standing crops: damage = harvest value (area × yield × best price) × a share between min and max; without yield or price the per-hectare range applies. | Roadmap V2 R2-C3 |
| `rpsim.formulas.insurance.hail-damage-share-max` | `0.3` | Upper bound. | Roadmap V2 R2-C3 |
| `rpsim.formulas.insurance.hail-rain-factor` | `1.0` | Hail probability × (1 + factor × rain share of the game month that just ended). | Roadmap V2 R2-C3 |
| `rpsim.formulas.insurance.report-deadline-days` | `5` | Game days to report a damage to the insurance; afterwards no payout. | TODO T-20 |
| `rpsim.formulas.insurance.settlement-delay-days-min` | `1` | Game days between report and payout (lower bound). | TODO T-20 |
| `rpsim.formulas.insurance.settlement-delay-days-max` | `3` | Upper bound. | TODO T-20 |
| `rpsim.formulas.insurance.first-offer-after-days` | `3` | Proactive offer of the insurance agent this many game days after the first farm export. | TODO T-20 |
| `rpsim.formulas.insurance.offer-valid-days` | `7` | Validity of an insurance offer (game days). | TODO T-20 |
| `rpsim.formulas.insurance.reoffer-cooldown-days` | `30` | After an uninsured damage the agent offers again at most once per this many game days. | TODO T-20 |
| `rpsim.formulas.insurance.cancel-after-missed-payments` | `2` | The insurance ends after this many unpaid premiums; while a premium is open the cover is suspended. | TODO T-20 |
| `rpsim.formulas.insurance.levels.BASIC.coverage-rate` | `0.6` | Tariff *Basis*: reimbursed share of a damage. | TODO T-20 |
| `rpsim.formulas.insurance.levels.BASIC.deductible` | `2000` | Tariff *Basis*: deductible per damage (€). | TODO T-20 |
| `rpsim.formulas.insurance.levels.BASIC.premium-rate` | `0.00025` | Tariff *Basis*: monthly premium per € of insured value. | TODO T-20 |
| `rpsim.formulas.insurance.levels.BASIC.min-premium` | `50` | Tariff *Basis*: minimum monthly premium (€). | TODO T-20 |
| `rpsim.formulas.insurance.levels.COMFORT.coverage-rate` | `0.9` | Tariff *Komfort*: reimbursed share. | TODO T-20 |
| `rpsim.formulas.insurance.levels.COMFORT.deductible` | `500` | Tariff *Komfort*: deductible (€). | TODO T-20 |
| `rpsim.formulas.insurance.levels.COMFORT.premium-rate` | `0.00075` | Tariff *Komfort*: monthly premium per € of insured value. | TODO T-20 |
| `rpsim.formulas.insurance.levels.COMFORT.min-premium` | `100` | Tariff *Komfort*: minimum monthly premium (€). | TODO T-20 |

## `rpsim.formulas.hunting` (TODO T-20)

Wild boar damage is simulated per game month (`DAMAGE`); the hunter compensates (`WILDLIFE_COMPENSATION`).

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.hunting.probability-per-month` | `0.15` | Chance per game month of wild boar damage on one own field, only in `periods`. | TODO T-20 |
| `rpsim.formulas.hunting.periods` | `[4, 5, 6, 7, 8]` | FS25 periods with wildlife damage (1 = March): June to October. | TODO T-20 |
| `rpsim.formulas.hunting.damage-per-hectare-min` | `150` | Damage in € per hectare (lower bound). | TODO T-20 |
| `rpsim.formulas.hunting.damage-per-hectare-max` | `600` | Upper bound. | TODO T-20 |
| `rpsim.formulas.hunting.crops` | `[MAIZE, WHEAT, BARLEY, OAT, POTATO]` | With the field export wild boars only damage standing crops of these FS25 fruit types; the damage scales with the growth progress. | Roadmap V2 R2-C3 |
| `rpsim.formulas.hunting.offer-share` | `0.5` | First compensation offer of the hunter as share of the damage (neutral trust). | TODO T-20 |
| `rpsim.formulas.hunting.max-share` | `0.9` | Highest share the hunter accepts on a counter demand (neutral trust); never shown to the AI. | TODO T-20 |
| `rpsim.formulas.hunting.trust-influence` | `0.2` | Shift of both shares at trust +100 / −100 (linear, bounded to 10–100 %). | TODO T-20 |
| `rpsim.formulas.hunting.max-rounds` | `2` | Counter demands before the offer is final. | TODO T-20 |
| `rpsim.formulas.hunting.decision-days` | `7` | Game days to answer; afterwards the last offer is paid. | TODO T-20 |
| `rpsim.formulas.hunting.measure-cost` | `400` | Own contribution of the player to a joint measure (drive hunt / fence), €. | TODO T-20 |
| `rpsim.formulas.hunting.measure-reputation-delta` | `3` | Village reputation (public action) of a joint measure. | TODO T-20 |
| `rpsim.formulas.hunting.measure-trust-delta` | `5` | Trust of the hunter for a joint measure. | TODO T-20 |
| `rpsim.formulas.hunting.measure-probability-factor` | `0.4` | Damage probability × this factor after a joint measure … | TODO T-20 |
| `rpsim.formulas.hunting.measure-effect-months` | `6` | … for this many game months. | TODO T-20 |
| `rpsim.formulas.hunting.agreement-trust-delta` | `2` | Trust of the hunter when an offer / demand is agreed. | TODO T-20 |
| `rpsim.formulas.hunting.dispute-trust-delta` | `-5` | Trust of the hunter when the compensation is refused. | TODO T-20 |
| `rpsim.formulas.hunting.dispute-reputation-delta` | `-2` | Village reputation (public action) of a refused compensation. | TODO T-20 |

## `rpsim.formulas.livestock` (TODO T-20)

Only active while the export contains animals (`assets.animals`). There is no verified mod API to add or remove
animals: the player trades them in the game; the trader pays a premium (`LIVESTOCK_PREMIUM`) per animal by which the
exported head count changed in the agreed direction. Vet invoices are booked as `VET_INVOICE`.

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.livestock.vet-visit-every-months` | `3` | Routine visit of the vet every n game months per animal type. | TODO T-20 |
| `rpsim.formulas.livestock.vet-base-fee` | `80` | Invoice per visit: base fee (€) … | TODO T-20 |
| `rpsim.formulas.livestock.vet-fee-per-animal` | `4` | … plus this amount per animal of the type (€). | TODO T-20 |
| `rpsim.formulas.livestock.trader-probability-per-month` | `0.25` | Chance per game month of an offer of the livestock trader. | TODO T-20 |
| `rpsim.formulas.livestock.trader-buy-share` | `0.3` | Share of offers where the player should buy animals (the rest are sell offers). | TODO T-20 |
| `rpsim.formulas.livestock.trader-max-herd-share` | `0.3` | Sell offers: at most this share of the herd; no offer when that is below `trader-quantity-min`. | TODO T-20 |
| `rpsim.formulas.livestock.trader-quantity-min` | `2` | Animals per offer (lower bound). | TODO T-20 |
| `rpsim.formulas.livestock.trader-quantity-max` | `6` | Animals per offer (upper bound). | TODO T-20 |
| `rpsim.formulas.livestock.trader-premium-share-min` | `0.05` | Premium per animal as share of the exported value per animal (lower bound, rounded to 10 €, at least 10 €). | TODO T-20 |
| `rpsim.formulas.livestock.trader-premium-share-max` | `0.12` | Upper bound. | TODO T-20 |
| `rpsim.formulas.livestock.trader-answer-days` | `5` | Game days to answer an offer, afterwards it expires. | TODO T-20 |
| `rpsim.formulas.livestock.trader-deadline-months` | `1` | Game months to carry out an accepted offer in the game; afterwards moved animals are paid (partial) or the offer lapses. | TODO T-20 |
| `rpsim.formulas.livestock.breeding-advice-every-months` | `6` | Advice of the breeding advisor every n game months per animal type (head count development since the last advice). | TODO T-20 |
| `rpsim.formulas.livestock.vet-emergency-health-threshold` | `40` | Roadmap V2 R2-A7: a husbandry below this health (0..100, `farm_facts.husbandries`) brings the vet for an emergency visit. | Roadmap V2 R2-A7 |
| `rpsim.formulas.livestock.vet-emergency-factor` | `2.5` | Invoice of the emergency visit = routine invoice (base fee + fee per animal) × factor (`VET_INVOICE`). | Roadmap V2 R2-A7 |
| `rpsim.formulas.livestock.vet-emergency-cooldown-days` | `5` | At most one emergency visit per husbandry within this many game days. | Roadmap V2 R2-A7 |
| `rpsim.formulas.livestock.keeper-food-warning-ratio` | `0.2` | An employed animal keeper warns by mail when the food of a husbandry falls below this ratio … | Roadmap V2 R2-A7 |
| `rpsim.formulas.livestock.keeper-water-warning-ratio` | `0.2` | … or its water condition below this ratio. | Roadmap V2 R2-A7 |
| `rpsim.formulas.livestock.keeper-warning-cooldown-days` | `3` | At most one warning mail of the keeper within this many game days. | Roadmap V2 R2-A7 |
| `rpsim.formulas.livestock.water-condition-titles` | `[Wasser, Water]` | Titles of the water entry in `husbandries[].conditions` - the game shows the localised fill type title, so the list must contain the title of the game language (manual test plan 10.12). | Roadmap V2 R2-A7 |

## `rpsim.formulas.energy` (TODO T-20)

The energy supplier uses the existing market mechanics (fixed-price contract = `PRICE_EVENT FIXED`, price fluctuation =
`PRICE_EVENT MULTIPLIER`; bands, premiums and quantities from `rpsim.formulas.market`), restricted to sell points of
the map that accept one of the configured fill types. Without such a sell point in `market_context.json` the energy
supplier does not appear.

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.energy.fill-types` | `[METHANE, SILAGE, CHAFF, MANURE, LIQUIDMANURE, DIGESTATE]` | Fill types the energy supplier buys (FS25 names). Whether a map sells them at a `SellingStation` is checked in the game (manual test plan 8.15). | TODO T-20 |
| `rpsim.formulas.energy.probability-per-month` | `0.35` | Chance per game month of a new offer. | TODO T-20 |
| `rpsim.formulas.energy.max-open` | `1` | Open offers / price events of the energy supplier at the same time. | TODO T-20 |
| `rpsim.formulas.energy.contract-share` | `0.6` | Share of fixed-price contracts (needs a current price of the pair), the rest are price fluctuations. | TODO T-20 |
| `rpsim.formulas.energy.spike-share` | `0.5` | Share of rising prices (`DEMAND_SPIKE`) among the fluctuations, the rest `DEMAND_SLUMP`. | TODO T-20 |

## `rpsim.formulas.lease` (TODO T-22)

Lease of NPC fields (not in vanilla): the rent is booked monthly as `LEASE_PAYMENT`; the field is given to the player
in the game (`FARMLAND_TRANSFER TO_PLAYER`) and goes back automatically at the end (`FROM_PLAYER`). In the tool the
owner character keeps the field (`leasedToPlayer`).

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.lease.annual-rent-share` | `0.05` | Yearly rent as share of the reference price (monthly rent = price × share / 12, rounded to 10 €). | TODO T-22 |
| `rpsim.formulas.lease.trust-influence` | `0.1` | Rent −10 % at trust +100, +10 % at −100 (linear). | TODO T-22 |
| `rpsim.formulas.lease.accept-probability` | `0.8` | Chance that the owner agrees to lease at neutral trust … | TODO T-22 |
| `rpsim.formulas.lease.accept-trust-influence` | `0.2` | … shifted by this much at trust +100 / −100. | TODO T-22 |
| `rpsim.formulas.lease.term-months` | `12` | Term of a lease and of a renewal in game months. | TODO T-22 |
| `rpsim.formulas.lease.offer-valid-days` | `7` | Game days to accept a lease offer. | TODO T-22 |
| `rpsim.formulas.lease.warning-months` | `1` | The owner writes this many game months before the end (renewal and purchase offer). | TODO T-22 |
| `rpsim.formulas.lease.renewal-factor-min` | `0.95` | Rent of a renewal = current rent × random factor (lower bound) … | TODO T-22 |
| `rpsim.formulas.lease.renewal-factor-max` | `1.1` | … upper bound. | TODO T-22 |
| `rpsim.formulas.lease.purchase-factor` | `1.05` | Purchase offer of a sell-willing owner: reference price × factor (booked as `FARMLAND_PURCHASE`). | TODO T-22 |
| `rpsim.formulas.lease.cancel-after-missed-payments` | `2` | The field goes back early after this many missed rents. | TODO T-22 |

## `rpsim.formulas.maintenance` (TODO T-22)

Maintenance contract of the workshop: monthly fee `MAINTENANCE_FEE`; while it is paid, the workshop repairs the most
worn own vehicles at every month start (`REPAIR_VEHICLE` → `Wearable:setDamageAmount(0, true)` in the game). Without a
contract the workshop sends repair hints and one unsolicited offer.

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.maintenance.fee-rate` | `0.002` | Monthly fee = value of the own vehicles × rate (rounded to 10 €) … | TODO T-22 |
| `rpsim.formulas.maintenance.min-fee` | `60` | … but at least this amount (€). | TODO T-22 |
| `rpsim.formulas.maintenance.offer-valid-days` | `7` | Game days to accept an offer. | TODO T-22 |
| `rpsim.formulas.maintenance.repair-below-condition` | `70` | Vehicles below this condition (0-100) are repaired at the monthly service … | TODO T-22 |
| `rpsim.formulas.maintenance.max-repairs-per-month` | `3` | … at most this many per game month, the most worn first. | TODO T-22 |
| `rpsim.formulas.maintenance.hint-below-condition` | `50` | Without contract: repair hint for the most worn vehicle below this condition … | TODO T-22 |
| `rpsim.formulas.maintenance.hint-every-months` | `3` | … at most every n game months. | TODO T-22 |
| `rpsim.formulas.maintenance.first-offer-below-condition` | `75` | One unsolicited offer when a vehicle is below this condition and there never was a contract. | TODO T-22 |
| `rpsim.formulas.maintenance.cancel-after-missed-payments` | `2` | The contract ends after this many missed fees (no repairs while a fee is open). | TODO T-22 |

## `rpsim.formulas.production-supply` (TODO T-22)

Delivery contracts with production points of the map: the existing fixed-price contract (`PRICE_EVENT FIXED`, premium,
quantity and deadline from `rpsim.formulas.market`) at sell points that `market_context.json` marks as `production`
(not the player's own).

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.production-supply.probability-per-month` | `0.3` | Chance per game month of a delivery contract offer. | TODO T-22 |
| `rpsim.formulas.production-supply.max-open` | `1` | Open delivery contract offers / contracts with productions at the same time. | TODO T-22 |

## `rpsim.formulas.contractor` (TODO T-22)

The contractor refers vanilla contracts of the game (`farm_facts.missions`, from `g_missionManager:getMissions()`); the
player takes them in the game's contracts menu. Nothing is started by the tool, so FS25_BetterContracts keeps working.

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.contractor.referral-probability` | `0.35` | Chance that a newly available contract is referred (decided once per contract) … | TODO T-22 |
| `rpsim.formulas.contractor.max-referrals-per-month` | `2` | … at most this many referrals per game month. | TODO T-22 |
| `rpsim.formulas.contractor.completed-trust-delta` | `3` | Trust of the contractor when a referred contract is completed. | TODO T-22 |
| `rpsim.formulas.contractor.client-trust-delta` | `2` | Trust of the client (FS25 NPC as village character, T-21) for a completed referred contract. | TODO T-22 |
| `rpsim.formulas.contractor.failed-trust-delta` | `-3` | Trust of the contractor when a referred contract fails. | TODO T-22 |

## `rpsim.formulas.neighbor-trade` (Roadmap V3 R3-H2..H4)

Trade with the neighbours (owner decisions, placeholders). Stock and needs of a neighbour are backend fiction derived from
his real fields in the game (`farm_facts.npcFields`, R3-H1); the goods move for real in the own silos (`STORAGE_TRANSFER`
+ `MONEY_TRANSACTION` as one batch). Only goods an own silo accepts are traded (`farm_facts.tradeStorage`).

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.neighbor-trade.roles.DAIRY` | `[STRAW, SILAGE, DRYGRASS_WINDROW]` | Needs of a dairy farm (Milchviehbetrieb). A role is rolled when the neighbour is created (older neighbours: when first needed). | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.roles.ARABLE` | `[SEEDS, FERTILIZER, LIQUIDFERTILIZER]` | Needs of an arable farm (Ackerbau). | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.roles.MIXED` | `[STRAW, SEEDS]` | Needs of a mixed farm (Gemischtbetrieb). | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.neighbor-sell-share` | `1.05` | The neighbour sells at this share of the price per 1000 l (best sell point price, else reference price). | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.neighbor-buy-share` | `0.95` | The neighbour buys at this share of the price. | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.trust-divisor` | `20` | Price bonus / malus from trust = trust / trust-divisor (in the player's favour) … | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.trust-cap` | `0.05` | … capped at ± this share (like the negotiation engine). | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.harvest-share` | `0.3` | Share of a neighbour's harvest (hectares × 10,000 × litersPerSqm) that goes into his stock. | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.by-products.WHEAT` | `STRAW` | By-product of a wheat harvest. | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.by-products.BARLEY` | `STRAW` | By-product of a barley harvest. | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.by-products.OAT` | `STRAW` | By-product of an oat harvest. | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.by-product-share` | `0.5` | By-product litres = this share of the grain litres that went into the stock. | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.monthly-decay` | `0.2` | The stock of every neighbour sinks by this share at the start of every game month (sales, own use). | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.reference-prices.STRAW` | `120` | € per 1000 l for straw when no sell point of the map buys it. | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.reference-prices.SILAGE` | `180` | € per 1000 l for silage without a sell point. | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.reference-prices.DRYGRASS_WINDROW` | `250` | € per 1000 l for hay without a sell point. | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.reference-prices.SEEDS` | `900` | € per 1000 l for seeds without a sell point. | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.reference-prices.FERTILIZER` | `1500` | € per 1000 l for fertiliser without a sell point. | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.reference-prices.LIQUIDFERTILIZER` | `1200` | € per 1000 l for liquid fertiliser without a sell point. | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.max-messages-per-month` | `2` | Offers and requests the neighbours send on their own per game month (the player's own requests do not count). | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.offer-probability-per-month` | `0.3` | Chance per game month that a neighbour offers goods of his stock the player has room for (R3-H3). | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.request-probability-per-month` | `0.3` | Chance per game month that a neighbour asks for goods of his needs the player has in his silos (R3-H4). | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.amount-min` | `2000` | Smallest amount (litres) of an offer or request. | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.amount-max` | `10000` | Largest amount (litres). | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.amount-step` | `500` | Amounts are multiples of this (litres). | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.max-share` | `0.5` | At most this share of the neighbour's stock (offer) or of the player's stock (request). | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.answer-days` | `5` | Game days to answer; the price holds that long. | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.trade-trust-delta` | `2` | Trust of the neighbour when a trade is done. | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.decline-trust-delta` | `-1` | Trust when the player declines an offer or request. | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.ignore-trust-delta` | `-2` | Trust when the player lets the deadline pass. | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.stock-missing-trust-delta` | `-1` | R3-H4: the goods were no longer in the silo when the sale was executed (`INSUFFICIENT_STOCK`). | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.reputation-delta` | `1` | Village reputation (`NEIGHBOR_HELP`) per fulfilled request of a neighbour … | Roadmap V3 R3-H |
| `rpsim.formulas.neighbor-trade.reputation-max-per-year` | `3` | … at most this many times per FS25 year. | Roadmap V3 R3-H |

## `rpsim.formulas.neighbor-missions` (Roadmap V3 R3-H5)

Neighbours ask for help with a real contract of the game on their own field (`MISSION_CREATE`); the contract appears in
the game's contract menu with the neighbour as client.

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.formulas.neighbor-missions.types` | `[PLOW, STONE_PICK]` | Contract types the neighbours offer (evidenced in the LUADOC; the mod maps them to `PlowMission` / `StonePickMission`). PLOW = harvested field with `plowLevel` 0, STONE_PICK = `stoneLevel` ≥ `fields.stone-high-level`. | Roadmap V3 R3-H5 |
| `rpsim.formulas.neighbor-missions.probability-per-month` | `0.4` | Chance per game month that a neighbour asks for help (only below the game's contract limit, `farm_facts.missionLimitReached`). | Roadmap V3 R3-H5 |
| `rpsim.formulas.neighbor-missions.max-per-month` | `1` | Requests of the neighbours on their own per game month. | Roadmap V3 R3-H5 |
| `rpsim.formulas.neighbor-missions.answer-days` | `5` | Game days to answer a request. | Roadmap V3 R3-H5 |
| `rpsim.formulas.neighbor-missions.success-bonus` | `250` | Bonus of the neighbour (€, `MONEY_TRANSACTION` `OTHER`) when the contract finished successfully; the game pays its own reward. | Roadmap V3 R3-H5 |
| `rpsim.formulas.neighbor-missions.success-trust-delta` | `3` | Trust of the neighbour when the contract finished successfully. | Roadmap V3 R3-H5 |
| `rpsim.formulas.neighbor-missions.failure-trust-delta` | `-3` | Trust when the contract failed or expired in the game. | Roadmap V3 R3-H5 |

## `rpsim.formulas.mechanic` (Roadmap V2 R2-A6)

An employed mechanic repairs part of the machines at the start of every game month, after the maintenance contract and
never the same vehicle twice. Capacity = `repair-points-per-month` × skill / 100 × effectMultiplier condition points,
spent on the most worn own vehicles below `repair-below-condition` (sent as `REPAIR_VEHICLE` with `targetDamage`).

| Key | Default | Meaning | Source |
| --- | --- | --- | --- |
| `rpsim.formulas.mechanic.repair-points-per-month` | `60` | Condition points a mechanic with skill 100 and full satisfaction repairs per game month. | Roadmap V2 R2-A6 |
| `rpsim.formulas.mechanic.repair-below-condition` | `90` | Only vehicles below this condition (0..100) are repaired. | Roadmap V2 R2-A6 |
| `rpsim.formulas.mechanic.overload-workload-per-vehicle` | `2` | Workload points the mechanic loses per vehicle still below the threshold after the month's repairs. | Roadmap V2 R2-A6 |

## `rpsim.formulas.vanilla-bypass` (Roadmap V2 R2-D)

The vanilla loan (finance menu) and the field menu of FS25 stay open (V1: recognise and warn, switch nothing off) - the
characters react instead. The player can switch the reactions off per savegame on the settings page. All values are
placeholders.

| Key | Default | Meaning | Source |
| --- | --- | --- | --- |
| `rpsim.formulas.vanilla-bypass.enabled` | `true` | Master switch of all reactions below (diary entries stay). | Roadmap V2 R2-D |
| `rpsim.formulas.vanilla-bypass.loan-min-increase` | `5000` | An increase of `liabilities.vanillaLoan.remainingAmount` by at least this amount (€, summed per game day) counts as a new vanilla loan; the bank advisor writes once per day. | Roadmap V2 R2-D1 |
| `rpsim.formulas.vanilla-bypass.loan-trust-per10k` | `1` | Trust loss of the bank advisor per 10,000 € taken. | Roadmap V2 R2-D1 |
| `rpsim.formulas.vanilla-bypass.loan-trust-max` | `8` | Cap of that trust loss per reaction. | Roadmap V2 R2-D1 |
| `rpsim.formulas.vanilla-bypass.loan-interest-surcharge` | `0.01` | From the second vanilla loan while one is open: added to the interest of new credits until the vanilla loan is repaid in full. | Roadmap V2 R2-D1 |
| `rpsim.formulas.vanilla-bypass.loan-min-repayment` | `5000` | A repayment of at least this amount (€, summed per game day) gets an answer of the bank. | Roadmap V2 R2-D1 |
| `rpsim.formulas.vanilla-bypass.loan-repaid-trust-delta` | `1` | Trust of the bank advisor per answered repayment. | Roadmap V2 R2-D1 |
| `rpsim.formulas.vanilla-bypass.field-trust-delta` | `-8` | Trust of the former owner when their field is bought in the field menu. | Roadmap V2 R2-D2 |
| `rpsim.formulas.vanilla-bypass.field-reputation-delta` | `-2` | Village reputation (public action `FIELD_BYPASS`) for the same. | Roadmap V2 R2-D2 |
| `rpsim.formulas.vanilla-bypass.compensation-share` | `0.1` | The former owner claims this share of the game price of the field (0 = no claim). | Roadmap V2 R2-D2 |
| `rpsim.formulas.vanilla-bypass.compensation-decision-days` | `7` | Game days to pay or refuse; no answer = refused. | Roadmap V2 R2-D2 |
| `rpsim.formulas.vanilla-bypass.compensation-decline-trust-delta` | `-5` | Additional trust loss when the claim is refused or ignored. | Roadmap V2 R2-D2 |
| `rpsim.formulas.vanilla-bypass.outside-helpers-hint` | `true` | One hint of the cooperative when a helper without employee runs (Roadmap V2 R2-D3). | Roadmap V2 R2-D3 |

## `rpsim.formulas.tax` (Roadmap V2 R2-E1)

Tax office and tax advisor. One assessment per FS25 year at the start of period 1 of the next year, only from the
complete months of the booking journal (R2-B; without a journal the year stays `NO_DATA`): profit = operating income +
operating expenses (without `excluded-categories`) - depreciation - interest part of the tool credit installments;
taxable = max(0, profit - allowance); tax = taxable × rate - advisor reduction; balance = tax - paid prepayments
(positive = bill, negative = refund `RPSIM_TAX_REFUND`). Prepayments at the start of periods 1, 4, 7 and 10 from the
last assessment. Bills are paid by button in the contracts page (owner decision), not debited automatically. The
difficulty `HARSH` uses the `hard-*` values. All values are placeholders, not a real tax law.

| Key | Default | Meaning | Source |
| --- | --- | --- | --- |
| `rpsim.formulas.tax.enabled` | `true` | Master switch of the tax office (assessment, prepayments, audits). | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.rate` | `0.25` | Flat tax rate on the taxable profit. | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.allowance` | `20000` | Tax-free allowance per FS25 year (€). | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.hard-rate` | `0.3` | Tax rate in the difficulty `HARSH`. | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.hard-allowance` | `10000` | Allowance in the difficulty `HARSH`. | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.depreciation-rate` | `0.1` | Yearly depreciation as a share of the exported value of vehicles and placeables (`assets`) at the assessment. | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.excluded-categories` | `[RPSIM_TAX_PAYMENT, RPSIM_TAX_REFUND, RPSIM_FINE]` | Journal categories that are not part of the tax base (owner decision: taxes and fines). | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.prepayment-share` | `1.0` | Share of the last assessed tax billed as prepayments, split into four quarters (0 = no prepayments). | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.payment-days` | `14` | Game days to pay a tax bill. | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.late-fee-rate` | `0.01` | Late fee per started game month after the deadline, as a share of the bill (added to the amount due). | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.enforcement-after-months` | `2` | Game months after the deadline until the tax office threatens enforcement (once per bill). | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.reminder-trust-delta` | `-2` | Trust of the tax office per reminder. | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.enforcement-trust-delta` | `-5` | Trust of the tax office at the enforcement threat. | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.advisor-monthly-fee` | `150` | Monthly fee of the tax advisor contract (booking `RPSIM_OTHER`). | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.advisor-tax-reduction` | `0.1` | With an active advisor contract: the tax is reduced by this share. | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.advisor-reminder-days` | `3` | The advisor reminds of an unpaid bill this many game days before its deadline. | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.advisor-audit-factor` | `0.5` | With an advisor the audit probability is multiplied by this factor. | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.advisor-offer-valid-days` | `7` | Game days the advisor offer stays open. | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.audit-probability` | `0.15` | Chance of a tax audit at every assessment. | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.audit-days` | `7` | Game days between the announcement and the result of the audit. | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.audit-jump-factor` | `2.0` | A month whose operating expenses exceed this factor × the monthly average of the year counts as a jump month; its excess over the average is disputed. | Roadmap V2 R2-E1 |
| `rpsim.formulas.tax.audit-disallowed-share` | `0.5` | Share of the disputed expenses (unknown categories + jump months, owner decision) the audit does not accept; back tax = disputed × share × rate. | Roadmap V2 R2-E1 |

## `rpsim.formulas.authority` (Roadmap V2 R2-E2)

The agricultural authority checks only what the export measures: crop rotation (crop history of C1), cultivation duty
(own field without crop with weeds or stones above `fields.weed-high-state` / `fields.stone-high-level`, only when the
savegame has them switched on) and animal welfare (`husbandries`: health, food, water). Inspections are announced and
decided after `inspection-days`, so the player can always react. All values are placeholders.

| Key | Default | Meaning | Source |
| --- | --- | --- | --- |
| `rpsim.formulas.authority.enabled` | `true` | Master switch of rotation premium and inspections. | Roadmap V2 R2-E2 |
| `rpsim.formulas.authority.rotation-premium-per-ha` | `40` | Rotation premium (booking `RPSIM_SUBSIDY`) per hectare of fields whose main crop differs from the year before, paid at the end of every FS25 year (owner decision: per hectare). | Roadmap V2 R2-E2 |
| `rpsim.formulas.authority.rotation-cut-share` | `0.5` | When a field repeats its crop for the second time, the whole premium of the year is cut by this share (owner decision); the first repetition only gets a notice. | Roadmap V2 R2-E2 |
| `rpsim.formulas.authority.duty-months` | `3` | Game months without crop (with weeds or stones) before a cultivation duty inspection is announced; also the gap after a decision. | Roadmap V2 R2-E2 |
| `rpsim.formulas.authority.duty-fine` | `500` | Fine (booking `RPSIM_FINE`) when the field is still violated at the deadline. | Roadmap V2 R2-E2 |
| `rpsim.formulas.authority.welfare-health-threshold` | `30` | A husbandry with animal health below this value (or empty food / water) counts as bad (same scale as the vet emergency of R2-A7). | Roadmap V2 R2-E2 |
| `rpsim.formulas.authority.welfare-days` | `3` | Game days a husbandry stays bad before an animal welfare inspection is announced. | Roadmap V2 R2-E2 |
| `rpsim.formulas.authority.welfare-fine` | `1000` | Fine of a repeated animal welfare violation (the first one brings a requirement with a new deadline). | Roadmap V2 R2-E2 |
| `rpsim.formulas.authority.welfare-reputation-delta` | `-3` | Village reputation (public action `AUTHORITY_FINE`) at an animal welfare fine. | Roadmap V2 R2-E2 |
| `rpsim.formulas.authority.inspection-days` | `5` | Game days between the announcement (or a requirement) and the decision. | Roadmap V2 R2-E2 |
| `rpsim.formulas.authority.violation-trust-delta` | `-3` | Trust of the authority per requirement or fine. | Roadmap V2 R2-E2 |
| `rpsim.formulas.authority.max-inspections-per-month` | `2` | Cap of new inspection announcements per game month. | Roadmap V2 R2-E2 |

## `rpsim.formulas.family` (Roadmap V2 R2-E3)

Family characters chosen in the onboarding (parents, partner, children - each switch on its own). They do not count as
villagers for the reputation and never leave the village. All values are placeholders.

| Key | Default | Meaning | Source |
| --- | --- | --- | --- |
| `rpsim.formulas.family.enabled` | `true` | Master switch of the monthly family events (retirement payment, occasions, harvest help, family field). | Roadmap V2 R2-E3 |
| `rpsim.formulas.family.retirement-payment` | `800` | Monthly retirement payment to the parents (booking `RPSIM_FAMILY`), only for the start stories "inherited" and "returned home" (owner decision). | Roadmap V2 R2-E3 |
| `rpsim.formulas.family.field-sold-trust-delta` | `-15` | Trust of every family member when the family field (chosen by the player) is no longer owned. | Roadmap V2 R2-E3 |
| `rpsim.formulas.family.harvest-periods` | `[6, 7, 8]` | FS25 periods (1 = March) in which a family member may offer help with the harvest. | Roadmap V2 R2-E3 |
| `rpsim.formulas.family.harvest-help-probability` | `0.5` | Chance per harvest period. | Roadmap V2 R2-E3 |
| `rpsim.formulas.family.harvest-help-trust-delta` | `2` | Trust of the helping family member. | Roadmap V2 R2-E3 |
| `rpsim.formulas.family.school-start-period` | `7` | FS25 period of the school start of the youngest child (occasion message). | Roadmap V2 R2-E3 |

## `rpsim.formulas.clubs` (Roadmap V2 R2-E4)

Clubs (one character with role `CLUB` per club, created when first needed) and the festival calendar. Replaces the
fixed invitation calendar `village-life.invitation-every-periods` of V1. All values are placeholders.

| Key | Default | Meaning | Source |
| --- | --- | --- | --- |
| `rpsim.formulas.clubs.enabled` | `true` | Master switch of invitations and sponsoring requests. | Roadmap V2 R2-E4 |
| `rpsim.formulas.clubs.festivals` | Maibaum 3, Schützenfest 4, Feuerwehrfest 6, Erntedankfest 8, Weihnachtsmarkt 10 | Festival calendar: `{ key, period, host }` - invitation at the start of the FS25 period (1 = March); the host is a club (`SHOOTING_CLUB`, `FIRE_BRIGADE`, `SPORTS_CLUB`) or a character role (`VILLAGER`, `COOPERATIVE`). | Roadmap V2 R2-E4 |
| `rpsim.formulas.clubs.invitation-days` | `5` | Game days to accept or decline an invitation; no answer = ignored. | Roadmap V2 R2-E4 |
| `rpsim.formulas.clubs.invitation-accept-trust-delta` | `2` | Trust of the host when the player accepts. | Roadmap V2 R2-E4 |
| `rpsim.formulas.clubs.invitation-ignore-trust-delta` | `-1` | Trust of the host when the invitation is ignored (declining costs nothing). | Roadmap V2 R2-E4 |
| `rpsim.formulas.clubs.sponsoring-probability-per-month` | `0.3` | Chance per game month that a club asks for sponsoring. | Roadmap V2 R2-E4 |
| `rpsim.formulas.clubs.sponsoring-cooldown-days` | `30` | Game days after the last request before the next one. | Roadmap V2 R2-E4 |
| `rpsim.formulas.clubs.sponsoring-tiers` | `[250, 500, 1000]` | Amounts the player can choose (booking `RPSIM_SPONSORING`); empty = no requests. | Roadmap V2 R2-E4 |
| `rpsim.formulas.clubs.sponsoring-reputation-per100` | `0.5` | Village reputation (public action `SPONSORING`) per 100 € sponsored. | Roadmap V2 R2-E4 |
| `rpsim.formulas.clubs.sponsoring-trust-delta` | `3` | Trust of the club when the player sponsors. | Roadmap V2 R2-E4 |
| `rpsim.formulas.clubs.sponsoring-decline-trust-delta` | `-1` | Trust of the club when the request is declined or expires. | Roadmap V2 R2-E4 |
| `rpsim.formulas.clubs.sponsoring-decision-days` | `7` | Game days to answer a sponsoring request. | Roadmap V2 R2-E4 |

## `rpsim.formulas.fields` (Roadmap V2 R2-C)

Fields, crops and weather from `farm_facts.fields` / `fieldRules` / `weather`. Growth phase: no crop = empty; the mod's
flags `withered` / `cut` (FS25 `getIsWithered` / `getIsCut`) decide withered and harvested; otherwise below
`minHarvestingGrowthState` growing, up to `max` harvestable, above `max` withered (mods without the flags). Weeds,
stones, lime and plowing are only evaluated when the savegame has them switched on (`fieldRules`). All values are
placeholders.

| Key | Default | Meaning | Source |
| --- | --- | --- | --- |
| `rpsim.formulas.fields.yield-liters-per-sqm` | `{}` | Yield in liters per m² per FS25 fruit type, only for mods that do not export `litersPerSqm`; empty = unknown (hail uses the per-hectare range, the bank counts no standing crop). | Roadmap V2 R2-C3 / C5 |
| `rpsim.formulas.fields.rain-sample-max-gap-minutes` | `180` | Rain hours are extrapolated from the weather samples (sample and hold); a gap above this many game minutes (backend was off) is not counted. | Roadmap V2 R2-C2 |
| `rpsim.formulas.fields.weed-high-state` | `5` | FS25 `weedState` from which the neighbor minds the weeds (🟡 manual test plan 10.15). | Roadmap V2 R2-C4 |
| `rpsim.formulas.fields.stone-high-level` | `2` | FS25 `stoneLevel` from which the neighbor minds the stones (🟡 manual test plan 10.15). | Roadmap V2 R2-C4 |
| `rpsim.formulas.fields.neighbor-after-months` | `2` | Game months weeds / stones stay high before the neighbor writes (friendly). | Roadmap V2 R2-C4 |
| `rpsim.formulas.fields.neighbor-repeat-months` | `1` | Game months after the friendly message before the annoyed one. | Roadmap V2 R2-C4 |
| `rpsim.formulas.fields.neighbor-trust-delta` | `-2` | Trust of the neighbor at the annoyed message ("nothing happened"). | Roadmap V2 R2-C4 |
| `rpsim.formulas.fields.fallow-gossip-months` | `4` | Game months without a crop (empty or stubble) before the village gossips about the field; a withered crop is talked about at once. | Roadmap V2 R2-C4 |
| `rpsim.formulas.fields.harvest-congratulation-trust-delta` | `1` | Trust of the cooperative when every harvestable field of an FS25 year was harvested and nothing withered. | Roadmap V2 R2-C4 |
| `rpsim.formulas.fields.max-messages-per-month` | `2` | Cap of the field messages (neighbor, gossip, congratulation) per game month. | Roadmap V2 R2-C4 |
| `rpsim.formulas.fields.hints-enabled` | `true` | Field work hints of the cooperative (harvest ready, lime, plowing); the player can also switch them off per savegame in the settings. | Roadmap V2 R2-C6 |
| `rpsim.formulas.fields.hint-cooldown-days` | `7` | At most one hint per this many game days. | Roadmap V2 R2-C6 |

## `rpsim.formulas.finance` (Roadmap V2 R2-B)

Real farm finances from the mod's booking journal (`farm_facts.finances`, sums per FS25 period and money type). Each
category belongs to exactly one class; the operating cash flow of the credit check is operating income + expenses of
the complete months in `credit.cashflow-window-days` (without a journal the V1 balance method stays). Only money type
names evidenced in the FS25 code or in the game's journal are listed (vehicle and field purchases were checked in the
game, [manual test plan 10.9 / 10.11](manual-test-plan.md#10-roadmap-v2-in-the-real-fs25)); `OTHER` (*Sonstiges*) stays
unlisted on purpose because it can be anything; unknown categories count as operating by their sign and are
logged once (add them here). Tool bookings arrive as `RPSIM_<REASON>`; their classes follow the V1 list of
non-operating reasons (`LiquidityService.NON_OPERATING`).

| Key | Default | Meaning | Source |
| --- | --- | --- | --- |
| `rpsim.formulas.finance.categories.HARVEST_INCOME` | `OPERATING_INCOME` | FS25 money type: operating income. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.SOLD_PRODUCTS` | `OPERATING_INCOME` | FS25 money type: operating income. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.MISSIONS` | `OPERATING_INCOME` | FS25 money type: operating income. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.PROPERTY_INCOME` | `OPERATING_INCOME` | FS25 money type: operating income. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.SOLD_ANIMALS` | `OPERATING_INCOME` | FS25 money type: operating income. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_EMPLOYEE_EFFECT` | `OPERATING_INCOME` | tool booking: operating income. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_SUBSIDY` | `OPERATING_INCOME` | tool booking: operating income. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_LIVESTOCK_PREMIUM` | `OPERATING_INCOME` | tool booking: operating income. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_TAX_REFUND` | `OPERATING_INCOME` | tool booking: operating income. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_LEASE_INCOME` | `OPERATING_INCOME` | tool booking: operating income (lease of an own field, R3-L1). | Roadmap V3 R3-Q1 |
| `rpsim.formulas.finance.categories.RPSIM_GOODS_SALE` | `OPERATING_INCOME` | tool booking: operating income (goods to a neighbour or the farm shop, R3-H4 / R3-M3). | Roadmap V3 R3-Q1 |
| `rpsim.formulas.finance.categories.PURCHASE_FUEL` | `OPERATING_EXPENSE` | FS25 money type: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.PURCHASE_SEEDS` | `OPERATING_EXPENSE` | FS25 money type: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.PURCHASE_FERTILIZER` | `OPERATING_EXPENSE` | FS25 money type: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.PURCHASE_WATER` | `OPERATING_EXPENSE` | FS25 money type: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.PURCHASE_PALLETS` | `OPERATING_EXPENSE` | FS25 money type: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.PURCHASE_CONSUMABLES` | `OPERATING_EXPENSE` | FS25 money type: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.BOUGHT_MATERIALS` | `OPERATING_EXPENSE` | FS25 money type: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.VEHICLE_RUNNING_COSTS` | `OPERATING_EXPENSE` | FS25 money type: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.VEHICLE_REPAIR` | `OPERATING_EXPENSE` | FS25 money type: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.LEASING_COSTS` | `OPERATING_EXPENSE` | FS25 money type: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.PROPERTY_MAINTENANCE` | `OPERATING_EXPENSE` | FS25 money type: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.AI` | `OPERATING_EXPENSE` | FS25 money type: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.NEW_ANIMALS_COST` | `OPERATING_EXPENSE` | FS25 money type: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_SALARY_PAYMENT` | `OPERATING_EXPENSE` | tool booking: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_INSURANCE_PREMIUM` | `OPERATING_EXPENSE` | tool booking: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_VET_INVOICE` | `OPERATING_EXPENSE` | tool booking: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_LEASE_PAYMENT` | `OPERATING_EXPENSE` | tool booking: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_MAINTENANCE_FEE` | `OPERATING_EXPENSE` | tool booking: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_TAX_PAYMENT` | `OPERATING_EXPENSE` | tool booking: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_FINE` | `OPERATING_EXPENSE` | tool booking: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_FAMILY` | `OPERATING_EXPENSE` | tool booking: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_SPONSORING` | `OPERATING_EXPENSE` | tool booking: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_COMPENSATION` | `OPERATING_EXPENSE` | tool booking: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_TRAINING` | `OPERATING_EXPENSE` | tool booking: operating expense (training of a machine operator). | Schulungen |
| `rpsim.formulas.finance.categories.RPSIM_GOODS_PURCHASE` | `OPERATING_EXPENSE` | tool booking: operating expense (goods from a neighbour, R3-H3). | Roadmap V3 R3-Q1 |
| `rpsim.formulas.finance.categories.RPSIM_CONTRACT_PENALTY` | `OPERATING_EXPENSE` | tool booking: operating expense (shortfall of a forward contract, R3-M2). | Roadmap V3 R3-Q1 |
| `rpsim.formulas.finance.categories.RPSIM_OTHER` | `OPERATING_EXPENSE` | tool booking: operating expense. | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.SHOP_PROPERTY_BUY` | `INVESTMENT` | FS25 money type: investment (changes only the assets, not the cash flow). | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.SHOP_VEHICLE_BUY` | `INVESTMENT` | FS25 money type: vehicle purchase in the shop (seen in the game's journal), investment (changes only the assets, not the cash flow). | Roadmap V2 R2-B2, manual test 10.9 |
| `rpsim.formulas.finance.categories.FIELD_BUY` | `INVESTMENT` | FS25 money type: field purchase in the farmland menu (seen in the game's journal), investment (changes only the assets, not the cash flow). | Roadmap V2 R2-B2, manual test 10.11 |
| `rpsim.formulas.finance.categories.RPSIM_FARMLAND_PURCHASE` | `INVESTMENT` | tool booking: investment (changes only the assets, not the cash flow). | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_VEHICLE_PURCHASE` | `INVESTMENT` | tool booking: investment (used vehicle, R3-V2). | Roadmap V3 R3-Q1 |
| `rpsim.formulas.finance.categories.SHOP_VEHICLE_SELL` | `DIVESTMENT` | FS25 money type: divestment (not part of the cash flow). | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.SHOP_PROPERTY_SELL` | `DIVESTMENT` | FS25 money type: sale of a building (counterpart of `SHOP_PROPERTY_BUY`), divestment (not part of the cash flow). | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.FIELD_SELL` | `DIVESTMENT` | FS25 money type: field sale in the farmland menu (counterpart of `FIELD_BUY`), divestment (not part of the cash flow). | Roadmap V2 R2-B2, manual test 10.11 |
| `rpsim.formulas.finance.categories.RPSIM_FARMLAND_SALE` | `DIVESTMENT` | tool booking: divestment (not part of the cash flow). | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_VEHICLE_SALE` | `DIVESTMENT` | tool booking: divestment (own vehicle to a neighbour, R3-V3). | Roadmap V3 R3-Q1 |
| `rpsim.formulas.finance.categories.RPSIM_CREDIT_DISBURSEMENT` | `FINANCING` | tool booking: financing (not part of the cash flow). | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_CREDIT_INSTALLMENT` | `FINANCING` | tool booking: financing (not part of the cash flow). | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_CREDIT_PENALTY` | `FINANCING` | tool booking: financing (not part of the cash flow). | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_CREDIT_CALLBACK` | `FINANCING` | tool booking: financing (not part of the cash flow). | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_CREDIT_SPECIAL_REPAYMENT` | `FINANCING` | tool booking: financing (not part of the cash flow). | Sondertilgung |
| `rpsim.formulas.finance.categories.RPSIM_CREDIT_PREPAYMENT_FEE` | `FINANCING` | tool booking: financing (not part of the cash flow). | Sondertilgung |
| `rpsim.formulas.finance.categories.RPSIM_STARTING_CAPITAL_ADJUSTMENT` | `FINANCING` | tool booking: financing (not part of the cash flow). | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_DAMAGE` | `IGNORE` | tool booking: ignored one-off booking (not part of the cash flow, as in V1). | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_INSURANCE_PAYOUT` | `IGNORE` | tool booking: ignored one-off booking (not part of the cash flow, as in V1). | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.categories.RPSIM_WILDLIFE_COMPENSATION` | `IGNORE` | tool booking: ignored one-off booking (not part of the cash flow, as in V1). | Roadmap V2 R2-B2 |
| `rpsim.formulas.finance.early-warning-enabled` | `true` | The bank writes before an installment fails when the operating result was negative for several months (R2-B5). | Roadmap V2 R2-B5 |
| `rpsim.formulas.finance.early-warning-negative-months` | `2` | Complete months in a row with a negative operating result; only while a bank loan runs, once per streak. | Roadmap V2 R2-B5 |
| `rpsim.formulas.finance.record-enabled` | `true` | The cooperative congratulates on the highest harvest revenue of a complete month since the start. | Roadmap V2 R2-B5 |
| `rpsim.formulas.finance.record-categories` | `[HARVEST_INCOME, SOLD_PRODUCTS]` | Money types that count as harvest revenue for the record. | Roadmap V2 R2-B5 |
| `rpsim.formulas.finance.record-min-months` | `3` | Complete months of history before a record counts. | Roadmap V2 R2-B5 |
| `rpsim.formulas.finance.record-trust-delta` | `2` | Trust of the cooperative for a record month. | Roadmap V2 R2-B5 |

## Profiles

| Profile | Purpose | Overrides |
| --- | --- | --- |
| `dev` (default) | local development against the bridge simulator | H2 file DB `backend/data/rpsim-dev`, bridge path = simulator runtime folder |
| `prod` | playing with FS25 (release `start` scripts) | H2 file DB `~/.rpsim/rpsim`, bridge path = FS25 `modSettings/FS25_RPSim`; `server.address` unset (Roadmap V3 R3-N1: the home-network filter decides by the sender address) |
| `e2e` | Playwright tests and screenshot generator | in-memory H2, AI provider `FAKE`, fast polling, bridge folder under `frontend/e2e/.runtime` |
| `test` (tests only) | unit/integration tests | bridge scheduler off, provider `NONE`, narration worker off |
