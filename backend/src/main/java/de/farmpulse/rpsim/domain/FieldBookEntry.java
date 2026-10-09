package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3.3 R33-F1: one season of an own field in the field book. Every value exists twice: as detected from the game
 * ({@code ...Auto}) and as corrected by the player ({@code ...Manual}, null = not corrected); the correction wins (owner
 * decision 2026-10-08) and can be given back (2026-10-09). The {@code last...} values are the field state of the last
 * export, used to detect the measures and the harvest.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "field_book_entry")
public class FieldBookEntry extends SavegameScoped {

    public enum Status { RUNNING, HARVESTED, NO_HARVEST }

    @Column(name = "farmland_id", nullable = false)
    private int farmlandId;

    @Column(name = "field_name", length = 64)
    private String fieldName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    /** FS25 year of the end of the entry (harvest year); null while RUNNING. */
    @Column(name = "harvest_year")
    private Integer harvestYear;

    /** Area of the field when the entry ended (owner decision 2026-10-09: fixed, not editable). */
    @Column(name = "hectares")
    private Double hectares;

    /** The first entry of the field after the update: the levels found at its start count as done (2026-10-09). */
    @Column(name = "first_entry", nullable = false)
    private boolean firstEntry;

    /** Ended in a closed harvest year: kept back as a notice until the year is reopened (R33-F5). */
    @Column(name = "held", nullable = false)
    private boolean held;

    @Column(name = "started_game_time", nullable = false)
    private long startedGameTime;

    @Column(name = "ended_game_time")
    private Long endedGameTime;

    @Column(name = "fruit_type_auto", length = 64)
    private String fruitTypeAuto;

    @Column(name = "fruit_type_manual", length = 64)
    private String fruitTypeManual;

    @Column(name = "fill_type_auto", length = 64)
    private String fillTypeAuto;

    @Column(name = "fill_type_manual", length = 64)
    private String fillTypeManual;

    /** Litres counted by the mod (or booked by a contractor harvest); null = none counted. */
    @Column(name = "liters_auto")
    private Double litersAuto;

    @Column(name = "liters_manual")
    private Double litersManual;

    /** Fertilisations detected: 1 = first, 2 = second (rises of the spray level). */
    @Column(name = "fert_count_auto", nullable = false)
    private int fertCountAuto;

    @Column(name = "fert1_manual")
    private Boolean fert1Manual;

    @Column(name = "fert2_manual")
    private Boolean fert2Manual;

    @Column(name = "limed_auto", nullable = false)
    private boolean limedAuto;

    @Column(name = "limed_manual")
    private Boolean limedManual;

    @Column(name = "rolled_auto", nullable = false)
    private boolean rolledAuto;

    @Column(name = "rolled_manual")
    private Boolean rolledManual;

    @Column(name = "weeds_auto", nullable = false)
    private boolean weedsAuto;

    @Column(name = "weeds_manual")
    private Boolean weedsManual;

    @Column(name = "mulched_auto", nullable = false)
    private boolean mulchedAuto;

    @Column(name = "mulched_manual")
    private Boolean mulchedManual;

    /** Kinds of fertiliser seen with a rise of the spray level, comma separated (FS25 FieldSprayType names). */
    @Column(name = "spray_types", length = 255)
    private String sprayTypes;

    @Column(name = "last_phase", length = 16)
    private String lastPhase;

    @Column(name = "last_growth_state")
    private Integer lastGrowthState;

    @Column(name = "last_spray_level")
    private Integer lastSprayLevel;

    @Column(name = "last_lime_level")
    private Integer lastLimeLevel;

    @Column(name = "last_roller_level")
    private Integer lastRollerLevel;

    @Column(name = "last_weed_state")
    private Integer lastWeedState;

    @Column(name = "last_stubble_level")
    private Integer lastStubbleLevel;

    public String fruitType() {
        return fruitTypeManual != null ? fruitTypeManual : fruitTypeAuto;
    }

    public String fillType() {
        return fillTypeManual != null ? fillTypeManual : fillTypeAuto;
    }

    public Double liters() {
        return litersManual != null ? litersManual : litersAuto;
    }

    public boolean fert1() {
        return fert1Manual != null ? fert1Manual : fertCountAuto >= 1;
    }

    public boolean fert2() {
        return fert2Manual != null ? fert2Manual : fertCountAuto >= 2;
    }

    public boolean limed() {
        return limedManual != null ? limedManual : limedAuto;
    }

    public boolean rolled() {
        return rolledManual != null ? rolledManual : rolledAuto;
    }

    public boolean weeds() {
        return weedsManual != null ? weedsManual : weedsAuto;
    }

    public boolean mulched() {
        return mulchedManual != null ? mulchedManual : mulchedAuto;
    }
}
