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
                               CalendarView calendar, List<String> detectedMods) {
    }

    /** FS25 calendar: period 1..12 (1 = March), periodName as shown in the game. */
    public record CalendarView(int period, String periodName, int dayInPeriod, int daysPerPeriod, Integer year) {
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

    public record CreditApplicationView(Long id, long amount, String purpose, int termMonths, String status,
                                        long submittedAtGameTime, long decisionVisibleAtGameTime, String decision,
                                        String reasonCategory, Long offeredAmount, Integer offeredTermMonths,
                                        Double offeredInterestRatePercent, Long loanId) {
    }

    public record LoanPaymentView(long gameTime, long amount, String type) {
    }

    public record LoanView(Long id, long principal, long remainingAmount, double interestRatePercent, int termMonths,
                           long monthlyInstallment, String purpose, String status, boolean legacy, boolean blocksNewCredit,
                           long nextDueGameTime, boolean overdue, int escalationLevel, int missedInstallments,
                           int paidInstallments, Long deferredUntilGameTime, List<LoanPaymentView> history) {
    }

    public record DeferralView(boolean granted, String reasonCategory) {
    }

    public record JobPostingView(Long id, String jobRole, String status, long createdAtGameTime, Long filledEmployeeId) {
    }

    public record ApplicationView(Long id, CharacterRef applicant, String description, int skill, long expectedSalary,
                                  String status) {
    }

    public record NeedsView(double payFairness, double workload, double appreciation, double workingConditions,
                            double satisfaction, double effectiveSkill) {
    }

    public record EmployeeView(Long id, CharacterRef character, String jobRole, int skill, long monthlySalary, String status,
                               NeedsView needs, boolean warningSent, boolean salaryOverdue, Long timeOffUntilGameTime) {
    }

    public record FarmlandView(int farmlandId, double hectares, long referencePrice, String ownerType, CharacterRef owner,
                               boolean inNegotiation, boolean tradeable) {
    }

    public record OfferView(int round, String offeredBy, String characterName, long amount, String result, Long counterAmount,
                            long gameTime) {
    }

    public record NegotiationView(Long id, String assetType, String assetId, String kind, String direction, String initiatedBy,
                                  String status, CharacterRef counterpart, CharacterRef announcer, long basePrice,
                                  Long askingPrice, int roundsUsed, int maxRounds, Long lastCounterOffer, Long finalPrice,
                                  Long closesAtGameTime, CharacterRef winner, List<OfferView> offers) {
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

    public record AiSettingsView(String provider, String model, String baseUrl, boolean apiKeySet, List<String> providers) {
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
                               int missedPayments, boolean paymentOverdue, String endReason) {
    }

    /** TODO T-20 / T-22: simulated incident or one-off offer of a service character. */
    public record CaseView(Long id, String kind, String status, CharacterRef character, Integer farmlandId, Double hectares,
                           Long damageAmount, Long payoutAmount, Long costAmount, Long offerAmount, int roundsUsed,
                           boolean measureAgreed, String reference, long gameTime, Long deadlineGameTime, String resolution,
                           Long measureCost) {
    }

    /** Insurance tariff preview for the current farm. */
    public record InsuranceQuoteView(String level, long monthlyPremium, int coveragePercent, long deductible) {
    }
}
