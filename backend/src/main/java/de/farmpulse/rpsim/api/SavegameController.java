package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.api.Views.CalendarView;
import de.farmpulse.rpsim.api.Views.SavegameView;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.communication.CallService;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.CommunicationRepository;
import de.farmpulse.rpsim.savegame.SavegameContext;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.village.VillageReputationService;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Header context: active savegame, game day, balance (live), unread counts. 204 if no savegame is linked yet. */
@RestController
@RequestMapping("/api/savegame")
public class SavegameController {

    private final SavegameContext context;
    private final LiquidityService liquidity;
    private final CommunicationRepository communications;
    private final CallService calls;
    private final VillageReputationService reputation;
    private final FactsService facts;

    public SavegameController(SavegameContext context, LiquidityService liquidity, CommunicationRepository communications,
                              CallService calls, VillageReputationService reputation, FactsService facts) {
        this.context = context;
        this.liquidity = liquidity;
        this.communications = communications;
        this.calls = calls;
        this.reputation = reputation;
        this.facts = facts;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<SavegameView> current() {
        return context.findActive().map(this::view).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    SavegameView view(Savegame sg) {
        return new SavegameView(sg.getId(), sg.getBridgeSavegameId(), sg.getMapName(), sg.getCurrentGameTime(),
                GameTime.dayIndex(sg.getCurrentGameTime()), liquidity.latestBalance(sg), sg.getTonePreset().name(),
                communications.countBySavegameAndChannelAndReadFlagFalse(sg, Channel.MAIL),
                calls.pending(sg).stream().filter(c -> c.getCallStatus() == de.farmpulse.rpsim.domain.CallStatus.RINGING).count(),
                reputation.tier(sg).name(), calendar(sg),
                facts.marketContext(sg).map(c -> c.detectedMods() == null ? List.<String>of() : c.detectedMods())
                        .orElse(List.of()));
    }

    static CalendarView calendar(Savegame sg) {
        if (sg.getCalPeriod() == null) {
            return null;
        }
        return new CalendarView(sg.getCalPeriod(), sg.getCalPeriodName(),
                sg.getCalDayInPeriod() == null ? 1 : sg.getCalDayInPeriod(),
                sg.getCalDaysPerPeriod() == null ? 1 : sg.getCalDaysPerPeriod(), sg.getCalYear());
    }
}
