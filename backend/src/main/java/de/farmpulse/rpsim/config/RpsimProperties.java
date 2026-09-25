package de.farmpulse.rpsim.config;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All tunable values of the backend. Every formula weight/threshold of the technical concept
 * (chapter "Formeln der Fakten-Ebene") lives here as configuration - never as a code constant.
 * The Java defaults mirror application.yml (asserted by {@code RpsimPropertiesDefaultsTest}); the concept
 * marks all of them as placeholders for playtesting.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "rpsim")
public class RpsimProperties {

    private Bridge bridge = new Bridge();
    private Time time = new Time();
    private Ai ai = new Ai();
    private Formulas formulas = new Formulas();

    @Getter @Setter
    public static class Bridge {
        /** Folder modSettings/FS25_RPSim (contains export/ and import/). */
        private String path = "../tools/bridge-simulator/runtime/modSettings/FS25_RPSim";
        /** Real-time polling interval of the bridge reader (ms). */
        private long pollIntervalMs = 2000;
        /** Whether the bridge scheduler runs (disabled in unit tests). */
        private boolean enabled = true;
    }

    @Getter @Setter
    public static class Time {
        /**
         * Game days per game month. TODO(offene-frage): FS25 period/season field not verified; fallback is this
         * fixed counter (default 1 = FS25 default "days per period").
         */
        private int gameDaysPerMonth = 1;
        /** Game months per game year (fallback year counter for rotation budget and invitations). */
        private int monthsPerYear = 12;
    }

    @Getter @Setter
    public static class Ai {
        /** Active provider: FAKE, OPENAI, ANTHROPIC, GEMINI, OLLAMA, NONE (NONE = always fallback texts). */
        private String provider = "NONE";
        private int timeoutSeconds = 30;
        private int maxAttempts = 2;
        /** Local, git-ignored file where the settings UI stores provider/key/model. */
        private String localConfigFile = "./data/local-config/ai-provider.properties";
        private Provider openai = new Provider("https://api.openai.com/v1", "gpt-4o-mini");
        private Provider anthropic = new Provider("https://api.anthropic.com/v1", "claude-haiku-4-5");
        private Provider gemini = new Provider("https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash");
        private Provider ollama = new Provider("http://localhost:11434", "llama3.1");
        /** Locale used for fallback templates and prompts. V1: de only. */
        private String locale = "de";
        /** Worker polling interval (ms). */
        private long workerIntervalMs = 1500;
    }

    @Getter @Setter
    public static class Provider {
        private String baseUrl;
        private String model;
        private String apiKey = "";

        public Provider() {
        }

        public Provider(String baseUrl, String model) {
            this.baseUrl = baseUrl;
            this.model = model;
        }
    }

    @Getter @Setter
    public static class Formulas {
        private Trust trust = new Trust();
        private Credit credit = new Credit();
        private Credit creditHard = Credit.hardDefaults();
        private Market market = new Market();
        private Negotiation negotiation = new Negotiation();
        private Satisfaction satisfaction = new Satisfaction();
        private Hiring hiring = new Hiring();
        private Reputation reputation = new Reputation();
        private Rotation rotation = new Rotation();
        private Absence absence = new Absence();
        private VillageLife villageLife = new VillageLife();
        private Onboarding onboarding = new Onboarding();
        private Calls calls = new Calls();
        private Messages messages = new Messages();
        private Tone tone = new Tone();
        private Memory memory = new Memory();
        private Storage storage = new Storage();
    }

    /** Technical concept "TrustScoreService": capped score from TrustEvent history, decay on inactivity. */
    @Getter @Setter
    public static class Trust {
        private double min = -100;
        private double max = 100;
        private double neutral = 0;
        /** Game days without events before decay towards neutral starts. */
        private double decayStartDays = 10;
        /** Points per game day the score moves towards neutral after decayStartDays. */
        private double decayPerDay = 0.5;
        private double onTimePayment = 2;
        private double missedPayment = -5;
        private double promiseKept = 3;
        private double promiseBroken = -6;
        private double callDeclined = -3;
        private double callMissed = -1;
        private double negotiationDeal = 3;
        /** Fachkonzept "Vorgeschichte": villageRelation shifts start trust of generated village characters. */
        private double villageRelationStrained = -10;
        private double villageRelationConnected = 10;
    }

