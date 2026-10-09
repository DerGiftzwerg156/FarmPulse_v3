package de.farmpulse.rpsim.club;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.bridge.LiquidityService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.CharacterGeneratorService;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.newspaper.VillageNewsService;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameMonthPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import de.farmpulse.rpsim.village.PublicActionService;
import de.farmpulse.rpsim.village.VillageReputationService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V2 R2-E4: clubs and festivals.
 * <ul>
 *   <li>Festival calendar ({@code clubs.festivals}: FS25 period and host): at the start of the period the host invites;
 *   the player accepts or declines by button (accepting: trust of the host, declining: neutral, no answer: a little
 *   trust lost).</li>
 *   <li>Sponsoring: now and then a club (own character per club, owner decision) asks for support in fixed tiers; the
 *   amount raises the village reputation (public action SPONSORING), declining costs a little trust.</li>
 * </ul>
 */
@Service
public class ClubService {

    public static final String RELATED = "CLUB";
    public static final List<String> CLUBS = List.of("SHOOTING_CLUB", "FIRE_BRIGADE", "SPORTS_CLUB");

    private final SavegameRepository savegames;
    private final CharacterRepository characters;
    private final ServiceCaseRepository cases;
    private final CharacterGeneratorService generator;
    private final CharacterLookup lookup;
    private final VillageReputationService reputation;
    private final PublicActionService publicActions;
    private final LiquidityService liquidity;
    private final OutboxService outbox;
    private final NarrationRequestService narration;
    private final TrustScoreService trust;
    private final DiaryService diary;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;
    private final VillageNewsService villageNews;

