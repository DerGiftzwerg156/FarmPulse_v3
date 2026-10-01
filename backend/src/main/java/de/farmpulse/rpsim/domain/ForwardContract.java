package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3 R3-M2: forward contract - a quantity of a fill type sold in advance to a sell point at a fixed price (EUR
 * per 1000 l), delivered in the delivery month through {@code PRICE_EVENT / FIXED}; the mod reports the delivered
 * quantity in {@code contractReports}.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "forward_contract")
public class ForwardContract extends SavegameScoped {

    /** Concluded, waiting for or in the delivery month. */
    public static final String OPEN = "OPEN";
    public static final String FULFILLED = "FULFILLED";
    public static final String SHORTFALL = "SHORTFALL";

    @Column(name = "fill_type", nullable = false, length = 64)
    private String fillType;

    @Column(name = "sell_point", nullable = false, length = 128)
    private String sellPoint;

    @Column(name = "quantity", nullable = false)
    private long quantity;

    @Column(name = "fixed_price", nullable = false)
    private long fixedPrice;

    /** Current price of the sell point when concluded. */
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

    @Column(name = "instruction_id", length = 64)
    private String instructionId;

    @Column(name = "created_game_time", nullable = false)
    private long createdGameTime;

    @Column(name = "delivered_quantity")
    private Long deliveredQuantity;

    @Column(name = "end_reason", length = 32)
    private String endReason;

    @Column(name = "penalty")
    private Long penalty;
}