    /** Technical concept "Bonitäts-Score" + "Zahlungsausfall-Eskalation" + "Kreditantrag & Bearbeitungszeit". */
    @Getter @Setter
    public static class Credit {
        private double weightDebtServiceCoverage = 0.30;
        private double weightEquityRatio = 0.25;
        private double weightLiquidityBuffer = 0.15;
        private double weightLoanToFarmSize = 0.15;
        private double weightPaymentHistory = 0.15;
        /** Start without history: neutral ~70. */
        private double paymentHistoryNeutral = 70;
        private double trustDivisor = 10;
        /** trustBonus = clamp(trustScore / trustDivisor, -trustCap, +trustCap). */
        private double trustCap = 8;
        private double approveThreshold = 75;
        private double counterThreshold = 45;
        /** Saturation: coverage (monthly cash flow / monthly installment) that yields 100 points. */
        private double debtServiceCoverageFull = 2.0;
        /** Saturation: liquidity (balance / requested amount) that yields 100 points. */
        private double liquidityFullRatio = 0.5;
        /** Saturation: farm size (asset value / (debt + requested)) that yields 100 points. */
        private double loanToFarmSizeFullRatio = 3.0;
        /** Moving window for the cash-flow trend (game days). */
        private int cashflowWindowDays = 30;
        private double baseInterestRate = 0.05;
        /** Counter offer: max amount reduction / interest surcharge at the counter threshold (scaled by gap). */
        private double counterMaxAmountReduction = 0.5;
        private double counterMaxInterestSurcharge = 0.04;
        private int counterMaxTermReductionMonths = 12;
        private int minTermMonths = 6;
        private int maxTermMonths = 240;
        private double processingDaysMin = 1;
        private double processingDaysMax = 2;
        /** Office clerk shortens processing time slightly: hours per clerk (scaled by skill/100). */
        private double officeClerkReductionHours = 6;
        /** Escalation (days overdue). */
        private double reminderAfterDays = 1;
        private double penaltyAfterDays = 3;
        private double trustLossAfterDays = 5;
        /** Repeated default: number of missed installments that triggers the final stage. */
        private int finalStageAfterMissedInstallments = 3;
        /** Final stage: CALLBACK (immediate full repayment) or BLOCK (no new credit). */
        private String finalStage = "CALLBACK";
        private double penaltyRate = 0.05;
        private double trustLossDelta = -10;
        private double publicDefaultDelta = -30;
        /** Deferral (Stundung): formula check. */
        private int deferralMonths = 2;
        private int maxDeferralsPerLoan = 1;
        private double deferralMinPaymentHistory = 50;
        private int deferralMaxEscalationLevel = 1;
        /** Legacy loan from onboarding (no scoring, no disbursement). */
        private double legacyInterestRate = 0.06;
        private int legacyTermMonths = 60;
        /** Payment history score: points lost per missed installment / gained per on-time one. */
        private double paymentHistoryMissedPenalty = 15;
        private double paymentHistoryOnTimeGain = 2;

        static Credit hardDefaults() {
            Credit c = new Credit();
            // Technical concept "Ton-/Genre-Konfigurationsprofile": CreditConfig.HART
            c.setApproveThreshold(85);
            c.setCounterThreshold(55);
            c.setTrustCap(5);
            return c;
        }
    }

    /** Technical concept "Preis-Events & Sonderkontrakte". */
    @Getter @Setter
    public static class Market {
        private double dailySpawnProbability = 0.15;
        private int maxActiveEvents = 3;
        private double advanceNoticeProbability = 0.15;
        private int advanceNoticeDaysMin = 2;
        private int advanceNoticeDaysMax = 4;
        private double rumorAccurateProbability = 0.7;
        /** Target weighting: weight = baseWeight + stockWeight * (stock share of that fill type). */
        private double targetBaseWeight = 1.0;
        private double targetStockWeight = 4.0;
        private Map<String, Double> typeWeights = new LinkedHashMap<>(Map.of(
                "DEMAND_SPIKE", 3.0, "DEMAND_SLUMP", 2.0, "HARVEST_FAILURE", 1.0, "HARVEST_SURPLUS", 1.0,
                "SPECIAL_OFFER", 1.0, "SUBSIDY", 0.5, "RUMOR", 1.5));
        private Map<String, Band> bands = defaultBands();
        private double specialOfferPremiumMin = 1.10;
        private double specialOfferPremiumMax = 1.30;
        private double specialOfferQuantityMin = 5000;
        private double specialOfferQuantityMax = 30000;
        private double specialOfferDaysMin = 3;
        private double specialOfferDaysMax = 7;
        private double subsidyAmountMin = 2000;
        private double subsidyAmountMax = 15000;
        private double eventCallProbability = 0.2;