    public ClubService(SavegameRepository savegames, CharacterRepository characters, ServiceCaseRepository cases,
                       CharacterGeneratorService generator, CharacterLookup lookup, VillageReputationService reputation,
                       PublicActionService publicActions, LiquidityService liquidity, OutboxService outbox,
                       NarrationRequestService narration, TrustScoreService trust, DiaryService diary,
                       RandomSource random, RpsimProperties props, GameTime gameTime,
                       VillageNewsService villageNews) {
        this.villageNews = villageNews;
        this.savegames = savegames;
        this.characters = characters;
        this.cases = cases;
        this.generator = generator;
        this.lookup = lookup;
        this.reputation = reputation;
        this.publicActions = publicActions;
        this.liquidity = liquidity;
        this.outbox = outbox;
        this.narration = narration;
        this.trust = trust;
        this.diary = diary;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.Clubs cfg() {
        return props.getFormulas().getClubs();
    }

    // ------------------------------------------------------------------------------------------ clubs

    /** The chair of a club, created (and introduced) with the first occasion. */
    @Transactional
    public Character club(Savegame sg, String key) {
        Optional<Character> existing = characters.findBySavegameAndStatus(sg, CharacterStatus.ACTIVE).stream()
                .filter(c -> c.getRole() == CharacterRole.CLUB && key.equals(c.getAffiliation())).findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        Character c = generator.generate(sg, CharacterGeneratorService.Spec.of(CharacterRole.CLUB, CharacterCategory.MANDATORY,
                reputation.baseTrustForNewCharacter(sg)), random.nextLong());
        generator.affiliate(c, key);
        narration.request(sg, NarrationEventType.CHARACTER_INTRODUCTION).from(c)
                .facts(NarrationFacts.builder().put("role", CharacterRole.CLUB).put("club", key).build())
                .category(CommunicationCategory.VILLAGE_LIFE).related(RELATED, c.getId()).submit();
        diary.addAuto(sg, "ROTATION", c.getName() + " stellt sich vor", c.getShortDescription(), RELATED, c.getId());
        return c;
    }

    /** Host of a festival: a club key or a character role. */
    Optional<Character> host(Savegame sg, String host) {
        if (CLUBS.contains(host)) {
            return Optional.of(club(sg, host));
        }
        try {
            CharacterRole role = CharacterRole.valueOf(host);
            return lookup.firstActive(sg, role, CharacterRole.COOPERATIVE);
        } catch (IllegalArgumentException e) {
            return lookup.firstActive(sg, CharacterRole.COOPERATIVE, CharacterRole.VILLAGER);
        }
    }

    // ------------------------------------------------------------------------------------------ festivals

    /** Start of a period: every festival of this period sends an invitation with RSVP (called by VillageLifeService). */
    @Transactional
    public boolean invite(Savegame sg) {
        long now = sg.getCurrentGameTime();
        if (!cfg().isEnabled() || !gameTime.isMonthStart(sg, now)) {
            return false;
        }
        int period = gameTime.periodOfYear(sg, now);
        boolean any = false;
        for (RpsimProperties.Festival f : cfg().getFestivals()) {
            if (f.getPeriod() != period) {
                continue;
            }
            Optional<Character> host = host(sg, f.getHost());
            if (host.isEmpty()) {
                continue;
            }
            ServiceCase sc = new ServiceCase();
            sc.setSavegame(sg);
            sc.setKind(CaseKind.INVITATION);
            sc.setStatus(CaseStatus.AWAITING_PLAYER);
            sc.setCharacter(host.get());
            sc.setReference(f.getKey());
            sc.setGameTime(now);
            sc.setDeadlineGameTime(now + GameTime.days(cfg().getInvitationDays()));
            sc.setCreatedAt(Instant.now());
            cases.save(sc);
            narration.request(sg, NarrationEventType.VILLAGE_INVITATION).from(host.get())
                    .facts(NarrationFacts.builder().put("occasion", f.getKey())
                            .put("rsvpDays", Math.round(cfg().getInvitationDays())).build())
                    .category(CommunicationCategory.VILLAGE_LIFE).related(RELATED, sc.getId())
                    .formLink("/kalender?case=" + sc.getId()).submit();
            villageNews.add(sg, VillageNewsService.Section.VILLAGE, "FESTIVAL", host.get().getName() + " lädt zum "
                    + label(f.getKey()) + " ein.");
            any = true;
        }
        return any;
    }

    private ServiceCase open(Savegame sg, Long id, CaseKind kind) {
        ServiceCase sc = cases.findById(id).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + id));
        if (sc.getKind() != kind || sc.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Das ist bereits erledigt.");
        }
        return sc;
    }

    private void close(Savegame sg, ServiceCase sc, CaseStatus status, String resolution) {
        sc.setStatus(status);
        sc.setResolution(resolution);
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
    }

    @Transactional
    public ServiceCase acceptInvitation(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id, CaseKind.INVITATION);
        close(sg, sc, CaseStatus.SETTLED, "ACCEPTED");
        trust.recordEvent(sc.getCharacter(), cfg().getInvitationAcceptTrustDelta(), TrustReason.INVITATION_ACCEPTED,
                "Zusage " + sc.getReference());
        diary.addAuto(sg, "ROTATION", "Zusage: " + label(sc.getReference()), "Zugesagt bei " + sc.getCharacter().getName() + ".",
                RELATED, sc.getId());
        return sc;
    }

    @Transactional
    public ServiceCase declineInvitation(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id, CaseKind.INVITATION);
        close(sg, sc, CaseStatus.DECLINED, "DECLINED");
        return sc; // declining politely is neutral
    }

    // ------------------------------------------------------------------------------------------ sponsoring

    @EventListener
    @Order(66)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (!cfg().isEnabled() || cfg().getSponsoringTiers().isEmpty()) {
            return;
        }
        Long last = sg.getLastSponsoringGameTime();
        if (last != null && last <= sg.getCurrentGameTime()
                && GameTime.toDays(sg.getCurrentGameTime() - last) < cfg().getSponsoringCooldownDays()) {
            return;
        }
        if (random.chance(cfg().getSponsoringProbabilityPerMonth())) {
            requestSponsoring(sg, random.pick(CLUBS));
        }
    }

    @Transactional
    public ServiceCase requestSponsoring(Savegame sg, String clubKey) {
        long now = sg.getCurrentGameTime();
        Character club = club(sg, clubKey);
        sg.setLastSponsoringGameTime(now);
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.SPONSORING_REQUEST);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setCharacter(club);
        sc.setReference(clubKey);
        sc.setGameTime(now);
        sc.setDeadlineGameTime(now + GameTime.days(cfg().getSponsoringDecisionDays()));
        sc.setCreatedAt(Instant.now());
        cases.save(sc);
        List<Long> tiers = cfg().getSponsoringTiers();
        narration.request(sg, NarrationEventType.SPONSORING_REQUEST).from(club)
                .facts(NarrationFacts.builder().put("club", clubKey).put("smallestTier", tiers.getFirst())
                        .put("largestTier", tiers.getLast())
                        .put("decisionDays", Math.round(cfg().getSponsoringDecisionDays())).build())
                .category(CommunicationCategory.VILLAGE_LIFE).related(RELATED, sc.getId())
                .formLink("/village?case=" + sc.getId()).submit();
        return sc;
    }

    /** Village reputation of a sponsoring amount (formula of the village reputation: public action delta). */
    public double reputationDelta(long amount) {
        return amount / 100.0 * cfg().getSponsoringReputationPer100();
    }

    @Transactional
    public ServiceCase sponsor(Savegame sg, Long id, long amount) {
        ServiceCase sc = open(sg, id, CaseKind.SPONSORING_REQUEST);
        if (!cfg().getSponsoringTiers().contains(amount)) {
            throw new BusinessRuleException("INVALID_TIER", "Bitte eine der angebotenen Stufen wählen.");
        }
        if (liquidity.available(sg) < amount) {
            throw new BusinessRuleException("INSUFFICIENT_FUNDS", "Der Kontostand reicht für diese Zahlung nicht.");
        }
        close(sg, sc, CaseStatus.SETTLED, "SPONSORED");
        sc.setPayoutAmount(amount);
        outbox.money(sg, -amount, MoneyReason.SPONSORING, "Sponsoring " + label(sc.getReference()),
                new Related(RELATED, sc.getId()));
        publicActions.record(sg, PublicActionType.SPONSORING, reputationDelta(amount),
                "Sponsoring " + sc.getReference() + " " + amount + " €");
        trust.recordEvent(sc.getCharacter(), cfg().getSponsoringTrustDelta(), TrustReason.SPONSORING,
                "Sponsoring " + amount + " €");
        narration.request(sg, NarrationEventType.SPONSORING_THANKS).from(sc.getCharacter())
                .facts(NarrationFacts.builder().put("club", sc.getReference()).put("amount", amount).build())
                .category(CommunicationCategory.VILLAGE_LIFE).related(RELATED, sc.getId()).submit();
        diary.addAuto(sg, "ROTATION", "Sponsoring: " + label(sc.getReference()), amount + " € für den Verein.",
                RELATED, sc.getId());
        villageNews.add(sg, VillageNewsService.Section.VILLAGE, "CLUB_SPONSORING", "Der Verein \"" + label(sc.getReference())
                + "\" freut sich über einen Sponsor aus der Landwirtschaft.");
        return sc;
    }

    @Transactional
    public ServiceCase declineSponsoring(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id, CaseKind.SPONSORING_REQUEST);
        close(sg, sc, CaseStatus.DECLINED, "DECLINED");
        trust.recordEvent(sc.getCharacter(), cfg().getSponsoringDeclineTrustDelta(), TrustReason.SPONSORING_DECLINED,
                "Sponsoring abgelehnt");
        return sc;
    }

    /** Daily: unanswered invitations cost a little trust, unanswered sponsoring requests count as declined. */
    @EventListener
    @Order(67)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (ServiceCase sc : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.AWAITING_PLAYER)) {
            if (sc.getDeadlineGameTime() == null || sc.getDeadlineGameTime() >= now) {
                continue;
            }
            if (sc.getKind() == CaseKind.INVITATION) {
                close(sg, sc, CaseStatus.EXPIRED, "IGNORED");
                trust.recordEvent(sc.getCharacter(), cfg().getInvitationIgnoreTrustDelta(), TrustReason.INVITATION_IGNORED,
                        "Keine Antwort auf die Einladung " + sc.getReference());
            } else if (sc.getKind() == CaseKind.SPONSORING_REQUEST) {
                close(sg, sc, CaseStatus.EXPIRED, "IGNORED");
                trust.recordEvent(sc.getCharacter(), cfg().getSponsoringDeclineTrustDelta(), TrustReason.SPONSORING_DECLINED,
                        "Keine Antwort auf die Sponsoring-Anfrage");
            }
        }
    }

    public static String label(String key) {
        return switch (key == null ? "" : key) {
            case "MAIBAUM" -> "Maibaumaufstellen";
            case "SCHUETZENFEST" -> "Schützenfest";
            case "FEUERWEHRFEST" -> "Feuerwehrfest";
            case "ERNTEDANKFEST" -> "Erntedankfest";
            case "WEIHNACHTSMARKT" -> "Weihnachtsmarkt";
            case "SHOOTING_CLUB" -> "Schützenverein";
            case "FIRE_BRIGADE" -> "Freiwillige Feuerwehr";
            case "SPORTS_CLUB" -> "Sportverein";
            default -> key;
        };
    }
}
