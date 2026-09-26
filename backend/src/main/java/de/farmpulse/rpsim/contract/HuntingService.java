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
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import de.farmpulse.rpsim.village.PublicActionService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TODO T-20 hunter: wild boar damage is simulated per game month on one own field (DAMAGE). The hunter offers a
 * compensation (share of the damage, better with trust); the player accepts, demands more (rounds, the hunter's limit
 * is a formula), agrees on a joint measure (own contribution, fewer damages for some months, better village
 * reputation) or refuses (public dispute). Without an answer the last offer is paid.
 */
@Service
public class HuntingService {

    public static final String RELATED = InsuranceService.RELATED;

    private final ServiceCaseRepository cases;
    private final SavegameRepository savegames;
    private final FactsService facts;
    private final OutboxService outbox;
    private final ServiceRoleService roles;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final TrustScoreService trust;
    private final PublicActionService publicActions;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public HuntingService(ServiceCaseRepository cases, SavegameRepository savegames, FactsService facts, OutboxService outbox,
                          ServiceRoleService roles, NarrationRequestService narration, DiaryService diary,
                          TrustScoreService trust, PublicActionService publicActions, RandomSource random,
                          RpsimProperties props, GameTime gameTime) {
        this.cases = cases;
        this.savegames = savegames;
        this.facts = facts;
        this.outbox = outbox;
        this.roles = roles;
        this.narration = narration;
        this.diary = diary;
        this.trust = trust;
        this.publicActions = publicActions;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.Hunting cfg() {
        return props.getFormulas().getHunting();
    }

    /** Share of the damage the hunter pays, shifted linearly by the trust (−100..100), bounded to 10..100 %. */
    public static double share(double base, double trustScore, double trustInfluence) {
        return Math.max(0.1, Math.min(1.0, base + trustInfluence * trustScore / 100.0));
    }

    long firstOffer(long damage, double trustScore) {
        return round10(damage * share(cfg().getOfferShare(), trustScore, cfg().getTrustInfluence()));
    }

    long limit(long damage, double trustScore) {
        return round10(damage * share(cfg().getMaxShare(), trustScore, cfg().getTrustInfluence()));
    }

    // ------------------------------------------------------------------------------------------ spawning

    @EventListener
    @Order(71)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (!cfg().getPeriods().contains(gameTime.periodOfYear(sg, e.gameTime()))) {
            return;
        }
        double p = cfg().getProbabilityPerMonth() * (measureActive(sg) ? cfg().getMeasureProbabilityFactor() : 1.0);
        if (random.chance(p)) {
            damage(sg);
        }
    }

