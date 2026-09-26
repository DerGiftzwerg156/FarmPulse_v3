package de.farmpulse.rpsim.contract;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.character.ServiceRoleService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Channel;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Contract;
import de.farmpulse.rpsim.domain.ContractKind;
import de.farmpulse.rpsim.domain.ContractStatus;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.ContractRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TODO T-20 insurance: the insurance agent sells a storm/hail insurance (monthly premium, coverage, deductible).
 * Storms and hail are only simulated (no game event is read): per game month in the configured periods a damage may
 * occur; it always costs money (DAMAGE). With an active, paid-up insurance the player reports the damage within the
 * deadline and receives {@code round(damage × coverage) − deductible} (INSURANCE_PAYOUT) after a short settlement delay.
 */
@Service
public class InsuranceService {

    public static final String RELATED = "SERVICE_CASE";
    static final EnumSet<CaseKind> KINDS = EnumSet.of(CaseKind.STORM_DAMAGE, CaseKind.HAIL_DAMAGE);

    private final ContractRepository contracts;
    private final ServiceCaseRepository cases;
    private final SavegameRepository savegames;
    private final ContractBillingService billing;
    private final FactsService facts;
    private final OutboxService outbox;
    private final ServiceRoleService roles;
    private final CharacterLookup lookup;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public InsuranceService(ContractRepository contracts, ServiceCaseRepository cases, SavegameRepository savegames,
                            ContractBillingService billing, FactsService facts, OutboxService outbox,
                            ServiceRoleService roles, CharacterLookup lookup, NarrationRequestService narration,
                            DiaryService diary, RandomSource random, RpsimProperties props, GameTime gameTime) {
        this.contracts = contracts;
        this.cases = cases;
        this.savegames = savegames;
        this.billing = billing;
        this.facts = facts;
        this.outbox = outbox;
        this.roles = roles;
        this.lookup = lookup;
        this.narration = narration;
        this.diary = diary;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.Insurance cfg() {
        return props.getFormulas().getInsurance();
    }

    // ------------------------------------------------------------------------------------------ contract

    /** Insured value: reference prices of the own fields + value of the own buildings. */
    public static long insuredValue(FarmFacts f) {
        if (f == null || f.assets() == null) {
            return 0;
        }
        double v = f.assets().farmland().stream().mapToDouble(BridgeDtos.OwnedFarmland::price).sum();
        v += f.assets().placeables().stream().mapToDouble(BridgeDtos.Placeable::value).sum();
        return Math.round(v);
    }

    public long monthlyPremium(Savegame sg, String level) {
        RpsimProperties.InsuranceLevel l = level(level);
        long value = insuredValue(facts.latest(sg).orElse(null));
        return Math.max(l.getMinPremium(), Math.round(value * l.getPremiumRate()));
    }

    private RpsimProperties.InsuranceLevel level(String level) {
        RpsimProperties.InsuranceLevel l = cfg().getLevels().get(level);
        if (l == null) {
            throw new BusinessRuleException("UNKNOWN_LEVEL", "Unbekannter Versicherungstarif: " + level);
        }
        return l;
    }

    public Optional<Contract> active(Savegame sg) {
        return contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.INSURANCE,
                List.of(ContractStatus.ACTIVE)).stream().findFirst();
    }

