package de.farmpulse.rpsim.villagelife;

import java.util.List;
import java.util.Optional;

import de.farmpulse.rpsim.authority.BurdeningEvents;
import de.farmpulse.rpsim.authority.BurdeningEvents.Burden;
import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.ChronicleService;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.FieldPhase;
import de.farmpulse.rpsim.domain.NightWorkSample;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.field.FieldService;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.newspaper.VillageNewsService;
import de.farmpulse.rpsim.repository.NightWorkSampleRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.1 R31-D4 (owner decisions 2026-10-05): complaints about night work. Every export with running helpers
 * ({@code workforce.activeJobs}) at night ({@code calendar.dayTimeMs} from {@code night-start-hour} to
 * {@code night-end-hour}) counts the game time since the last export (at most {@code max-sample-gap-minutes}) - not
 * while an own field is HARVESTABLE (the village understands the harvest). From {@code threshold-hours} night hours
 * within {@code window-days} a villager complains: friendly the first time, annoyed (and gossip in the newspaper) when
 * the last complaint was within {@code annoyed-within-days}; then the count starts again. The samples are kept
 * independently of the switch: the guests of the farm holidays (D6) hear the noise too.
 */
@Service
public class NightWorkService {

    public static final String RELATED = "NIGHT_WORK";
    static final long HOUR = GameTime.hours(1);

    private final NightWorkSampleRepository samples;
    private final SavegameRepository savegames;
    private final FactsService facts;
    private final CharacterLookup lookup;
    private final NarrationRequestService narration;
    private final TrustScoreService trust;
    private final VillageNewsService news;
    private final BurdeningEvents burdens;
    private final RandomSource random;
    private final RpsimProperties props;

    public NightWorkService(NightWorkSampleRepository samples, SavegameRepository savegames, FactsService facts,
                            CharacterLookup lookup, NarrationRequestService narration, TrustScoreService trust,
                            VillageNewsService news, BurdeningEvents burdens, RandomSource random, RpsimProperties props) {
        this.samples = samples;
        this.savegames = savegames;
        this.facts = facts;
        this.lookup = lookup;
        this.narration = narration;
        this.trust = trust;
        this.news = news;
        this.burdens = burdens;
        this.random = random;
        this.props = props;
    }

    private RpsimProperties.NightWork cfg() {
        return props.getFormulas().getNightWork();
    }

    /** Whether the time of day lies in the night (the night wraps around midnight). */
    public static boolean isNight(long dayTimeMs, int startHour, int endHour) {
        long start = startHour * HOUR;
        long end = endHour * HOUR;
        return start <= end ? dayTimeMs >= start && dayTimeMs < end : dayTimeMs >= start || dayTimeMs < end;
    }

    static boolean harvestTime(FarmFacts f) {
        return f.fields() != null && f.fields().stream().anyMatch(x -> FieldService.phase(x) == FieldPhase.HARVESTABLE);
    }

    @EventListener
    @Order(86)
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        Optional<FarmFacts> latest = facts.latest(sg);
        if (latest.isEmpty()) {
            return;
        }
        record(sg, latest.get(), e.gameTime());
        if (cfg().isEnabled() && burdens.on(sg, Burden.NIGHT_WORK)) {
            check(sg);
        }
    }

    /** Counts the time since the last export when helpers run at night outside the harvest. */
    @Transactional
    public long record(Savegame sg, FarmFacts f, long gameTime) {
        Long last = sg.getNightLastFactsGameTime();
        sg.setNightLastFactsGameTime(gameTime);
        if (last == null || gameTime <= last) {
            return 0;
        }
        BridgeDtos.Calendar cal = f.calendar();
        List<BridgeDtos.ActiveJob> jobs = f.workforce() == null || f.workforce().activeJobs() == null ? List.of()
                : f.workforce().activeJobs();
        if (cal == null || cal.dayTimeMs() == null || jobs.isEmpty()
                || !isNight(cal.dayTimeMs(), cfg().getNightStartHour(), cfg().getNightEndHour()) || harvestTime(f)) {
            return 0;
        }
        long ms = Math.min(gameTime - last, Math.round(cfg().getMaxSampleGapMinutes() * 60_000));
        NightWorkSample s = new NightWorkSample();
        s.setSavegame(sg);
        s.setGameTime(gameTime);
        s.setNightMs(ms);
        samples.save(s);
        return ms;
    }

    /** Night milliseconds of helpers in (from, to]. */
    public long nightMs(Savegame sg, long from, long to) {
        return samples.sumNightMs(sg, from, to);
    }

    /** A complaint from the threshold on (window since the last complaint at most). */
    @Transactional
    public boolean check(Savegame sg) {
        long now = sg.getCurrentGameTime();
        long from = now - GameTime.days(cfg().getWindowDays());
        if (sg.getNightCountedFrom() != null) {
            from = Math.max(from, sg.getNightCountedFrom());
        }
        long ms = nightMs(sg, from, now);
        if (ms < cfg().getThresholdHours() * HOUR) {
            return false;
        }
        List<Character> villagers = lookup.activeDynamic(sg).stream().filter(c -> c.getRole() == CharacterRole.VILLAGER)
                .toList();
        if (villagers.isEmpty()) {
            return false;
        }
        Long lastComplaint = sg.getLastNightComplaintGameTime();
        boolean annoyed = lastComplaint != null && GameTime.toDays(now - lastComplaint) <= cfg().getAnnoyedWithinDays();
        sg.setNightCountedFrom(now);
        sg.setLastNightComplaintGameTime(now);
        Character from0 = random.pick(villagers);
        double delta = (annoyed ? cfg().getAnnoyedTrustDelta() : cfg().getFriendlyTrustDelta()) * burdens.factor(sg);
        trust.recordEvent(from0, delta, TrustReason.NIGHT_WORK, annoyed ? "Genervt vom Lärm in der Nacht"
                : "Beschwerde über Lärm in der Nacht");
        narration.request(sg, NarrationEventType.NIGHT_WORK_COMPLAINT).from(from0)
                .facts(NarrationFacts.builder().put("level", annoyed ? "NIGHT_ANNOYED" : "NIGHT_FRIENDLY")
                        .put("nightHours", Math.round(ms / (double) HOUR)).build())
                .category(CommunicationCategory.VILLAGE_LIFE).related(RELATED, from0.getId()).submit();
        if (annoyed) {
            news.add(sg, VillageNewsService.Section.VILLAGE, "NIGHT_WORK", "Im Dorf wird über die Nachtruhe geredet: Auf "
                    + ChronicleService.farmName(sg) + " brummen nachts die Maschinen.");
        }
        return true;
    }
}
