package de.farmpulse.rpsim.contract;

import java.time.Instant;
import java.util.List;

import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.CalendarChangedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.time.GameTimeAdvancedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monthly payments of all contracts (TODO T-20 / T-22), booked at the start of every game month (FS25 period) like
 * salaries. Without liquidity - or when the mod refuses the booking (T-03) - the payment stays due and
 * {@link ContractEvents.PaymentMissed} is published once per overdue episode; the kind-specific services react
 * (suspended insurance cover, lease termination ...).
 */
@Service
public class ContractBillingService {

    public static final String RELATED = "CONTRACT";

    private final ContractRepository contracts;
    private final SavegameRepository savegames;
    private final LiquidityService liquidity;
    private final OutboxService outbox;
    private final GameTime gameTime;
    private final ApplicationEventPublisher events;

    public ContractBillingService(ContractRepository contracts, SavegameRepository savegames, LiquidityService liquidity,
                                  OutboxService outbox, GameTime gameTime, ApplicationEventPublisher events) {
        this.contracts = contracts;
        this.savegames = savegames;
        this.liquidity = liquidity;
        this.outbox = outbox;
        this.gameTime = gameTime;
        this.events = events;
    }

    public static MoneyReason reason(ContractKind kind) {
        return switch (kind) {
            case INSURANCE -> MoneyReason.INSURANCE_PREMIUM;
            case LEASE -> MoneyReason.LEASE_PAYMENT;
            case MAINTENANCE -> MoneyReason.MAINTENANCE_FEE;
        };
    }

    /** Activates an accepted contract: the first payment is due at the start of the next game month. */
    @Transactional
    public Contract activate(Savegame sg, Contract c) {
        long now = sg.getCurrentGameTime();
        c.setStatus(ContractStatus.ACTIVE);
        c.setStartedAtGameTime(now);
        c.setNextDueGameTime(gameTime.addMonths(sg, now, 1));
        if (c.getTermMonths() != null) {
            c.setEndsAtGameTime(gameTime.addMonths(sg, now, c.getTermMonths()));
        }
        if (c.getCreatedAt() == null) {
            c.setCreatedAt(Instant.now());
        }
        return contracts.save(c);
    }

    @EventListener
    @Order(20)
    @Transactional
    public void onGameTime(GameTimeAdvancedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        bill(sg);
    }

    @Transactional
    public void bill(Savegame sg) {
        long now = sg.getCurrentGameTime();
        for (Contract c : contracts.findBySavegameAndStatusOrderByIdAsc(sg, ContractStatus.ACTIVE)) {
            while (c.getNextDueGameTime() != null && c.getNextDueGameTime() <= now
                    && (c.getEndsAtGameTime() == null || c.getNextDueGameTime() < c.getEndsAtGameTime())) {
                if (liquidity.available(sg) < c.getMonthlyAmount()) {
                    missed(sg, c);
                    break;
                }
                outbox.money(sg, -c.getMonthlyAmount(), reason(c.getKind()), note(c), new Related(RELATED, c.getId()));
                c.setNextDueGameTime(gameTime.addMonths(sg, c.getNextDueGameTime(), 1));
                c.setPaymentOverdue(false);
                events.publishEvent(new ContractEvents.PaymentBooked(sg.getId(), c.getId()));
            }
        }
    }

    private void missed(Savegame sg, Contract c) {
        if (c.isPaymentOverdue()) {
            return;
        }
        c.setPaymentOverdue(true);
        c.setMissedPayments(c.getMissedPayments() + 1);
        events.publishEvent(new ContractEvents.PaymentMissed(sg.getId(), c.getId(), c.getMissedPayments()));
    }

    /** T-03: the mod refused a monthly payment - it is due again and counts as missed. */
    @Transactional
    public boolean onPaymentFailed(Long contractId) {
        Contract c = contracts.findById(contractId).orElse(null);
        if (c == null || c.getStatus() != ContractStatus.ACTIVE || c.getNextDueGameTime() == null) {
            return false;
        }
        Savegame sg = c.getSavegame();
        c.setNextDueGameTime(gameTime.addMonths(sg, c.getNextDueGameTime(), -1));
        c.setPaymentOverdue(false);
        missed(sg, c);
        return true;
    }

    static String note(Contract c) {
        return switch (c.getKind()) {
            case INSURANCE -> "Versicherungsprämie";
            case LEASE -> "Pacht Feld " + c.getFarmlandId();
            case MAINTENANCE -> "Wartungsvertrag";
        };
    }

    /** T-08: "days per period" changed - due and end dates keep their month. */
    @EventListener
    @Transactional
    public void onCalendarChanged(CalendarChangedEvent e) {
        for (ContractStatus st : List.of(ContractStatus.ACTIVE, ContractStatus.OFFERED)) {
            for (Contract c : contracts.findBySavegame_IdAndStatus(e.savegameId(), st)) {
                c.setNextDueGameTime(e.remap(c.getNextDueGameTime()));
                c.setEndsAtGameTime(e.remap(c.getEndsAtGameTime()));
            }
        }
    }
}
