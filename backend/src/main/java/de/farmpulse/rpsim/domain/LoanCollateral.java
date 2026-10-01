package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Roadmap V3 R3-K1: an own field as collateral (Grundschuld) - requested or proposed in a credit application, pledged
 * for its loan. The collateral value (field price x loan-to-value) is fixed when the application is scored.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "loan_collateral")
public class LoanCollateral extends SavegameScoped {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id")
    private CreditApplication application;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_id")
    private Loan loan;

    @Column(name = "farmland_id", nullable = false)
    private int farmlandId;

    @Column(name = "collateral_value", nullable = false)
    private long collateralValue;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 16)
    private CollateralStatus status;

    /** The bank agreed to a sale in the tool; the proceeds repay the collateral value. */
    @Column(name = "sale_consent", nullable = false)
    private boolean saleConsent;

    /** Repayment booked with the sale of the field (re-pledged when the mod refuses it). */
    @Column(name = "release_instruction_id", length = 64)
    private String releaseInstructionId;

    @Column(name = "pledged_game_time")
    private Long pledgedGameTime;

    @Column(name = "released_game_time")
    private Long releasedGameTime;
}
