package de.farmpulse.rpsim.api;

import java.util.List;

/** Response DTOs of the REST API. Raw formula values (scores, trust numbers, NPC limits) never appear here. */
public final class Views {

    private Views() {
    }

    /**
     * Header context. calendar (TODO T-08): FS25 period of the savegame, null before the first calendar export.
     * detectedMods (TODO T-09): installed mods with overlapping features (warning only).
     */
    public record SavegameView(Long id, String savegameId, String mapName, long gameTime, long gameDay, long balance,
                               String tonePreset, long unreadMails, long pendingCalls, String reputationTier,
                               CalendarView calendar, List<String> detectedMods, WeatherView weather) {
    }

    /**
     * Hof-Tablet status bar: weather of the last farm_facts (Roadmap V2 R2-C2). rain / groundWetness 0..1, temperature
     * in °C (null with an older mod). The whole view is null before the first weather export.
     */
    public record WeatherView(boolean raining, double rainFallScale, double groundWetness, Double temperature) {
    }

    /** FS25 calendar: period 1..12 (1 = March), periodName as shown in the game. */
    public record CalendarView(int period, String periodName, int dayInPeriod, int daysPerPeriod, Integer year,
                               String season) {
    }

    public record PreviewView(Long characterId, String name, String role, String category, String jobRole,
                              String description) {
    }

    public record OnboardingView(Long id, String status, boolean freeTextRejected, List<PreviewView> cast) {
    }

    public record DetectedView(String savegameId, String mapName, String firstSeen, String lastSeen, long gameTime) {
    }

    public record CharacterRef(Long id, String name, String role, String status) {
    }

    public record MessageView(Long id, Long threadRootId, String channel, String initiatedBy, CharacterRef character,
                              String subject, String body, long gameTime, boolean read, String category, String eventType,
                              String formLink, boolean usedFallback, String callStatus, Long ringDeadlineGameTime,
                              boolean openTopic, String relatedEntityType, Long relatedEntityId) {
    }

    public record ThreadView(MessageView message, List<MessageView> thread) {
    }

    /** Result of marking mails as read: how many were unread before. */
    public record MarkReadView(int marked) {
    }

    /**
     * Roadmap V3 R3-K1: {@code collateralFarmlandIds} chosen by the player, {@code proposedFarmlandIds} named by the bank
     * (counter offer "mit Grundschuld", {@code collateralRequired}); value, coverage and discount once decided.
     */
    public record CreditApplicationView(Long id, long amount, String purpose, int termMonths, String status,
                                        long submittedAtGameTime, long decisionVisibleAtGameTime, String decision,
                                        String reasonCategory, Long offeredAmount, Integer offeredTermMonths,
                                        Double offeredInterestRatePercent, Long loanId, List<Integer> collateralFarmlandIds,
                                        List<Integer> proposedFarmlandIds, Long collateralValue, Double coveragePercent,
                                        Double interestDiscountPercent, Boolean collateralRequired) {
    }

    /** Roadmap V3 R3-K1: a field pledged for a loan; {@code saleConsent} = the bank agreed to a sale. */
    public record CollateralView(int farmlandId, long collateralValue, String status, boolean saleConsent, Long loanId,
                                 String purpose, boolean leaseConsent) {
    }

    /**
     * Roadmap V3 R3-L1: leasing out own fields - the term range of the form and the lease-out contracts (newest
     * first). Amounts per ha and month are in the negotiations; the contracts carry the monthly rent.
     */
    public record LeaseOutView(int termYearsMin, int termYearsMax, List<ContractView> contracts) {
    }

    /** Roadmap V3 R3-K1: own fields for the credit form and the pledged fields. */
    public record CollateralOverviewView(double loanToValuePercent, double requiredAboveSharePercent,
                                         double maxInterestDiscountPercent, long requiredAboveAmount,
                                         List<CollateralOptionView> eligible, List<CollateralView> pledged) {
    }

    public record CollateralOptionView(int farmlandId, double hectares, long price, long collateralValue) {
    }

    public record LoanPaymentView(long gameTime, long amount, String type) {
    }

