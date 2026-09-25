package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Loan with automatic monthly installments until repaid (fact file stays with the savegame, not the person). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "loan")
public class Loan extends SavegameScoped {

    @Column(name = "application_id")
    private Long applicationId;

    @Column(name = "principal", nullable = false)
    private long principal;

    @Column(name = "remaining_amount", nullable = false)
    private long remainingAmount;

    @Column(name = "interest_rate", nullable = false)
    private double interestRate;

    @Column(name = "term_months", nullable = false)
    private int termMonths;

    @Column(name = "monthly_installment", nullable = false)
    private long monthlyInstallment;

    @Column(name = "purpose", length = 500)
    private String purpose;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private LoanStatus status;

    @Column(name = "legacy", nullable = false)
    private boolean legacy;

    @Column(name = "blocks_new_credit", nullable = false)
    private boolean blocksNewCredit;

    @Column(name = "started_at_game_time", nullable = false)
    private long startedAtGameTime;

    @Column(name = "next_due_game_time", nullable = false)
    private long nextDueGameTime;

    @Column(name = "overdue_since_game_time")
    private Long overdueSinceGameTime;

    @Column(name = "escalation_level", nullable = false)
    private int escalationLevel;

    @Column(name = "missed_installments", nullable = false)
    private int missedInstallments;

    @Column(name = "paid_installments", nullable = false)
    private int paidInstallments;

    @Column(name = "deferrals_used", nullable = false)
    private int deferralsUsed;

    @Column(name = "deferred_until_game_time")
    private Long deferredUntilGameTime;
}
