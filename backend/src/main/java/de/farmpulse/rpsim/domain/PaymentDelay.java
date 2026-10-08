package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3 R3-T1: one payment delay - a missed loan installment, an overdue tax bill, a missed contract payment, an
 * overdue salary or an unpaid claim after the sale of a pledged field. Read only by the milestone "year without delay".
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "payment_delay")
public class PaymentDelay extends SavegameScoped {

    public static final String LOAN = "LOAN_INSTALLMENT";
    public static final String TAX = "TAX_BILL";
    public static final String CONTRACT = "CONTRACT_PAYMENT";
    public static final String SALARY = "SALARY";
    public static final String COLLATERAL_CLAIM = "COLLATERAL_CLAIM";
    /** Roadmap V3.1 R31-B2 / R31-B5: overdue repayment of a grant or fee of the social insurance. */
    public static final String AUTHORITY_BILL = "AUTHORITY_BILL";
    /** Roadmap V3.2 R32-I4 / R32-I5: overdue claim of an investor (repayment, open compensation). */
    public static final String INVESTOR_CLAIM = "INVESTOR_CLAIM";
    /** A delay still open at the year change counts for the new year too. */
    public static final String OPEN_AT_YEAR_START = "OPEN_AT_YEAR_START";

    @Column(name = "game_time", nullable = false)
    private long gameTime;

    @Column(name = "kind", nullable = false, length = 32)
    private String kind;
}