    public record LoanView(Long id, long principal, long remainingAmount, double interestRatePercent, int termMonths,
                           long monthlyInstallment, String purpose, String status, boolean legacy, boolean blocksNewCredit,
                           long nextDueGameTime, boolean overdue, int escalationLevel, int missedInstallments,
                           int paidInstallments, Long deferredUntilGameTime, List<LoanPaymentView> history,
                           int remainingInstallments, SpecialRepaymentTermsView specialRepayment,
                           List<CollateralView> collateral, double rateCutTotalPercent) {
    }

    public record DeferralView(boolean granted, String reasonCategory) {
    }

    /**
     * Sondertilgung conditions right now: {@code refusal} = null when allowed (else LOAN_NOT_ACTIVE / LOAN_DEFERRED /
     * LOAN_OVERDUE), fee-free amount left in this FS25 year, fee rate above it, pro-rata interest of a full repayment.
     */
    public record SpecialRepaymentTermsView(boolean allowed, String refusal, long freeAmountLeft, double feeRatePercent,
                                            long payoffInterest) {
    }

    public record SpecialRepaymentView(long amount, long interest, long fee, long remainingAmount,
                                       int remainingInstallments, boolean paidOff) {
    }

    /** applicationsAwaited = applications still on their way (they arrive the next game day, owner decision 2026-10-06). */
    public record JobPostingView(Long id, String jobRole, String status, long createdAtGameTime, Long filledEmployeeId,
                                 boolean applicationsAwaited) {
    }

    /** training = the training a machine operator applicant brings along ("Schulungen"), null = none. */
    public record ApplicationView(Long id, CharacterRef applicant, String description, int skill, long expectedSalary,
                                  String status, String training) {
    }

    /** "Schulungen": one training of the catalog with its price and the FS25 shop categories it unlocks. */
    public record TrainingOfferView(String training, long cost, List<String> vehicleCategories) {
    }

    public record NeedsView(double payFairness, double workload, double appreciation, double workingConditions,
                            double satisfaction, double effectiveSkill) {
    }

    /**
     * Roadmap V2 R2-A: onStrike (A5); hoursThisMonth / hoursLastMonth = hours driven as FS25 helper (A4), null when the mod
     * reports no worked time or the employee is no machine operator. "Schulungen": finished trainings, the running one
     * and its end (the employee is away until then). Roadmap V3.1 R31-A5: contractEndsAtGameTime = end of a seasonal
     * worker's fixed-term contract. Owner decisions 2026-10-06: trainingFromGameTime = start of the absence for the booked
     * training (the next game day); startsAtGameTime / startsAtPeriod = first working day (FS25 period, 1 = March) of a
     * PENDING_START employee and severance = what cancelling him costs (null otherwise).
     */
    public record EmployeeView(Long id, CharacterRef character, String jobRole, int skill, long monthlySalary, String status,
                               NeedsView needs, boolean warningSent, boolean salaryOverdue, Long timeOffUntilGameTime,
                               boolean onStrike, Double hoursThisMonth, Double hoursLastMonth, List<String> trainings,
                               String trainingInProgress, Long trainingUntilGameTime, Long apprenticeshipEndsAtGameTime,
                               Long contractEndsAtGameTime, String absenceKind, Long absenceUntilGameTime,
                               boolean getWellSent, Long trainingFromGameTime, Long startsAtGameTime, Integer startsAtPeriod,
                               Long severance) {
    }

    /** Roadmap V2 R2-A1 / R2-A3: helper switches of the savegame; workforceTracked = the mod reports helper jobs. */
    public record HelperSettingsView(String helperWageMode, boolean strictHelperLimit, boolean workforceTracked) {
    }

    /** Roadmap V2 R2-C: fruitType and phase (EMPTY, GROWING, HARVESTABLE, HARVESTED, WITHERED) of own fields only. */
    public record FarmlandView(int farmlandId, double hectares, long referencePrice, String ownerType, CharacterRef owner,
                               boolean inNegotiation, boolean tradeable, boolean leased, String fruitType, String phase,
                               boolean familyField, String collateral, boolean saleConsent,
                               boolean leasedOut, boolean leaseConsent, Long leaseOutGuideRate) {
    }

    /**
     * Roadmap V2 R2-D: reactions to the vanilla loan and the game's field menu; interestSurchargePercent &gt; 0 while
     * new credits cost more after repeated vanilla loans.
     */
    public record BypassSettingsView(boolean reactionsEnabled, double interestSurchargePercent) {
    }

