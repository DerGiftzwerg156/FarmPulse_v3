package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Recurring contract of the fact layer (TODO T-20 insurance, T-22 lease / maintenance). The monthly amount is a
 * formula result; it is booked at the start of every game month (FS25 period) like salaries.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "contract")
public class Contract extends SavegameScoped {

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "kind", nullable = false, length = 32)
    private ContractKind kind;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private ContractStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    private Character character;

    /** Variant, e.g. insurance level BASIC / COMFORT. */
    @Column(name = "level", length = 32)
    private String level;

    @Column(name = "farmland_id")
    private Integer farmlandId;

    @Column(name = "monthly_amount", nullable = false)
    private long monthlyAmount;

    /** Insurance: share of a damage that is reimbursed (0..1). */
    @Column(name = "coverage_rate")
    private Double coverageRate;

    /** Insurance: deductible per damage case. */
    @Column(name = "deductible")
    private Long deductible;

    @Column(name = "term_months")
    private Integer termMonths;

    @Column(name = "started_at_game_time")
    private Long startedAtGameTime;

    @Column(name = "ends_at_game_time")
    private Long endsAtGameTime;

    @Column(name = "next_due_game_time")
    private Long nextDueGameTime;

    @Column(name = "offer_expires_at_game_time")
    private Long offerExpiresAtGameTime;

    @Column(name = "missed_payments", nullable = false)
    private int missedPayments;

    /** True while a monthly payment is overdue (e.g. insurance cover is suspended). */
    @Column(name = "payment_overdue", nullable = false)
    private boolean paymentOverdue;

    @Column(name = "renewal_offered", nullable = false)
    private boolean renewalOffered;

    @Column(name = "end_reason", length = 64)
    private String endReason;

    /** TODO T-22 lease: new monthly rent offered for a renewal (one month before the end). */
    @Column(name = "renewal_amount")
    private Long renewalAmount;

    /** TODO T-22 lease: price at which the owner offers to sell the leased field (null = not for sale). */
    @Column(name = "purchase_price")
    private Long purchasePrice;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