        private static Map<String, Band> defaultBands() {
            Map<String, Band> m = new LinkedHashMap<>();
            m.put("DEMAND_SPIKE", new Band(1.08, 1.25, 12, 36, 72, 168, 48, 120));
            m.put("DEMAND_SLUMP", new Band(0.75, 0.92, 12, 36, 72, 168, 48, 120));
            m.put("HARVEST_FAILURE", new Band(1.10, 1.30, 24, 48, 96, 240, 72, 168));
            m.put("HARVEST_SURPLUS", new Band(0.70, 0.90, 24, 48, 96, 240, 72, 168));
            return m;
        }
    }

    /** Strength/duration band per event type (multiplier and hours). */
    @Getter @Setter
    public static class Band {
        private double multiplierMin;
        private double multiplierMax;
        private double rampHoursMin;
        private double rampHoursMax;
        private double holdHoursMin;
        private double holdHoursMax;
        private double decayHoursMin;
        private double decayHoursMax;

        public Band() {
        }

        public Band(double mMin, double mMax, double rMin, double rMax, double hMin, double hMax, double dMin, double dMax) {
            this.multiplierMin = mMin;
            this.multiplierMax = mMax;
            this.rampHoursMin = rMin;
            this.rampHoursMax = rMax;
            this.holdHoursMin = hMin;
            this.holdHoursMax = hMax;
            this.decayHoursMin = dMin;
            this.decayHoursMax = dMax;
        }
    }

    /** Technical concept "Verhandlungs-Preisfindung". */
    @Getter @Setter
    public static class Negotiation {
        private int maxRounds = 3;
        /** Offer >= counterBand * effectiveMinAccept -> counter offer. */
        private double counterBand = 0.9;
        private double trustDivisor = 20;
        /** trustAdjustment = clamp(trustScore / trustDivisor, -trustCap, +trustCap). */
        private double trustCap = 0.05;
        /** stubbornnessDiscount per negotiation trait, within [0.05, 0.20]. */
        private Map<String, Double> stubbornnessDiscount = new LinkedHashMap<>(Map.of(
                "NEGOTIABLE", 0.20, "NEUTRAL", 0.12, "STUBBORN", 0.05));
        private double npcBidMin = 0.90;
        private double npcBidMax = 1.15;
        private double npcCounterOfferMin = 0.85;
        private double npcCounterOfferMax = 1.0;
        /** Interest check for buying NPCs: virtualWealth >= askingPrice * interestWealthFactor. */
        private double interestWealthFactor = 1.0;
        private int maxInterestedBuyers = 3;
        private double auctionDailySpawnProbability = 0.05;
        private double auctionDurationDays = 3;
        private int auctionBiddersMin = 1;
        private int auctionBiddersMax = 3;
        /** Displayed leading NPC bid = min(npcMaxBid, playerBid + increment * basePrice). */
        private double auctionBidIncrementRatio = 0.01;
        /** Tie: player wins if trust towards the announcing character >= this value. */
        private double auctionTieTrustThreshold = 0;
        private double saleOfferValidDays = 5;
        private double virtualWealthMin = 20000;
        private double virtualWealthMax = 400000;
        private double sellWillingProbability = 0.3;
    }

    /** Technical concept "Satisfaction-Formel". */
    @Getter @Setter
    public static class Satisfaction {
        private double categoryWeight = 0.25;
        private double multiplierMin = 0.5;
        private double multiplierMax = 1.2;
        /** employeeEffectAmount = baselineOutputValue * (effectMultiplier - 1). */
        private double baselineOutputValue = 1500;
        private double startValue = 70;
        private double payFairnessDecayPerDay = 0.5;
        private double workloadDecayPerDay = 0.7;
        private double appreciationDecayPerDay = 1.0;
        /** payFairness points per 1 % raise. */
        private double raisePointsPerPercent = 2;
        private double raiseMaxPoints = 30;
        private double timeOffPointsPerDay = 15;
        private double conversationPoints = 8;
        private double conversationCooldownDays = 1;
        private double salaryOverdueDelta = -15;
        private double warningThreshold = 30;
        private double warningAfterDays = 14;
        private double terminationAfterDays = 30;
    }

