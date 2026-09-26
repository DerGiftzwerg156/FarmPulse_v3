package de.farmpulse.rpsim.village;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.credit.CreditScoringService;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Village life beyond the economy (technical concept "Dorfleben-Modul"): three sub-types with DIFFERENT triggers,
 * deliberately rare, all through the normal Communication/NarrationJob pipeline and without any money instruction.
 * <ul>
 *   <li>congratulations - fact based: cash-flow trend from the snapshot series (reuses the credit scoring cash flow)</li>
 *   <li>invitations - calendar based (FS25 periods)</li>
 *   <li>gossip - plain daily random roll with minimal facts</li>
 * </ul>
 */
@Service
public class VillageLifeService {

    public enum Season { SPRING, SUMMER, AUTUMN, WINTER }

    private final SavegameRepository savegames;
    private final CreditScoringService cashflow;
    private final CharacterLookup lookup;
    private final NarrationRequestService narration;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public VillageLifeService(SavegameRepository savegames, CreditScoringService cashflow, CharacterLookup lookup,
                              NarrationRequestService narration, RandomSource random, RpsimProperties props,
                              GameTime gameTime) {
        this.savegames = savegames;
        this.cashflow = cashflow;
        this.lookup = lookup;
        this.narration = narration;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.VillageLife cfg() {
        return props.getFormulas().getVillageLife();
    }

    @EventListener
    @Order(60)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        congratulate(sg);
        invite(sg);
        if (random.chance(cfg().getGossipDailyProbability())) {
            gossip(sg);
        }
    }

    private Optional<Character> villager(Savegame sg) {
        List<Character> dyn = lookup.activeDynamic(sg);
        return dyn.isEmpty() ? lookup.firstActive(sg, CharacterRole.COOPERATIVE) : Optional.of(random.pick(dyn));
    }

    /** Fact-based trigger: clearly positive cash-flow trend (current window vs. previous window). */
    @Transactional
    public boolean congratulate(Savegame sg) {
        long now = sg.getCurrentGameTime();
        if (sg.getLastCongratulationGameTime() != null
                && GameTime.toDays(now - sg.getLastCongratulationGameTime()) < cfg().getCongratulationCooldownDays()) {
            return false;
        }
        long window = GameTime.days(props.getFormulas().getCredit().getCashflowWindowDays());
        double current = cashflow.monthlyCashflow(sg, now - window, now);
        double previous = cashflow.monthlyCashflow(sg, now - 2 * window, now - window);
        boolean trendUp = current >= cfg().getCongratulationMinCashflow()
                && (previous <= 0 || current / previous >= cfg().getCongratulationTrendRatio());
        if (!trendUp) {
            return false;
        }
        Optional<Character> from = villager(sg);
        if (from.isEmpty()) {
            return false;
        }
        sg.setLastCongratulationGameTime(now);
        narration.request(sg, NarrationEventType.VILLAGE_CONGRATULATION).from(from.get())
                .facts(NarrationFacts.builder().put("occasion", "GOOD_BUSINESS_TREND").build())
                .category(CommunicationCategory.VILLAGE_LIFE).submit();
        return true;
    }

    /**
     * Calendar trigger (TODO T-08: FS25 periods): on the first day of every {@code invitationEveryPeriods}-th period of
     * the year, counted from period 1 (March). The season follows the FS25 period.
     */
    @Transactional
    public boolean invite(Savegame sg) {
        long now = sg.getCurrentGameTime();
        int every = cfg().getInvitationEveryPeriods();
        if (every <= 0 || !gameTime.isMonthStart(sg, now) || (gameTime.periodOfYear(sg, now) - 1) % every != 0) {
            return false;
        }
        Optional<Character> from = lookup.firstActive(sg, CharacterRole.COOPERATIVE, CharacterRole.VILLAGER);
        if (from.isEmpty()) {
            return false;
        }
        Season season = season(sg, now);
        narration.request(sg, NarrationEventType.VILLAGE_INVITATION).from(from.get())
                .facts(NarrationFacts.builder().put("season", season).put("occasion", occasion(season)).build())
                .category(CommunicationCategory.VILLAGE_LIFE).submit();
        return true;
    }

    /** FS25 period 1..3 (March-May) spring, 4..6 summer, 7..9 autumn, 10..12 winter. */
    public Season season(Savegame sg, long gameTimeMs) {
        return seasonOfPeriod(gameTime.periodOfYear(sg, gameTimeMs));
    }

    public static Season seasonOfPeriod(int period) {
        return Season.values()[Math.min(3, Math.max(0, (period - 1) / 3))];
    }

    static String occasion(Season s) {
        return switch (s) {
            case SPRING -> "MAIBAUM";
            case SUMMER -> "DORFFEST";
            case AUTUMN -> "ERNTEDANKFEST";
            case WINTER -> "WEIHNACHTSMARKT";
        };
    }

    /** Pure random roll with minimal fact payload and a short cooldown. */
    @Transactional
    public boolean gossip(Savegame sg) {
        long now = sg.getCurrentGameTime();
        if (sg.getLastGossipGameTime() != null && GameTime.toDays(now - sg.getLastGossipGameTime()) < cfg().getGossipCooldownDays()) {
            return false;
        }
        List<Character> dyn = lookup.activeDynamic(sg);
        if (dyn.size() < 2) {
            return false;
        }
        Character teller = random.pick(dyn);
        Character subject = random.pick(dyn.stream().filter(c -> !c.getId().equals(teller.getId())).toList());
        sg.setLastGossipGameTime(now);
        narration.request(sg, NarrationEventType.VILLAGE_GOSSIP).from(teller)
                .facts(NarrationFacts.builder().put("aboutName", subject.getName()).build())
                .category(CommunicationCategory.VILLAGE_LIFE).submit();
        return true;
    }
}
