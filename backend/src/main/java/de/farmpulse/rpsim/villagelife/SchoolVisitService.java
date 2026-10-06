package de.farmpulse.rpsim.villagelife;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
 * Roadmap V3.1 R31-D6 (owner decisions 2026-10-05): farm tours for school classes. At a month start outside the
 * {@code excluded-periods} (summer holidays) the village school (role SCHOOL, created at its first occasion) asks with
 * {@code probability-per-month} - only when a stable has a health of at least {@code min-health}. Accepted by button
 * within {@code answer-days}: {@code allowance} as GUEST_INCOME, village reputation (public action SCHOOL_VISIT, also in
 * the newspaper), trust of the teacher and a diary entry. Declining has no consequence.
 */
@Service
public class SchoolVisitService {

    public static final String RELATED = "SCHOOL_VISIT";

    private final ServiceCaseRepository cases;
    private final SavegameRepository savegames;
    private final FactsService facts;
    private final ServiceRoleService roles;
    private final OutboxService outbox;
    private final NarrationRequestService narration;
    private final TrustScoreService trust;
    private final PublicActionService publicActions;
    private final DiaryService diary;
    private final RandomSource random;
    private final GameTime gameTime;
    private final RpsimProperties props;

    public SchoolVisitService(ServiceCaseRepository cases, SavegameRepository savegames, FactsService facts,
                              ServiceRoleService roles, OutboxService outbox, NarrationRequestService narration,
                              TrustScoreService trust, PublicActionService publicActions, DiaryService diary,
                              RandomSource random, GameTime gameTime, RpsimProperties props) {
        this.cases = cases;
        this.savegames = savegames;
        this.facts = facts;
        this.roles = roles;
        this.outbox = outbox;
        this.narration = narration;
        this.trust = trust;
        this.publicActions = publicActions;
        this.diary = diary;
        this.random = random;
        this.gameTime = gameTime;
        this.props = props;
    }

    private RpsimProperties.SchoolVisit cfg() {
        return props.getFormulas().getSchoolVisit();
    }

    /** A stable with good health (the condition of a visit). */
    boolean healthyAnimals(Savegame sg) {
        FarmFacts f = facts.latest(sg).orElse(null);
        return f != null && f.husbandries() != null && f.husbandries().stream()
                .anyMatch(h -> h != null && h.health() != null && h.health() >= cfg().getMinHealth());
    }

    @EventListener
    @Order(89)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (!cfg().isEnabled() || cfg().getExcludedPeriods().contains(gameTime.periodOfYear(sg, sg.getCurrentGameTime()))
                || !healthyAnimals(sg) || openRequest(sg).isPresent()) {
            return;
        }
        if (random.chance(cfg().getProbabilityPerMonth())) {
            request(sg);
        }
    }

    private Optional<ServiceCase> openRequest(Savegame sg) {
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.SCHOOL_VISIT)).stream()
                .filter(c -> c.getStatus() == CaseStatus.AWAITING_PLAYER).findFirst();
    }

    @Transactional
    public ServiceCase request(Savegame sg) {
        long now = sg.getCurrentGameTime();
        Character teacher = roles.ensure(sg, CharacterRole.SCHOOL);
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.SCHOOL_VISIT);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setCharacter(teacher);
        sc.setOfferAmount(cfg().getAllowance());
        sc.setGameTime(now);
        sc.setDeadlineGameTime(now + GameTime.days(cfg().getAnswerDays()));
        sc.setCreatedAt(Instant.now());
        cases.save(sc);
        narration.request(sg, NarrationEventType.SCHOOL_VISIT_REQUEST).from(teacher)
                .facts(NarrationFacts.builder().put("allowance", cfg().getAllowance())
                        .put("answerDays", Math.round(cfg().getAnswerDays())).build())
                .category(CommunicationCategory.VILLAGE_LIFE).related(RELATED, sc.getId())
                .formLink("/kalender?case=" + sc.getId()).submit();
        return sc;
    }

    private ServiceCase open(Savegame sg, Long id) {
        ServiceCase sc = cases.findById(id).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + id));
        if (sc.getKind() != CaseKind.SCHOOL_VISIT || sc.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Das ist bereits erledigt.");
        }
        return sc;
    }

    /** "Zusagen": the class visits the farm. */
    @Transactional
    public ServiceCase accept(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id);
        if (!healthyAnimals(sg)) {
            throw new BusinessRuleException("NO_HEALTHY_ANIMALS", "Für eine Hofführung braucht es einen Stall mit gesunden Tieren.");
        }
        sc.setStatus(CaseStatus.SETTLED);
        sc.setResolution("VISITED");
        sc.setPayoutAmount(sc.getOfferAmount());
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        outbox.money(sg, sc.getOfferAmount(), MoneyReason.GUEST_INCOME, "Hofführung Schulklasse", new Related(RELATED, sc.getId()));
        publicActions.record(sg, PublicActionType.SCHOOL_VISIT, cfg().getReputationDelta(), "Hofführung für eine Schulklasse");
        trust.recordEvent(sc.getCharacter(), cfg().getTrustDelta(), TrustReason.SCHOOL_VISIT, "Hofführung der Schulklasse");
        narration.request(sg, NarrationEventType.SCHOOL_VISIT_THANKS).from(sc.getCharacter())
                .facts(NarrationFacts.builder().put("allowance", sc.getOfferAmount()).build())
                .category(CommunicationCategory.VILLAGE_LIFE).related(RELATED, sc.getId()).submit();
        diary.addAuto(sg, "ROTATION", "Schulklasse zu Besuch", "Eine Klasse der Dorfschule war mit "
                + sc.getCharacter().getName() + " auf dem Hof.", RELATED, sc.getId());
        return sc;
    }

    /** "Absagen": without consequence. */
    @Transactional
    public ServiceCase decline(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id);
        sc.setStatus(CaseStatus.DECLINED);
        sc.setResolution("DECLINED");
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        return sc;
    }

    @EventListener
    @Order(89)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        openRequest(sg).filter(sc -> sc.getDeadlineGameTime() != null && sc.getDeadlineGameTime() < now).ifPresent(sc -> {
            sc.setStatus(CaseStatus.EXPIRED);
            sc.setResolution("IGNORED");
            sc.setClosedAtGameTime(now);
        });
    }
}