    /**
     * Roadmap V2 R2-E1: tax overview - estimate of the running FS25 year from the journal (complete months, without
     * depreciation and interest), next prepayment and the last assessment with its calculation.
     */
    public record TaxOverviewView(Integer currentYear, long incomeSoFar, long expenseSoFar, long estimatedTax,
                                  double ratePercent, long allowance, Long nextPrepayment, Integer nextPrepaymentPeriod,
                                  TaxAssessmentView lastAssessment, boolean advisorActive, boolean journalAvailable,
                                  int openBills) {
    }

    public record TaxAssessmentView(int taxYear, int months, long operatingIncome, long operatingExpense,
                                    long depreciation, long interest, long profit, long allowance, long taxable,
                                    double ratePercent, long advisorReduction, long tax, long prepayments, long balance,
                                    String auditStatus) {
    }

    /**
     * Roadmap V2 R2-F2: occasions asked in the game; available = the backend asks at all (rpsim.bridge.ingame-prompts),
     * kinds = the ones switched on for this savegame, allKinds in display order.
     */
    public record PromptSettingsView(boolean available, List<String> kinds, List<String> allKinds) {
    }

    /** Roadmap V2 R2-C6: field work hints of the cooperative; fieldsTracked = the mod reports the fields. */
    public record FieldSettingsView(boolean fieldHintsEnabled, boolean fieldsTracked) {
    }

    /**
     * Roadmap V3.1 R31-B: switches of the burdening events; tonePreset and idyllicFactor explain the world mode (in
     * IDYLLIC the animal disease is off and the rest scaled).
     */
    public record BurdenSettingsView(boolean areaCheck, boolean fertilizer, boolean disease, boolean sickLeave,
                                     boolean nightWork, boolean cropDamage, boolean dieselTheft,
                                     String tonePreset, double idyllicFactor) {
    }

    /** Roadmap V3 R3-T2: optional farm name; {@code mapName} is the fallback shown when it is empty. */
    public record FarmSettingsView(String farmName, String mapName) {
    }

    /** Roadmap V3 R3-T1: a reached milestone (title of its diary entry). */
    public record MilestoneView(String key, String title, long gameTime, long gameDay) {
    }

    public record OfferView(int round, String offeredBy, String characterName, long amount, String result, Long counterAmount,
                            long gameTime) {
    }

    public record NegotiationView(Long id, String assetType, String assetId, String kind, String direction, String initiatedBy,
                                  String status, CharacterRef counterpart, CharacterRef announcer, long basePrice,
                                  Long askingPrice, int roundsUsed, int maxRounds, Long lastCounterOffer, Long finalPrice,
                                  Long closesAtGameTime, CharacterRef winner, List<OfferView> offers,
                                  Integer leaseTermMonths) {
    }

    public record OfferResultView(String result, Long counterAmount, int roundsLeft, NegotiationView negotiation) {
    }

    public record MarketEventView(Long id, String eventType, String status, String fillType, String sellPoint,
                                  Double peakMultiplier, Long fixedPrice, Long maxQuantity, Long deadlineGameTime,
                                  Long subsidyAmount, long startGameTime, Long endGameTime, CharacterRef character,
                                  Long deliveredQuantity, String endReason, Boolean playerParticipation) {
    }

    public record StorageView(String fillType, long amount, long capacity, double bestPrice, String bestSellPoint,
                              long value) {
    }

    public record StorageOverview(long gameTime, long totalValue, List<StorageView> items) {
    }

    /** trend (TODO T-10): CLIMBING / FALLING / STABLE as shown by the game, null if unknown. */
    public record PriceView(String sellPoint, String sellPointName, String fillType, double currentPrice, String trend) {
    }

    public record PricePoint(long gameTime, double price) {
    }

    public record PriceSeries(String sellPoint, String sellPointName, String fillType, List<PricePoint> points) {
    }

    public record FarmlandShort(int farmlandId, double hectares, long referencePrice, boolean inNegotiation) {
    }

    public record CharacterView(Long id, String name, String role, String category, String status, String trustLevel,
                                String shortDescription, List<FarmlandShort> farmlands) {
    }

    public record CharacterDetailView(Long id, String name, String role, String category, String status, String trustLevel,
                                      String traits, String speechStyle, String backstory, String shortDescription,
                                      List<FarmlandShort> farmlands, boolean pacingActive, boolean openTopic,
                                      List<MessageView> recentMessages) {
    }

