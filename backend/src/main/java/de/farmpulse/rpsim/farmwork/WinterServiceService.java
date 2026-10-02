package de.farmpulse.rpsim.farmwork;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.ServiceRoleService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.ContractBillingService;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.1 R31-A4: winter service for the municipality (owner decisions in QUESTIONS.md).
 * <ul>
 *   <li>Offer: once a year in offer-period (October) by the authority, for one winter (winter-periods), when an own
 *   vehicle of the configured shop categories exists ({@code assets.vehicles[].category}, borrowed machines do not
 *   count) and the mod reports the snow height. After a winter with the contract it is offered again only with
 *   renewal-probability (a snow day) or renewal-probability-without-snow (none).</li>
 *   <li>Snow day: a game day of a winter month on which a sample of {@code weather.snowHeight} reaches snow-threshold
 *   (counted from the exports like the rain time of R2-C2, once per day); an in-game hint announces it.</li>
 *   <li>Payment: at every month start for the winter month that ended - base fee + snow days x fee as WINTER_SERVICE.
 *   Without snow (or with snow switched off) only the base fee. Whether the player really cleared snow is not checked
 *   (roadmap "Bewusst nicht aufgenommen").</li>
 * </ul>
 */
@Service
public class WinterServiceService {

    private final ContractRepository contracts;
    private final SavegameRepository savegames;
    private final FactsService facts;
    private final LoanedVehicles loaned;
    private final OutboxService outbox;
    private final ServiceRoleService roles;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public WinterServiceService(ContractRepository contracts, SavegameRepository savegames, FactsService facts,
                                LoanedVehicles loaned, OutboxService outbox, ServiceRoleService roles,
                                NarrationRequestService narration, DiaryService diary, RandomSource random,
                                RpsimProperties props, GameTime gameTime) {
        this.contracts = contracts;
        this.savegames = savegames;
        this.facts = facts;
        this.loaned = loaned;
        this.outbox = outbox;
        this.roles = roles;
        this.narration = narration;
        this.diary = diary;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.WinterService cfg() {
        return props.getFormulas().getWinterService();
    }

    private List<Contract> all(Savegame sg) {
        return contracts.findBySavegameOrderByIdDesc(sg).stream().filter(c -> c.getKind() == ContractKind.WINTER_SERVICE)
                .toList();
    }

    public Optional<Contract> current(Savegame sg) {
        return all(sg).stream().filter(c -> c.getStatus() == ContractStatus.OFFERED || c.getStatus() == ContractStatus.ACTIVE)
                .findFirst();
    }

    /** An own vehicle (not borrowed) of the configured shop categories. */
    public boolean qualifies(Savegame sg, FarmFacts f) {
        if (f == null || f.assets() == null || f.assets().vehicles() == null) {
            return false;
        }
        return loaned.own(sg, f.assets().vehicles()).stream().anyMatch(v -> v != null && v.category() != null
                && cfg().getVehicleCategories().contains(v.category()));
    }

    // ------------------------------------------------------------------------------------------ month start

    @EventListener
    @Order(85)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        int period = gameTime.periodOfYear(sg, now);
        int ended = period == 1 ? GameTime.PERIODS_PER_YEAR : period - 1;
        for (Contract c : all(sg)) {
            if (c.getStatus() == ContractStatus.OFFERED && c.getOfferExpiresAtGameTime() != null
                    && c.getOfferExpiresAtGameTime() <= now) {
                c.setStatus(ContractStatus.DECLINED);
                c.setEndReason("EXPIRED");
            } else if (c.getStatus() == ContractStatus.ACTIVE && cfg().getWinterPeriods().contains(ended)
                    && c.getStartedAtGameTime() != null && c.getStartedAtGameTime() < now) {
                pay(sg, c);
                if (!cfg().getWinterPeriods().contains(period)) {
                    end(sg, c);
                }
            }
        }
        if (period == cfg().getOfferPeriod()) {
            offer(sg);
        }
    }

    /** Base fee + snow days of the winter month that ended (WINTER_SERVICE income). */
    private void pay(Savegame sg, Contract c) {
        long amount = cfg().getBaseFeePerMonth() + (long) c.getSnowDays() * cfg().getFeePerSnowDay();
        outbox.money(sg, amount, MoneyReason.WINTER_SERVICE, "Winterdienst (" + c.getSnowDays()
                + (c.getSnowDays() == 1 ? " Einsatztag)" : " Einsatztage)"), new Related(ContractBillingService.RELATED, c.getId()));
        c.setSnowDays(0);
    }

