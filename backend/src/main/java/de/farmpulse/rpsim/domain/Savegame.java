package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Aggregate root. Every other entity references it; nothing is reused across savegames. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "savegame")
public class Savegame {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Technical review 10/2026, Phase 1.4 (R-2): optimistic locking - an outdated write fails instead of overwriting. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "bridge_savegame_id", unique = true, length = 255)
    private String bridgeSavegameId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private SavegameStatus status;

    @Column(name = "map_name", length = 255)
    private String mapName;

    @Column(name = "current_game_time", nullable = false)
    private long currentGameTime;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "tone_preset", nullable = false, length = 32)
    private TonePreset tonePreset;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "farm_origin", length = 32)
    private FarmOrigin farmOrigin;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "village_relation", length = 32)
    private VillageRelation villageRelation;

    @Lob
    @Column(name = "backstory_free_text")
    private String backstoryFreeText;

    @Column(name = "free_text_rejected", nullable = false)
    private boolean freeTextRejected;

    @Column(name = "starting_capital_target", nullable = false)
    private long startingCapitalTarget;

    @Column(name = "starting_capital_adjusted", nullable = false)
    private boolean startingCapitalAdjusted;

    @Column(name = "legacy_loan_amount")
    private Long legacyLoanAmount;

    @Lob
    @Column(name = "initial_employees_json")
    private String initialEmployeesJson;

    @Column(name = "dynamic_rotations_this_year", nullable = false)
    private int dynamicRotationsThisYear;

    @Column(name = "rotation_year_index", nullable = false)
    private int rotationYearIndex;

    @Column(name = "last_processed_game_day")
    private Long lastProcessedGameDay;

    @Column(name = "first_game_time")
    private Long firstGameTime;

    @Lob
    @Column(name = "market_context_json")
    private String marketContextJson;

    @Column(name = "generation_seed", nullable = false)
    private long generationSeed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "linked_at")
    private Instant linkedAt;

    @Column(name = "last_congratulation_game_time")
    private Long lastCongratulationGameTime;

    @Column(name = "last_gossip_game_time")
    private Long lastGossipGameTime;

    // ---- TODO T-08: FS25 calendar (game month = FS25 period), updated from every farm_facts.json

    /** Monotonic month counter of the current period. */
    @Column(name = "cal_month_index")
    private Long calMonthIndex;

    /** Game time at which the current period started. */
    @Column(name = "cal_month_start_game_time")
    private Long calMonthStartGameTime;

    @Column(name = "cal_days_per_period")
    private Integer calDaysPerPeriod;

    /** FS25 period 1..12 (1 = March). */
    @Column(name = "cal_period")
    private Integer calPeriod;

    @Column(name = "cal_day_in_period")
    private Integer calDayInPeriod;

    @Column(name = "cal_year")
    private Integer calYear;

    /** Localized period name as shown in the game (g_i18n:formatPeriod()). */
    @Column(name = "cal_period_name", length = 64)
    private String calPeriodName;

    /** TODO T-21: name of the current season from the game's Season table (e.g. WINTER), null if unknown. */
    @Column(name = "cal_season", length = 64)
    private String calSeason;

    // ---- Roadmap V2 R2-B5: evaluation of the booking journal, one complete month after the other

    /** Continuous month number (year * 12 + period - 1) of the last evaluated complete month. */
    @Column(name = "fin_last_month_key")
    private Long finLastMonthKey;

    /** Number of complete months evaluated so far. */
    @Column(name = "fin_months_seen", nullable = false)
    private int finMonthsSeen;

    /** Negative operating results in a row up to the last evaluated month. */
    @Column(name = "fin_negative_streak", nullable = false)
    private int finNegativeStreak;

    /** The bank already warned about the current negative streak. */
    @Column(name = "fin_warning_sent", nullable = false)
    private boolean finWarningSent;

    /** Highest harvest revenue of a complete month so far (record). */
    @Column(name = "fin_record_revenue")
    private Double finRecordRevenue;

    // ---- Roadmap V2 R2-A: employees as FS25 helpers

    /** A1: EMPLOYEES = helpers driven by an employee cost no game wage; VANILLA = game wage as in the base game. */
    @Enumerated(EnumType.STRING)
    @Column(name = "helper_wage_mode", nullable = false, length = 16)
    private HelperWageMode helperWageMode = HelperWageMode.EMPLOYEES;

    /** A3: strict mode - at most as many helpers as active machine operators. */
    @Column(name = "strict_helper_limit", nullable = false)
    private boolean strictHelperLimit;

    /** A0: content of the last EMPLOYEE_ROSTER sent to the mod (sent again only when it changes or after a rewind). */
    @Lob
    @Column(name = "roster_json")
    private String rosterJson;

    @Column(name = "roster_sent_game_time")
    private Long rosterSentGameTime;

    /** A4: the mod reports worked time (farm_facts.workforce) - the workload of machine operators follows it. */
    @Column(name = "workforce_tracked", nullable = false)
    private boolean workforceTracked;

    /** A7: the mod reports husbandry values (farm_facts.husbandries) - keepers work with the real stables. */
    @Column(name = "husbandries_tracked", nullable = false)
    private boolean husbandriesTracked;

    // ---- Roadmap V2 R2-C: fields, crops and weather

    /** C2: game time and rain state of the last weather sample (rain hours = sample and hold). */
    @Column(name = "last_weather_game_time")
    private Long lastWeatherGameTime;

    @Column(name = "last_weather_raining")
    private Boolean lastWeatherRaining;

    /** C1: the mod reports the fields (farm_facts.fields) - damages and reactions follow the real crops. */
    @Column(name = "fields_tracked", nullable = false)
    private boolean fieldsTracked;

    /** C4: FS25 year of the last field sample (a new year closes the harvest year of the fields). */
    @Column(name = "field_year")
    private Integer fieldYear;

    /** C4: monthly cap of the field messages - game month index and messages sent in it. */
    @Column(name = "field_messages_month")
    private Long fieldMessagesMonth;

    @Column(name = "field_messages_count", nullable = false)
    private int fieldMessagesCount;

    /** C6: last field work hint of the cooperative (at most one per cooldown). */
    @Column(name = "last_field_hint_game_time")
    private Long lastFieldHintGameTime;

    /** C6: the player can switch the field work hints off (settings page). */
    @Column(name = "field_hints_enabled", nullable = false)
    private boolean fieldHintsEnabled = true;

    // ---- Roadmap V2 R2-D: the vanilla loan and the field menu become part of the story

    /** D1: remaining vanilla loan of the last export and its game time (a smaller game time = reload). */
    @Column(name = "vanilla_loan_seen")
    private Double vanillaLoanSeen;

    @Column(name = "vanilla_loan_seen_game_time")
    private Long vanillaLoanSeenGameTime;

    /** D1: increases / repayments since the last daily reaction. */
    @Column(name = "vanilla_loan_pending_taken", nullable = false)
    private double vanillaLoanPendingTaken;

    @Column(name = "vanilla_loan_pending_repaid", nullable = false)
    private double vanillaLoanPendingRepaid;

    /** D1: vanilla loans taken while the loan is open; from the second one new credits get a surcharge. */
    @Column(name = "vanilla_loan_takings", nullable = false)
    private int vanillaLoanTakings;

    @Column(name = "vanilla_loan_surcharge", nullable = false)
    private boolean vanillaLoanSurcharge;

    /** D: the player can switch the reactions to the vanilla loan / field menu off (settings page). */
    @Column(name = "vanilla_bypass_enabled", nullable = false)
    private boolean vanillaBypassEnabled = true;

    /** D3: the hint about helpers without employee was sent (once per savegame). */
    @Column(name = "outside_helpers_hint_sent", nullable = false)
    private boolean outsideHelpersHintSent;

    // ---- Roadmap V2 R2-E: new roleplay areas

    /** E2: game month and inspections announced in it (cap). */
    @Column(name = "authority_month")
    private Long authorityMonth;

    @Column(name = "authority_count", nullable = false)
    private int authorityCount;

    /** E4: last sponsoring request of a club. */
    @Column(name = "last_sponsoring_game_time")
    private Long lastSponsoringGameTime;

    /** E3: family chosen in the onboarding. */
    @Column(name = "family_parents", nullable = false)
    private boolean familyParents;

    @Column(name = "family_partner", nullable = false)
    private boolean familyPartner;

    @Column(name = "family_children", nullable = false)
    private boolean familyChildren;

    /** E3: farmland the player marked as family field ("the field at the brook"). */
    @Column(name = "family_field_id")
    private Integer familyFieldId;

    /** E3: monthly retirement payment to the parents (null = none). */
    @Column(name = "retirement_payment")
    private Long retirementPayment;

    /** Roadmap V2 R2-F2: occasions asked in the game (comma separated PromptKind names); null = configured default. */
    @Column(name = "ingame_prompt_kinds", length = 255)
    private String ingamePromptKinds;

    /** Roadmap V3 R3-K2: month index of the shortfall the bank advisor last warned about (once per shortfall). */
    @Column(name = "liquidity_warning_month")
    private Long liquidityWarningMonth;

    /** Roadmap V3 R3-M3: factor on the farm-shop order probability (refusals lower it, deliveries raise it). */
    @Column(name = "farm_shop_factor", nullable = false)
    private double farmShopFactor = 1.0;

    /** Roadmap V3.2 R32-G1: factor on the bulk-order request probability (refusals lower it, full deliveries raise it). */
    @Column(name = "bulk_order_factor", nullable = false)
    private double bulkOrderFactor = 1.0;

    /** Roadmap V3 R3-W1: dry growth months in a row of the current series (0 = no series). */
    @Column(name = "drought_dry_months", nullable = false)
    private int droughtDryMonths;

    /** Roadmap V3 R3-W1: month index of the first dry month of the current series. */
    @Column(name = "drought_series_start_month")
    private Long droughtSeriesStartMonth;

    /** Roadmap V3 R3-W1: the current series already declared its drought (one per series). */
    @Column(name = "drought_series_declared", nullable = false)
    private boolean droughtSeriesDeclared;

    /** Roadmap V3 R3-T1: payment delays count from this game time on (set at the first check after the update). */
    @Column(name = "milestone_watch_from")
    private Long milestoneWatchFrom;

    /** Roadmap V3 R3-T1: closed harvest years in a row with checked fields and no crop-rotation complaint. */
    @Column(name = "rotation_clean_years", nullable = false)
    private int rotationCleanYears;

    /** Roadmap V3 R3-T2: optional farm name (settings, onboarding); heads the chronicle. */
    @Column(name = "farm_name", length = 60)
    private String farmName;

    /** Roadmap V3.1 R31-B: burdening events switched per savegame (settings, default on). */
    @Column(name = "burden_area_check", nullable = false)
    private boolean burdenAreaCheck = true;

    @Column(name = "burden_fertilizer", nullable = false)
    private boolean burdenFertilizer = true;

    @Column(name = "burden_disease", nullable = false)
    private boolean burdenDisease = true;

    @Column(name = "burden_sick_leave", nullable = false)
    private boolean burdenSickLeave = true;

    /** Roadmap V3.1 R31-B3: confirmed findings of the fertiliser rules (the first one is a warning, later ones a fine). */
    @Column(name = "fertilizer_violations", nullable = false)
    private int fertilizerViolations;

    /** Roadmap V3.1 R31-D: burdening events of D (crop damage off by default, roadmap fallback). */
    @Column(name = "burden_night_work", nullable = false)
    private boolean burdenNightWork = true;

    @Column(name = "burden_crop_damage", nullable = false)
    private boolean burdenCropDamage = false;

    @Column(name = "burden_diesel_theft", nullable = false)
    private boolean burdenDieselTheft = true;

    /** Roadmap V3.1 R31-D2: character posts of the current game day in the village chat. */
    @Column(name = "chat_posts_day")
    private Long chatPostsDay;

    @Column(name = "chat_posts_count", nullable = false)
    private int chatPostsCount;

    /** Roadmap V3.1 R31-D3: next invitation, missed invitations in a row, loner reputation so far, rumour bonus. */
    @Column(name = "stammtisch_next_game_time")
    private Long stammtischNextGameTime;

    @Column(name = "stammtisch_missed", nullable = false)
    private int stammtischMissed;

    @Column(name = "stammtisch_loner_sum", nullable = false)
    private double stammtischLonerSum;

    @Column(name = "stammtisch_rumor_bonus", nullable = false)
    private boolean stammtischRumorBonus;

    /** Roadmap V3.1 R31-D4: game time of the last export, night time counted from, last complaint. */
    @Column(name = "night_last_facts_game_time")
    private Long nightLastFactsGameTime;

    @Column(name = "night_counted_from")
    private Long nightCountedFrom;

    @Column(name = "last_night_complaint_game_time")
    private Long lastNightComplaintGameTime;

    /** Roadmap V3.1 R31-D5: the hint before the first complaint was shown. */
    @Column(name = "crop_damage_hint_sent", nullable = false)
    private boolean cropDamageHintSent;

    /** Roadmap V3.1 R31-D6: holiday flat set up since; last organic fertilising seen (smell complaint). */
    @Column(name = "farm_holiday_since")
    private Long farmHolidaySince;

    @Column(name = "last_organic_spread_game_time")
    private Long lastOrganicSpreadGameTime;

    /** Roadmap V3.1 R31-D7: shares, board seat, missed board meetings, effects of the last general assembly. */
    @Column(name = "coop_shares", nullable = false)
    private int coopShares;

    @Column(name = "coop_board", nullable = false)
    private boolean coopBoard;

    @Column(name = "coop_board_missed", nullable = false)
    private int coopBoardMissed;

    @Column(name = "coop_dividend_bonus", nullable = false)
    private double coopDividendBonus;

    @Column(name = "coop_grain_store", nullable = false)
    private boolean coopGrainStore;
}