    public record ProactiveView(MessageView message, boolean pacingActive) {
    }

    public record DiaryView(Long id, long gameTime, long gameDay, String entryType, String category, String title,
                            String text) {
    }

    public record ReputationView(String tier, String label) {
    }

    /** {@code editable}: false on other devices than the gaming PC (review 10/2026 Phase 0.4, read-only there). */
    public record AiSettingsView(String provider, String model, String baseUrl, boolean apiKeySet, List<String> providers,
                                 boolean editable) {
    }

    public record GameSettingsView(String tonePreset, String toneLabel) {
    }

    /** Dashboard notice (T-02 / T-03): kind + raw details, the frontend renders the text. */
    public record NoticeView(Long id, String kind, String status, long gameTime, java.util.Map<String, Object> details,
                             String relatedType, Long relatedId) {
    }

    /** TODO T-20 / T-22: recurring contract (insurance, lease, maintenance). */
    public record ContractView(Long id, String kind, String status, CharacterRef character, String level, Integer farmlandId,
                               long monthlyAmount, Integer coveragePercent, Long deductible, Integer termMonths,
                               Long startedAtGameTime, Long endsAtGameTime, Long nextDueGameTime, Long offerExpiresAtGameTime,
                               int missedPayments, boolean paymentOverdue, String endReason, Long renewalAmount,
                               Long purchasePrice, Integer snowDays, Integer snowDaysTotal, Boolean theftCover) {
    }

    /** TODO T-20 / T-22: simulated incident or one-off offer of a service character. */
    public record CaseView(Long id, String kind, String status, CharacterRef character, Integer farmlandId, Double hectares,
                           Long damageAmount, Long payoutAmount, Long costAmount, Long offerAmount, int roundsUsed,
                           boolean measureAgreed, String reference, long gameTime, Long deadlineGameTime, String resolution,
                           Long measureCost, Integer quantity, String direction, Integer baselineCount, String title,
                           List<Long> tiers) {
    }

    /** Insurance tariff preview for the current farm. */
    public record InsuranceQuoteView(String level, long monthlyPremium, int coveragePercent, long deductible) {
    }

    /**
     * Roadmap V2 R2-B4: farm bookkeeping from the booking journal. {@code available} is false when the mod exports no
     * journal (older mod); months are oldest first, amounts signed (expenses negative), rounded to whole euros.
     */
    public record FinanceOverview(boolean available, List<FinanceMonthView> months) {
    }

    public record FinanceMonthView(int year, int period, boolean complete, long operatingIncome, long operatingExpenses,
                                   long operatingResult, long investment, long divestment, long financing, long ignored,
                                   List<FinanceLineView> lines) {
    }

    /** financeClass: OPERATING_INCOME, OPERATING_EXPENSE, INVESTMENT, DIVESTMENT, FINANCING or IGNORE. */
    public record FinanceLineView(String category, long amount, String financeClass) {
    }

    /**
     * Booking statement ("Kontoauszug"): the entries of one game month ({@code year}/{@code period}, null when there is
     * none) and the months that have entries. {@code available} = the mod exports single bookings.
     */
    public record StatementView(boolean available, Integer year, Integer period, List<StatementMonthView> months,
                                List<StatementEntryView> entries) {
    }

    public record StatementMonthView(int year, int period, long entries) {
    }

    /**
     * One entry: a single booking or the daily sum of {@code count} bookings. {@code sellPointName} from the market
     * context; {@code vehicleMatch} (PENDING / MATCHED / AMBIGUOUS / NONE) and {@code vehicleNames} only for shop
     * vehicle purchases and sales.
     */
    public record StatementEntryView(long seq, long gameTime, int year, int period, Integer day, String category,
                                     String financeClass, long amount, int count, boolean single, Long liters,
                                     String fillType, String sellPoint, String sellPointName, String note,
                                     String vehicleMatch, String vehicleNames) {
    }

    /**
     * Hof-Tablet "Aufgaben": one open decision (or announced deadline) of any area. {@code type} names the source and
     * which of the optional payloads is set: CASE, CONTRACT_OFFER, LEASE_RENEWAL, CREDIT_COUNTER, CALL, NEGOTIATION,
     * MARKET_OFFER, POSTING. {@code kind} is the case / contract / event kind where there is one.
     */
    public record TaskView(String key, String type, String kind, Long deadlineGameTime, long gameTime, CaseView serviceCase,
                           ContractView contract, CreditApplicationView application, MessageView call,
                           NegotiationView negotiation, MarketEventView marketEvent, JobPostingView posting,
                           Integer pendingApplicants) {
    }

