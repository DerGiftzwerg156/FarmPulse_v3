package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3.1 R31-A2: a borrowed machine of a neighbour (LOAN: rent per game day, back after the agreed days) or a
 * demo machine of the workshop (DEMO: free for 1-2 game days, then a purchase offer). The machine is loaded with
 * VEHICLE_SPAWN (price 0) and taken back with VEHICLE_REMOVE; while it stands on the farm it is no asset of the farm.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "machine_loan")
public class MachineLoan extends SavegameScoped {

    public static final String LOAN = "LOAN";
    public static final String DEMO = "DEMO";
    /** VEHICLE_SPAWN on its way (or waiting for a new attempt after NO_SPACE). */
    public static final String DELIVERING = "DELIVERING";
    /** On the farm. */
    public static final String ACTIVE = "ACTIVE";
    /** VEHICLE_REMOVE on its way. */
    public static final String RETURNING = "RETURNING";
    /** DEMO: the workshop's purchase offer is negotiated (the machine stays meanwhile). */
    public static final String PURCHASE_OFFER = "PURCHASE_OFFER";
    public static final String RETURNED = "RETURNED";
    public static final String PURCHASED = "PURCHASED";
    /** The machine disappeared without being returned (claim = game value). */
    public static final String LOST = "LOST";
    /** Never delivered (refused by the game). */
    public static final String FAILED = "FAILED";

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** The neighbour (LOAN) or the workshop (DEMO). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    private Character character;

    @Column(name = "store_xml_filename", nullable = false, length = 512)
    private String storeXmlFilename;

    @Column(name = "vehicle_name", length = 255)
    private String vehicleName;

    @Column(name = "category_name", length = 64)
    private String categoryName;

    @Column(name = "list_price", nullable = false)
    private long listPrice;

    @Column(name = "age_months", nullable = false)
    private int ageMonths;

    @Column(name = "operating_hours", nullable = false)
    private int operatingHours;

    @Column(name = "damage", nullable = false)
    private double damage;

    @Column(name = "wear", nullable = false)
    private double wear;

    /** Agreed game days. */
    @Column(name = "days", nullable = false)
    private int days;

    /** Rent per game day (0 for a demo). */
    @Column(name = "daily_rent", nullable = false)
    private long dailyRent;

    /** uniqueId in the game (ack result of VEHICLE_SPAWN). */
    @Column(name = "vehicle_id", length = 64)
    private String vehicleId;

    /** Condition 0..100 at the delivery (from the rolled damage) and in the last export. */
    @Column(name = "start_condition")
    private Double startCondition;

    @Column(name = "last_condition")
    private Double lastCondition;

    /** Game value in the last export (claim when the machine disappears). */
    @Column(name = "last_value")
    private Long lastValue;

    @Column(name = "delivered_game_time")
    private Long deliveredGameTime;

    /** End of the agreed time (delivery + days). */
    @Column(name = "ends_game_time")
    private Long endsGameTime;

    @Column(name = "rent_days_booked", nullable = false)
    private int rentDaysBooked;

    @Column(name = "late_days", nullable = false)
    private int lateDays;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_game_time")
    private Long nextAttemptGameTime;

    /** DEMO: the used-machine deal of the purchase offer. */
    @Column(name = "vehicle_deal_id")
    private Long vehicleDealId;

    /** Damage compensation or claim booked at the end. */
    @Column(name = "compensation")
    private Long compensation;

    @Column(name = "end_reason", length = 64)
    private String endReason;

    @Column(name = "created_game_time", nullable = false)
    private long createdGameTime;

    @Column(name = "closed_game_time")
    private Long closedGameTime;

    public boolean running() {
        return DELIVERING.equals(status) || ACTIVE.equals(status) || RETURNING.equals(status)
                || PURCHASE_OFFER.equals(status);
    }
}
