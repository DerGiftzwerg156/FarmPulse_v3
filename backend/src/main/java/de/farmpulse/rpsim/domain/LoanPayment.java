package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Repayment history of a loan. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "loan_payment")
public class LoanPayment extends SavegameScoped {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_id")
    private Loan loan;

    @Column(name = "game_time", nullable = false)
    private long gameTime;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "type", nullable = false, length = 32)
    private LoanPaymentType type;

    @Column(name = "instruction_id", length = 64)
    private String instructionId;

    /** Principal share of an installment (T-03: needed to reverse a payment the mod could not execute). */
    @Column(name = "principal_part")
    private Long principalPart;

    /** Whether this installment earned the on-time trust bonus (reversed together with the payment). */
    @Column(name = "trust_bonus_given")
    private Boolean trustBonusGiven;
}