    /** A joint measure agreed within the last measureEffectMonths lowers the damage probability. */
    boolean measureActive(Savegame sg) {
        long now = sg.getCurrentGameTime();
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.WILDLIFE_DAMAGE)).stream()
                .anyMatch(c -> c.isMeasureAgreed() && c.getClosedAtGameTime() != null
                        && gameTime.addMonths(sg, c.getClosedAtGameTime(), cfg().getMeasureEffectMonths()) > now);
    }

    /** Wild boar damage on one random own field; the hunter reports it with a first offer. */
    @Transactional
    public Optional<ServiceCase> damage(Savegame sg) {
        FarmFacts f = facts.latest(sg).orElse(null);
        if (f == null || f.assets().farmland().isEmpty()) {
            return Optional.empty();
        }
        BridgeDtos.OwnedFarmland field = random.pick(f.assets().farmland());
        double ha = field.hectares() == null ? 0 : field.hectares();
        long damage = round10(ha * random.uniform(cfg().getDamagePerHectareMin(), cfg().getDamagePerHectareMax()));
        if (damage <= 0) {
            return Optional.empty();
        }
        Character hunter = roles.ensure(sg, CharacterRole.HUNTER);
        long now = sg.getCurrentGameTime();
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.WILDLIFE_DAMAGE);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setCharacter(hunter);
        sc.setFarmlandId(field.farmlandId());
        sc.setHectares(ha);
        sc.setDamageAmount(damage);
        sc.setOfferAmount(firstOffer(damage, trust.getCurrentTrust(hunter)));
        sc.setGameTime(now);
        sc.setDeadlineGameTime(now + GameTime.days(cfg().getDecisionDays()));
        sc.setCreatedAt(Instant.now());
        cases.save(sc);
        outbox.money(sg, -damage, MoneyReason.DAMAGE, "Wildschaden Feld " + field.farmlandId(), new Related(RELATED, sc.getId()));
        narration.request(sg, NarrationEventType.WILDLIFE_DAMAGE_REPORTED).from(hunter)
                .facts(NarrationFacts.builder().put("farmlandId", field.farmlandId()).put("damageAmount", damage)
                        .put("offer", sc.getOfferAmount()).put("decisionDays", Math.round(cfg().getDecisionDays())).build())
                .channel(random.chance(0.5) ? Channel.CALL : Channel.MAIL)
                .category(CommunicationCategory.HUNTING).related(RELATED, sc.getId())
                .formLink("/contracts?case=" + sc.getId()).submit();
        diary.addAuto(sg, "HUNTING", "Wildschaden auf Feld " + field.farmlandId(), "Wildschweine haben Schaden von "
                + damage + " € angerichtet. " + hunter.getName() + " bietet " + sc.getOfferAmount() + " € Ersatz.",
                RELATED, sc.getId());
        return Optional.of(sc);
    }

    // ------------------------------------------------------------------------------------------ player decisions

    ServiceCase open(Savegame sg, Long caseId) {
        ServiceCase sc = cases.findById(caseId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + caseId));
        if (sc.getKind() != CaseKind.WILDLIFE_DAMAGE || sc.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Dieser Wildschaden ist bereits geregelt.");
        }
        return sc;
    }

    @Transactional
    public ServiceCase accept(Savegame sg, Long caseId) {
        ServiceCase sc = open(sg, caseId);
        settle(sg, sc, sc.getOfferAmount(), "ACCEPTED");
        trust.recordEvent(sc.getCharacter(), cfg().getAgreementTrustDelta(), TrustReason.WILDLIFE_AGREEMENT,
                "Wildschaden Feld " + sc.getFarmlandId());
        return sc;
    }

    /** Counter demand of the player: accepted up to the hunter's limit, otherwise a new offer (limited rounds). */
    @Transactional
    public ServiceCase counter(Savegame sg, Long caseId, long demand) {
        ServiceCase sc = open(sg, caseId);
        if (demand <= 0 || demand > sc.getDamageAmount()) {
            throw new BusinessRuleException("INVALID_DEMAND", "Die Forderung muss zwischen 1 € und der Schadenshöhe liegen.");
        }
        if (demand <= sc.getOfferAmount()) {
            settle(sg, sc, demand, "ACCEPTED");
            return sc;
        }
        if (sc.getRoundsUsed() >= cfg().getMaxRounds()) {
            throw new BusinessRuleException("FINAL_OFFER", "Das Angebot des Jagdpächters ist endgültig.");
        }
        long limit = limit(sc.getDamageAmount(), trust.getCurrentTrust(sc.getCharacter()));
        sc.setRoundsUsed(sc.getRoundsUsed() + 1);
        if (demand <= limit) {
            settle(sg, sc, demand, "DEMAND_ACCEPTED");
            trust.recordEvent(sc.getCharacter(), cfg().getAgreementTrustDelta(), TrustReason.WILDLIFE_AGREEMENT,
                    "Wildschaden Feld " + sc.getFarmlandId());
            return sc;
        }
        long next = Math.min(limit, round10((sc.getOfferAmount() + limit) / 2.0));
        sc.setOfferAmount(Math.max(sc.getOfferAmount(), next));
        int left = cfg().getMaxRounds() - sc.getRoundsUsed();
        narration.request(sg, NarrationEventType.WILDLIFE_COUNTER).from(sc.getCharacter())
                .facts(NarrationFacts.builder().put("playerDemand", demand).put("offer", sc.getOfferAmount())
                        .put("roundsLeft", left).put("final", left <= 0).build())
                .category(CommunicationCategory.HUNTING).related(RELATED, sc.getId()).submit();
        return sc;
    }

    /** Joint measure: the player contributes, the hunter pays the current offer; fewer damages, better reputation. */
    @Transactional
    public ServiceCase measure(Savegame sg, Long caseId) {
        ServiceCase sc = open(sg, caseId);
        sc.setMeasureAgreed(true);
        sc.setCostAmount(cfg().getMeasureCost());
        outbox.money(sg, -cfg().getMeasureCost(), MoneyReason.OTHER, "Beitrag Wildschutzmaßnahme", new Related(RELATED, sc.getId()));
        settle(sg, sc, sc.getOfferAmount(), "MEASURE");
        trust.recordEvent(sc.getCharacter(), cfg().getMeasureTrustDelta(), TrustReason.WILDLIFE_AGREEMENT,
                "Gemeinsame Maßnahme gegen Wildschäden");
        publicActions.record(sg, PublicActionType.WILDLIFE_MEASURE, cfg().getMeasureReputationDelta(),
                "Gemeinsame Maßnahme mit dem Jagdpächter");
        return sc;
    }

    /** Refusal: no compensation, a public dispute. */
    @Transactional
    public ServiceCase decline(Savegame sg, Long caseId) {
        ServiceCase sc = open(sg, caseId);
        sc.setStatus(CaseStatus.DECLINED);
        sc.setResolution("DISPUTE");
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        trust.recordEvent(sc.getCharacter(), cfg().getDisputeTrustDelta(), TrustReason.WILDLIFE_DISPUTE,
                "Entschädigung abgelehnt");
        publicActions.record(sg, PublicActionType.WILDLIFE_DISPUTE, cfg().getDisputeReputationDelta(),
                "Streit mit dem Jagdpächter um Wildschaden");
        narration.request(sg, NarrationEventType.WILDLIFE_DISPUTE).from(sc.getCharacter())
                .facts(NarrationFacts.builder().put("farmlandId", sc.getFarmlandId()).put("offer", sc.getOfferAmount()).build())
                .category(CommunicationCategory.HUNTING).related(RELATED, sc.getId()).submit();
        diary.addAuto(sg, "HUNTING", "Streit um Wildschaden", "Die Entschädigung für Feld " + sc.getFarmlandId()
                + " wurde abgelehnt. Das spricht sich herum.", RELATED, sc.getId());
        return sc;
    }

    private void settle(Savegame sg, ServiceCase sc, long amount, String resolution) {
        sc.setStatus(CaseStatus.SETTLED);
        sc.setResolution(resolution);
        sc.setPayoutAmount(amount);
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        if (amount > 0) {
            outbox.money(sg, amount, MoneyReason.WILDLIFE_COMPENSATION, "Wildschadenersatz Feld " + sc.getFarmlandId(),
                    new Related(RELATED, sc.getId()));
        }
        narration.request(sg, NarrationEventType.WILDLIFE_SETTLED).from(sc.getCharacter())
                .facts(NarrationFacts.builder().put("farmlandId", sc.getFarmlandId()).put("payout", amount)
                        .put("measure", sc.isMeasureAgreed()).put("measureCost", sc.isMeasureAgreed() ? sc.getCostAmount() : null)
                        .build())
                .category(CommunicationCategory.HUNTING).related(RELATED, sc.getId()).submit();
        diary.addAuto(sg, "HUNTING", "Wildschaden geregelt", amount + " € Ersatz für Feld " + sc.getFarmlandId()
                + (sc.isMeasureAgreed() ? ", gemeinsame Maßnahme vereinbart." : "."), RELATED, sc.getId());
    }

    /** Without an answer within the deadline the hunter pays his last offer. */
    @EventListener
    @Order(71)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (ServiceCase sc : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.AWAITING_PLAYER)) {
            if (sc.getKind() == CaseKind.WILDLIFE_DAMAGE && sc.getDeadlineGameTime() != null && sc.getDeadlineGameTime() < now) {
                settle(sg, sc, sc.getOfferAmount(), "OFFER_PAID_BY_DEFAULT");
            }
        }
    }

    public List<ServiceCase> cases(Savegame sg) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.WILDLIFE_DAMAGE));
    }

    private static long round10(double v) {
        return Math.round(v / 10.0) * 10;
    }
}
