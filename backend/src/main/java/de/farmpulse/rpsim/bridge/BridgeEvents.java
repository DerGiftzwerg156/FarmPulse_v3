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

    /**
     * The mod acknowledged an instruction (APPLIED / REJECTED / FAILED). Roadmap V3 (R3-Q1): {@code result} = optional
     * result of the action (e.g. vehicleId), empty when the ack carries none.
     */
    public record InstructionAcked(Long savegameId, String instructionId, String status, String relatedType, Long relatedId,
                                   java.util.Map<String, Object> result) {

        public InstructionAcked(Long savegameId, String instructionId, String status, String relatedType, Long relatedId) {
            this(savegameId, instructionId, status, relatedType, relatedId, java.util.Map.of());
        }
    }

    /** Roadmap V2 R2-F1: new answers of the yes/no questions were read (not yet processed ones only). */
    public record PlayerResponsesRead(Long savegameId, java.util.List<BridgeDtos.PlayerAnswer> responses) {
    }

    /** Roadmap V2 R2-F2: instructions.json is about to be written (open questions are synchronised first). */
    public record InstructionsWriting(Long savegameId) {
    }

    /** T-02 / R2-F1: the savegame was reloaded at an earlier game time. */
    public record Rewound(Long savegameId, long previousGameTime, long rewoundToGameTime) {
    }

    /** A FIXED contract ended (reverse channel for delivered quantities). */
    public record ContractReported(Long savegameId, String instructionId, long deliveredQuantity, long maxQuantity,
                                   String endReason) {
    }
}
