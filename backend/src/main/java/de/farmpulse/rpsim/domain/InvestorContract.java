package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3.2 R32-I: one package of an investor's offer ({@link CaseKind#INVESTOR_OFFER}) and, once accepted, the
 * running contract. The term covers full FS25 years from {@code startMonthIndex} (period 1 of the FS25 year after the
 * acceptance) to {@code endMonthIndex} (period 12 of the last year); the money flows at the acceptance (owner decision
 * 2026-10-08).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "investor_contract")
public class InvestorContract extends SavegameScoped {

    public static final String OFFERED = "OFFERED";
    /** Another package of the same offer was accepted. */
    public static final String DISCARDED = "DISCARDED";
    public static final String DECLINED = "DECLINED";
    public static final String EXPIRED = "EXPIRED";
    public static final String ACTIVE = "ACTIVE";
    public static final String ENDED = "ENDED";
    public static final String TERMINATED = "TERMINATED";

    /** Silent partnership: equity, bought back at face value at the end. */
    public static final String SILENT = "SILENT";
    /** Subordinated loan: debt, repaid at the end; the considerations replace the interest. */
    public static final String SUBORDINATED = "SUBORDINATED";

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    private Character character;

    /** Key of {@code rpsim.formulas.investor.kinds}. */
    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "package_no", nullable = false)
    private int packageNo;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "capital_type", nullable = false, length = 16)
    private String capitalType;

    @Column(name = "years", nullable = false)
    private int years;

    @Column(name = "target_return", nullable = false)
    private double targetReturn;

    /** amount x target return x years (EUR). */
    @Column(name = "target_value", nullable = false)
    private long targetValue;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** An extension offer: the contract it continues (no money flows). */
    @Column(name = "extension_of")
    private Long extensionOf;

    @Column(name = "extended_by")
    private Long extendedBy;

    @Column(name = "start_month_index", nullable = false)
    private long startMonthIndex;

    @Column(name = "end_month_index", nullable = false)
    private long endMonthIndex;

    /** FS25 year of the start month. */
    @Column(name = "start_year", nullable = false)
    private int startYear;

    @Column(name = "offered_game_time", nullable = false)
    private long offeredGameTime;

    @Column(name = "accepted_game_time")
    private Long acceptedGameTime;

    @Column(name = "breaches", nullable = false)
    private int breaches;

    /** The end of the term was announced (three months before). */
    @Column(name = "announced", nullable = false)
    private boolean announced;

    /** The repayment of the last month was booked or became a claim. */
    @Column(name = "repayment_due", nullable = false)
    private boolean repaymentDue;

    @Column(name = "ended_game_time")
    private Long endedGameTime;

    @Column(name = "end_reason", length = 32)
    private String endReason;

    public int endYear() {
        return startYear + years - 1;
    }

    public boolean silent() {
        return SILENT.equals(capitalType);
    }
}
