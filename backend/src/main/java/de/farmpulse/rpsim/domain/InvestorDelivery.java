package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3.2 R32-I3: a delivery of goods (W1 / W2, {@code STORAGE_TRANSFER OUT}), milk (W3,
 * {@code HUSBANDRY_TRANSFER}) or animals (A1, {@code ANIMAL_TRANSFER OUT}) without money. It counts once the mod
 * acknowledged it (status DONE); a re-sent instruction after a rewind does not count twice.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "investor_delivery")
public class InvestorDelivery extends SavegameScoped {

    public static final String SENT = "SENT";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "obligation_id")
    private InvestorObligation obligation;

    @Column(name = "quantity", nullable = false)
    private long quantity;

    @Column(name = "husbandry_unique_id", length = 64)
    private String husbandryUniqueId;

    @Column(name = "instruction_id", length = 64)
    private String instructionId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** FS25 year in which the mod took the goods (farm report). */
    @Column(name = "delivery_year")
    private Integer deliveryYear;

    @Column(name = "sent_game_time", nullable = false)
    private long sentGameTime;

    @Column(name = "done_game_time")
    private Long doneGameTime;
}
