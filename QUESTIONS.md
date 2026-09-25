# Open questions to the project owner

Log of decisions that are not answered by `docs/concept/*`. Format: one row per question.

| Date | AP | Question | Proposed default | Status |
| --- | --- | --- | --- | --- |
| 2026-09-25 | AP-0.1 | License: the work-package plan proposes MIT unless the owner decides otherwise. | MIT (committed as `LICENSE`, copyright "Keno (DerGiftzwerg156)"). Please confirm or name another license. | open – default applied |
| 2026-09-25 | AP-3.1 / AP-4.5 | How many game days form one "game month" (salaries, installments, `EMPLOYEE_EFFECT`)? The FS25 period field is an open technical point. | `rpsim.time.game-days-per-month = 1` (FS25 default "days per period"); change in config if you play with more days per period. | open – default applied |
| 2026-09-25 | AP-4.2 | The concept leaves the normalisation of the five credit metrics open ("mit Sättigung"). Without a pro-forma view an absurd request (e.g. 50 Mio. on a 0.5 Mio. farm) still reached the counter-offer band because equity and missing cash-flow history dominate. | `equityRatio` is computed pro forma after financing: (assets − debt) / (assets + requested). Saturation = linear up to a configurable "full" ratio, then capped at 100. With no cash-flow history `debtServiceCoverage` uses the configurable placeholder `debt-service-coverage-no-history: 50`. | open – default applied |
