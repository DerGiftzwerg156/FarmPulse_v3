package de.farmpulse.rpsim.villagelife;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.farmpulse.rpsim.authority.BurdeningEvents;
import de.farmpulse.rpsim.authority.BurdeningEvents.Burden;
import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.CropDamageEvent;
import de.farmpulse.rpsim.domain.CropDamageStreak;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.negotiation.FarmlandOwnershipService;
import de.farmpulse.rpsim.repository.CropDamageEventRepository;
import de.farmpulse.rpsim.repository.CropDamageStreakRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.1 R31-D5 (owner decisions 2026-10-05): crop damage on neighbour fields from the vehicle samples of the
 * export ({@code vehiclePositions}, every 10 s). {@code min-samples} samples in a row of one vehicle on the farmland of an
 * active character (FarmlandOwnership) with a crop ({@code onCrop}) and without a running order there (a case
 * IN_PROGRESS on the farmland, R3-H5 / A1) or a lease to the player count as one incident:
 * <ul>
 *   <li>the very first incident of a savegame only brings the in-game hint {@code hint-text};</li>
 *   <li>then a complaint of the owner (trust, at most one per field and game day);</li>
 *   <li>a repetition at the same owner within {@code repeat-days}: a compensation claim of
 *   {@code compensation-per-sample} per sample (case CROP_DAMAGE_CLAIM in "Flurkarte", pay / refuse like R2-D2;
 *   refused or unanswered after {@code decision-days}: {@code decline-trust-delta}).</li>
 * </ul>
 * Switched per savegame (off by default); in the world mode IDYLLIC trust losses and the claim × idyllic factor.
 */
@Service
public class CropDamageService {

    public static final String RELATED = "CROP_DAMAGE";

    private final CropDamageStreakRepository streaks;
    private final CropDamageEventRepository incidents;
    private final ServiceCaseRepository cases;
    private final SavegameRepository savegames;
    private final FactsService facts;
    private final FarmlandOwnershipService ownership;
    private final OutboxService outbox;
    private final NarrationRequestService narration;
    private final TrustScoreService trust;
    private final DiaryService diary;
    private final BurdeningEvents burdens;
    private final RpsimProperties props;

    public CropDamageService(CropDamageStreakRepository streaks, CropDamageEventRepository incidents,
                             ServiceCaseRepository cases, SavegameRepository savegames, FactsService facts,
                             FarmlandOwnershipService ownership, OutboxService outbox, NarrationRequestService narration,
                             TrustScoreService trust, DiaryService diary, BurdeningEvents burdens, RpsimProperties props) {
        this.streaks = streaks;
        this.incidents = incidents;
        this.cases = cases;
        this.savegames = savegames;
        this.facts = facts;
        this.ownership = ownership;
        this.outbox = outbox;
        this.narration = narration;
        this.trust = trust;
        this.diary = diary;
        this.burdens = burdens;
        this.props = props;
    }

    private RpsimProperties.CropDamage cfg() {
        return props.getFormulas().getCropDamage();
    }

