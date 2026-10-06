package de.farmpulse.rpsim.villagelife;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.Negotiation;
import de.farmpulse.rpsim.domain.NegotiationKind;
import de.farmpulse.rpsim.domain.NegotiationStatus;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.negotiation.FarmlandOwnershipService;
import de.farmpulse.rpsim.repository.NegotiationRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import de.farmpulse.rpsim.village.PublicActionService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.1 R31-D3 (owner decisions 2026-10-05): the regulars' table in the village pub. Every
 * {@code interval-days} a villager invites (case STAMMTISCH_INVITATION in "Kalender", in-game question STAMMTISCH);
 * the answer is due within {@code answer-days}.
 * <ul>
 *   <li>Attending: trust with {@code attendees} random villagers, the next rumour is accurate with
 *   {@code market.rumor-accurate-probability} + {@code rumor-accuracy-bonus} (MarketEventEngine) and with
 *   {@code tip-probability} a tip on a running auction or a sell-willing field owner.</li>
 *   <li>Declined or unanswered: after {@code loner-after-missed} invitations in a row the farm counts as
 *   "eigenbrötlerisch" (public action STAMMTISCH_LONER), capped at {@code loner-reputation-cap} in total.</li>
 * </ul>
 */
@Service
public class StammtischService {

    public static final String RELATED = "STAMMTISCH";

    private final ServiceCaseRepository cases;
    private final SavegameRepository savegames;
    private final CharacterLookup lookup;
    private final NegotiationRepository negotiations;
    private final FarmlandOwnershipService ownership;
    private final NarrationRequestService narration;
    private final TrustScoreService trust;
    private final PublicActionService publicActions;
    private final DiaryService diary;
    private final RandomSource random;
    private final RpsimProperties props;

    public StammtischService(ServiceCaseRepository cases, SavegameRepository savegames, CharacterLookup lookup,
                             NegotiationRepository negotiations, FarmlandOwnershipService ownership,
                             NarrationRequestService narration, TrustScoreService trust, PublicActionService publicActions,
                             DiaryService diary, RandomSource random, RpsimProperties props) {
        this.cases = cases;
        this.savegames = savegames;
        this.lookup = lookup;
        this.negotiations = negotiations;
        this.ownership = ownership;
        this.narration = narration;
        this.trust = trust;
        this.publicActions = publicActions;
        this.diary = diary;
        this.random = random;
        this.props = props;
    }

    private RpsimProperties.Stammtisch cfg() {
        return props.getFormulas().getStammtisch();
    }

