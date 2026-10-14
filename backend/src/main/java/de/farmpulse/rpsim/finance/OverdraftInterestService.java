package de.farmpulse.rpsim.finance;

import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Overdraft interest (owner decisions 2026-10-14): at every month start, while the overdraft is on, a negative balance
 * of the latest snapshot costs balance x interest rate per year / 12 (rounded to whole euro), booked as
 * {@code OVERDRAFT_INTEREST}. The booking carries {@code ignoreFunds}, so the mod books it even beyond the limit.
 */
@Service
public class OverdraftInterestService {

    private final SavegameRepository savegames;
    private final LiquidityService liquidity;
    private final OutboxService outbox;

    public OverdraftInterestService(SavegameRepository savegames, LiquidityService liquidity, OutboxService outbox) {
        this.savegames = savegames;
        this.liquidity = liquidity;
        this.outbox = outbox;
    }

    @EventListener
    @Order(20)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long interest = interest(sg);
        if (interest > 0) {
            outbox.money(sg, -interest, MoneyReason.OVERDRAFT_INTEREST, "Dispozinsen", Related.none());
        }
    }

    /** Interest of one month on the negative balance of the latest snapshot; 0 while the overdraft is off. */
    public long interest(Savegame sg) {
        if (!sg.isOverdraftEnabled() || sg.getOverdraftInterestPercent() <= 0) {
            return 0;
        }
        long balance = liquidity.latestBalance(sg);
        if (balance >= 0) {
            return 0;
        }
        return Math.round(-balance * sg.getOverdraftInterestPercent() / 100.0 / GameTime.PERIODS_PER_YEAR);
    }
}
