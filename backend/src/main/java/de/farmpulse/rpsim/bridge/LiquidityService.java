package de.farmpulse.rpsim.bridge;

import java.util.List;
import java.util.Set;

import de.farmpulse.rpsim.domain.FactsSnapshot;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.FactsSnapshotRepository;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Liquidity as seen by the fact layer: latest snapshot balance minus money instructions that are queued but
 * not yet applied by the mod. Used to detect payment failures ("zu wenig Liquidität im FactsSnapshot").
 */
@Service
public class LiquidityService {

    /** Money reasons that are not operating cash flow (financing / one-off transactions). */
    public static final Set<String> NON_OPERATING = Set.of("CREDIT_DISBURSEMENT", "CREDIT_INSTALLMENT", "CREDIT_PENALTY",
            "CREDIT_CALLBACK", "STARTING_CAPITAL_ADJUSTMENT", "FARMLAND_PURCHASE", "FARMLAND_SALE");

    private final FactsSnapshotRepository snapshots;
    private final OutboxInstructionRepository outbox;
    private final JsonMapper json;

    public LiquidityService(FactsSnapshotRepository snapshots, OutboxInstructionRepository outbox, JsonMapper json) {
        this.snapshots = snapshots;
        this.outbox = outbox;
        this.json = json;
    }

    public long latestBalance(Savegame sg) {
        return snapshots.findFirstBySavegameOrderByGameTimeDescIdDesc(sg).map(FactsSnapshot::getBalance).orElse(0L);
    }

    public long pendingMoney(Savegame sg) {
        return outbox.findBySavegameAndStatusOrderByIdAsc(sg, InstructionStatus.PENDING).stream()
                .filter(o -> o.getType() == InstructionType.MONEY_TRANSACTION)
                .mapToLong(this::amount).sum();
    }

    /** Balance available for new payments. */
    public long available(Savegame sg) {
        return latestBalance(sg) + pendingMoney(sg);
    }

    public long amount(OutboxInstruction o) {
        JsonNode n = json.readTree(o.getPayloadJson());
        return n.path("amount").asLong(0);
    }

    public String reason(OutboxInstruction o) {
        return json.readTree(o.getPayloadJson()).path("reason").asString("");
    }

    /** Sum of applied non-operating tool bookings acknowledged within (from, to]. */
    public long nonOperatingApplied(Savegame sg, long from, long to) {
        List<OutboxInstruction> all = outbox.findBySavegameAndTypeOrderByIdAsc(sg, InstructionType.MONEY_TRANSACTION);
        return all.stream()
                .filter(o -> o.getStatus() == InstructionStatus.APPLIED && o.getAckedAtGameTime() != null
                        && o.getAckedAtGameTime() > from && o.getAckedAtGameTime() <= to)
                .filter(o -> NON_OPERATING.contains(reason(o)))
                .mapToLong(this::amount).sum();
    }
}
