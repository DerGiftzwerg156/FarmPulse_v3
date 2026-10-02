package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3 R3-V2 / R3-V3: one used-machine deal - a purchase from the workshop or a neighbour (BUY: catalog item and
 * used values rolled by the backend, VEHICLE_SPAWN after the agreement) or the sale of an own machine to neighbours
 * (SELL: one negotiation per interested neighbour in a sale group, VEHICLE_REMOVE + VEHICLE_SALE after the agreement).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "vehicle_deal")
public class VehicleDeal extends SavegameScoped {

    public static final String BUY = "BUY";
    public static final String SELL = "SELL";
    /** Negotiation running. */
    public static final String OPEN = "OPEN";
    /** Agreed, the instruction is on its way to the game. */
    public static final String AGREED = "AGREED";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    /** The negotiation ended without an agreement (expired, rejected, withdrawn). */
    public static final String ENDED = "ENDED";
    public static final String WORKSHOP = "WORKSHOP";
    public static final String NEIGHBOR = "NEIGHBOR";

    @Column(name = "direction", nullable = false, length = 16)
    private String direction;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** BUY: WORKSHOP or NEIGHBOR. */
    @Column(name = "seller_kind", length = 16)
    private String sellerKind;

    /** BUY: the seller; SELL: the buyer once agreed. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    private Character character;

    @Column(name = "store_xml_filename", length = 512)
    private String storeXmlFilename;

    @Column(name = "vehicle_name", length = 255)
    private String vehicleName;

    @Column(name = "category_name", length = 64)
    private String categoryName;

    @Column(name = "list_price")
    private Long listPrice;

    @Column(name = "age_months")
    private Integer ageMonths;

    @Column(name = "operating_hours")
    private Integer operatingHours;

    @Column(name = "damage")
    private Double damage;

    @Column(name = "wear")
    private Double wear;

    /** BUY: used price of the game formula; SELL: the game's sell value (assets.vehicles[].value). */
    @Column(name = "game_price")
    private Long gamePrice;

    /** Base of the negotiation: BUY game price with markup / discount, SELL the game value. */
    @Column(name = "base_price", nullable = false)
    private long basePrice;

    @Column(name = "asking_price")
    private Long askingPrice;

    @Column(name = "final_price")
    private Long finalPrice;

    /** SELL: the own vehicle; BUY: the delivered vehicle (ack result.vehicleId). */
    @Column(name = "vehicle_id", length = 64)
    private String vehicleId;

    @Column(name = "sale_group_id", length = 64)
    private String saleGroupId;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_game_time")
    private Long nextAttemptGameTime;

    @Column(name = "failure_reason", length = 64)
    private String failureReason;

    @Column(name = "created_game_time", nullable = false)
    private long createdGameTime;

    @Column(name = "closed_game_time")
    private Long closedGameTime;

    /** Roadmap V3.1 R31-A2: the deal buys the demo machine of this loan (no delivery, only the price). */
    @Column(name = "demo_loan_id")
    private Long demoLoanId;
}
