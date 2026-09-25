package de.farmpulse.rpsim.bridge;

/** Application events emitted by the bridge synchronisation. */
public final class BridgeEvents {

    private BridgeEvents() {
    }

    /** A new farm_facts.json snapshot of a linked savegame was stored. */
    public record FactsIngested(Long savegameId, Long snapshotId, long gameTime, boolean first) {
    }

    /** market_context.json of a linked savegame changed (initial export or after FARMLAND_TRANSFER). */
    public record MarketContextUpdated(Long savegameId) {
    }

    /** The mod acknowledged an instruction (APPLIED / REJECTED / FAILED). */
    public record InstructionAcked(Long savegameId, String instructionId, String status, String relatedType, Long relatedId) {
    }

    /** A FIXED contract ended (reverse channel for delivered quantities). */
    public record ContractReported(Long savegameId, String instructionId, long deliveredQuantity, long maxQuantity,
                                   String endReason) {
    }
}