    /** Technical concept "Kündigung & Bewerbung". */
    @Getter @Setter
    public static class Hiring {
        private int candidatesMin = 3;
        private int candidatesMax = 5;
        private int skillMin = 30;
        private int skillMax = 95;
        private Map<String, Double> baseSalary = new LinkedHashMap<>(Map.of(
                "MACHINE_OPERATOR", 2400.0, "MECHANIC", 2700.0, "ANIMAL_KEEPER", 2200.0, "OFFICE_CLERK", 2300.0));
        /** Expected salary = baseSalary * (1 + salarySkillFactor * (skill - 50) / 50). */
        private double salarySkillFactor = 0.3;
    }

    /** Technical concept "Dorf-Ansehen". */
    @Getter @Setter
    public static class Reputation {
        private double trustWeight = 0.6;
        private double publicWeight = 0.4;
        /** Half-life of public action events in game days. */
        private double publicHalfLifeDays = 60;
        private double newCharacterFactor = 0.2;
        private double newCharacterCap = 15;
        private double goodThreshold = 25;
        private double controversialThreshold = -25;
    }

    /** Technical concept "Dynamische-Charaktere-Rotation". */
    @Getter @Setter
    public static class Rotation {
        private int maxPerYear = 2;
        private double dailyProbability = 0.02;
        private double moveAwayShare = 0.5;
        private double retirementShare = 0.4;
        private int minDynamicCharacters = 3;
        private int maxDynamicCharacters = 8;
    }

    /** Technical concept "Pflichtrollen-Abwesenheit". */
    @Getter @Setter
    public static class Absence {
        private double dailyProbability = 0.01;
        private int durationDaysMin = 2;
        private int durationDaysMax = 6;
        private double substituteProbability = 0.5;
        private double delayHours = 24;
    }

    /** Technical concept "Dorfleben-Modul". */
    @Getter @Setter
    public static class VillageLife {
        /** Congratulation when cash-flow trend (current window / previous window) >= this ratio. */
        private double congratulationTrendRatio = 1.25;
        private double congratulationMinCashflow = 1000;
        private double congratulationCooldownDays = 20;
        /** Invitation calendar (fallback): day-of-year offsets (in game days since year start). */
        private int invitationEveryDays = 6;
        private double gossipDailyProbability = 0.05;
        private double gossipCooldownDays = 3;
    }

    /** Fachkonzept "Vorgeschichte & Onboarding-Ablauf". */
    @Getter @Setter
    public static class Onboarding {
        private int dynamicCharacters = 5;
        private int storyHooksMin = 1;
        private int storyHooksMax = 2;
        private double storyHookSpreadDaysMin = 3;
        private double storyHookSpreadDaysMax = 21;
        private int maxFreeTextLength = 2000;
    }

    /** Technical concept "Anruf-Zustandsautomat". */
    @Getter @Setter
    public static class Calls {
        /** Ring timeout in GAME minutes (pauses with the game). */
        private double ringTimeoutGameMinutes = 120;
    }

    /** Technical concept "Proaktive Nachricht". */
    @Getter @Setter
    public static class Messages {
        private double pacingCooldownDays = 1;
    }

    /** Fachkonzept "Freie Antworten des Spielers": tone classifier deltas, capped. */
    @Getter @Setter
    public static class Tone {
        private double friendlyDelta = 1;
        private double rudeDelta = -2;
        private double cap = 2;
    }

    /** Technical concept "Vier Bausteine im Prompt": deterministic memory facts. */
    @Getter @Setter
    public static class Memory {
        private int maxFacts = 8;
    }

    /** Technical concept "Warenbestand-Bewertung". */
    @Getter @Setter
    public static class Storage {
        /** Exported prices are per this many liters. */
        private double priceUnitLiters = 1000;
        /** Max points returned by the price history endpoint (down-sampling). */
        private int historyMaxPoints = 500;
    }
}
