package de.farmpulse.rpsim.domain;

/**
 * Roadmap V2 R2-F2: occasions that can be asked in the game as a yes/no question. Each one is switched on per savegame
 * (default: only calls). The numbers of every question come from the backend; the answer calls the same service method
 * as the button in the browser.
 */
public enum PromptKind {
    /** Incoming call: accept / decline. */
    CALL,
    /** Offered lease, maintenance or insurance contract (accept / decline) and the renewal of a lease. */
    CONTRACT_OFFER,
    /** Compensation offer of the hunting tenant (accept; negotiating stays in the browser). */
    WILDLIFE_OFFER,
    /** Counter offer of the bank (accept / decline). */
    CREDIT_COUNTER,
    /** Invitation to a festival (R2-E4, accept / decline). */
    INVITATION,
    /** Owner decision: compensation claim of R2-D2 (pay / refuse). */
    COMPENSATION_CLAIM,
    /** Owner decision: tax bill of R2-E1 (pay; "no" leaves it open). */
    TAX_BILL,
    /** Owner decision: offer of the tax advisor of R2-E1 (accept / decline). */
    TAX_ADVISOR
}
