package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3.1 R31-B2: a machine bought with a grant (a vehicle that appeared in {@code assets.vehicles} after the
 * approval). Sold within the binding period = a pro-rata repayment ({@code repaymentCaseId}, case GRANT_REPAYMENT).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "investment_grant_object")
public class InvestmentGrantObject extends SavegameScoped {

    @Column(name = "grant_id", nullable = false)
    private Long grantId;

    @Column(name = "vehicle_unique_id", nullable = false, length = 64)
    private String vehicleUniqueId;

    /** Game value when the vehicle was first seen. */
    @Column(name = "vehicle_value", nullable = false)
    private double vehicleValue;

    @Column(name = "seen_game_time", nullable = false)
    private long seenGameTime;

    @Column(name = "sold_game_time")
    private Long soldGameTime;

    @Column(name = "repayment")
    private Long repayment;

    @Column(name = "repayment_case_id")
    private Long repaymentCaseId;
}
