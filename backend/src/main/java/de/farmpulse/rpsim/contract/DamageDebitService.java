package de.farmpulse.rpsim.contract;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.domain.InstructionStatus;
import de.farmpulse.rpsim.domain.InstructionType;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.OutboxInstruction;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.OutboxInstructionRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Logic review LR-M10 (owner decision 2026-10-20): a damage the game has to bear (DAMAGE: hail, storm, wildlife) that the
 * mod refused for lack of money is booked again as soon as the liquidity covers it - like an uncollected call-back
 * (T-03). The insurance payout or wildlife compensation runs as before. A damage is open while the latest DAMAGE booking
 * of its case is FAILED with INSUFFICIENT_FUNDS; the new booking carries the same case, so it replaces the refused one.
 */
@Service
public class DamageDebitService {

    private final OutboxInstructionRepository instructions;
    private final OutboxService outbox;
    private final LiquidityService liquidity;
    private final SavegameRepository savegames;
    private final JsonMapper json;

    public DamageDebitService(OutboxInstructionRepository instructions, OutboxService outbox, LiquidityService liquidity,
                              SavegameRepository savegames, JsonMapper json) {
        this.instructions = instructions;
        this.outbox = outbox;
        this.liquidity = liquidity;
        this.savegames = savegames;
        this.json = json;
    }

    @EventListener
    @Order(89)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        rebook(savegames.findById(e.savegameId()).orElseThrow());
    }

    /** Books every refused damage the liquidity covers again; returns how many were sent. */
    @Transactional
    public int rebook(Savegame sg) {
        Map<String, OutboxInstruction> latest = new LinkedHashMap<>();
        for (OutboxInstruction o : instructions.findBySavegameAndTypeOrderByIdAsc(sg, InstructionType.MONEY_TRANSACTION)) {
            if (o.getRelatedEntityId() != null && MoneyReason.DAMAGE.name().equals(liquidity.reason(o))) {
                latest.put(o.getRelatedEntityType() + ":" + o.getRelatedEntityId(), o); // ordered by id: the last wins
            }
        }
        int sent = 0;
        for (OutboxInstruction o : latest.values()) {
            if (o.getStatus() != InstructionStatus.FAILED
                    || !Objects.equals(LiquidityService.INSUFFICIENT_FUNDS, o.getAckMessage())) {
                continue;
            }
            long amount = liquidity.amount(o);
            if (amount >= 0 || liquidity.available(sg) < -amount) {
                continue;
            }
            String note = json.readTree(o.getPayloadJson()).path("note").asString("");
            outbox.money(sg, amount, MoneyReason.DAMAGE, note, new Related(o.getRelatedEntityType(), o.getRelatedEntityId()));
            sent++;
        }
        return sent;
    }
}