    private void end(Savegame sg, Contract c) {
        c.setStatus(ContractStatus.ENDED);
        c.setEndReason("WINTER_OVER");
        c.setEndsAtGameTime(sg.getCurrentGameTime());
        narration.request(sg, NarrationEventType.WINTER_SERVICE_ENDED).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("snowDays", c.getSnowDaysTotal()).build())
                .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId()).submit();
        diary.addAuto(sg, "CONTRACT", "Winterdienst beendet", c.getSnowDaysTotal()
                + (c.getSnowDaysTotal() == 1 ? " Einsatztag" : " Einsatztage") + " in diesem Winter.",
                ContractBillingService.RELATED, c.getId());
    }

    /** October: the authority offers the contract for the coming winter (renewal chance after a winter with it). */
    Optional<Contract> offer(Savegame sg) {
        if (!cfg().isEnabled() || current(sg).isPresent()) {
            return Optional.empty();
        }
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.weather() == null || f.weather().snowHeight() == null || !qualifies(sg, f)) {
            return Optional.empty(); // no snow height (older mod, snow off) or no suitable vehicle: not offered
        }
        Optional<Contract> last = all(sg).stream().filter(c -> c.getStatus() == ContractStatus.ENDED).findFirst();
        if (last.isPresent()) {
            double chance = last.get().getSnowDaysTotal() > 0 ? cfg().getRenewalProbability()
                    : cfg().getRenewalProbabilityWithoutSnow();
            if (!random.chance(chance)) {
                return Optional.empty();
            }
        }
        long now = sg.getCurrentGameTime();
        Contract c = new Contract();
        c.setSavegame(sg);
        c.setKind(ContractKind.WINTER_SERVICE);
        c.setStatus(ContractStatus.OFFERED);
        c.setCharacter(roles.ensure(sg, CharacterRole.AUTHORITY));
        c.setMonthlyAmount(cfg().getBaseFeePerMonth());
        c.setTermMonths(cfg().getWinterPeriods().size());
        c.setOfferExpiresAtGameTime(gameTime.monthStart(sg, gameTime.monthIndex(sg, now) + 1)); // until the winter starts
        c.setCreatedAt(java.time.Instant.now());
        contracts.save(c);
        narration.request(sg, NarrationEventType.WINTER_SERVICE_OFFER).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("baseFee", cfg().getBaseFeePerMonth())
                        .put("feePerSnowDay", cfg().getFeePerSnowDay()).put("months", cfg().getWinterPeriods().size())
                        .put("renewal", last.isPresent()).build())
                .category(CommunicationCategory.CONTRACT).related(ContractBillingService.RELATED, c.getId())
                .formLink("/aemter").submit();
        return Optional.of(c);
    }

    // ------------------------------------------------------------------------------------------ decisions

    @Transactional
    public Contract accept(Savegame sg, Long id) {
        Contract c = own(sg, id);
        if (c.getStatus() != ContractStatus.OFFERED) {
            throw new BusinessRuleException("NOT_OFFERED", "Dieses Angebot ist nicht mehr offen.");
        }
        if (c.getOfferExpiresAtGameTime() != null && c.getOfferExpiresAtGameTime() <= sg.getCurrentGameTime()) {
            throw new BusinessRuleException("OFFER_EXPIRED", "Das Angebot ist abgelaufen.");
        }
        long now = sg.getCurrentGameTime();
        c.setStatus(ContractStatus.ACTIVE);
        c.setStartedAtGameTime(now);
        c.setNextDueGameTime(null); // paid by this service, not by the generic monthly billing
        c.setSnowDays(0);
        c.setSnowDaysTotal(0);
        diary.addAuto(sg, "CONTRACT", "Winterdienst übernommen", cfg().getBaseFeePerMonth() + " € je Wintermonat plus "
                + cfg().getFeePerSnowDay() + " € je Einsatztag.", ContractBillingService.RELATED, c.getId());
        return c;
    }

    @Transactional
    public Contract decline(Savegame sg, Long id) {
        Contract c = own(sg, id);
        if (c.getStatus() != ContractStatus.OFFERED) {
            throw new BusinessRuleException("NOT_OFFERED", "Dieses Angebot ist nicht mehr offen.");
        }
        c.setStatus(ContractStatus.DECLINED);
        c.setEndReason("PLAYER");
        return c;
    }

    private Contract own(Savegame sg, Long id) {
        return contracts.findById(id).filter(c -> c.getSavegame().getId().equals(sg.getId())
                && c.getKind() == ContractKind.WINTER_SERVICE).orElseThrow(() -> new NotFoundException("contract " + id));
    }

    // ------------------------------------------------------------------------------------------ snow days

    /** Every export: in a winter month a sample with snow from the threshold counts the game day once. */
    @EventListener
    @Order(85)
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        Contract c = current(sg).filter(x -> x.getStatus() == ContractStatus.ACTIVE).orElse(null);
        if (c == null || !cfg().getWinterPeriods().contains(gameTime.periodOfYear(sg, e.gameTime()))) {
            return;
        }
        FarmFacts f = facts.latest(sg).orElse(null);
        BridgeDtos.Weather w = f == null ? null : f.weather();
        if (w == null || w.snowHeight() == null || w.snowHeight() < cfg().getSnowThreshold()) {
            return;
        }
        long day = GameTime.dayIndex(e.gameTime());
        if (c.getLastSnowDay() != null && c.getLastSnowDay() >= day) {
            return; // counted already (or a sample of an older save)
        }
        c.setLastSnowDay(day);
        c.setSnowDays(c.getSnowDays() + 1);
        c.setSnowDaysTotal(c.getSnowDaysTotal() + 1);
        outbox.notification(sg, cfg().getNotificationText(), "INFO", GameTime.days(day + 1),
                new Related(ContractBillingService.RELATED, c.getId()));
    }

    public List<Contract> contracts(Savegame sg) {
        return all(sg);
    }
}
