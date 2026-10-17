package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap vehicle dealer RFH-B / K3: a position of an order - the choice of the cart, the options with saveId for the
 * delivery, list price, express fee, L1/L2 delivery span and its shares of the agreed price and of the deposit.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "dealer_order_item")
public class DealerOrderItem extends SavegameScoped {
    public static final String OPEN = "OPEN";
    public static final String DELIVERED = "DELIVERED";
    public static final String CANCELLED = "CANCELLED";

    @Column(name = "order_id", nullable = false)
    private long orderId;

    @Column(name = "delivery_id", nullable = false)
    private long deliveryId;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "xml_filename", nullable = false, length = 512)
    private String xmlFilename;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "choice_json", nullable = false, length = 4000)
    private String choiceJson;

    @Column(name = "options_json", nullable = false, length = 4000)
    private String optionsJson;

    @Column(name = "list_price", nullable = false)
    private long listPrice;

    @Column(name = "express_fee", nullable = false)
    private long expressFee;

    @Column(name = "days_min", nullable = false)
    private double daysMin;

    @Column(name = "days_max", nullable = false)
    private double daysMax;

    @Column(name = "price_share")
    private Long priceShare;

    @Column(name = "deposit_share")
    private Long depositShare;

    @Column(name = "status", nullable = false, length = 16)
    private String status;
}