    /** A written offer of the insurance agent (replaces an older open offer). */
    @Transactional
    public Contract offer(Savegame sg, String level) {
        if (active(sg).isPresent()) {
            throw new BusinessRuleException("INSURANCE_ACTIVE", "Es besteht bereits eine Versicherung.");
        }
        RpsimProperties.InsuranceLevel l = level(level);
        for (Contract old : contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.INSURANCE,
                List.of(ContractStatus.OFFERED))) {
            old.setStatus(ContractStatus.DECLINED);
            old.setEndReason("REPLACED");
        }
        Character agent = roles.ensure(sg, CharacterRole.INSURANCE_AGENT);
        Contract c = new Contract();
        c.setSavegame(sg);
        c.setKind(ContractKind.INSURANCE);
        c.setStatus(ContractStatus.OFFERED);
        c.setCharacter(agent);
        c.setLevel(level);
        c.setMonthlyAmount(monthlyPremium(sg, level));
        c.setCoverageRate(l.getCoverageRate());
        c.setDeductible(l.getDeductible());
        c.setOfferExpiresAtGameTime(sg.getCurrentGameTime() + GameTime.days(cfg().getOfferValidDays()));
        c.setCreatedAt(Instant.now());
        contracts.save(c);
        narration.request(sg, NarrationEventType.INSURANCE_OFFER).from(agent)
                .facts(NarrationFacts.builder().put("level", level).put("monthlyPremium", c.getMonthlyAmount())
                        .put("coveragePercent", Math.round(l.getCoverageRate() * 100)).put("deductible", l.getDeductible())
                        .put("validDays", Math.round(cfg().getOfferValidDays())).build())
                .category(CommunicationCategory.INSURANCE).related(ContractBillingService.RELATED, c.getId())
                .formLink("/contracts?contract=" + c.getId()).submit();
        return c;
    }

    @Transactional
    public Contract accept(Savegame sg, Long contractId) {
        Contract c = own(sg, contractId);
        if (c.getKind() != ContractKind.INSURANCE || c.getStatus() != ContractStatus.OFFERED) {
            throw new BusinessRuleException("NOT_OFFERED", "Dieses Angebot ist nicht mehr offen.");
        }
        if (c.getOfferExpiresAtGameTime() != null && c.getOfferExpiresAtGameTime() < sg.getCurrentGameTime()) {
            throw new BusinessRuleException("OFFER_EXPIRED", "Das Angebot ist abgelaufen.");
        }
        billing.activate(sg, c);
        narration.request(sg, NarrationEventType.INSURANCE_CONFIRMED).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("level", c.getLevel()).put("monthlyPremium", c.getMonthlyAmount())
                        .build())
                .category(CommunicationCategory.INSURANCE).related(ContractBillingService.RELATED, c.getId()).submit();
        diary.addAuto(sg, "INSURANCE", "Versicherung abgeschlossen", "Sturm- und Hagelversicherung (" + c.getLevel()
                + "), Prämie " + c.getMonthlyAmount() + " € pro Monat.", ContractBillingService.RELATED, c.getId());
        return c;
    }

    @Transactional
    public Contract decline(Savegame sg, Long contractId) {
        Contract c = own(sg, contractId);
        if (c.getStatus() != ContractStatus.OFFERED) {
            throw new BusinessRuleException("NOT_OFFERED", "Dieses Angebot ist nicht mehr offen.");
        }
        c.setStatus(ContractStatus.DECLINED);
        c.setEndReason("PLAYER");
        return c;
    }

    /** The player cancels: the cover ends immediately, no further premiums. */
    @Transactional
    public Contract cancel(Savegame sg, Long contractId) {
        Contract c = own(sg, contractId);
        if (c.getKind() != ContractKind.INSURANCE || c.getStatus() != ContractStatus.ACTIVE) {
            throw new BusinessRuleException("NOT_ACTIVE", "Diese Versicherung läuft nicht.");
        }
        end(sg, c, ContractStatus.CANCELLED, "PLAYER");
        return c;
    }

    private void end(Savegame sg, Contract c, ContractStatus status, String reason) {
        c.setStatus(status);
        c.setEndReason(reason);
        c.setEndsAtGameTime(sg.getCurrentGameTime());
        narration.request(sg, NarrationEventType.INSURANCE_CANCELLED).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("reason", reason).build())
                .category(CommunicationCategory.INSURANCE).related(ContractBillingService.RELATED, c.getId()).submit();
        diary.addAuto(sg, "INSURANCE", "Versicherung beendet", "MISSED_PAYMENTS".equals(reason)
                ? "Die Versicherung wurde wegen unbezahlter Prämien gekündigt." : "Die Versicherung wurde gekündigt.",
                ContractBillingService.RELATED, c.getId());
    }

    Contract own(Savegame sg, Long id) {
        return contracts.findById(id).filter(c -> c.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("contract " + id));
    }

    @EventListener
    @Transactional
    public void onPaymentMissed(ContractEvents.PaymentMissed e) {
        Contract c = contracts.findById(e.contractId()).orElse(null);
        if (c == null || c.getKind() != ContractKind.INSURANCE || c.getStatus() != ContractStatus.ACTIVE) {
            return;
        }
        Savegame sg = c.getSavegame();
        if (e.missedPayments() >= cfg().getCancelAfterMissedPayments()) {
            end(sg, c, ContractStatus.ENDED, "MISSED_PAYMENTS");
            return;
        }
        narration.request(sg, NarrationEventType.INSURANCE_PREMIUM_OVERDUE).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("monthlyPremium", c.getMonthlyAmount()).build())
                .category(CommunicationCategory.INSURANCE).related(ContractBillingService.RELATED, c.getId()).submit();
    }

    // ------------------------------------------------------------------------------------------ daily / monthly

    @EventListener
    @Order(70)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (Contract c : contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.INSURANCE,
                List.of(ContractStatus.OFFERED))) {
            if (c.getOfferExpiresAtGameTime() != null && c.getOfferExpiresAtGameTime() < now) {
                c.setStatus(ContractStatus.DECLINED);
                c.setEndReason("EXPIRED");
            }
        }
        for (ServiceCase sc : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.AWAITING_PLAYER)) {
            if (KINDS.contains(sc.getKind()) && sc.getDeadlineGameTime() != null && sc.getDeadlineGameTime() < now) {
                sc.setStatus(CaseStatus.EXPIRED);
                sc.setResolution("DEADLINE_MISSED");
                sc.setClosedAtGameTime(now);
                narration.request(sg, NarrationEventType.INSURANCE_CLAIM_REJECTED).from(sc.getCharacter())
                        .facts(NarrationFacts.builder().put("reason", "DEADLINE_MISSED").put("damageType", sc.getKind())
                                .build())
                        .category(CommunicationCategory.INSURANCE).related(RELATED, sc.getId()).submit();
            }
        }
        maybeFirstOffer(sg);
    }

    /** One proactive offer a few game days after the farm was linked, if the player never had an insurance. */
    void maybeFirstOffer(Savegame sg) {
        if (sg.getFirstGameTime() == null
                || sg.getCurrentGameTime() - sg.getFirstGameTime() < GameTime.days(cfg().getFirstOfferAfterDays())
                || !contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.INSURANCE,
                        EnumSet.allOf(ContractStatus.class)).isEmpty()
                || insuredValue(facts.latest(sg).orElse(null)) <= 0) {
            return;
        }
        offer(sg, "BASIC");
    }

    @EventListener
    @Order(70)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        int period = gameTime.periodOfYear(sg, e.gameTime());
        if (cfg().getHailPeriods().contains(period) && random.chance(cfg().getHailProbabilityPerMonth())) {
            hail(sg);
        }
        if (cfg().getStormPeriods().contains(period) && random.chance(cfg().getStormProbabilityPerMonth())) {
            storm(sg);
        }
    }

    // ------------------------------------------------------------------------------------------ damage

    /** Hail on one random own field: hectares × damage per hectare. */
    @Transactional
    public Optional<ServiceCase> hail(Savegame sg) {
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.assets().farmland().isEmpty()) {
            return Optional.empty();
        }
        BridgeDtos.OwnedFarmland field = random.pick(f.assets().farmland());
        double ha = field.hectares() == null ? 0 : field.hectares();
        long damage = round10(ha * random.uniform(cfg().getHailDamagePerHectareMin(), cfg().getHailDamagePerHectareMax()));
        if (damage <= 0) {
            return Optional.empty();
        }
        return Optional.of(damage(sg, CaseKind.HAIL_DAMAGE, field.farmlandId(), ha, damage));
    }

    /** Storm damage on the buildings: share of the building value. */
    @Transactional
    public Optional<ServiceCase> storm(Savegame sg) {
        FarmFacts f = facts.latest(sg).orElse(null);
        double buildings = f == null ? 0 : f.assets().placeables().stream().mapToDouble(BridgeDtos.Placeable::value).sum();
        long damage = round10(buildings * random.uniform(cfg().getStormDamageShareMin(), cfg().getStormDamageShareMax()));
        if (damage <= 0) {
            return Optional.empty();
        }
        return Optional.of(damage(sg, CaseKind.STORM_DAMAGE, null, null, damage));
    }

    ServiceCase damage(Savegame sg, CaseKind kind, Integer farmlandId, Double hectares, long damage) {
        long now = sg.getCurrentGameTime();
        Optional<Contract> insurance = active(sg);
        boolean covered = insurance.isPresent() && !insurance.get().isPaymentOverdue();
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(kind);
        sc.setFarmlandId(farmlandId);
        sc.setHectares(hectares);
        sc.setDamageAmount(damage);
        sc.setGameTime(now);
        sc.setCreatedAt(Instant.now());
        Character narrator;
        if (covered) {
            narrator = insurance.get().getCharacter();
            sc.setContractId(insurance.get().getId());
            sc.setStatus(CaseStatus.AWAITING_PLAYER);
            sc.setDeadlineGameTime(now + GameTime.days(cfg().getReportDeadlineDays()));
        } else {
            narrator = insurance.map(Contract::getCharacter)
                    .or(() -> lookup.firstActive(sg, CharacterRole.NEIGHBOR_FARMER, CharacterRole.VILLAGER))
                    .orElse(null);
            sc.setStatus(CaseStatus.SETTLED);
            sc.setResolution(insurance.isPresent() ? "COVER_SUSPENDED" : "UNINSURED");
            sc.setClosedAtGameTime(now);
        }
        sc.setCharacter(narrator);
        cases.save(sc);
        outbox.money(sg, -damage, MoneyReason.DAMAGE, (kind == CaseKind.HAIL_DAMAGE ? "Hagelschaden Feld " + farmlandId
                : "Sturmschaden Gebäude"), new Related(RELATED, sc.getId()));
        narration.request(sg, NarrationEventType.DAMAGE_NOTICE).from(narrator)
                .facts(NarrationFacts.builder().put("damageType", kind).put("farmlandId", farmlandId)
                        .put("damageAmount", damage).put("insured", covered)
                        .put("coverSuspended", insurance.isPresent() && !covered)
                        .put("reportDeadlineDays", covered ? Math.round(cfg().getReportDeadlineDays()) : null).build())
                .channel(covered ? Channel.CALL : Channel.MAIL)
                .category(CommunicationCategory.INSURANCE).related(RELATED, sc.getId())
                .formLink(covered ? "/contracts?case=" + sc.getId() : null).submit();
        diary.addAuto(sg, "INSURANCE", kind == CaseKind.HAIL_DAMAGE ? "Hagel auf Feld " + farmlandId : "Sturmschaden",
                "Schaden: " + damage + " €" + (covered ? " – versichert, Meldung ausstehend." : " – nicht versichert."),
                RELATED, sc.getId());
        if (!covered) {
            maybeReoffer(sg);
        }
        return sc;
    }

    /** After an uninsured damage the agent tries again - at most once per cooldown. */
    void maybeReoffer(Savegame sg) {
        if (active(sg).isPresent()) {
            return;
        }
        long now = sg.getCurrentGameTime();
        boolean recent = contracts.findBySavegameAndKindAndStatusInOrderByIdAsc(sg, ContractKind.INSURANCE,
                EnumSet.allOf(ContractStatus.class)).stream()
                .anyMatch(c -> c.getCreatedAt() != null && c.getOfferExpiresAtGameTime() != null
                        && now - (c.getOfferExpiresAtGameTime() - GameTime.days(cfg().getOfferValidDays()))
                        < GameTime.days(cfg().getReofferCooldownDays()));
        if (!recent && insuredValue(facts.latest(sg).orElse(null)) > 0) {
            offer(sg, "BASIC");
        }
    }

    /** Payout of a covered damage: round(damage × coverage) − deductible, never negative. */
    public static long payout(long damage, double coverageRate, long deductible) {
        return Math.max(0, Math.round(damage * coverageRate) - deductible);
    }

    /** The player reports the damage (mail or call) - the insurance settles it. */
    @Transactional
    public ServiceCase report(Savegame sg, Long caseId, Channel channel) {
        ServiceCase sc = cases.findById(caseId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + caseId));
        if (!KINDS.contains(sc.getKind()) || sc.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Dieser Schaden wurde bereits bearbeitet.");
        }
        long now = sg.getCurrentGameTime();
        Contract c = contracts.findById(sc.getContractId()).orElseThrow();
        long amount = payout(sc.getDamageAmount(), c.getCoverageRate(), c.getDeductible());
        long delay = GameTime.days(random.uniform(cfg().getSettlementDelayDaysMin(), cfg().getSettlementDelayDaysMax()));
        sc.setStatus(CaseStatus.SETTLED);
        sc.setResolution("PAID");
        sc.setPayoutAmount(amount);
        sc.setClosedAtGameTime(now);
        if (amount > 0) {
            outbox.money(sg, amount, MoneyReason.INSURANCE_PAYOUT, "Versicherungsleistung", new Related(RELATED, sc.getId()),
                    null, now + delay);
        }
        narration.request(sg, NarrationEventType.INSURANCE_CLAIM_SETTLED).from(c.getCharacter())
                .facts(NarrationFacts.builder().put("damageType", sc.getKind()).put("damageAmount", sc.getDamageAmount())
                        .put("payout", amount).put("deductible", c.getDeductible())
                        .put("coveragePercent", Math.round(c.getCoverageRate() * 100))
                        .put("payoutInDays", Math.round(GameTime.toDays(delay) * 10) / 10.0).build())
                .channel(channel == null ? Channel.MAIL : channel)
                .category(CommunicationCategory.INSURANCE).related(RELATED, sc.getId()).submit();
        diary.addAuto(sg, "INSURANCE", "Schaden gemeldet", "Die Versicherung zahlt " + amount + " € (Schaden "
                + sc.getDamageAmount() + " €).", RELATED, sc.getId());
        return sc;
    }

    public List<ServiceCase> cases(Savegame sg) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, KINDS);
    }

    private static long round10(double v) {
        return Math.round(v / 10.0) * 10;
    }
}
