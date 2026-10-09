package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3.1 R31-B1: the area payment application of one FS25 year ({@code cropYear}). OPEN from the mail of the
 * authority until it is SUBMITTED or LAPSED (late-max-days after the deadline); PAID at the payment period. The
 * on-site check: NONE (not rolled yet), PENDING (selected, announcement waits for a free inspection slot), ANNOUNCED,
 * DONE, NOT_SELECTED.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "direct_payment_application")
public class DirectPaymentApplication extends SavegameScoped {

    public static final String OPEN = "OPEN";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String LAPSED = "LAPSED";
    public static final String PAID = "PAID";

    public static final String CHECK_NONE = "NONE";
    public static final String CHECK_PENDING = "PENDING";
    public static final String CHECK_ANNOUNCED = "ANNOUNCED";
    public static final String CHECK_DONE = "DONE";
    public static final String CHECK_NOT_SELECTED = "NOT_SELECTED";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    private Character character;

    @Column(name = "crop_year", nullable = false)
    private int cropYear;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "opened_game_time", nullable = false)
    private long openedGameTime;

    @Column(name = "deadline_game_time", nullable = false)
    private long deadlineGameTime;

    @Column(name = "submitted_game_time")
    private Long submittedGameTime;

    /** Started game days after the deadline at the submission (late cut). */
    @Column(name = "late_days", nullable = false)
    private int lateDays;

    @Column(name = "check_status", nullable = false, length = 32)
    private String checkStatus = CHECK_NONE;

    /** The AUTHORITY_INSPECTION case of the on-site check. */
    @Column(name = "check_case_id")
    private Long checkCaseId;

    @Column(name = "deviating_hectares")
    private Double deviatingHectares;

    @Column(name = "deviation_cut")
    private Long deviationCut;

    @Column(name = "rotation_cut")
    private Long rotationCut;

    @Column(name = "late_cut")
    private Long lateCut;

    /** Full premium of the declared fields (before cuts), set at the payment. */
    @Column(name = "premium")
    private Long premium;

    @Column(name = "paid_amount")
    private Long paidAmount;

    @Column(name = "closed_game_time")
    private Long closedGameTime;
}
