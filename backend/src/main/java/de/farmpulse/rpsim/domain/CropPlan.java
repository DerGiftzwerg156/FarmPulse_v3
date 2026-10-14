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
 * Roadmap V4 R4-A: the plan of one field for one season. {@code fruitType} null = fallow ("Brache"). The season it is
 * for: {@code targetEntryId} (the running field book entry had no crop when the plan was made) or the first entry after
 * {@code afterEntryId}. When that season begins with a crop the plan is MATCHED or DEVIATED, {@code entryId} names the
 * entry and the forecast of that moment is kept in the {@code planned*} values.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "crop_plan")
public class CropPlan extends SavegameScoped {

    public enum Status { OPEN, MATCHED, DEVIATED }

    @Column(name = "farmland_id", nullable = false)
    private int farmlandId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "fruit_type", length = 64)
    private String fruitType;

    @Column(name = "fill_type", length = 64)
    private String fillType;

    /** Chosen sowing month (FS25 period 1..12), null = the sowing window of the crop. */
    @Column(name = "sow_period")
    private Integer sowPeriod;

    @Column(name = "note", length = 200)
    private String note;

    @Column(name = "created_game_time", nullable = false)
    private long createdGameTime;

    @Column(name = "target_entry_id")
    private Long targetEntryId;

    @Column(name = "after_entry_id")
    private Long afterEntryId;

    @Column(name = "entry_id")
    private Long entryId;

    @Column(name = "started_game_time")
    private Long startedGameTime;

    @Column(name = "planned_hectares")
    private Double plannedHectares;

    @Column(name = "planned_liters_per_ha")
    private Double plannedLitersPerHa;

    @Column(name = "planned_yield_source", length = 16)
    private String plannedYieldSource;

    @Column(name = "planned_price")
    private Double plannedPrice;

    @Column(name = "planned_margin")
    private Double plannedMargin;

    /** FS25 year of the last reminder mail at sowing time (once per year). */
    @Column(name = "reminded_year")
    private Integer remindedYear;
}