    @EventListener
    @Order(87)
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        if (!cfg().isEnabled() || !burdens.on(sg, Burden.CROP_DAMAGE)) {
            return;
        }
        facts.latest(sg).ifPresent(f -> sample(sg, f));
    }

    /** Updates the streaks with one export; returns the incidents that reached the threshold. */
    @Transactional
    public List<CropDamageEvent> sample(Savegame sg, FarmFacts f) {
        long now = sg.getCurrentGameTime();
        Map<String, CropDamageStreak> open = new HashMap<>();
        streaks.findBySavegameOrderByIdAsc(sg).forEach(s -> open.put(s.getVehicleId(), s));
        List<CropDamageEvent> out = new java.util.ArrayList<>();
        List<BridgeDtos.VehiclePosition> positions = f.vehiclePositions() == null ? List.of() : f.vehiclePositions();
        for (BridgeDtos.VehiclePosition p : positions) {
            if (p.uniqueId() == null) {
                continue;
            }
            CropDamageStreak s = open.remove(p.uniqueId());
            Optional<Character> owner = Boolean.TRUE.equals(p.onCrop()) && p.farmlandId() != null
                    ? damagedOwner(sg, p.farmlandId()) : Optional.empty();
            if (owner.isEmpty()) {
                if (s != null) {
                    streaks.delete(s);
                }
                continue;
            }
            if (s == null || s.getFarmlandId() != p.farmlandId()) {
                if (s != null) {
                    streaks.delete(s);
                }
                s = new CropDamageStreak();
                s.setSavegame(sg);
                s.setVehicleId(p.uniqueId());
                s.setFarmlandId(p.farmlandId());
            }
            s.setSamples(s.getSamples() + 1);
            s.setLastGameTime(now);
            streaks.save(s);
            if (!s.isReported() && s.getSamples() >= cfg().getMinSamples()) {
                s.setReported(true);
                s.setIncidentId(incident(sg, owner.get(), s.getFarmlandId(), s.getSamples()).map(ev -> {
                    out.add(ev);
                    return ev.getId();
                }).orElse(null));
            } else if (s.isReported() && s.getIncidentId() != null) {
                grow(sg, s);
            }
        }
        open.values().forEach(streaks::delete); // not driven any more: the row is broken
        return out;
    }

    /** The row goes on after the incident: every further sample counts for the incident and its open claim. */
    private void grow(Savegame sg, CropDamageStreak s) {
        incidents.findById(s.getIncidentId()).ifPresent(ev -> {
            ev.setSamples(s.getSamples());
            if (ev.getClaimCaseId() != null) {
                cases.findById(ev.getClaimCaseId()).filter(c -> c.getStatus() == CaseStatus.AWAITING_PLAYER).ifPresent(c -> {
                    c.setQuantity(s.getSamples());
                    c.setOfferAmount(claimAmount(sg, s.getSamples()));
                });
            }
        });
    }

    private long claimAmount(Savegame sg, int samples) {
        return Math.round(cfg().getCompensationPerSample() * samples * burdens.factor(sg));
    }

    /** The active character owning the farmland, unless the player works or leases there. */
    Optional<Character> damagedOwner(Savegame sg, int farmlandId) {
        Optional<FarmlandOwnership> o = ownership.get(sg, farmlandId);
        if (o.isEmpty() || o.get().getOwnerType() != OwnerType.CHARACTER || o.get().getOwnerCharacter() == null
                || o.get().getOwnerCharacter().getStatus() != CharacterStatus.ACTIVE || o.get().isLeasedToPlayer()) {
            return Optional.empty();
        }
        boolean order = cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.IN_PROGRESS).stream()
                .anyMatch(c -> c.getFarmlandId() != null && c.getFarmlandId() == farmlandId);
        return order ? Optional.empty() : Optional.of(o.get().getOwnerCharacter());
    }

    private Optional<CropDamageEvent> incident(Savegame sg, Character owner, int farmlandId, int samples) {
        long now = sg.getCurrentGameTime();
        if (!sg.isCropDamageHintSent()) {
            sg.setCropDamageHintSent(true);
            outbox.notification(sg, cfg().getHintText(), "WARNING", now + GameTime.days(1), new Related(RELATED, null));
            return Optional.empty();
        }
        long day = GameTime.dayIndex(now);
        boolean today = incidents.findBySavegameAndFarmlandIdOrderByIdDesc(sg, farmlandId).stream()
                .anyMatch(x -> GameTime.dayIndex(x.getGameTime()) == day);
        if (today) {
            return Optional.empty();
        }
        boolean repeated = incidents.findBySavegameAndCharacterOrderByIdDesc(sg, owner).stream()
                .anyMatch(x -> GameTime.toDays(now - x.getGameTime()) <= cfg().getRepeatDays());
        CropDamageEvent ev = new CropDamageEvent();
        ev.setSavegame(sg);
        ev.setCharacter(owner);
        ev.setFarmlandId(farmlandId);
        ev.setSamples(samples);
        ev.setGameTime(now);
        incidents.save(ev);
        double factor = burdens.factor(sg);
        trust.recordEvent(owner, cfg().getTrustDelta() * factor, TrustReason.CROP_DAMAGE, "Fahrspuren auf Feld " + farmlandId);
        if (!repeated) {
            narration.request(sg, NarrationEventType.CROP_DAMAGE_COMPLAINT).from(owner)
                    .facts(NarrationFacts.builder().put("farmlandId", farmlandId).build())
                    .category(CommunicationCategory.NEGOTIATION).related(RELATED, ev.getId()).submit();
            return Optional.of(ev);
        }
        long amount = claimAmount(sg, samples);
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(CaseKind.CROP_DAMAGE_CLAIM);
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setCharacter(owner);
        sc.setFarmlandId(farmlandId);
        sc.setQuantity(samples);
        sc.setOfferAmount(amount);
        sc.setGameTime(now);
        sc.setDeadlineGameTime(now + GameTime.days(cfg().getDecisionDays()));
        sc.setCreatedAt(Instant.now());
        cases.save(sc);
        ev.setClaimCaseId(sc.getId());
        narration.request(sg, NarrationEventType.CROP_DAMAGE_CLAIM).from(owner)
                .facts(NarrationFacts.builder().put("farmlandId", farmlandId).put("amount", amount).put("samples", samples)
                        .put("decisionDays", Math.round(cfg().getDecisionDays())).build())
                .category(CommunicationCategory.NEGOTIATION).related(RELATED, sc.getId())
                .formLink("/farmland?case=" + sc.getId()).submit();
        return Optional.of(ev);
    }

    private ServiceCase open(Savegame sg, Long id) {
        ServiceCase sc = cases.findById(id).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + id));
        if (sc.getKind() != CaseKind.CROP_DAMAGE_CLAIM || sc.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Das ist bereits erledigt.");
        }
        return sc;
    }

    /** "Zahlen": the compensation as COMPENSATION. */
    @Transactional
    public ServiceCase pay(Savegame sg, Long id) {
        ServiceCase sc = open(sg, id);
        sc.setStatus(CaseStatus.SETTLED);
        sc.setResolution("PAID");
        sc.setPayoutAmount(sc.getOfferAmount());
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        outbox.money(sg, -sc.getOfferAmount(), MoneyReason.COMPENSATION, "Flurschaden Feld " + sc.getFarmlandId(),
                new Related(RELATED, sc.getId()));
        diary.addAuto(sg, "NEGOTIATION", "Flurschaden bezahlt", sc.getOfferAmount() + " € an "
                + sc.getCharacter().getName() + " für Fahrspuren auf Feld " + sc.getFarmlandId() + ".", RELATED, sc.getId());
        return sc;
    }

    /** "Ablehnen" (or no answer): trust loss. */
    @Transactional
    public ServiceCase decline(Savegame sg, Long id) {
        return refuse(sg, open(sg, id), "DECLINED");
    }

    private ServiceCase refuse(Savegame sg, ServiceCase sc, String resolution) {
        sc.setStatus(resolution.equals("IGNORED") ? CaseStatus.EXPIRED : CaseStatus.DECLINED);
        sc.setResolution(resolution);
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        trust.recordEvent(sc.getCharacter(), cfg().getDeclineTrustDelta() * burdens.factor(sg),
                TrustReason.CROP_DAMAGE_CLAIM_DECLINED, "Entschädigung für Feld " + sc.getFarmlandId() + " verweigert");
        return sc;
    }

    @EventListener
    @Order(87)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        long now = sg.getCurrentGameTime();
        for (ServiceCase sc : cases.findBySavegameAndKindInOrderByIdDesc(sg, List.of(CaseKind.CROP_DAMAGE_CLAIM))) {
            if (sc.getStatus() == CaseStatus.AWAITING_PLAYER && sc.getDeadlineGameTime() != null
                    && sc.getDeadlineGameTime() < now) {
                refuse(sg, sc, "IGNORED");
            }
        }
    }
}
