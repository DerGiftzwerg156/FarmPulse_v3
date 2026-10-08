package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3.2 R32-G3: bulk order with a delivery month - the whole amount of a request ({@link CaseKind#BULK_ORDER})
 * sold to the buyer's sell point at a fixed price (EUR per 1000 l), delivered by the player in that whole month through
 * {@code PRICE_EVENT / FIXED}; the mod reports the delivered quantity in {@code contractReports} (like
 * {@link ForwardContract}).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "bulk_order")
public class BulkOrder extends SavegameScoped {

    /** Agreed, waiting for or in the delivery month. */
    public static final String OPEN = "OPEN";
    public static final String FULFILLED = "FULFILLED";
    public static final String SHORTFALL = "SHORTFALL";

    /** The request (service case BULK_ORDER) the order was agreed on. */
    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    private Character character;

    @Column(name = "fill_type", nullable = false, length = 64)
    private String fillType;

    @Column(name = "sell_point", nullable = false, length = 128)
    private String sellPoint;

    @Column(name = "quantity", nullable = false)
    private long quantity;

    @Column(name = "fixed_price", nullable = false)
    private long fixedPrice;

    /** Price of the sell point when agreed. */
    @Column(name = "base_price", nullable = false)
    private double basePrice;

    @Column(name = "lead_months", nullable = false)
    private int leadMonths;

    @Column(name = "delivery_start_game_time", nullable = false)
    private long deliveryStartGameTime;

    @Column(name = "deadline_game_time", nullable = false)
    private long deadlineGameTime;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** The PRICE_EVENT / FIXED instruction. */
    @Column(name = "instruction_id", length = 64)
    private String instructionId;

    /** The NOTIFICATION at the start of the delivery month. */
    @Column(name = "notice_instruction_id", length = 64)
    private String noticeInstructionId;

    @Column(name = "created_game_time", nullable = false)
    private long createdGameTime;

    @Column(name = "delivered_quantity")
    private Long deliveredQuantity;

    @Column(name = "end_reason", length = 32)
    private String endReason;

    @Column(name = "penalty")
    private Long penalty;
}
