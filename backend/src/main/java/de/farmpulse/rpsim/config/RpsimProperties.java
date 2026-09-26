package de.farmpulse.rpsim.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private Ai ai = new Ai();
    private Formulas formulas = new Formulas();
    private Web web = new Web();

    @Getter @Setter
    public static class Web {
        /**
         * Folder with the built Angular app (release: {@code web/}). When set, the backend serves it on "/" with an
         * SPA fallback to index.html, so players only start one process. Empty = API only (dev: ng serve).
         */
        private String staticDir = "";
    }

    @Getter @Setter
    public static class Bridge {
        /** Folder modSettings/FS25_RPSim (contains export/ and import/). */
        private String path = "../tools/bridge-simulator/runtime/modSettings/FS25_RPSim";
        /** Real-time polling interval of the bridge reader (ms). */
        private long pollIntervalMs = 2000;
        /** Whether the bridge scheduler runs (disabled in unit tests). */
        private boolean enabled = true;
        /**
         * T-02: a savegame reloaded without saving moves game time backwards. Lost bookings of a rewind up to this
         * many game hours are re-sent automatically; deeper rewinds ask the player.
         */
        private double rewindAutoResendMaxHours = 24;
        /**
         * T-02: bookings acknowledged up to this many game hours before the reloaded point are also checked (the
         * first export after loading happens slightly after the saved point). Must stay below the mod's
         * processedRetentionGameDays.
         */
        private double rewindLookbackHours = 24;
        /** TODO T-21: new mails and incoming calls are shown as in-game notification (NOTIFICATION instruction). */
        private boolean ingameNotifications = true;
        /** TODO T-21: a notification is dropped by the mod when it is processed later than this many game hours. */
        private double notificationMaxAgeHours = 2;
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
        private Provider anthropic = new Provider("https://api.anthropic.com", "claude-opus-5");
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
        private Insurance insurance = new Insurance();
        private Hunting hunting = new Hunting();
        private Livestock livestock = new Livestock();
        private Energy energy = new Energy();
        private Lease lease = new Lease();
        private Maintenance maintenance = new Maintenance();
        private ProductionSupply productionSupply = new ProductionSupply();
        private Contractor contractor = new Contractor();
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
        /** Display abstraction only (UI never shows the raw score): thresholds of the five trust levels. */
        private double displayVeryGood = 50;
        private double displayGood = 15;
        private double displayStrained = -15;
        private double displayBad = -50;
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
        /** Debt service coverage points used while no cash-flow history exists yet (placeholder). */
        private double debtServiceCoverageNoHistory = 50;
        /** Minimum game days of snapshot history before the cash-flow trend is used. */
        private double cashflowMinHistoryDays = 1;
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
        /** Upper bound of the office-clerk reduction as share of the rolled processing time. */
        private double officeClerkMaxReductionShare = 0.5;
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
        /** Share of unowned map farmlands assigned to dynamic NPCs when the ownership table is first built. */
        private double npcOwnedShare = 0.4;
        /**
         * TODO T-21: NPC-owned fields belong to the FS25 NPC of the farmland (market_context farmlands[].npc) instead
         * of an invented village character. Falls back to village characters when the export has no NPC.
         */
        private boolean useGameNpcOwners = true;
    }

    /** Technical concept "Satisfaction-Formel". */
    @Getter @Setter
    public static class Satisfaction {
        private double categoryWeight = 0.25;
        /** Upper bound of each need category (0..categoryMax). */
        private double categoryMax = 100;
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
        /** Invitation calendar: every n-th FS25 period of the year (counted from period 1 = March), 0 = never. */
        private int invitationEveryPeriods = 6;
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

    /**
     * TODO T-20: storm/hail insurance and the simulated damage events. Damages cost money (DAMAGE); an active
     * insurance reimburses damage × coverage − deductible after the damage was reported in time. Placeholders.
     */
    @Getter @Setter
    public static class Insurance {
        /** Chance per game month (= FS25 period) that a storm damages the farm buildings, only in stormPeriods. */
        private double stormProbabilityPerMonth = 0.15;
        /** FS25 periods with storms (1 = March): September to February. */
        private List<Integer> stormPeriods = new ArrayList<>(List.of(7, 8, 9, 10, 11, 12));
        /** Storm damage as share of the value of the farm buildings (placeables). */
        private double stormDamageShareMin = 0.005;
        private double stormDamageShareMax = 0.03;
        /** Chance per game month that hail hits one of the player's fields, only in hailPeriods. */
        private double hailProbabilityPerMonth = 0.2;
        /** FS25 periods with hail: May to August. */
        private List<Integer> hailPeriods = new ArrayList<>(List.of(3, 4, 5, 6));
        private double hailDamagePerHectareMin = 200;
        private double hailDamagePerHectareMax = 900;
        /** Game days the damage can be reported to the insurance. */
        private double reportDeadlineDays = 5;
        /** Game days between report and payout. */
        private double settlementDelayDaysMin = 1;
        private double settlementDelayDaysMax = 3;
        /** Proactive offer of the insurance agent this many game days after the first farm export. */
        private double firstOfferAfterDays = 3;
        private double offerValidDays = 7;
        /** A new offer after an uninsured damage at the earliest after this many game days. */
        private double reofferCooldownDays = 30;
        /** Insurance ends after this many missed premiums. */
        private int cancelAfterMissedPayments = 2;
        /** Insured value = farmland reference prices + building values; monthly premium = value × premiumRate. */
        private Map<String, InsuranceLevel> levels = new LinkedHashMap<>(Map.of(
                "BASIC", new InsuranceLevel(0.6, 2000, 0.00025, 50),
                "COMFORT", new InsuranceLevel(0.9, 500, 0.00075, 100)));
    }

    @Getter @Setter
    public static class InsuranceLevel {
        private double coverageRate;
        private long deductible;
        private double premiumRate;
        private long minPremium;

        public InsuranceLevel() {
        }

        public InsuranceLevel(double coverageRate, long deductible, double premiumRate, long minPremium) {
            this.coverageRate = coverageRate;
            this.deductible = deductible;
            this.premiumRate = premiumRate;
            this.minPremium = minPremium;
        }
    }

    /**
     * TODO T-20 hunter: simulated wild boar damage on an own field (DAMAGE), compensation offered by the hunter
     * (WILDLIFE_COMPENSATION), counter demands in rounds, joint measures that lower further damage and improve the
     * village reputation. Placeholders.
     */
    @Getter @Setter
    public static class Hunting {
        /** Chance per game month of wildlife damage on one own field, only in periods. */
        private double probabilityPerMonth = 0.15;
        /** FS25 periods with wild boar damage (1 = March): June to October. */
        private List<Integer> periods = new ArrayList<>(List.of(4, 5, 6, 7, 8));
        private double damagePerHectareMin = 150;
        private double damagePerHectareMax = 600;
        /** First offer of the hunter as share of the damage (neutral trust). */
        private double offerShare = 0.5;
        /** Highest share the hunter accepts (neutral trust). */
        private double maxShare = 0.9;
        /** Shift of both shares at trust +100 / −100 (linear). */
        private double trustInfluence = 0.2;
        /** Counter demands before the hunter's offer is final. */
        private int maxRounds = 2;
        /** Game days to answer; without answer the last offer is paid. */
        private double decisionDays = 7;
        /** Contribution of the player to a joint measure (drive hunt / fence), €. */
        private long measureCost = 400;
        private double measureReputationDelta = 3;
        private double measureTrustDelta = 5;
        /** Damage probability × this factor for measureEffectMonths after a joint measure. */
        private double measureProbabilityFactor = 0.4;
        private int measureEffectMonths = 6;
        private double agreementTrustDelta = 2;
        private double disputeTrustDelta = -5;
        private double disputeReputationDelta = -2;
    }

    /**
     * TODO T-20 vet / livestock trader / breeding advisor - only active with animals in the export. Animals are bought
     * and sold by the player in the game; the trader pays a brokerage premium when the exported head count changes
     * accordingly. Placeholders.
     */
    @Getter @Setter
    public static class Livestock {
        /** Routine visit of the vet every n game months per animal type. */
        private int vetVisitEveryMonths = 3;
        private long vetBaseFee = 80;
        private long vetFeePerAnimal = 4;
        /** Chance per game month of a trader offer. */
        private double traderProbabilityPerMonth = 0.25;
        /** Share of buy offers (the rest are sell offers). */
        private double traderBuyShare = 0.3;
        /** Sell offers: at most this share of the herd, at least traderQuantityMin animals. */
        private double traderMaxHerdShare = 0.3;
        private int traderQuantityMin = 2;
        private int traderQuantityMax = 6;
        /** Premium per animal as share of the exported value per animal. */
        private double traderPremiumShareMin = 0.05;
        private double traderPremiumShareMax = 0.12;
        /** Game days to answer an offer. */
        private double traderAnswerDays = 5;
        /** Game months to carry out an accepted offer in the game. */
        private int traderDeadlineMonths = 1;
        /** Breeding advice every n game months per animal type. */
        private int breedingAdviceEveryMonths = 6;
    }

    /**
     * TODO T-20 energy supplier: fixed-price contracts (FIXED) and price fluctuations (MULTIPLIER) for biogas fill types
     * at sell points of the map that accept them. Only active when the market context contains such a sell point (the
     * tool never invents a biogas plant). Price bands and contract bounds come from {@code rpsim.formulas.market}.
     */
    @Getter @Setter
    public static class Energy {
        /** Fill types the energy supplier buys (FS25 fill type names, verified in the game code). */
        private List<String> fillTypes = new ArrayList<>(List.of("METHANE", "SILAGE", "CHAFF", "MANURE", "LIQUIDMANURE",
                "DIGESTATE"));
        /** Chance per game month of a new offer of the energy supplier. */
        private double probabilityPerMonth = 0.35;
        /** At most this many open offers / price events of the energy supplier at the same time. */
        private int maxOpen = 1;
        /** Share of fixed-price contracts; the rest are price fluctuations. */
        private double contractShare = 0.6;
        /** Share of rising prices among the fluctuations (DEMAND_SPIKE, the rest DEMAND_SLUMP). */
        private double spikeShare = 0.5;
    }

    /**
     * TODO T-22 lease of NPC fields (not in vanilla): monthly rent (LEASE_PAYMENT), the field is transferred to the
     * player for the term (FARMLAND_TRANSFER TO_PLAYER) and goes back automatically at the end (FROM_PLAYER), after a
     * warning one month before with a renewal and - if the owner sells - a purchase offer. Placeholders.
     */
    @Getter @Setter
    public static class Lease {
        /** Yearly rent as share of the reference price of the field (neutral trust). */
        private double annualRentShare = 0.05;
        /** Rent shift at trust +100 / −100 (−10 % / +10 %). */
        private double trustInfluence = 0.1;
        /** Chance that the owner agrees to lease at neutral trust … */
        private double acceptProbability = 0.8;
        /** … shifted by this much at trust +100 / −100. */
        private double acceptTrustInfluence = 0.2;
        private int termMonths = 12;
        private double offerValidDays = 7;
        /** Warning with renewal / purchase offer this many game months before the end. */
        private int warningMonths = 1;
        /** Rent of a renewal = current rent × a random factor in [min, max]. */
        private double renewalFactorMin = 0.95;
        private double renewalFactorMax = 1.1;
        /** Purchase offer of a sell-willing owner: reference price × this factor. */
        private double purchaseFactor = 1.05;
        /** The lease ends early (field goes back) after this many missed rents. */
        private int cancelAfterMissedPayments = 2;
    }

    /**
     * TODO T-22 maintenance contract of the workshop: monthly fee (MAINTENANCE_FEE); while it is paid, the workshop
     * repairs the most worn own vehicles every game month (REPAIR_VEHICLE). Without a contract it sends repair hints.
     * Vehicle condition from farm_facts (0-100). Placeholders.
     */
    @Getter @Setter
    public static class Maintenance {
        /** Monthly fee = max(minFee, value of the own vehicles × feeRate). */
        private double feeRate = 0.002;
        private long minFee = 60;
        private double offerValidDays = 7;
        /** Vehicles below this condition are repaired at the monthly service … */
        private double repairBelowCondition = 70;
        /** … at most this many per game month (the most worn first). */
        private int maxRepairsPerMonth = 3;
        /** Without contract: repair hint for the most worn vehicle below this condition … */
        private double hintBelowCondition = 50;
        /** … at most every n game months. */
        private int hintEveryMonths = 3;
        /** First unsolicited offer when a vehicle is below this condition and there never was a contract. */
        private double firstOfferBelowCondition = 75;
        private int cancelAfterMissedPayments = 2;
    }

    /**
     * TODO T-22 delivery contracts with production points of the map (bakery, dairy ...): fixed-price contracts
     * (PRICE_EVENT FIXED, player decides) at sell points that market_context.json marks as production. Bounds of price
     * premium, quantity and deadline come from rpsim.formulas.market (special offers). Placeholders.
     */
    @Getter @Setter
    public static class ProductionSupply {
        /** Chance per game month of a delivery contract offer. */
        private double probabilityPerMonth = 0.3;
        /** Open delivery contract offers / contracts with productions at the same time. */
        private int maxOpen = 1;
    }

    /**
     * TODO T-22 contractor: refers vanilla contracts of the game (g_missionManager) by mail; the player takes them in
     * the game's contracts menu. Completing a referred contract improves trust with the contractor and the client (FS25
     * NPC, if it is a village character). Placeholders.
     */
    @Getter @Setter
    public static class Contractor {
        /** Chance that a newly available contract is referred … */
        private double referralProbability = 0.35;
        /** … at most this many referrals per game month. */
        private int maxReferralsPerMonth = 2;
        private double completedTrustDelta = 3;
        /** Trust of the client (FS25 NPC as village character) for a completed referred contract. */
        private double clientTrustDelta = 2;
        private double failedTrustDelta = -3;
    }
}
