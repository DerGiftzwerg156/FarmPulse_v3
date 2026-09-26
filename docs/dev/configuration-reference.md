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

## `rpsim.web` – Web

| Key | Default | Meaning | Concept |
| --- | --- | --- | --- |
| `rpsim.web.static-dir` | `""` | Folder of the built Angular app. When set (release: `web/`) the backend serves it on `/` with an SPA fallback. Empty = API only. | – |

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
| `rpsim.formulas.village-life.invitation-every-periods` | `6` | Invitation calendar: an invitation on the first day of every n-th FS25 period of the year, counted from period 1 = March (6 → March and September; 0 = off). | Dorfleben-Modul |
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

## Profiles

| Profile | Purpose | Overrides |
| --- | --- | --- |
| `dev` (default) | local development against the bridge simulator | H2 file DB `backend/data/rpsim-dev`, bridge path = simulator runtime folder |
| `prod` | playing with FS25 (release `start` scripts) | H2 file DB `~/.rpsim/rpsim`, bridge path = FS25 `modSettings/FS25_RPSim`, `server.address=127.0.0.1` |
| `e2e` | Playwright tests and screenshot generator | in-memory H2, AI provider `FAKE`, fast polling, bridge folder under `frontend/e2e/.runtime` |
| `test` (tests only) | unit/integration tests | bridge scheduler off, provider `NONE`, narration worker off |
