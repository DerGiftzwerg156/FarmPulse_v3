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
 * Roadmap V4 R4-D: one line of the contribution margin of a field book entry. Amounts are signed (costs negative,
 * income positive); the correction ({@code litersManual} / {@code amountManual}) wins and can be given back.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "field_margin_item")
public class FieldMarginItem extends SavegameScoped {

    /** Kind of a line; the input kinds come from the fill type ({@code rpsim.formulas.field-margin.categories}). */
    public enum Kind {
        SEED, FERTILIZER, LIME, PROTECTION, MANURE, OTHER_INPUT, CONTRACTOR, PREMIUM, RENT, OVERHEAD, EXTRA_COST,
        EXTRA_REVENUE;

        /** Direct costs (D1): what the crop used. */
        public boolean direct() {
            return this == SEED || this == FERTILIZER || this == LIME || this == PROTECTION || this == MANURE
                    || this == OTHER_INPUT || this == EXTRA_COST;
        }
    }

    /** MEASURED = counted by the mod or booked by the tool, DISTRIBUTED = overheads by area, ENTERED = added by hand. */
    public enum Source { MEASURED, DISTRIBUTED, ENTERED }

    @Column(name = "entry_id", nullable = false)
    private long entryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private Source source;

    /** Key of the booking (fill type of a counter line, instruction id, month), so nothing is booked twice. */
    @Column(name = "reference", length = 80)
    private String reference;

    @Column(name = "fill_type", length = 64)
    private String fillType;

    @Column(name = "liters_auto")
    private Double litersAuto;

    @Column(name = "helper_liters")
    private Double helperLiters;

    @Column(name = "amount_auto")
    private Double amountAuto;

    @Column(name = "liters_manual")
    private Double litersManual;

    @Column(name = "amount_manual")
    private Double amountManual;

    @Column(name = "note", length = 200)
    private String note;

    /** R33-F5: booked for an entry of a closed year - shown as a notice, taken over when the year is reopened. */
    @Column(name = "held", nullable = false)
    private boolean held;

    @Column(name = "game_time", nullable = false)
    private long gameTime;

    public Double liters() {
        return litersManual != null ? litersManual : litersAuto;
    }

    /**
     * The amount as it counts: the corrected amount; else the measured amount scaled to corrected litres; else the
     * measured amount (null = no price known).
     */
    public Double amount() {
        if (amountManual != null) {
            return amountManual;
        }
        if (litersManual != null && amountAuto != null && litersAuto != null && litersAuto > 0) {
            return amountAuto * litersManual / litersAuto;
        }
        return amountAuto;
    }

    public boolean corrected() {
        return litersManual != null || amountManual != null;
    }
}
