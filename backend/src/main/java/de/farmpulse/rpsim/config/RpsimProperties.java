package de.farmpulse.rpsim.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
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
    private Db db = new Db();

    /** Technical review 10/2026, Phase 0.1 (S-1): password of the H2 file database. */
    @Getter @Setter
    public static class Db {
        /**
         * Properties file with the database password (owner-only permissions), created on the first start. Empty =
         * the password from {@code spring.datasource.password} is used as it is (in-memory databases of tests/e2e).
         */
        private String passwordFile = "";
    }

    @Getter @Setter
    public static class Web {
        /**
         * Folder with the built Angular app (release: {@code web/}). When set, the backend serves it on "/" with an
         * SPA fallback to index.html, so players only start one process. Empty = API only (dev: ng serve).
         */
        private String staticDir = "";
        /**
         * Technical review 10/2026, Phase 0.2 (S-2): host names accepted in the {@code Host} header besides
         * {@code localhost}, IP addresses and the name of this computer, e.g. {@code mein-pc.fritz.box}. Protects
         * against DNS rebinding: a web page cannot point one of these names at this computer.
         */
        private List<String> allowedHosts = new ArrayList<>();
        /** Roadmap V3 R3-N1/N2: access from devices in the home network (tablet). */
        private Lan lan = new Lan();
    }

    /**
     * Roadmap V3 R3-N2: PIN login of devices in the home network (owner decisions, QUESTIONS.md). The switch "Im
     * Heimnetz erreichbar" and the PIN hash live in the database of the installation, not in the savegame.
     */
    @Getter @Setter
    public static class Lan {
        /** Shortest PIN (digits only). */
        private int pinMinLength = 4;
        /** Longest PIN (digits only). */
        private int pinMaxLength = 8;
        /** Wrong PINs from one sender address before it is locked. */
        private int maxFailedAttempts = 5;
        /** Lock of a sender address after too many wrong PINs (real minutes). */
        private int lockoutMinutes = 5;
        /** Validity of a session cookie (real days); only the hash of the cookie value is stored. */
        private int sessionDays = 30;
        /** PBKDF2WithHmacSHA256 iterations of the PIN hash (OWASP Password Storage Cheat Sheet: 600,000). */
        private int pbkdf2Iterations = 600_000;
        /** Name of the session cookie (HttpOnly, SameSite=Strict). */
        private String cookieName = "FP_LAN_SESSION";
    }

    @Getter @Setter
    public static class Bridge {
        /** Folder modSettings/FS25_RPSim (contains export/ and import/). */
        private String path = "../tools/bridge-simulator/runtime/modSettings/FS25_RPSim";
        /** Real-time polling interval of the bridge reader (ms). */
        private long pollIntervalMs = 2000;
        /**
         * Technical review 10/2026, Phase 1.3: a listener of the bridge cycle that fails is retried by the next cycles;
         * after this many failed attempts it is skipped for that event and the player gets a notice.
         */
        private int stepMaxAttempts = 3;
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
        /** Roadmap V2 R2-F2: master switch of the yes/no questions in the game. */
        private boolean ingamePrompts = true;
        /** Roadmap V2 R2-F2: occasions asked in the game unless the player chose others (PromptKind names). */
        private List<String> promptDefaultKinds = new ArrayList<>(List.of("CALL"));
        /** Roadmap V2 R2-F2: a question without its own deadline (bank counter offer) expires after this many game hours. */
        private double promptMaxAgeHours = 48;
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
        private Trainings training = new Trainings();
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
        private NeighborTrade neighborTrade = new NeighborTrade();
        private NeighborMissions neighborMissions = new NeighborMissions();
        private PriceAlarm priceAlarm = new PriceAlarm();
        private ForwardContract forwardContract = new ForwardContract();
        private FarmShop farmShop = new FarmShop();
        private BulkOrder bulkOrder = new BulkOrder();
        private Finance finance = new Finance();
        private LiquidityPlan liquidityPlan = new LiquidityPlan();
        private Drought drought = new Drought();
        private UsedVehicle usedVehicle = new UsedVehicle();
        private Mechanic mechanic = new Mechanic();
        private OfficeClerk officeClerk = new OfficeClerk();
        private Apprentice apprentice = new Apprentice();
        private Milestones milestones = new Milestones();
        private LeaseOut leaseOut = new LeaseOut();
        private ContractorWork contractorWork = new ContractorWork();
        private MachineLoan machineLoan = new MachineLoan();
        private LivestockTrade livestockTrade = new LivestockTrade();
        private WinterService winterService = new WinterService();
        private SeasonalWorker seasonalWorker = new SeasonalWorker();
        private BurdeningEvents burdeningEvents = new BurdeningEvents();
        private DirectPayment directPayment = new DirectPayment();
        private InvestmentGrant investmentGrant = new InvestmentGrant();
        private FertilizerRules fertilizerRules = new FertilizerRules();
        private AnimalDisease animalDisease = new AnimalDisease();
        private SocialInsurance socialInsurance = new SocialInsurance();
        private SickLeave sickLeave = new SickLeave();
        private VillageNewspaper villageNewspaper = new VillageNewspaper();
        private VillageChat villageChat = new VillageChat();
        private Stammtisch stammtisch = new Stammtisch();
        private NightWork nightWork = new NightWork();
        private CropDamage cropDamage = new CropDamage();
        private FarmHoliday farmHoliday = new FarmHoliday();
        private SchoolVisit schoolVisit = new SchoolVisit();
        private CoopShares coopShares = new CoopShares();
        private CoopAssembly coopAssembly = new CoopAssembly();
        private CoopBoard coopBoard = new CoopBoard();
        private DieselTheft dieselTheft = new DieselTheft();
        private Fields fields = new Fields();
        private VanillaBypass vanillaBypass = new VanillaBypass();
        private Tax tax = new Tax();
        private Authority authority = new Authority();
        private Family family = new Family();
        private Clubs clubs = new Clubs();
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
        /**
         * Roadmap V2 R2-C5: standing crops count as asset in the credit check - area x yield x best price x growth
         * progress x this discount.
         */
        private double standingCropDiscount = 0.5;
        /**
         * Sondertilgung: share of the original principal that may be repaid early per FS25 year without a fee; the
         * part above it costs {@code specialRepaymentFeeRate} (Vorfälligkeitsentschädigung, booked on top).
         */
        private double specialRepaymentFreeShare = 0.10;
        private double specialRepaymentFeeRate = 0.01;
        /** Trust bonus at the bank advisor for a Sondertilgung of at least this share of the remaining debt. */
        private double specialRepaymentTrustDelta = 3;
        private double specialRepaymentTrustMinShare = 0.05;
        /** Roadmap V3 R3-K1: own fields as loan collateral (Grundschuld). */
        private Collateral collateral = new Collateral();
        /** Roadmap V3 R3-K3: annual review with the bank advisor at the year change. */
        private AnnualReview annualReview = new AnnualReview();

        static Credit hardDefaults() {
            Credit c = new Credit();
            // Technical concept "Ton-/Genre-Konfigurationsprofile": CreditConfig.HART
            c.setApproveThreshold(85);
            c.setCounterThreshold(55);
            c.setTrustCap(5);
            // R3-K1 (owner decision): the bank realises pledged fields on a call-back only in the harsh world mode
            c.getCollateral().setRealiseOnCallback(true);
            return c;
        }
    }

    /** Roadmap V3 R3-K1: collateral of a loan - own fields with a Grundschuld (owner decisions, placeholders). */
    @Getter @Setter
    public static class Collateral {
        /** Collateral value = field price (assets.farmland[].price) x loan-to-value. */
        private double loanToValue = 0.6;
        /** Interest discount at full coverage (collateral value / loan amount, max 1), proportional below. */
        private double maxInterestDiscount = 0.01;
        /** Bonus on the metric "loan too large for the farm": points x coverage (the metric stays capped at 100). */
        private double farmSizeBonus = 20;
        /** Loans above this share of the farm assets need collateral covering the part above it. */
        private double requiredAboveShare = 0.5;
        /** Pledged field sold in the game menu: trust delta at the bank advisor ... */
        private double menuSaleTrustDelta = -10;
        /** ... and game days to pay the claimed Sondertilgung. */
        private double claimDays = 10;
        /** Claim unpaid after the deadline: trust delta (plus a missed installment and no new credit until paid). */
        private double claimOverdueTrustDelta = -5;
        /** Call-back: the bank takes the pledged fields and credits their collateral value (profile credit-hard only). */
        private boolean realiseOnCallback = false;
    }

    /** Roadmap V3 R3-K3: annual review (owner decisions, placeholders). */
    @Getter @Setter
    public static class AnnualReview {
        private boolean enabled = true;
        /** Game days to accept the invitation and, afterwards, the offered rate cut. */
        private double invitationDays = 10;
        private double offerDays = 10;
        /** Credit score at the cut-off date: from good-score a rate cut is offered, below bad-score a serious talk. */
        private double goodScore = 75;
        private double badScore = 45;
        /** Rate cut per review on every running loan, at most max-cut-per-loan over its term, never below min-rate. */
        private double rateCut = 0.0025;
        private double maxCutPerLoan = 0.01;
        private double minRate = 0.01;
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
        /**
         * Roadmap V2 R2-A5: between warning and resignation - below strike-threshold for strike-after-days the employee
         * goes on strike (the helper stops, the salary keeps running) until the score is back at the threshold.
         */
        private double strikeThreshold = 30;
        private double strikeAfterDays = 21;
        /** Roadmap V2 R2-A4 / R2-A7: workload from the real game (worked hours, animals per keeper). */
        private Workload workload = new Workload();
    }

    /**
     * Roadmap V2 R2-A4: with worked time from the mod, the workload of machine operators follows the real hours instead
     * of the simulated decay (evaluated every game day). R2-A7: the workload of animal keepers follows the animals per
     * keeper. Placeholders.
     */
    @Getter @Setter
    public static class Workload {
        /** Target hours of a machine operator per game day (a game month = days per period). */
        private double targetHoursPerDay = 8;
        /** Workload points lost per hour above the target of a day. */
        private double overtimePenaltyPerHour = 2;
        /** Workload points regained per hour below the target of a day (a light recovery). */
        private double recoveryPerHour = 0.5;
        /** The positive monthly EMPLOYEE_EFFECT of machine operators scales with min(1, hours / target hours). */
        private boolean effectScalesWithHours = true;
        /** R2-A7: animals one keeper can handle; more animals per keeper cost workload. */
        private double animalsPerKeeper = 80;
        /** Workload points lost per day and per 100 % overload (animals per keeper above animals-per-keeper). */
        private double keeperOverloadPenaltyPerDay = 3;
        /** Workload points regained per day when the keeper handles at most animals-per-keeper. */
        private double keeperRecoveryPerDay = 0.5;
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
        /**
         * Owner decision 2026-10-06: the applications arrive the next game day, each at a random time between
         * application-hour-min and application-hour-max o'clock.
         */
        private int applicationHourMin = 8;
        private int applicationHourMax = 17;
        /**
         * Owner decision 2026-10-06: cancelling a hired employee before the first working day costs severance-factor x
         * the agreed monthly salary (SEVERANCE), in the world mode HARSH severance-factor-harsh x.
         */
        private double severanceFactor = 1.5;
        private double severanceFactorHarsh = 3.0;
    }

    /**
     * Owner decision "Schulungen": without a training a machine operator drives small and medium tractors and every
     * vehicle whose FS25 shop category no training lists. A training costs money, the employee is away for
     * duration-days (no helper) and appreciates it. Machine operator applicants bring a training along with
     * applicant-chance and expect applicant-salary-premium more salary then. Placeholders.
     */
    @Getter @Setter
    public static class Trainings {
        private double durationDays = 1;
        private double appreciationPoints = 8;
        /** Price (€) per training, key = Training name. */
        private Map<String, Long> cost = new LinkedHashMap<>(Map.of(
                "LARGE_TRACTOR", 4500L, "SELF_PROPELLED", 6000L, "SPECIAL_HARVESTER", 7500L,
                "COMBINE", 9000L, "FORAGE_HARVESTER", 9000L, "TRUCK", 12000L));
        private double applicantChance = 0.3;
        private double applicantSalaryPremium = 0.08;
        /**
         * FS25 shop categories (StoreItem.categoryName, upper case) per training; the mod sends a helper of a vehicle in
         * one of these categories only with an employee who has the training. Categories not listed need no training.
         */
        private Map<String, List<String>> categories = defaultCategories();

        private static Map<String, List<String>> defaultCategories() {
            Map<String, List<String>> m = new LinkedHashMap<>();
            m.put("LARGE_TRACTOR", List.of("TRACTORSL"));
            m.put("COMBINE", List.of("HARVESTERS"));
            m.put("FORAGE_HARVESTER", List.of("FORAGEHARVESTERS"));
            m.put("SPECIAL_HARVESTER", List.of("BEETVEHICLES", "POTATOVEHICLES", "VEGETABLEVEHICLES", "COTTONVEHICLES",
                    "SUGARCANEVEHICLES", "GRAPEVEHICLES", "OLIVEVEHICLES"));
            m.put("TRUCK", List.of("TRUCKS"));
            m.put("SELF_PROPELLED", List.of("SPRAYERVEHICLES", "MOWERVEHICLES", "FRONTLOADERVEHICLES",
                    "TELELOADERVEHICLES", "SKIDSTEERVEHICLES", "WHEELLOADERVEHICLES"));
            return m;
        }
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
        /**
         * Roadmap V2 R2-C3: with the field export hail hits only standing crops; damage = area x yield x best price x
         * a damage share between min and max. Without yield or price the per-hectare range above applies.
         */
        private double hailDamageShareMin = 0.05;
        private double hailDamageShareMax = 0.3;
        /** R2-C3: hail probability x (1 + factor x rain share of the month that just ended). */
        private double hailRainFactor = 1.0;
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
        /** Roadmap V3 R3-W3: weather-index drought insurance - premium per hectare and month, payout per hectare. */
        private double droughtPremiumPerHectare = 4;
        private double droughtPayoutPerHectare = 200;
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
        /**
         * Roadmap V2 R2-C3: with the field export wild boars only damage standing crops of these FS25 fruit types;
         * the damage scales with the growth progress.
         */
        private List<String> crops = new ArrayList<>(List.of("MAIZE", "WHEAT", "BARLEY", "OAT", "POTATO"));
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
        /** Roadmap V2 R2-A7: emergency visit of the vet when a husbandry's health (0..100) falls below this value. */
        private double vetEmergencyHealthThreshold = 40;
        /** Invoice of an emergency visit = routine invoice x this factor. */
        private double vetEmergencyFactor = 2.5;
        /** At most one emergency visit per husbandry within this many game days. */
        private double vetEmergencyCooldownDays = 5;
        /** R2-A7: the animal keeper warns when food (ratio 0..1) of a husbandry falls below this value ... */
        private double keeperFoodWarningRatio = 0.2;
        /** ... or the water condition (getConditionInfos entry with one of water-condition-titles) below this value. */
        private double keeperWaterWarningRatio = 0.2;
        /** At most one warning mail of the keeper within this many game days. */
        private double keeperWarningCooldownDays = 3;
        /** Titles of the water condition as the game shows them (localised fill type title). */
        private List<String> waterConditionTitles = new ArrayList<>(List.of("Wasser", "Water"));
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

    /**
     * Roadmap V3 R3-H2..H4: trade with the neighbours (owner decisions, QUESTIONS.md; placeholders). Stock and needs of a
     * neighbour are backend fiction derived from his real fields; the goods move for real in the own silos.
     * <ul>
     *   <li>roles: role -> fill types the neighbour needs (only goods an own silo accepts are traded).</li>
     *   <li>price per 1000 l: best current price of the sell points, else reference-prices; the neighbour sells at
     *   neighbor-sell-share and buys at neighbor-buy-share of it; trust moves the price by trust / trust-divisor,
     *   capped at trust-cap (like the negotiation engine) in the player's favour.</li>
     *   <li>stock: harvest-share of a harvest (area x litersPerSqm) goes into the neighbour's stock, by-products (fruit ->
     *   by-product) add by-product-share of it; the stock sinks by monthly-decay every game month.</li>
     * </ul>
     */
    @Getter @Setter
    public static class NeighborTrade {
        private Map<String, List<String>> roles = defaultRoles();
        private double neighborSellShare = 1.05;
        private double neighborBuyShare = 0.95;
        private double trustDivisor = 20;
        private double trustCap = 0.05;
        private double harvestShare = 0.3;
        private Map<String, String> byProducts = defaultByProducts();
        private double byProductShare = 0.5;
        private double monthlyDecay = 0.2;
        /** € per 1000 l for goods without a sell point on the map. */
        private Map<String, Double> referencePrices = defaultReferencePrices();
        /** Messages of neighbours about trade (offers and requests) per game month. */
        private int maxMessagesPerMonth = 2;
        /** Chance per game month that a neighbour offers goods of his stock on his own (R3-H3). */
        private double offerProbabilityPerMonth = 0.3;
        /** Chance per game month that a neighbour asks for goods of the player (R3-H4). */
        private double requestProbabilityPerMonth = 0.3;
        private int amountMin = 2000;
        private int amountMax = 10000;
        private int amountStep = 500;
        /** At most this share of the neighbour's stock (offer) or the player's stock (request). */
        private double maxShare = 0.5;
        /** Game days to answer an offer or a request; the price holds that long. */
        private double answerDays = 5;
        private double tradeTrustDelta = 2;
        private double declineTrustDelta = -1;
        private double ignoreTrustDelta = -2;
        /** R3-H4: the goods were no longer in the silo when the sale was executed - the neighbour is disappointed. */
        private double stockMissingTrustDelta = -1;
        /** R3-H4: village reputation per fulfilled request of a neighbour ... */
        private double reputationDelta = 1;
        /** ... at most this many times per FS25 year. */
        private int reputationMaxPerYear = 3;

        private static Map<String, List<String>> defaultRoles() {
            Map<String, List<String>> m = new LinkedHashMap<>();
            m.put("DAIRY", new ArrayList<>(List.of("STRAW", "SILAGE", "DRYGRASS_WINDROW")));
            m.put("ARABLE", new ArrayList<>(List.of("SEEDS", "FERTILIZER", "LIQUIDFERTILIZER")));
            m.put("MIXED", new ArrayList<>(List.of("STRAW", "SEEDS")));
            return m;
        }

        private static Map<String, String> defaultByProducts() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("WHEAT", "STRAW");
            m.put("BARLEY", "STRAW");
            m.put("OAT", "STRAW");
            return m;
        }

        private static Map<String, Double> defaultReferencePrices() {
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("STRAW", 120.0);
            m.put("SILAGE", 180.0);
            m.put("DRYGRASS_WINDROW", 250.0);
            m.put("SEEDS", 900.0);
            m.put("FERTILIZER", 1500.0);
            m.put("LIQUIDFERTILIZER", 1200.0);
            return m;
        }
    }

    /**
     * Roadmap V3 R3-H5: neighbours ask for help with real contracts of the game on their own fields (owner decisions,
     * placeholders). The game pays the reward; the tool adds a bonus on success and moves trust.
     */
    /** Roadmap V3 R3-M1: price alarms in the Agrarbörse (owner decisions, placeholders). */
    @Getter @Setter
    public static class PriceAlarm {
        /** Active alarms per savegame. */
        private int maxActive = 10;
        /** Game days the in-game hint stays valid (the mod drops it when processed later). */
        private double notificationDays = 1;
    }

    /** Roadmap V3 R3-M2: forward contracts - harvest sold in advance at a fixed price (owner decisions, placeholders). */
    @Getter @Setter
    public static class ForwardContract {
        /** Fixed price = current price x (1 + factor-per-month x months of lead); negative = discount. */
        private double factorPerMonth = -0.02;
        private int minLeadMonths = 1;
        private int maxLeadMonths = 12;
        private long minQuantity = 1000;
        private long maxQuantity = 200000;
        private long quantityStep = 1000;
        private int maxOpen = 5;
        /** Penalty = shortfall x fixed price x share, booked as CONTRACT_PENALTY. */
        private double penaltyShare = 0.25;
        /** Trust of the land agent: shortfall / full delivery. */
        private double shortfallTrustDelta = -5;
        private double fulfilledTrustDelta = 3;
    }

    /** Roadmap V3 R3-M3: farm shop - villagers order small amounts from the own silos (owner decisions, placeholders). */
    @Getter @Setter
    public static class FarmShop {
        private boolean enabled = true;
        /** Fill types the villagers ask for (only what lies in own silos is ordered). */
        private List<String> fillTypes = new ArrayList<>(List.of("POTATO", "WHEAT", "OAT", "SUGARBEET", "CANOLA"));
        /** Farm-shop price = best market price x markup. */
        private double markup = 1.3;
        private long amountMin = 200;
        private long amountMax = 2000;
        private long amountStep = 100;
        /** At most this share of the stock of a fill type. */
        private double maxShare = 0.2;
        private int maxOrdersPerMonth = 2;
        /** Chance per possible order and month, multiplied by the refusal factor of the savegame. */
        private double probability = 0.4;
        private double answerDays = 3;
        /** Each refused or ignored order multiplies the factor with this; a delivered order divides by it (max 1). */
        private double refusalFactor = 0.75;
        private double minFactor = 0.1;
        /** Village reputation per delivered order, at most reputation-max-per-year times per FS25 year. */
        private double reputationDelta = 1;
        private int reputationMaxPerYear = 4;
    }

    /** Roadmap V3.2 R32-G: bulk orders of bulk buyers at a sell point of the map (owner decisions 2026-10-08). */
    @Getter @Setter
    public static class BulkOrder {
        private boolean enabled = true;
        /** Fill types a bulk buyer orders (only with a sell point of the map that accepts them, no production). */
        private List<String> fillTypes = new ArrayList<>(List.of("WHEAT", "BARLEY", "CANOLA", "SUNFLOWER", "SOYBEAN",
                "MAIZE", "POTATO", "SUGARBEET"));
        /** Fixed amount range per fill type (litres), independent of the farm size; fill types without entry: none. */
        private Map<String, Amount> amounts = new LinkedHashMap<>();
        /** At most this many requests per month, each with probability x the refusal factor of the savegame. */
        private int maxRequestsPerMonth = 1;
        private double probability = 0.3;
        /** Each refused, ignored or short order multiplies the factor with this; a full delivery divides by it (max 1). */
        private double refusalFactor = 0.75;
        private double minFactor = 0.1;
        private double answerDays = 5;
        /** Share of the requests that come as a call instead of a mail. */
        private double callShare = 0.1;
        /** Instant delivery from the own silos: best market price x instant-markup. */
        private double instantMarkup = 1.25;
        /** Delivery month: today's price of the sell point x (1 + term-base-markup + term-markup-per-month x months). */
        private double termBaseMarkup = 0.05;
        private double termMarkupPerMonth = 0.01;
        private int minLeadMonths = 1;
        private int maxLeadMonths = 12;
        /** At most this many open orders with a delivery month per savegame. */
        private int maxOpen = 3;
        /** Shortfall x fixed price x penalty-share as CONTRACT_PENALTY. */
        private double penaltyShare = 0.25;
        /** Trust of the bulk buyer for a full delivery (instant or delivery month) and for a shortfall. */
        private double fulfilledTrustDelta = 3;
        private double shortfallTrustDelta = -5;

        /** Amount range of one fill type: min..max in steps of step. */
        @Getter @Setter
        public static class Amount {
            private long min;
            private long max;
            private long step;
        }
    }

    @Getter @Setter
    public static class NeighborMissions {
        /** Tool names of the evidenced contract types (the mod maps them to PlowMission / StonePickMission). */
        private List<String> types = new ArrayList<>(List.of("PLOW", "STONE_PICK"));
        private double probabilityPerMonth = 0.4;
        private int maxPerMonth = 1;
        private double answerDays = 5;
        /** Booked as OTHER when the contract finished successfully. */
        private long successBonus = 250;
        private double successTrustDelta = 3;
        private double failureTrustDelta = -3;
    }

    /** Roadmap V2 R2-B2: class of a booking in the journal (farm_facts.finances). */
    public enum FinanceClass {
        OPERATING_INCOME, OPERATING_EXPENSE, INVESTMENT, DIVESTMENT, FINANCING, IGNORE
    }

    /** Roadmap V3 R3-P1: the office clerk reminds of deadlines, lowers audits and pays tax bills in time (placeholders). */
    @Getter @Setter
    public static class OfficeClerk {
        /** Game days before a deadline the clerk writes a reminder. */
        private double reminderDays = 3;
        /** Audit factor = 1 - audit-reduction-max x effective skill / 100 (best clerk; with a tax advisor the smaller factor). */
        private double auditReductionMax = 0.5;
        /** Below this workload the clerk is overloaded and does not pay tax bills on the deadline day. */
        private double overloadWorkload = 30;
    }

    /** Roadmap V3 R3-L1: leasing out own fields to neighbours (owner decisions in QUESTIONS.md). */
    @Getter @Setter
    public static class LeaseOut {
        /** Guide rent per year as share of the field price; per ha and month = price x share / 12 / ha. */
        private double annualRentShare = 0.05;
        /** Term the player can choose, in FS25 years. */
        private int termYearsMin = 1;
        private int termYearsMax = 3;
        /** Interested neighbours: capital >= desired rent x ha x 12 x years; at most this many. */
        private int maxInterested = 3;
        /** First bid of a neighbour = desired rent x random(min, max), capped at the negotiation formula's limit. */
        private double firstBidMin = 0.85;
        private double firstBidMax = 1.0;
        /** Game days the bids stay open. */
        private double offerValidDays = 5;
        /** The tenant offers a renewal this many game months before the end ... */
        private int warningMonths = 1;
        /** ... at the current rent x random(min, max). */
        private double renewalFactorMin = 0.95;
        private double renewalFactorMax = 1.1;
        /** Fallback (field state at the return): the return waits for an empty or harvested field at most this long. */
        private int returnDelayMaxMonths = 1;
        /** Trust of every family member when the family field is leased out (a sale costs family.field-sold-trust-delta). */
        private double familyTrustDelta = -5;
        /** Trust of the tenant when the player takes the field back in the game menu. */
        private double reclaimTrustDelta = -10;
    }

    /** Roadmap V3 R3-T1: the fixed list of milestones - each can be switched off (owner decisions). */
    @Getter @Setter
    public static class Milestones {
        /** The first bank loan fully repaid. */
        private boolean loanRepaidEnabled = true;
        /** A full FS25 year without a payment delay. */
        private boolean yearWithoutDelayEnabled = true;
        /** The area of all fields of the farm reaches area-hectares. */
        private boolean areaEnabled = true;
        private double areaHectares = 100;
        /** The first congratulation of the cooperative on a record harvest revenue. */
        private boolean recordHarvestEnabled = true;
        /** crop-rotation-years closed harvest years in a row without a crop-rotation complaint. */
        private boolean cropRotationEnabled = true;
        private int cropRotationYears = 5;
        /** The first completed goods trade with a neighbour. */
        private boolean neighborTradeEnabled = true;
    }

    /** Roadmap V3 R3-P2: apprentices (owner decisions, placeholders). */
    @Getter @Setter
    public static class Apprentice {
        /** Fixed monthly salary (no skill premium). */
        private long salary = 900;
        private int skillMin = 10;
        private int skillMax = 30;
        /** Skill points at every month start, up to skill-cap. */
        private int skillPerMonth = 2;
        private int skillCap = 70;
        /** Training time in FS25 years. */
        private int trainingYears = 2;
        private int maxApprentices = 2;
        /** The takeover request comes this many months before the end. */
        private int takeoverNoticeMonths = 1;
        /** A counter offer is accepted from this share of the demand. */
        private double counterAcceptShare = 0.9;
    }

    /** Roadmap V3.1 R31-A1: the contractor works an own field (owner decisions 2026-10-02, placeholders). */
    @Getter @Setter
    public static class ContractorWork {
        private boolean enabled = true;
        /** Price in EUR per hectare and work, material (lime, seed) included. */
        private Map<String, Double> pricePerHa = defaultWorkPrices();
        /** The work is done at the end of the game day done-after-days days after the order day (1 = the next day). */
        private int doneAfterDays = 1;
        /** At most this many works of one field in one order, done on the same day. */
        private int maxWorksPerOrder = 3;
        /** Fertilising is offered below this spray level (FS25 sprayLevelMaxValue). */
        private int maxSprayLevel = 2;
        private double doneTrustDelta = 2;
        /** Fruit types offered for sowing (FS25 fruit type names). */
        private List<String> sowFruitTypes = new ArrayList<>(List.of("WHEAT", "BARLEY", "OAT", "CANOLA", "MAIZE",
                "SUNFLOWER", "SOYBEAN", "SORGHUM"));
        private Yield yield = new Yield();

        /**
         * Yield factor of a contractor harvest (product): fertilisation by sprayLevel (index = level, the last entry for
         * higher levels), lime and plow level 0 when the savegame needs them, weeds per weedState step (capped).
         */
        @Getter @Setter
        public static class Yield {
            private List<Double> sprayFactors = new ArrayList<>(List.of(0.85, 0.95, 1.0));
            private double limeMissingFactor = 0.9;
            private double plowMissingFactor = 0.9;
            private double weedStep = 0.05;
            private double weedMax = 0.2;
        }

        private static Map<String, Double> defaultWorkPrices() {
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("PLOW", 110.0);
            m.put("CULTIVATE", 80.0);
            m.put("LIME", 60.0);
            m.put("SOW", 100.0);
            m.put("FERTILIZE", 70.0);
            m.put("HARVEST", 180.0);
            return m;
        }
    }

    /**
     * Roadmap V3.1 R31-A2: borrowed machine of a neighbour and demo machine of the workshop (owner decisions
     * 2026-10-02, placeholders). Age and hours of a borrowed machine use the ranges of used-vehicle.
     */
    @Getter @Setter
    public static class MachineLoan {
        private boolean enabled = true;
        /** The player picks 1..5 game days. */
        private int daysMin = 1;
        private int daysMax = 5;
        /** Rent per game day = list price x share, trust as in neighbor-trade (trust-divisor / trust-cap). */
        private double rentSharePerDay = 0.003;
        /** Surcharge per day of a late return = share of the daily rent. */
        private double lateSurchargeShare = 0.5;
        /** Machines a neighbour or the workshop offers to choose from. */
        private int choices = 3;
        /** Neighbour role -> shop categories he lends. */
        private Map<String, List<String>> roleCategories = defaultRoleCategories();
        /** Compensation = condition points lost / 100 x list price x share (COMPENSATION). */
        private double compensationShare = 0.5;
        /** From this loss of condition points the lender's trust changes by damage-trust-delta. */
        private double damageTrustPoints = 10;
        private double damageTrustDelta = -3;
        /** The machine disappeared without being returned: claim = game value, trust. */
        private double lostTrustDelta = -20;
        /** A daily rent could not be booked: the neighbour takes the machine back at once, trust. */
        private double rentMissedTrustDelta = -3;
        private int demoDaysMin = 1;
        private int demoDaysMax = 2;
        /** Chance of a demo offered by the workshop at a month start. */
        private double demoOfferProbability = 0.2;
        /** Purchase offer after the demo: list price x (1 - discount). */
        private double demoDiscount = 0.1;
        /** Days to answer a demo the workshop offers on its own. */
        private double demoAnswerDays = 5;

        private static Map<String, List<String>> defaultRoleCategories() {
            Map<String, List<String>> m = new LinkedHashMap<>();
            m.put("DAIRY", new ArrayList<>(List.of("TRACTORSM", "TRACTORSL", "FORAGEHARVESTERS", "MOWERVEHICLES")));
            m.put("ARABLE", new ArrayList<>(List.of("TRACTORSL", "HARVESTERS", "BEETVEHICLES", "POTATOVEHICLES")));
            m.put("MIXED", new ArrayList<>(List.of("TRACTORSM", "TRACTORSL", "HARVESTERS")));
            return m;
        }
    }

    /**
     * Roadmap V3.1 R31-A3: livestock trade with the neighbours (owner decisions 2026-10-02, placeholders). Answer time
     * and trust changes as in neighbor-trade.
     */
    @Getter @Setter
    public static class LivestockTrade {
        private boolean enabled = true;
        /** Neighbour role -> animal types he keeps and trades. */
        private Map<String, List<String>> roleAnimals = defaultRoleAnimals();
        /** Stock per animal type, rolled when first needed. */
        private int stockMin = 20;
        private int stockMax = 60;
        private int countMin = 1;
        private int countMax = 10;
        /** Price per animal = game value per animal of the player's stable x share, trust as in neighbor-trade. */
        private double neighborSellShare = 1.05;
        private double neighborBuyShare = 0.95;
        private double offerProbabilityPerMonth = 0.3;
        private double requestProbabilityPerMonth = 0.3;
        private int maxMessagesPerMonth = 1;
        /** Age in months of animals bought from a neighbour, per animal type. */
        private Map<String, Integer> ageMonths = defaultAges();

        private static Map<String, List<String>> defaultRoleAnimals() {
            Map<String, List<String>> m = new LinkedHashMap<>();
            m.put("DAIRY", new ArrayList<>(List.of("COW")));
            m.put("MIXED", new ArrayList<>(List.of("COW", "PIG", "SHEEP")));
            m.put("ARABLE", new ArrayList<>());
            return m;
        }

        private static Map<String, Integer> defaultAges() {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("COW", 12);
            m.put("PIG", 6);
            m.put("SHEEP", 12);
            return m;
        }
    }

    /** Roadmap V3.1 R31-A4: winter service contract of the authority (owner decisions 2026-10-02, placeholders). */
    @Getter @Setter
    public static class WinterService {
        private boolean enabled = true;
        /** Own vehicles of these shop categories qualify (assets.vehicles[].category). */
        private List<String> vehicleCategories = new ArrayList<>(List.of("TRACTORSM", "TRACTORSL"));
        /** FS25 periods of the winter (November-February) and the period of the offer (October). */
        private List<Integer> winterPeriods = new ArrayList<>(List.of(9, 10, 11, 12));
        private int offerPeriod = 8;
        private long baseFeePerMonth = 400;
        private long feePerSnowDay = 150;
        /** A game day with a snow height from this value (metres) is a snow day. */
        private double snowThreshold = 0.05;
        /** Offer again next October after a winter with / without a snow day. */
        private double renewalProbability = 0.9;
        private double renewalProbabilityWithoutSnow = 0.5;
        /** In-game hint on a snow day (once per day). */
        private String notificationText = "Schnee! Winterdienst ab 5 Uhr";
    }

    /** Roadmap V3.1 R31-A5: seasonal workers for the harvest (owner decisions 2026-10-02, placeholders). */
    @Getter @Setter
    public static class SeasonalWorker {
        /** FS25 periods (June-October) in which a seasonal job can be posted; the contract ends with contract-end-period. */
        private List<Integer> postingPeriods = new ArrayList<>(List.of(4, 5, 6, 7, 8));
        private int contractEndPeriod = 8;
        /** Salary = machine operator formula at the skill x factor. */
        private double salaryFactor = 1.25;
        private int skillMin = 30;
        private int skillMax = 70;
        private int maxWorkers = 3;
        /** A worker who left with this satisfaction or more applies again the next year. */
        private double returnSatisfaction = 60;
    }

    /**
     * Roadmap V3.1 R31-B: burdening events (on-site check B1, fertiliser checks B3, animal disease B4, sickness and
     * accident B5) are switched per savegame; in the world mode IDYLLIC their probabilities, cuts and fines are scaled
     * by idyllic-factor and the animal disease is off (owner decision 2026-10-05).
     */
    @Getter @Setter
    public static class BurdeningEvents {
        private double idyllicFactor = 0.5;
    }

    /**
     * Roadmap V3.1 R31-B1: area payment application and premium (owner decisions 2026-10-05, placeholders). The rotation
     * cut of a check uses authority.rotation-cut-share, the announcement authority.inspection-days.
     */
    @Getter @Setter
    public static class DirectPayment {
        private boolean enabled = true;
        /** FS25 periods: mail and form from open-period, deadline = end of deadline-period, payment at payment-period. */
        private int openPeriod = 1;
        private int deadlinePeriod = 3;
        private int paymentPeriod = 10;
        /** A late application loses late-cut-per-day of the premium per started game day; after late-max-days none. */
        private double lateCutPerDay = 0.01;
        private int lateMaxDays = 25;
        private double premiumPerHa = 250;
        /** On-site check: chance per application, rolled at the first month start of check-periods. */
        private double checkProbability = 0.1;
        private List<Integer> checkPeriods = new ArrayList<>(List.of(4, 5, 6, 7, 8));
        /** Cut = premium of the deviating area x cut-factor (x cut-factor-repeat when the savegame deviated before). */
        private double cutFactor = 1.5;
        private double cutFactorRepeat = 3.0;
        /** Crops offered per field (plus the fruit types of the own fields and BRACHE = fallow). */
        private List<String> crops = new ArrayList<>(List.of("WHEAT", "BARLEY", "OAT", "CANOLA", "MAIZE", "SUNFLOWER",
                "SOYBEAN", "SORGHUM", "GRASS"));
    }

    /**
     * Roadmap V3.1 R31-B2: investment grant (owner decisions 2026-10-05, placeholders). Bills of a repayment follow
     * tax.payment-days / tax.late-fee-rate like a tax bill.
     */
    @Getter @Setter
    public static class InvestmentGrant {
        private boolean enabled = true;
        private long minSum = 10000;
        /** Processing time; with an office clerk x (1 - clerk-reduction-max x effective skill / 100). */
        private double processingDays = 10;
        private double clerkReductionMax = 0.5;
        /** The purchase must follow within purchase-months game months after the approval. */
        private int purchaseMonths = 6;
        private int maxOpenPerKind = 1;
        /** Grant = grant-share x min(recognised, planned sum), at most grant-max. */
        private double grantShare = 0.3;
        private long grantMax = 50000;
        /** Funded machines sold within binding-months game months after the payment are repaid pro rata. */
        private int bindingMonths = 24;
    }

    /** Roadmap V3.1 R31-B3: fertiliser rules (owner decisions 2026-10-05, placeholders). */
    @Getter @Setter
    public static class FertilizerRules {
        private boolean enabled = true;
        /** FS25 periods of the closed period for organic fertiliser on arable land (November-January). */
        private List<Integer> closedPeriods = new ArrayList<>(List.of(9, 10, 11));
        private List<String> organicSprayTypes = new ArrayList<>(List.of("LIQUID_MANURE", "MANURE"));
        /** Fruit types that are grassland, not arable land. */
        private List<String> excludedFruitTypes = new ArrayList<>(List.of("GRASS"));
        /** False = fallback of the manual test plan: every rise of sprayLevel counts ("Düngung festgestellt"). */
        private boolean requireSprayType = true;
        /** Fine of a repeated finding (the first one is a warning) and the loss of village reputation. */
        private long fine = 1000;
        private double reputationDelta = -3;
        /** Slurry store: condition titles of getConditionInfos (localised, like livestock.water-condition-titles). */
        private List<String> slurryConditionTitles = new ArrayList<>(List.of("Gülle", "Slurry", "Liquid Manure"));
        private double slurryWarningRatio = 0.85;
        private double slurryWarningDays = 5;
        private double slurryWarningCooldownDays = 10;
        /** At the start of reminder-period every farm with a slurry store from reminder-min-ratio is reminded. */
        private int reminderPeriod = 8;
        private double reminderMinRatio = 0.5;
    }

    /** Roadmap V3.1 R31-B4: animal disease and restricted zone (owner decisions 2026-10-05, placeholders). */
    @Getter @Setter
    public static class AnimalDisease {
        private boolean enabled = true;
        private double probabilityPerMonth = 0.02;
        /** Months after the lifting before the next disease can break out. */
        private int cooldownMonths = 12;
        private int zoneMonths = 3;
        private List<Disease> diseases = new ArrayList<>(List.of(new Disease("ASP", new ArrayList<>(List.of("PIG"))),
                new Disease("AVIAN_FLU", new ArrayList<>(List.of("CHICKEN"))),
                new Disease("BLUETONGUE", new ArrayList<>(List.of("SHEEP", "COW")))));
        /** Compulsory vet check per stable = (livestock.vet-base-fee + vet-fee-per-animal x animals) x vet-fee-factor. */
        private double vetFeeFactor = 2.0;
        /** Requirement per affected stable: health from requirement-health within requirement-days, else a fine. */
        private double requirementHealth = 60;
        private double requirementDays = 10;
        private long requirementFine = 1000;
        /** After the lifting the neighbour trade (A3) of the type starts at price-factor-after, back to 1 linearly. */
        private double priceFactorAfter = 0.8;
        private int priceRecoveryMonths = 3;
    }

    @Getter @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Disease {
        private String key;
        private List<String> animalTypes = new ArrayList<>();
    }

    /** Roadmap V3.1 R31-B5: annual fee of the agricultural social insurance (owner decisions 2026-10-05, placeholders). */
    @Getter @Setter
    public static class SocialInsurance {
        private boolean enabled = true;
        /** Fee = base-fee + fee-per-ha x hectares of own fields + fee-per-employee x active employees. */
        private long baseFee = 300;
        private double feePerHa = 12;
        private long feePerEmployee = 180;
        /** FS25 period of the bill (April). */
        private int billPeriod = 2;
    }

    /** Roadmap V3.1 R31-B5: sickness and work accidents of employees (owner decisions 2026-10-05, placeholders). */
    @Getter @Setter
    public static class SickLeave {
        private boolean enabled = true;
        private double sicknessProbabilityPerDay = 0.005;
        private double accidentProbabilityPerDay = 0.003;
        private List<String> accidentRoles = new ArrayList<>(List.of("MACHINE_OPERATOR", "SEASONAL_WORKER", "APPRENTICE"));
        /** Accident risk x risk-bad-factor for each of the needs workload / working conditions below risk-bad-threshold. */
        private double riskBadThreshold = 40;
        private double riskBadFactor = 1.5;
        /** ... and x risk-good-factor when both are from risk-good-threshold. */
        private double riskGoodThreshold = 70;
        private double riskGoodFactor = 0.5;
        private int sicknessDaysMin = 2;
        private int sicknessDaysMax = 5;
        private int accidentDaysMin = 3;
        private int accidentDaysMax = 10;
        /** Get-well wishes (once per absence). */
        private double getWellAppreciation = 8;
        private double getWellTrustDelta = 2;
    }

    /** Roadmap V3.1 R31-D1: village newspaper "Dorfblatt" (owner decisions 2026-10-05). */
    @Getter @Setter
    public static class VillageNewspaper {
        private boolean enabled = true;
        /** An extra issue at the middle of the month (besides the one at every period start). */
        private boolean midMonthIssue = false;
        /** Market section: the fill types with the largest price change against the previous period. */
        private int marketTopCount = 3;
        /** At most this many entries per section (classifieds, deeds ...). */
        private int maxItemsPerSection = 6;
    }

    /** Roadmap V3.1 R31-D2: village group chat "Dorfchat" (owner decisions 2026-10-05, placeholders). */
    @Getter @Setter
    public static class VillageChat {
        private boolean enabled = true;
        private double dailyPostProbability = 0.3;
        private int maxPostsPerDay = 3;
        private List<String> topics = new ArrayList<>(List.of("GREEN_WASTE", "FIRE_BRIGADE_DRILL", "LOST_DOG",
                "CHURCH_BAZAAR", "ROAD_WORKS", "WEATHER_WARNING"));
        /** Random villagers in a club group besides the chair. */
        private int clubGroupVillagers = 3;
        /** A player post changes trust only once per group within these game days. */
        private double pacingDays = 1;
    }

    /** Roadmap V3.1 R31-D3: regulars' table in the village pub (owner decisions 2026-10-05, placeholders). */
    @Getter @Setter
    public static class Stammtisch {
        private boolean enabled = true;
        private double intervalDays = 14;
        private double answerDays = 2;
        private int attendees = 3;
        private double attendTrustDelta = 2;
        /** Added to market.rumor-accurate-probability for the next rumour after an evening. */
        private double rumorAccuracyBonus = 0.15;
        private double tipProbability = 0.3;
        private int lonerAfterMissed = 3;
        private double lonerReputationDelta = -1;
        private double lonerReputationCap = -3;
    }

    /** Roadmap V3.1 R31-D4: complaints about night work (owner decisions 2026-10-05, placeholders). */
    @Getter @Setter
    public static class NightWork {
        private boolean enabled = true;
        private int nightStartHour = 22;
        private int nightEndHour = 6;
        private double thresholdHours = 3;
        private double windowDays = 7;
        private double annoyedWithinDays = 30;
        private double friendlyTrustDelta = -1;
        private double annoyedTrustDelta = -3;
        /** Game time between two exports counts at most this long (a pause or a loaded save is no night work). */
        private double maxSampleGapMinutes = 60;
    }

    /** Roadmap V3.1 R31-D5: crop damage on neighbour fields (owner decisions 2026-10-05, placeholders). */
    @Getter @Setter
    public static class CropDamage {
        private boolean enabled = true;
        private int minSamples = 3;
        private double trustDelta = -3;
        private double repeatDays = 60;
        private long compensationPerSample = 150;
        private double declineTrustDelta = -5;
        private double decisionDays = 7;
        private String hintText = "Pass auf, wo du langfährst – das ist ein bestelltes Feld eines Nachbarn.";
    }

    /** Roadmap V3.1 R31-D6: farm holidays (owner decisions 2026-10-05, placeholders). */
    @Getter @Setter
    public static class FarmHoliday {
        private boolean enabled = true;
        private long setupCost = 20000;
        private double baseIncomePerMonth = 800;
        /** Season factor per FS25 period of the month that ended; other periods other-season-factor. */
        private Map<Integer, Double> seasonFactors = new LinkedHashMap<>(Map.of(4, 1.5, 5, 1.5, 6, 1.5, 10, 1.2));
        private double otherSeasonFactor = 0.7;
        private Map<String, Double> reputationFactors = new LinkedHashMap<>(Map.of("GOOD", 1.2, "NEUTRAL", 1.0,
                "CONTROVERSIAL", 0.7));
        private double goodHealth = 70;
        private double goodHealthFactor = 1.2;
        private double badHealth = 40;
        private double badHealthFactor = 0.6;
        private double noiseCut = 0.1;
        private double smellCut = 0.1;
        private List<Integer> smellPeriods = new ArrayList<>(List.of(4, 5, 6));
    }

    /** Roadmap V3.1 R31-D6: school visits (owner decisions 2026-10-05, placeholders). */
    @Getter @Setter
    public static class SchoolVisit {
        private boolean enabled = true;
        private double probabilityPerMonth = 0.25;
        private List<Integer> excludedPeriods = new ArrayList<>(List.of(4, 5, 6));
        private double minHealth = 70;
        private double answerDays = 5;
        private long allowance = 150;
        private double reputationDelta = 2;
        private double trustDelta = 2;
    }

    /** Roadmap V3.1 R31-D7: cooperative shares and dividend (owner decisions 2026-10-05, placeholders). */
    @Getter @Setter
    public static class CoopShares {
        private boolean enabled = true;
        private long sharePrice = 500;
        private int maxShares = 200;
        /** Rate = base-dividend-rate x price index of the year, capped to [min-rate, max-rate]. */
        private double baseDividendRate = 0.04;
        private double minRate = 0.0;
        private double maxRate = 0.08;
        private int dividendPeriod = 1;
        private int noticeMonths = 12;
    }

    /** Roadmap V3.1 R31-D7: general assembly of the cooperative (owner decisions 2026-10-05, placeholders). */
    @Getter @Setter
    public static class CoopAssembly {
        private int period = 2;
        private double answerDays = 5;
        private List<String> topics = new ArrayList<>(List.of("DIVIDEND_UP", "GRAIN_STORE", "FESTIVAL_SPONSORING"));
        /** Votes per active character; a character votes yes with yes-base + trust / yes-trust-divisor. */
        private int characterVotes = 10;
        private double yesBase = 0.5;
        private double yesTrustDivisor = 200;
        /** Effects of an approved topic until the next assembly. */
        private double dividendUpPoints = 0.01;
        private double grainStoreRumorBonus = 0.05;
        private double festivalSponsoringReputation = 1;
    }

    /** Roadmap V3.1 R31-D7: board of the cooperative (owner decisions 2026-10-05, placeholders). */
    @Getter @Setter
    public static class CoopBoard {
        private int minShares = 40;
        private double minTrust = 50;
        private double rumorDaysEarlier = 2;
        private double forwardContractBonus = 0.1;
        private double reputationPerYear = 2;
        /** FS25 periods of the quarterly board meetings (mandatory dates in the calendar). */
        private List<Integer> meetingPeriods = new ArrayList<>(List.of(1, 4, 7, 10));
        private double meetingAnswerDays = 5;
        private double missedTrustDelta = -3;
        private int removedAfterMissed = 2;
    }

    /** Roadmap V3.1 R31-D8: diesel theft (owner decisions 2026-10-05, placeholders). */
    @Getter @Setter
    public static class DieselTheft {
        private boolean enabled = true;
        private double probabilityPerMonth = 0.05;
        private double minLiters = 100;
        private double shareMin = 0.3;
        private double shareMax = 0.6;
        private double maxLiters = 300;
        private double dieselPricePerLiter = 1.6;
        /** Insurance module "Diebstahl" of the storm / hail contract. */
        private long insurancePremiumPerMonth = 8;
        private long insuranceMinDamage = 150;
        private long tankLockPrice = 250;
        private double tankLockFactor = 0.1;
        private int maxAttempts = 3;
    }

    /**
     * Roadmap V3 R3-V2 / R3-V3: used machines bought from the workshop or a neighbour and own machines sold to neighbours
     * (owner decisions, placeholders). The used price follows the game's formula (Vehicle.calculateSellPrice).
     */
    @Getter @Setter
    public static class UsedVehicle {
        private boolean enabled = true;
        /** Chance of one offer at every month start (at most one open offer). */
        private double offerProbability = 0.5;
        /** Share of offers made by the workshop; the rest by an active neighbour. */
        private double workshopShare = 0.5;
        /** Price of the workshop = game used price x (1 + markup). */
        private double workshopMarkup = 0.10;
        /** Price of a neighbour = game used price x (1 - discount). */
        private double neighborDiscount = 0.05;
        /** Catalog entries offered: list price range. */
        private double minListPrice = 5000;
        private double maxListPrice = 400000;
        private int ageMonthsMin = 12;
        private int ageMonthsMax = 120;
        /** Operating hours so that the hour factor of the formula lies in this range. */
        private double hourFactorMin = 0.3;
        private double hourFactorMax = 0.9;
        private double damageMin = 0;
        private double damageMax = 0.3;
        private double wearMin = 0;
        private double wearMax = 0.5;
        /** Game formula: hour factor exponent with / without an engine, floor share of the list price. */
        private double motorizedExponent = 1.0;
        private double unmotorizedExponent = 1.3;
        private double minPriceShare = 0.03;
        /** Game days an offer (negotiation) stays open. */
        private double negotiationDays = 7;
        /** Delivery attempts after NO_SPACE (one per game day). */
        private int spawnMaxAttempts = 5;
        /** Validity of the in-game hint after a failed delivery or removal (game days). */
        private double notificationDays = 1;
        /** Sale to neighbours: interested buyers, first offer and cap as share of the game value. */
        private int saleBuyersMin = 1;
        private int saleBuyersMax = 3;
        private double saleOfferMin = 1.0;
        private double saleOfferMax = 1.1;
        private double saleCap = 1.1;
    }

    /** Roadmap V3 R3-W1 / R3-W2: drought from the rain share of growth months, drought aid (owner decisions, placeholders). */
    @Getter @Setter
    public static class Drought {
        private boolean enabled = true;
        /** Growth months (FS25 periods, 1 = March). */
        private List<Integer> periods = new ArrayList<>(List.of(3, 4, 5, 6, 7, 8));
        /** Dry growth months in a row that declare a drought. */
        private int minPeriods = 2;
        /** A month is dry when rain was below this share of the observed time ... */
        private double maxRainShare = 0.03;
        /** ... and counts only when at least this share of the month was observed (else unknown, breaks the series). */
        private double minObservedShare = 0.5;
        /** HARVEST_FAILURE for at most this many crops (largest area first) at every sell point accepting them. */
        private int maxCrops = 3;
        /** Drought aid of the authority per hectare of own fields growing in a drought month. */
        private double aidPerHectare = 150;
        private double aidApplicationDays = 15;
        /** Deduction on the aid with a drought insurance. */
        private double aidInsuranceDeduction = 0.5;
    }

    /** Roadmap V3 R3-K2: 12-month liquidity plan in the Bank app (owner decisions, placeholders). */
    @Getter @Setter
    public static class LiquidityPlan {
        /** Months ahead the plan shows (FS25 months). */
        private int horizonMonths = 12;
        /** Liquidity reserve = the known fixed postings of the month (salaries, installments, contracts, retirement) x factor. */
        private double reserveFactor = 1.0;
        /**
         * Journal categories the plan already lists as known postings: added back to the operating result so the
         * income estimate does not count them twice (the tax advisor fee is booked as OTHER and cannot be separated).
         */
        private List<String> knownPostingCategories = new ArrayList<>(List.of("RPSIM_SALARY_PAYMENT",
                "RPSIM_TAX_PAYMENT", "RPSIM_INSURANCE_PREMIUM", "RPSIM_MAINTENANCE_FEE", "RPSIM_LEASE_PAYMENT",
                "RPSIM_FAMILY"));
        /** The bank advisor writes once when the balance falls below zero within this many months. */
        private boolean advisorWarningEnabled = true;
        private int advisorWarningMonths = 3;
    }

    /**
     * Roadmap V2 R2-B (real farm finances from the mod's booking journal). Placeholders.
     * <ul>
     *   <li>categories: FS25 money type (name in the global MoneyType table) or RPSIM_&lt;REASON&gt; -> class. Only names
     *   evidenced in the FS25 code or the game's journal are listed; unknown categories count as operating by their sign (logged once).</li>
     *   <li>early warning (R2-B5): the bank writes when the operating result of this many complete months in a row was
     *   negative while a bank loan runs - once per streak.</li>
     *   <li>record (R2-B5): the cooperative congratulates on the highest HARVEST_INCOME + SOLD_PRODUCTS of a complete
     *   month since the start, once at least record-min-months complete months were seen.</li>
     * </ul>
     */
    @Getter @Setter
    public static class Finance {
        private Map<String, FinanceClass> categories = defaultCategories();
        private boolean earlyWarningEnabled = true;
        private int earlyWarningNegativeMonths = 2;
        private boolean recordEnabled = true;
        /** Money types that count as harvest revenue for the record. */
        private List<String> recordCategories = new ArrayList<>(List.of("HARVEST_INCOME", "SOLD_PRODUCTS"));
        private int recordMinMonths = 3;
        private double recordTrustDelta = 2;
        /**
         * Booking statement: a shop vehicle purchase / sale waits at most this many farm_facts exports (every 10 s) for
         * its vehicle to appear / disappear in assets.vehicles; afterwards it stays without vehicle name.
         */
        private int statementVehicleMatchExports = 3;

        private static Map<String, FinanceClass> defaultCategories() {
            Map<String, FinanceClass> m = new LinkedHashMap<>();
            m.put("HARVEST_INCOME", FinanceClass.OPERATING_INCOME);
            m.put("SOLD_PRODUCTS", FinanceClass.OPERATING_INCOME);
            m.put("MISSIONS", FinanceClass.OPERATING_INCOME);
            m.put("PROPERTY_INCOME", FinanceClass.OPERATING_INCOME);
            m.put("SOLD_ANIMALS", FinanceClass.OPERATING_INCOME);
            m.put("RPSIM_EMPLOYEE_EFFECT", FinanceClass.OPERATING_INCOME);
            m.put("RPSIM_SUBSIDY", FinanceClass.OPERATING_INCOME);
            m.put("RPSIM_LIVESTOCK_PREMIUM", FinanceClass.OPERATING_INCOME);
            m.put("RPSIM_TAX_REFUND", FinanceClass.OPERATING_INCOME);
            m.put("RPSIM_LEASE_INCOME", FinanceClass.OPERATING_INCOME); // Roadmap V3 (R3-Q1)
            m.put("RPSIM_GOODS_SALE", FinanceClass.OPERATING_INCOME); // Roadmap V3 (R3-Q1)
            m.put("RPSIM_LIVESTOCK_SALE", FinanceClass.OPERATING_INCOME); // Roadmap V3.1 (R31-Q1)
            m.put("RPSIM_WINTER_SERVICE", FinanceClass.OPERATING_INCOME); // Roadmap V3.1 (R31-Q1)
            m.put("RPSIM_DIRECT_PAYMENT", FinanceClass.OPERATING_INCOME); // Roadmap V3.1 (R31-Q1)
            m.put("RPSIM_INVESTMENT_GRANT", FinanceClass.OPERATING_INCOME); // Roadmap V3.1 (R31-Q1)
            m.put("RPSIM_GUEST_INCOME", FinanceClass.OPERATING_INCOME); // Roadmap V3.1 (R31-Q1)
            m.put("RPSIM_COOP_DIVIDEND", FinanceClass.OPERATING_INCOME); // Roadmap V3.1 (R31-Q1)
            m.put("RPSIM_FARM_HOLIDAY_SETUP", FinanceClass.INVESTMENT); // Roadmap V3.1 R31-D6
            m.put("RPSIM_TANK_LOCK", FinanceClass.OPERATING_EXPENSE); // Roadmap V3.1 R31-D8
            m.put("PURCHASE_FUEL", FinanceClass.OPERATING_EXPENSE);
            m.put("PURCHASE_SEEDS", FinanceClass.OPERATING_EXPENSE);
            m.put("PURCHASE_FERTILIZER", FinanceClass.OPERATING_EXPENSE);
            m.put("PURCHASE_WATER", FinanceClass.OPERATING_EXPENSE);
            m.put("PURCHASE_PALLETS", FinanceClass.OPERATING_EXPENSE);
            m.put("PURCHASE_CONSUMABLES", FinanceClass.OPERATING_EXPENSE);
            m.put("PRODUCTION_COSTS", FinanceClass.OPERATING_EXPENSE);
            m.put("BOUGHT_MATERIALS", FinanceClass.OPERATING_EXPENSE);
            m.put("VEHICLE_RUNNING_COSTS", FinanceClass.OPERATING_EXPENSE);
            m.put("VEHICLE_REPAIR", FinanceClass.OPERATING_EXPENSE);
            m.put("LEASING_COSTS", FinanceClass.OPERATING_EXPENSE);
            m.put("PROPERTY_MAINTENANCE", FinanceClass.OPERATING_EXPENSE);
            m.put("AI", FinanceClass.OPERATING_EXPENSE);
            m.put("NEW_ANIMALS_COST", FinanceClass.OPERATING_EXPENSE);
            m.put("RPSIM_SALARY_PAYMENT", FinanceClass.OPERATING_EXPENSE);
            m.put("RPSIM_INSURANCE_PREMIUM", FinanceClass.OPERATING_EXPENSE);
            m.put("RPSIM_VET_INVOICE", FinanceClass.OPERATING_EXPENSE);
            m.put("RPSIM_LEASE_PAYMENT", FinanceClass.OPERATING_EXPENSE);
            m.put("RPSIM_MAINTENANCE_FEE", FinanceClass.OPERATING_EXPENSE);
            m.put("RPSIM_TAX_PAYMENT", FinanceClass.OPERATING_EXPENSE);
            m.put("RPSIM_FINE", FinanceClass.OPERATING_EXPENSE);
            m.put("RPSIM_FAMILY", FinanceClass.OPERATING_EXPENSE);
            m.put("RPSIM_SPONSORING", FinanceClass.OPERATING_EXPENSE);
            m.put("RPSIM_COMPENSATION", FinanceClass.OPERATING_EXPENSE);
            m.put("RPSIM_TRAINING", FinanceClass.OPERATING_EXPENSE);
            m.put("RPSIM_SEVERANCE", FinanceClass.OPERATING_EXPENSE); // owner decision 2026-10-06
            m.put("RPSIM_GOODS_PURCHASE", FinanceClass.OPERATING_EXPENSE); // Roadmap V3 (R3-Q1)
            m.put("RPSIM_CONTRACT_PENALTY", FinanceClass.OPERATING_EXPENSE); // Roadmap V3 (R3-Q1)
            m.put("RPSIM_CONTRACTOR_FEE", FinanceClass.OPERATING_EXPENSE); // Roadmap V3.1 (R31-Q1)
            m.put("RPSIM_MACHINE_RENT", FinanceClass.OPERATING_EXPENSE); // Roadmap V3.1 (R31-Q1)
            m.put("RPSIM_LIVESTOCK_PURCHASE", FinanceClass.OPERATING_EXPENSE); // Roadmap V3.1 (R31-Q1)
            m.put("RPSIM_SOCIAL_INSURANCE", FinanceClass.OPERATING_EXPENSE); // Roadmap V3.1 (R31-Q1)
            m.put("RPSIM_INVESTOR_COMPENSATION", FinanceClass.OPERATING_EXPENSE); // Roadmap V3.2 (R32-Q1)
            m.put("RPSIM_OTHER", FinanceClass.OPERATING_EXPENSE);
            m.put("SHOP_PROPERTY_BUY", FinanceClass.INVESTMENT);
            m.put("SHOP_VEHICLE_BUY", FinanceClass.INVESTMENT);
            m.put("FIELD_BUY", FinanceClass.INVESTMENT);
            m.put("RPSIM_FARMLAND_PURCHASE", FinanceClass.INVESTMENT);
            m.put("RPSIM_VEHICLE_PURCHASE", FinanceClass.INVESTMENT); // Roadmap V3 (R3-Q1)
            m.put("SHOP_VEHICLE_SELL", FinanceClass.DIVESTMENT);
            m.put("SHOP_PROPERTY_SELL", FinanceClass.DIVESTMENT);
            m.put("FIELD_SELL", FinanceClass.DIVESTMENT);
            m.put("RPSIM_FARMLAND_SALE", FinanceClass.DIVESTMENT);
            m.put("RPSIM_VEHICLE_SALE", FinanceClass.DIVESTMENT); // Roadmap V3 (R3-Q1)
            m.put("RPSIM_CREDIT_DISBURSEMENT", FinanceClass.FINANCING);
            m.put("RPSIM_CREDIT_INSTALLMENT", FinanceClass.FINANCING);
            m.put("RPSIM_CREDIT_PENALTY", FinanceClass.FINANCING);
            m.put("RPSIM_CREDIT_CALLBACK", FinanceClass.FINANCING);
            m.put("RPSIM_CREDIT_SPECIAL_REPAYMENT", FinanceClass.FINANCING);
            m.put("RPSIM_CREDIT_PREPAYMENT_FEE", FinanceClass.FINANCING);
            m.put("RPSIM_STARTING_CAPITAL_ADJUSTMENT", FinanceClass.FINANCING);
            m.put("RPSIM_COOP_SHARES", FinanceClass.FINANCING); // Roadmap V3.1 (R31-Q1)
            m.put("RPSIM_INVESTOR_CAPITAL", FinanceClass.FINANCING); // Roadmap V3.2 (R32-Q1)
            m.put("RPSIM_INVESTOR_REPAYMENT", FinanceClass.FINANCING); // Roadmap V3.2 (R32-Q1)
            m.put("RPSIM_INVESTOR_PAYOUT", FinanceClass.FINANCING); // Roadmap V3.2 (R32-Q1, owner decision 2026-10-08)
            m.put("RPSIM_DAMAGE", FinanceClass.IGNORE);
            m.put("RPSIM_INSURANCE_PAYOUT", FinanceClass.IGNORE);
            m.put("RPSIM_WILDLIFE_COMPENSATION", FinanceClass.IGNORE);
            return m;
        }
    }

    /**
     * Roadmap V2 R2-A6: an employed mechanic repairs part of the machines every game month (after the maintenance
     * contract, never the same vehicle twice). Capacity = repair-points-per-month x skill / 100 x effectMultiplier
     * condition points, spent on the most worn own vehicles below repair-below-condition. Placeholders.
     */
    @Getter @Setter
    public static class Mechanic {
        private double repairPointsPerMonth = 60;
        private double repairBelowCondition = 90;
        /** Workload points lost per vehicle still below repair-below-condition after the month's repairs. */
        private double overloadWorkloadPerVehicle = 2;
    }

    /** Roadmap V2 R2-E1: tax office and tax advisor; tax year = FS25 year (placeholders). */
    @Getter @Setter
    public static class Tax {
        private boolean enabled = true;
        /** Tax on the taxable profit (profit - allowance); the harsh world mode uses the hard values. */
        private double rate = 0.19;
        private long allowance = 50000;
        private double hardRate = 0.25;
        private long hardAllowance = 25000;
        /** Simplified depreciation per year as share of the vehicle and building values at the end of the year. */
        private double depreciationRate = 0.1;
        /** Journal categories that do not count for the taxable profit (owner decision: taxes and fines). */
        private List<String> excludedCategories = new ArrayList<>(List.of("RPSIM_TAX_PAYMENT", "RPSIM_TAX_REFUND",
                "RPSIM_FINE"));
        /** Prepayments per quarter (periods 1, 4, 7, 10) = this share of the last assessed tax / 4. */
        private double prepaymentShare = 1.0;
        /** Game days to pay a bill of the tax office (pay by button). */
        private double paymentDays = 14;
        /** Late fee (booked as FINE) per started game month overdue, as share of the open tax. */
        private double lateFeeRate = 0.01;
        /** Overdue months before the tax office threatens enforcement (text and trust only, nothing is seized). */
        private int enforcementAfterMonths = 2;
        private double reminderTrustDelta = -2;
        private double enforcementTrustDelta = -5;
        /** Tax advisor: monthly fee, share of the tax saved, reminder before a deadline, audit factor. */
        private long advisorMonthlyFee = 150;
        private double advisorTaxReduction = 0.1;
        private double advisorReminderDays = 3;
        private double advisorAuditFactor = 0.5;
        private double advisorOfferValidDays = 7;
        /** Audit: chance per assessed year; result after audit-days. */
        private double auditProbability = 0.15;
        private double auditDays = 7;
        /** Audit: a month whose operating expenses exceed this multiple of the year's monthly average is disputed. */
        private double auditJumpFactor = 2.0;
        /** Audit: share of the disputed expenses (unknown categories + excess of jump months) that is not accepted. */
        private double auditDisallowedShare = 0.5;
    }

    /** Roadmap V2 R2-E2: authority - rotation, cultivation duty, animal welfare (placeholders). */
    @Getter @Setter
    public static class Authority {
        private boolean enabled = true;
        /** Rotation premium per hectare for fields with another crop than the year before (SUBSIDY). */
        private double rotationPremiumPerHa = 40;
        /** Share of the premium cut when a field has the same crop again after a notice. */
        private double rotationCutShare = 0.5;
        /** Cultivation duty: game months without a crop and with weeds / stones before the authority writes. */
        private double dutyMonths = 3;
        private long dutyFine = 500;
        /** Animal welfare: health below this, or food / water empty, for welfare-days game days. */
        private double welfareHealthThreshold = 30;
        private double welfareDays = 3;
        private long welfareFine = 1000;
        private double welfareReputationDelta = -3;
        /** Announced inspection: result after this many game days (the player can react). */
        private double inspectionDays = 5;
        private double violationTrustDelta = -3;
        /** At most this many inspections announced per game month. */
        private int maxInspectionsPerMonth = 2;
    }

    /** Roadmap V2 R2-E3: family and succession (placeholders). */
    @Getter @Setter
    public static class Family {
        private boolean enabled = true;
        /** Monthly retirement payment to the parents (only for an inherited farm or a return home). */
        private long retirementPayment = 800;
        private double fieldSoldTrustDelta = -15;
        /** Help at harvest time: chance per harvest period (FS25 periods) and trust. */
        private List<Integer> harvestPeriods = new ArrayList<>(List.of(6, 7, 8));
        private double harvestHelpProbability = 0.5;
        private double harvestHelpTrustDelta = 2;
        /** FS25 period of the school start of a child (September). */
        private int schoolStartPeriod = 7;
    }

    /** Roadmap V2 R2-E4: clubs and festivals (placeholders). */
    @Getter @Setter
    public static class Clubs {
        private boolean enabled = true;
        /** Festival calendar: FS25 period, host (club key or character role). */
        private List<Festival> festivals = new ArrayList<>(List.of(
                new Festival("MAIBAUM", 3, "VILLAGER"), new Festival("SCHUETZENFEST", 4, "SHOOTING_CLUB"),
                new Festival("FEUERWEHRFEST", 6, "FIRE_BRIGADE"), new Festival("ERNTEDANKFEST", 8, "COOPERATIVE"),
                new Festival("WEIHNACHTSMARKT", 10, "VILLAGER")));
        private double invitationDays = 5;
        private double invitationAcceptTrustDelta = 2;
        private double invitationIgnoreTrustDelta = -1;
        /** Sponsoring: chance per game month, cooldown, fixed tiers (€) and reputation per 100 €. */
        private double sponsoringProbabilityPerMonth = 0.3;
        private double sponsoringCooldownDays = 30;
        private List<Long> sponsoringTiers = new ArrayList<>(List.of(250L, 500L, 1000L));
        private double sponsoringReputationPer100 = 0.5;
        private double sponsoringTrustDelta = 3;
        private double sponsoringDeclineTrustDelta = -1;
        private double sponsoringDecisionDays = 7;
    }

    @Getter @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Festival {
        private String key;
        private int period;
        private String host;
    }

    /** Roadmap V2 R2-D: reactions to the vanilla loan and the FS25 field menu (placeholders). */
    @Getter @Setter
    public static class VanillaBypass {
        /** Master switch (the player can also switch the reactions off per savegame on the settings page). */
        private boolean enabled = true;
        /** D1: an increase of the vanilla loan by at least this amount (€, per game day) counts as a new loan. */
        private double loanMinIncrease = 5000;
        /** D1: trust loss of the bank advisor per 10,000 € taken, capped at loan-trust-max. */
        private double loanTrustPer10k = 1;
        private double loanTrustMax = 8;
        /** D1: from the second vanilla loan while one is open: surcharge on the interest of new credits until repaid. */
        private double loanInterestSurcharge = 0.01;
        /** D1: a repayment of at least this amount (€, per game day) gets a reaction of the bank. */
        private double loanMinRepayment = 5000;
        private double loanRepaidTrustDelta = 1;
        /** D2: an NPC field bought over the owner's head in the field menu. */
        private double fieldTrustDelta = -8;
        private double fieldReputationDelta = -2;
        /** D2: the former owner claims this share of the game price as compensation (0 = no claim). */
        private double compensationShare = 0.1;
        private double compensationDecisionDays = 7;
        /** D2: refusing (or ignoring) the claim costs this much more trust. */
        private double compensationDeclineTrustDelta = -5;
        /** D3: one-time hint of the cooperative about helpers without employee. */
        private boolean outsideHelpersHint = true;
    }

    /** Roadmap V2 R2-C: fields, crops and weather (placeholders). */
    @Getter @Setter
    public static class Fields {
        /**
         * Yield in liters per m² per FS25 fruit type, used only when the mod does not export {@code litersPerSqm}
         * (older mod). Empty = unknown (hail then uses the per-hectare range, the bank counts no standing crop).
         */
        private Map<String, Double> yieldLitersPerSqm = new LinkedHashMap<>();
        /** C2: a gap between two weather samples above this many game minutes is not counted (backend was off). */
        private double rainSampleMaxGapMinutes = 180;
        /** C4: weedState from which the neighbor minds the weeds (FS25 raw weed state). */
        private int weedHighState = 5;
        /** C4: stoneLevel from which the neighbor minds the stones (FS25 raw stone level). */
        private int stoneHighLevel = 2;
        /** C4: game months weeds / stones stay high before the neighbor writes (friendly). */
        private double neighborAfterMonths = 2;
        /** C4: game months after the friendly message before the annoyed one (with the trust loss). */
        private double neighborRepeatMonths = 1;
        private double neighborTrustDelta = -2;
        /** C4: game months without a crop before the village gossips about a fallow field. */
        private double fallowGossipMonths = 4;
        /** C4: congratulation of the cooperative when every harvestable field of an FS25 year was harvested in time. */
        private double harvestCongratulationTrustDelta = 1;
        /** C4: at most this many field messages (neighbor, gossip, congratulation) per game month. */
        private int maxMessagesPerMonth = 2;
        /** C6: field work hints of the cooperative (switch per savegame on the settings page as well). */
        private boolean hintsEnabled = true;
        private double hintCooldownDays = 7;
    }
}
