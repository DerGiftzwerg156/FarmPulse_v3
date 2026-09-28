package de.farmpulse.rpsim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V2 R2-E1: one FS25 year of the tax office with the traceable calculation of its assessment:
 * profit = operating income + operating expenses (without taxes and fines) - depreciation - interest of the tool
 * credits; taxable = max(0, profit - allowance); tax = taxable x rate - advisor reduction; balance = tax - prepayments.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "tax_year")
public class TaxYear extends SavegameScoped {

    public static final String OPEN = "OPEN";
    public static final String ASSESSED = "ASSESSED";
    public static final String NO_DATA = "NO_DATA";

    @Column(name = "tax_year", nullable = false)
    private int taxYear;

    @Column(name = "start_game_time", nullable = false)
    private long startGameTime;

    @Column(name = "status", nullable = false, length = 32)
    private String status = OPEN;

    /** Complete journal months of the year that went into the assessment. */
    @Column(name = "months", nullable = false)
    private int months;

    @Column(name = "operating_income", nullable = false)
    private long operatingIncome;

    /** Negative (booked expenses). */
    @Column(name = "operating_expense", nullable = false)
    private long operatingExpense;

    @Column(name = "depreciation", nullable = false)
    private long depreciation;

    @Column(name = "interest", nullable = false)
    private long interest;

    @Column(name = "profit", nullable = false)
    private long profit;

    @Column(name = "allowance", nullable = false)
    private long allowance;

    @Column(name = "taxable", nullable = false)
    private long taxable;

    @Column(name = "tax_rate", nullable = false)
    private double taxRate;

    @Column(name = "advisor_reduction", nullable = false)
    private long advisorReduction;

    @Column(name = "tax", nullable = false)
    private long tax;

    @Column(name = "prepayments", nullable = false)
    private long prepayments;

    /** tax - prepayments: > 0 back payment, < 0 refund. */
    @Column(name = "balance", nullable = false)
    private long balance;

    @Column(name = "assessed_game_time")
    private Long assessedGameTime;

    /** null, ANNOUNCED or DONE. */
    @Column(name = "audit_status", length = 32)
    private String auditStatus;

    @Column(name = "audit_result_game_time")
    private Long auditResultGameTime;

    /** Back payment the audit claims (computed when it is announced, while the journal still holds the year). */
    @Column(name = "audit_claim")
    private Long auditClaim;
}
