package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap vehicle dealer RFH-L: one delivery of an order ("zusammen": all positions, "einzeln": one) - one
 * VEHICLE_DELIVERY. {@code deliveryPointId} null = shop place of the game.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "dealer_delivery")
public class DealerDelivery extends SavegameScoped {
    public static final String OPEN = "OPEN";
    public static final String PLANNED = "PLANNED";
    public static final String SENT = "SENT";
    public static final String WAITING = "WAITING";
    public static final String DELIVERED = "DELIVERED";
    public static final String CANCELLED = "CANCELLED";

    @Column(name = "order_id", nullable = false)
    private long orderId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "delivery_point_id")
    private Long deliveryPointId;

    @Column(name = "express", nullable = false)
    private boolean express;

    @Column(name = "delivery_fee", nullable = false)
    private long deliveryFee;

    @Column(name = "agreed_game_time")
    private Long agreedGameTime;

    @Column(name = "due_game_time")
    private Long dueGameTime;

    @Column(name = "delay_checked", nullable = false)
    private boolean delayChecked;

    @Column(name = "delayed", nullable = false)
    private boolean delayed;

    @Column(name = "carries_trade_ins", nullable = false)
    private boolean carriesTradeIns;

    @Column(name = "instruction_id", length = 64)
    private String instructionId;

    @Column(name = "sent_as_retry", nullable = false)
    private boolean sentAsRetry;

    @Column(name = "sent_fee")
    private Long sentFee;

    @Column(name = "last_failure", length = 64)
    private String lastFailure;

    @Column(name = "last_failure_detail", length = 200)
    private String lastFailureDetail;

    @Column(name = "failure_mailed", nullable = false)
    private boolean failureMailed;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "place_used", length = 16)
    private String placeUsed;

    @Column(name = "place_name", length = 60)
    private String placeName;

    @Column(name = "delivered_game_time")
    private Long deliveredGameTime;

    @Column(name = "vehicle_ids", length = 1000)
    private String vehicleIds;
}
