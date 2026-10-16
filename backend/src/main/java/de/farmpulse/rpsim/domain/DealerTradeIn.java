package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap vehicle dealer RFH-B4: an own machine given in trade - game value and repair price at the agreement, the
 * dealer's offer for it. OPEN until the delivery removes it (REMOVED) or it disappears before (LOST).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "dealer_trade_in")
public class DealerTradeIn extends SavegameScoped {
    public static final String OPEN = "OPEN";
    public static final String REMOVED = "REMOVED";
    public static final String LOST = "LOST";

    @Column(name = "order_id", nullable = false)
    private long orderId;

    @Column(name = "vehicle_id", nullable = false, length = 64)
    private String vehicleId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "game_value", nullable = false)
    private long gameValue;

    @Column(name = "offer_value", nullable = false)
    private long offerValue;

    @Column(name = "repair_price_at_agreement")
    private Long repairPriceAtAgreement;

    @Column(name = "status", nullable = false, length = 16)
    private String status;
}
