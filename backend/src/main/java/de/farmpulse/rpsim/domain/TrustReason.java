package de.farmpulse.rpsim.domain;

/** Defined, logged trust events (never free AI interpretation). */
public enum TrustReason {
    ON_TIME_PAYMENT,
    /** T-03: an installment rewarded as on time was not executed by the mod. */
    ON_TIME_PAYMENT_REVERSED,
    /** A substantial Sondertilgung / its reversal when the mod could not book it. */
    SPECIAL_REPAYMENT,
    SPECIAL_REPAYMENT_REVERSED,
    MISSED_PAYMENT,
    PAYMENT_ESCALATION,
    PROMISE_KEPT,
    PROMISE_BROKEN,
    CALL_DECLINED,
    CALL_MISSED,
    TONE_FRIENDLY,
    TONE_RUDE,
    NEGOTIATION_DEAL,
    /** TODO T-20: wildlife damage settled amicably / with a joint measure. */
    WILDLIFE_AGREEMENT,
    /** TODO T-20: compensation refused, dispute. */
    WILDLIFE_DISPUTE,
    /** TODO T-22: a contract referred by the contractor was completed / failed. */
    MISSION_COMPLETED,
    MISSION_FAILED,
    /** Roadmap V2 R2-B5: record harvest revenue of a month, the cooperative congratulates. */
    RECORD_HARVEST,
    /** Roadmap V2 R2-C4: a field stayed weedy / stony although the neighbor asked. */
    FIELD_NEGLECTED,
    /** Roadmap V2 R2-C4: every harvestable field of a year was harvested in time. */
    HARVEST_IN_TIME,
    /** Roadmap V2 R2-D1: a vanilla loan was taken past the bank / repaid. */
    VANILLA_LOAN,
    VANILLA_LOAN_REPAID,
    /** Roadmap V2 R2-D2: field bought over the owner's head in the game menu / compensation refused. */
    FIELD_BYPASS,
    COMPENSATION_DECLINED,
    /** Roadmap V2 R2-E: tax paid late / enforcement threatened, authority violation, family, clubs, invitations. */
    TAX_OVERDUE,
    AUTHORITY_VIOLATION,
    FAMILY_FIELD_SOLD,
    FAMILY_HELP,
    SPONSORING,
    SPONSORING_DECLINED,
    INVITATION_ACCEPTED,
    INVITATION_IGNORED,
    /** Roadmap V3 R3-H: trade with a neighbour done / offer declined / ignored / goods missing in the silo. */
    NEIGHBOR_TRADE,
    NEIGHBOR_TRADE_DECLINED,
    NEIGHBOR_TRADE_IGNORED,
    NEIGHBOR_DISAPPOINTED,
    /** Roadmap V3 R3-K1: a pledged field was sold in the game menu. */
    COLLATERAL_SOLD,
    /** Roadmap V3 R3-K1: the claimed Sondertilgung after such a sale stayed unpaid. */
    COLLATERAL_CLAIM_OVERDUE,
    /** Roadmap V3 R3-M2: forward contract delivered in full / with a shortfall. */
    FORWARD_CONTRACT_FULFILLED,
    FORWARD_CONTRACT_SHORTFALL,
    /** Roadmap V3.2 R32-G2 / G4: bulk order delivered in full (instant or delivery month) / with a shortfall. */
    BULK_ORDER_FULFILLED,
    BULK_ORDER_SHORTFALL,
    /** Roadmap V3 R3-L1: the leased-out field was taken back in the game menu / the family field was leased out. */
    LEASE_OUT_RECLAIMED,
    FAMILY_FIELD_LEASED,
    /** Roadmap V3.1 R31-A1: the contractor finished a work on an own field. */
    CONTRACTOR_WORK,
    /** Roadmap V3.1 R31-A2: borrowed machine returned damaged / disappeared / a daily rent was not paid. */
    MACHINE_LOAN_DAMAGE,
    MACHINE_LOAN_LOST,
    MACHINE_LOAN_RENT_MISSED,
    /** Roadmap V3.1 R31-B2 / R31-B5: a bill of the authority / the social insurance paid late. */
    AUTHORITY_BILL_OVERDUE,
    /** Roadmap V3.1 R31-B5: get-well wishes to a sick or injured employee. */
    GET_WELL_WISHES,
    /** Roadmap V3.1 R31-D: regulars' table, night work, crop damage, school visit, board meeting missed. */
    STAMMTISCH,
    NIGHT_WORK,
    CROP_DAMAGE,
    CROP_DAMAGE_CLAIM_DECLINED,
    SCHOOL_VISIT,
    COOP_BOARD_MISSED,
    /** Roadmap V3.2 R32-I: large investors - offer, considerations, breaches, claim, end of term. */
    INVESTOR_OFFER_IGNORED,
    INVESTOR_FULFILLED,
    INVESTOR_REMINDER,
    INVESTOR_COMPENSATION,
    INVESTOR_TERMINATION,
    INVESTOR_CLAIM_OVERDUE,
    INVESTOR_CONTRACT_ENDED,
    INITIAL,
    OTHER
}
