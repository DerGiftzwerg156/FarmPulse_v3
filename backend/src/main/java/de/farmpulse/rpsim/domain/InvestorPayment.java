package de.farmpulse.rpsim.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Roadmap V3.2 R32-I: money between the farm and an investor - CAPITAL (INVESTOR_CAPITAL), PAYOUT (R1 / R2,
 * INVESTOR_PAYOUT), COMPENSATION (stage 2, INVESTOR_COMPENSATION) and REPAYMENT (buy-back / repayment,
 * INVESTOR_REPAYMENT). BOOKED = sent to the mod; OPEN = the mod refused it for lack of money (paid by button later,
 * owner decision 2026-10-08) or a repayment that did not fit; CLAIMED = part of a claim case ({@code claimCaseId}).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "investor_payment")
public class InvestorPayment extends SavegameScoped {

    public static final String CAPITAL = "CAPITAL";
    public static final String PAYOUT = "PAYOUT";
    public static final String COMPENSATION = "COMPENSATION";
    public static final String REPAYMENT = "REPAYMENT";

    public static final String BOOKED = "BOOKED";
    public static final String OPEN = "OPEN";
    public static final String CLAIMED = "CLAIMED";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contract_id")
    private InvestorContract contract;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    /** Positive amount (EUR). */
    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "payment_year")
    private Integer paymentYear;

    @Column(name = "game_time", nullable = false)
    private long gameTime;

    @Column(name = "instruction_id", length = 64)
    private String instructionId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "claim_case_id")
    private Long claimCaseId;

    @Column(name = "note", length = 200)
    private String note;
}