    @EventListener
    @Order(63)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (ServiceCase sc : cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.STAMMTISCH_INVITATION))) {
            if (sc.getStatus() == CaseStatus.AWAITING_PLAYER && sc.getDeadlineGameTime() != null
                    && sc.getDeadlineGameTime() < now) {
                close(sg, sc, CaseStatus.EXPIRED, "IGNORED");
                missed(sg);
            }
        }
        if (!cfg().isEnabled()) {
            return;
        }
        if (sg.getStammtischNextGameTime() == null) {
            sg.setStammtischNextGameTime(now + GameTime.days(cfg().getIntervalDays()));
        } else if (now >= sg.getStammtischNextGameTime()) {
            invite(sg);
        }
    }

    /** A villager invites to the next evening. */
    @Transactional
    public Optional<ServiceCase> invite(Savegame sg) {
        long now = sg.getCurrentGameTime();
        sg.setStammtischNextGameTime(now + GameTime.days(cfg().getIntervalDays()));
        List<Character> villagers = villagers(sg);
        if (villagers.isEmpty()) {
            return Optional.empty();
        }
        Character host = random.pick(villagers);
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.STAMMTISCH_INVITATION);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setCharacter(host);
        sc.setGameTime(now);
        sc.setDeadlineGameTime(now + GameTime.days(cfg().getAnswerDays()));
        sc.setCreatedAt(Instant.now());
        cases.save(sc);
        narration.request(sg, NarrationEventType.STAMMTISCH_INVITATION).from(host)
                .facts(NarrationFacts.builder().put("answerDays", Math.round(cfg().getAnswerDays())).build())
                .category(CommunicationCategory.VILLAGE_LIFE).related(RELATED, sc.getId())
                .formLink("/kalender?case=" + sc.getId()).submit();
        return Optional.of(sc);
    }

    private List<Character> villagers(Savegame sg) {
        return lookup.activeDynamic(sg).stream().filter(c -> c.getRole() == CharacterRole.VILLAGER).toList();
    }

    private ServiceCase open(Savegame sg, Long id) {
        ServiceCase sc = cases.findById(id).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + id));
        if (sc.getKind() != CaseKind.STAMMTISCH_INVITATION || sc.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Das ist bereits erledigt.");
        }
        return sc;
    }

    private void close(Savegame sg, ServiceCase sc, CaseStatus status, String resolution) {
        sc.setStatus(status);
        sc.setResolution(resolution);
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
    }

    /** "Hingehen": trust with the attendees, the rumour bonus and maybe a tip. */
    @Transactional
    public ServiceCase attend(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id);
        close(sg, sc, CaseStatus.SETTLED, "ATTENDED");
        sg.setStammtischMissed(0);
        sg.setStammtischRumorBonus(true);
        List<Character> pool = new ArrayList<>(villagers(sg));
        pool.removeIf(c -> c.getId().equals(sc.getCharacter().getId()));
        List<Character> attendees = new ArrayList<>();
        attendees.add(sc.getCharacter());
        while (attendees.size() < cfg().getAttendees() && !pool.isEmpty()) {
            Character c = random.pick(pool);
            pool.remove(c);
            attendees.add(c);
        }
        for (Character c : attendees) {
            trust.recordEvent(c, cfg().getAttendTrustDelta(), TrustReason.STAMMTISCH, "Abend am Stammtisch");
        }
        diary.addAuto(sg, "ROTATION", "Stammtisch", "Ein Abend in der Dorfkneipe mit "
                + String.join(", ", attendees.stream().map(Character::getName).toList()) + ".", RELATED, sc.getId());
        if (random.chance(cfg().getTipProbability())) {
            tip(sg).ifPresent(t -> narration.request(sg, NarrationEventType.STAMMTISCH_TIP)
                    .from(random.pick(attendees)).facts(NarrationFacts.builder().put("tip", t).build())
                    .category(CommunicationCategory.VILLAGE_LIFE).related(RELATED, sc.getId()).submit());
        }
        return sc;
    }

    /** A running auction, else a sell-willing field owner of the village. */
    Optional<String> tip(Savegame sg) {
        List<Negotiation> auctions = negotiations.findBySavegameAndStatus(sg, NegotiationStatus.OPEN).stream()
                .filter(n -> n.getKind() == NegotiationKind.AUCTION).toList();
        if (!auctions.isEmpty()) {
            Negotiation n = random.pick(auctions);
            return Optional.of("Feld " + n.getAssetId() + " wird gerade versteigert"
                    + (n.getClosesAtGameTime() == null ? "." : " – die Versteigerung läuft noch "
                    + Math.max(1, Math.round(GameTime.toDays(n.getClosesAtGameTime() - sg.getCurrentGameTime()))) + " Tage."));
        }
        List<FarmlandOwnership> willing = ownership.list(sg).stream()
                .filter(o -> o.getOwnerType() == OwnerType.CHARACTER && o.getOwnerCharacter() != null
                        && o.getOwnerCharacter().isSellWilling()
                        && o.getOwnerCharacter().getStatus() == CharacterStatus.ACTIVE && o.isTradeable())
                .toList();
        if (willing.isEmpty()) {
            return Optional.empty();
        }
        FarmlandOwnership o = random.pick(willing);
        return Optional.of(o.getOwnerCharacter().getName() + " würde Feld " + o.getFarmlandId() + " verkaufen.");
    }

    /** "Absagen": counts as missed like an unanswered invitation. */
    @Transactional
    public ServiceCase decline(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id);
        close(sg, sc, CaseStatus.DECLINED, "DECLINED");
        missed(sg);
        return sc;
    }

    private void missed(Savegame sg) {
        sg.setStammtischMissed(sg.getStammtischMissed() + 1);
        if (sg.getStammtischMissed() < cfg().getLonerAfterMissed()) {
            return;
        }
        sg.setStammtischMissed(0);
        double room = cfg().getLonerReputationCap() - sg.getStammtischLonerSum(); // <= 0
        double delta = Math.max(cfg().getLonerReputationDelta(), room);
        if (delta < 0) {
            sg.setStammtischLonerSum(sg.getStammtischLonerSum() + delta);
            publicActions.record(sg, PublicActionType.STAMMTISCH_LONER, delta, "Nie am Stammtisch");
        }
    }

    /** MarketEventEngine: consumes the bonus of the last evening for the next rumour. */
    @Transactional
    public double consumeRumorBonus(Savegame sg) {
        if (!sg.isStammtischRumorBonus()) {
            return 0;
        }
        sg.setStammtischRumorBonus(false);
        return cfg().getRumorAccuracyBonus();
    }
}
