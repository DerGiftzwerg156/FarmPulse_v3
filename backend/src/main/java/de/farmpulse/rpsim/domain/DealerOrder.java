package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap vehicle dealer RFH-B (owner decisions 2026-10-15): a request of the cart, the dealer's offer, the negotiation
 * and the order. Net prices = machines - trade-ins; {@code floorPrice} is the hidden lowest price the dealer accepts.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "dealer_order")
public class DealerOrder extends SavegameScoped {
    public static final String REQUESTED = "REQUESTED";
    public static final String OFFERED = "OFFERED";
    public static final String DEPOSIT_PENDING = "DEPOSIT_PENDING";
    public static final String ORDERED = "ORDERED";
    public static final String COMPLETED = "COMPLETED";
    public static final String REJECTED = "REJECTED";
    public static final String EXPIRED = "EXPIRED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "together", nullable = false)
    private boolean together;

    @Column(name = "requested_game_time", nullable = false)
    private long requestedGameTime;

    @Column(name = "offer_game_time")
    private Long offerGameTime;

    @Column(name = "valid_until_game_time")
    private Long validUntilGameTime;

    @Column(name = "list_total", nullable = false)
    private long listTotal;

    @Column(name = "trade_in_value", nullable = false)
    private long tradeInValue;

    @Column(name = "trade_in_offer", nullable = false)
    private long tradeInOffer;

    @Column(name = "offer_price")
    private Long offerPrice;

    @Column(name = "floor_price")
    private Long floorPrice;

    @Column(name = "agreed_price")
    private Long agreedPrice;

    @Column(name = "deposit")
    private Long deposit;

    @Column(name = "negotiation_id")
    private Long negotiationId;

    @Column(name = "closed_game_time")
    private Long closedGameTime;

    /** "Zurück in den Warenkorb" was used after "Keine Einigung" / "Abgelaufen" (the positions are in the cart again). */
    @Column(name = "returned_to_cart", nullable = false)
    private boolean returnedToCart;
}
