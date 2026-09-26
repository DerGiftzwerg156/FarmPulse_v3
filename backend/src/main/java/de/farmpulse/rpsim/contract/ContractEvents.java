package de.farmpulse.rpsim.contract;

/** Events of the contract billing (listened to by the kind-specific services). */
public final class ContractEvents {

    private ContractEvents() {
    }

    /** A monthly payment could not be made (no liquidity, or the mod refused the booking). */
    public record PaymentMissed(Long savegameId, Long contractId, int missedPayments) {
    }

    /** A monthly payment was booked. */
    public record PaymentBooked(Long savegameId, Long contractId) {
    }
}
