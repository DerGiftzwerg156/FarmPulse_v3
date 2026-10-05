package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3.1 R31-B2: application for an investment grant. APPLIED until the processing time ends, APPROVED while the
 * purchases count (rises of the journal type of the kind after the approval, up to the purchase deadline), PAID after
 * the proof (button or end of the deadline), EXPIRED when nothing was bought. {@code buyMonthKey} / {@code
 * buyMonthAmount}: journal month and its absolute sum of the purchase type at the last export; {@code sellMonthKey} /
 * {@code sellMonthAmount} the same for SHOP_VEHICLE_SELL during the binding period.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "investment_grant")
public class InvestmentGrant extends SavegameScoped {

    public static final String BUILDING = "BUILDING";
    public static final String MACHINE = "MACHINE";

    public static final String APPLIED = "APPLIED";
    public static final String APPROVED = "APPROVED";
    public static final String PAID = "PAID";
    public static final String EXPIRED = "EXPIRED";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    private Character character;

    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "planned_sum", nullable = false)
    private long plannedSum;

    @Column(name = "applied_game_time", nullable = false)
    private long appliedGameTime;

    @Column(name = "approval_due_game_time", nullable = false)
    private long approvalDueGameTime;

    @Column(name = "approved_game_time")
    private Long approvedGameTime;

    @Column(name = "purchase_deadline_game_time")
    private Long purchaseDeadlineGameTime;

    @Column(name = "recognised_sum", nullable = false)
    private long recognisedSum;

    @Column(name = "buy_month_key")
    private Long buyMonthKey;

    @Column(name = "buy_month_amount")
    private Double buyMonthAmount;

    @Column(name = "sell_month_key")
    private Long sellMonthKey;

    @Column(name = "sell_month_amount")
    private Double sellMonthAmount;

    /** Own vehicles (uniqueId, comma separated) at the approval - only vehicles that appear later are funded. */
    @Lob
    @Column(name = "baseline_vehicles")
    private String baselineVehicles;

    @Column(name = "grant_amount")
    private Long grantAmount;

    @Column(name = "paid_game_time")
    private Long paidGameTime;

    @Column(name = "binding_ends_game_time")
    private Long bindingEndsGameTime;

    @Column(name = "repaid_amount", nullable = false)
    private long repaidAmount;

    @Column(name = "closed_game_time")
    private Long closedGameTime;
}
