package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3.2 R32-I4: one billing period of a consideration - {@code periodKey} is the month index for monthly
 * considerations (W2, W3, A2) and the FS25 year for yearly ones. Status OPEN until the check, then FULFILLED, NO_DATA
 * (A2 without stable data), REMINDED (stage 1, grace until {@code graceUntil}), MADE_UP (delivered within the grace),
 * COMPENSATED (stage 2) or CANCELLED (termination during the grace). A breach counts even when made up.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "investor_period")
public class InvestorPeriod extends SavegameScoped {

    public static final String OPEN = "OPEN";
    public static final String FULFILLED = "FULFILLED";
    public static final String NO_DATA = "NO_DATA";
    public static final String REMINDED = "REMINDED";
    public static final String MADE_UP = "MADE_UP";
    public static final String COMPENSATED = "COMPENSATED";
    public static final String CANCELLED = "CANCELLED";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "obligation_id")
    private InvestorObligation obligation;

    @Column(name = "period_key", nullable = false)
    private long periodKey;

    @Column(name = "monthly", nullable = false)
    private boolean monthly;

    @Column(name = "period_year", nullable = false)
    private int periodYear;

    /** Required quantity of the period (litres, animals, hectares x 10 for A3), null where nothing is counted. */
    @Column(name = "required")
    private Long required;

    @Column(name = "delivered", nullable = false)
    private long delivered;

    /** A2: sum and number of the mean stable health of the exports in the month. */
    @Column(name = "health_sum", nullable = false)
    private double healthSum;

    @Column(name = "health_samples", nullable = false)
    private int healthSamples;

    /** P2 / P4: month of the request or invitation within the year. */
    @Column(name = "scheduled_month_index")
    private Long scheduledMonthIndex;

    /** P2 / P4: the case of the request or invitation. */
    @Column(name = "case_id")
    private Long caseId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "breach", nullable = false)
    private boolean breach;

    /** The reminder before the end of the period was sent. */
    @Column(name = "reminded_soon", nullable = false)
    private boolean remindedSoon;

    @Column(name = "reminder_case_id")
    private Long reminderCaseId;

    @Column(name = "grace_until")
    private Long graceUntil;

    @Column(name = "shortfall")
    private Long shortfall;

    @Column(name = "compensation")
    private Long compensation;

    @Column(name = "checked_game_time")
    private Long checkedGameTime;
}
