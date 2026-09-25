package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Credit application; the score is computed immediately but only becomes visible at decisionVisibleAtGameTime. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "credit_application")
public class CreditApplication extends SavegameScoped {

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "purpose", nullable = false, length = 500)
    private String purpose;

    @Column(name = "term_months", nullable = false)
    private int termMonths;

    @Column(name = "submitted_at_game_time", nullable = false)
    private long submittedAtGameTime;

    @Column(name = "decision_visible_at_game_time", nullable = false)
    private long decisionVisibleAtGameTime;

    @Column(name = "final_score", nullable = false)
    private double finalScore;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "decision", nullable = false, length = 32)
    private CreditDecision decision;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "reason_category", nullable = false, length = 40)
    private CreditReasonCategory reasonCategory;

    @Column(name = "offered_amount")
    private Long offeredAmount;

    @Column(name = "offered_term_months")
    private Integer offeredTermMonths;

    @Column(name = "offered_interest_rate")
    private Double offeredInterestRate;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private CreditApplicationStatus status;

    @Column(name = "loan_id")
    private Long loanId;

    @Column(name = "narrated", nullable = false)
    private boolean narrated;
}