    /** Open tasks sorted by deadline (none last) plus the number of yes/no questions waiting in the game. */
    public record TasksView(List<TaskView> items, int waitingPrompts) {
    }

    /**
     * Hof-Tablet "Kalender": agenda of the next game days, the debits of the coming month start and the year with its
     * fixed dates. Game times are in-game milliseconds; {@code period} is the FS25 period (1 = March).
     */
    public record CalendarOverviewView(long gameTime, Integer currentPeriod, Integer year, int daysPerPeriod,
                                       long nextMonthStart, Integer nextPeriod, List<AgendaEntryView> agenda,
                                       List<DebitView> monthStartDebits, long monthStartTotal, List<YearEventView> yearEvents) {
    }

    /** kind: e.g. CASE_DEADLINE, OFFER_EXPIRES, LOAN_INSTALLMENT, SALARY, CONTRACT_PAYMENT, LEASE_END, FESTIVAL. */
    public record AgendaEntryView(long gameTime, String kind, String subKind, String title, Long amount, String reference) {
    }

    /** kind: SALARIES, LOAN, CONTRACT, RETIREMENT; subKind = contract kind; count = employees / loans. */
    public record DebitView(String kind, String subKind, String label, long amount, int count) {
    }

    /** One fixed date of the FS25 year: FESTIVAL, TAX_ASSESSMENT, TAX_PREPAYMENT, FAMILY_BIRTHDAY, FAMILY_WEDDING_DAY,
     * SCHOOL_START, ROTATION_CHECK, Roadmap V3.1 R31-D7 COOP_ASSEMBLY, COOP_DIVIDEND, COOP_BOARD_MEETING. {@code reference} =
     * festival key or family member name. */
    public record YearEventView(int period, String kind, String reference) {
    }

    /**
     * Hof-Tablet app "Stall": the husbandries of the last farm_facts (Roadmap V2 R2-A7) with the thresholds the tool
     * reacts to. {@code tracked} is false with an older mod (no husbandry values, only the animals of assets.animals).
     */
    public record StablesView(boolean tracked, int animals, int keepers, double animalsPerKeeper, double healthWarnBelow,
                              double foodWarnBelow, double waterWarnBelow, List<BarnView> barns, List<VetDueView> vetDue) {
    }

    /**
     * One husbandry: health and productivity 0..100 (the mod exports productivity as factor 0..1, like the game's info
     * box shows it x 100), food / water / conditions 0..1 (null = not reported).
     */
    public record BarnView(String husbandryUniqueId, String type, int count, long value, Double health, Double productivity,
                           Double food, Double water, List<ConditionView> conditions, Long inspectionDeadline) {
    }

    public record ConditionView(String title, double ratio) {
    }

    /** Next routine visit of the vet per animal type (month start of the due month). */
    public record VetDueView(String type, long gameTime) {
    }

    /**
     * Hof-Tablet app "Flurkarte": the fields the player farms with their state from the last field sample (Roadmap V2
     * R2-C) - what needs doing (weeds, stones, lime, plowing, only when the savegame has them switched on) and the crop
     * rotation of the running FS25 year against the year before.
     */
    public record FieldOverviewView(Integer year, boolean tracked, List<FieldRowView> fields, RotationPreviewView rotation) {
    }

    /** rotation: CHANGED, SAME (a violation at the year's end) or UNKNOWN (no crop this or last year). */
    public record FieldRowView(int farmlandId, String name, Double hectares, String fruitType, String phase, boolean leased,
                               boolean familyField, boolean weedsHigh, boolean stonesHigh, boolean needsLime, boolean needsPlow,
                               String previousCrop, String currentCrop, String rotation, int rotationViolations) {
    }

    /**
     * Estimate of the rotation premium at the end of the year as the authority computes it (hectares with a changed main
     * crop x premium per ha, cut when a field repeats its crop a second time). null while the authority is off.
     */
    public record RotationPreviewView(double changedHectares, long premium, boolean cut, List<Integer> sameFields,
                                      double premiumPerHa) {
    }
}

