package de.farmpulse.rpsim.contract;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

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
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
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
 * TODO T-20 vet / livestock trader / breeding advisor. Only active while the export contains animals
 * ({@code assets.animals}). There is no verified mod API to add or remove animals, so the player trades animals in
 * the game; the trader pays a brokerage premium (LIVESTOCK_PREMIUM) when the exported head count changes as agreed.
 * <ul>
 *   <li>vet: routine visit per animal type every few game months, invoice (VET_INVOICE) + health note</li>
 *   <li>trader: sell / buy offers with a premium per animal</li>
 *   <li>breeding advisor: advice on offspring from the head count development (FS25 animals reproduce)</li>
 * </ul>
 */
@Service
public class LivestockService {

    public static final String RELATED = InsuranceService.RELATED;
    static final List<String> HEALTH_NOTES = List.of("ALL_HEALTHY", "HOOF_CARE", "VACCINATION_DUE", "FEED_CHECK");

    /** Head count and value per animal type from the export. */
    public record Herd(String type, int count, double value) {
        double unitValue() {
            return count <= 0 ? 0 : value / count;
        }
    }

    private final ServiceCaseRepository cases;
    private final SavegameRepository savegames;
    private final FactsService facts;
    private final OutboxService outbox;
    private final ServiceRoleService roles;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final RandomSource random;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public LivestockService(ServiceCaseRepository cases, SavegameRepository savegames, FactsService facts, OutboxService outbox,
                            ServiceRoleService roles, NarrationRequestService narration, DiaryService diary,
                            RandomSource random, RpsimProperties props, GameTime gameTime) {
        this.cases = cases;
        this.savegames = savegames;
        this.facts = facts;
        this.outbox = outbox;
        this.roles = roles;
        this.narration = narration;
        this.diary = diary;
        this.random = random;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.Livestock cfg() {
        return props.getFormulas().getLivestock();
    }

    /** Animals per type (all husbandries of a type summed up), sorted by type. */
    public static Map<String, Herd> herds(FarmFacts f) {
        Map<String, Herd> out = new TreeMap<>();
        if (f == null || f.assets() == null) {
            return out;
        }
        for (BridgeDtos.Animal a : f.assets().animals()) {
            if (a.count() == null || a.count() <= 0) {
                continue;
            }
            String type = a.type() == null ? "UNKNOWN" : a.type();
            Herd h = out.get(type);
            int count = (h == null ? 0 : h.count()) + a.count();
            double value = (h == null ? 0 : h.value()) + (a.estimatedValue() == null ? 0 : a.estimatedValue());
            out.put(type, new Herd(type, count, value));
        }
        return out;
    }

    Map<String, Herd> herds(Savegame sg) {
        return herds(facts.latest(sg).orElse(null));
    }

    // ------------------------------------------------------------------------------------------ monthly

    @EventListener
    @Order(72)
    @Transactional
    public void onMonth(GameMonthPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        Map<String, Herd> herds = herds(sg);
        if (herds.isEmpty()) {
            return;
        }
        for (Herd h : herds.values()) {
            if (due(sg, CaseKind.VET_VISIT, h.type(), cfg().getVetVisitEveryMonths())) {
                vetVisit(sg, h);
            }
            if (due(sg, CaseKind.BREEDING_ADVICE, h.type(), cfg().getBreedingAdviceEveryMonths())) {
                breedingAdvice(sg, h);
            }
        }
        if (random.chance(cfg().getTraderProbabilityPerMonth())) {
            traderOffer(sg, random.pick(new ArrayList<>(herds.values())));
        }
    }

    /** True when the last case of the kind for the animal type is at least {@code everyMonths} months old. */
    boolean due(Savegame sg, CaseKind kind, String type, int everyMonths) {
        long now = sg.getCurrentGameTime();
        return cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(kind)).stream()
                .filter(c -> type.equals(c.getReference())).findFirst()
                .map(c -> gameTime.monthIndex(sg, now) - gameTime.monthIndex(sg, c.getGameTime()) >= everyMonths)
                .orElse(true);
    }

    private ServiceCase newCase(Savegame sg, CaseKind kind, Character c, String type) {
        ServiceCase sc = new ServiceCase();
        sc.setSavegame(sg);
        sc.setKind(kind);
        sc.setCharacter(c);
        sc.setReference(type);
        sc.setGameTime(sg.getCurrentGameTime());
        sc.setCreatedAt(Instant.now());
        return sc;
    }

    /** Routine visit: invoice = base fee + fee per animal. */
    @Transactional
    public ServiceCase vetVisit(Savegame sg, Herd h) {
        Character vet = roles.ensure(sg, CharacterRole.VETERINARIAN);
        long invoice = cfg().getVetBaseFee() + cfg().getVetFeePerAnimal() * h.count();
        ServiceCase sc = newCase(sg, CaseKind.VET_VISIT, vet, h.type());
        sc.setStatus(CaseStatus.SETTLED);
        sc.setResolution("INVOICED");
        sc.setCostAmount(invoice);
        sc.setQuantity(h.count());
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        cases.save(sc);
        outbox.money(sg, -invoice, MoneyReason.VET_INVOICE, "Tierarzt " + h.type(), new Related(RELATED, sc.getId()));
        narration.request(sg, NarrationEventType.VET_VISIT).from(vet)
                .facts(NarrationFacts.builder().put("animalType", h.type()).put("animalCount", h.count())
                        .put("invoice", invoice).put("healthNote", random.pick(HEALTH_NOTES)).build())
                .category(CommunicationCategory.LIVESTOCK).related(RELATED, sc.getId()).submit();
        diary.addAuto(sg, "LIVESTOCK", "Tierarztbesuch", h.count() + " Tiere (" + h.type() + ") untersucht, Rechnung "
                + invoice + " €.", RELATED, sc.getId());
        return sc;
    }

    /** Breeding advice from the head count development since the last advice (offspring in FS25). */
    @Transactional
    public ServiceCase breedingAdvice(Savegame sg, Herd h) {
        Character advisor = roles.ensure(sg, CharacterRole.BREEDING_ADVISOR);
        Integer before = cases.findBySavegameAndKindInOrderByIdDesc(sg, EnumSet.of(CaseKind.BREEDING_ADVICE)).stream()
                .filter(c -> h.type().equals(c.getReference())).findFirst().map(ServiceCase::getQuantity).orElse(null);
        ServiceCase sc = newCase(sg, CaseKind.BREEDING_ADVICE, advisor, h.type());
        sc.setStatus(CaseStatus.SETTLED);
        sc.setResolution("ADVICE");
        sc.setQuantity(h.count());
        sc.setBaselineCount(before);
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        cases.save(sc);
        String trend = before == null ? "FIRST_CONTACT" : h.count() > before ? "GROWING" : h.count() < before ? "SHRINKING" : "STABLE";
        narration.request(sg, NarrationEventType.BREEDING_ADVICE).from(advisor)
                .facts(NarrationFacts.builder().put("animalType", h.type()).put("animalCount", h.count())
                        .put("previousCount", before).put("herdTrend", trend).build())
                .category(CommunicationCategory.LIVESTOCK).related(RELATED, sc.getId()).submit();
        return sc;
    }

    /** Sell or buy offer of the trader with a premium per animal. */
    @Transactional
    public Optional<ServiceCase> traderOffer(Savegame sg, Herd h) {
        boolean buy = random.chance(cfg().getTraderBuyShare());
        int quantity;
        if (buy) {
            quantity = random.intBetween(cfg().getTraderQuantityMin(), cfg().getTraderQuantityMax());
        } else {
            int max = Math.min(cfg().getTraderQuantityMax(), (int) Math.floor(h.count() * cfg().getTraderMaxHerdShare()));
            if (max < cfg().getTraderQuantityMin()) {
                return Optional.empty();
            }
            quantity = random.intBetween(cfg().getTraderQuantityMin(), max);
        }
        long premium = Math.max(10, round10(h.unitValue()
                * random.uniform(cfg().getTraderPremiumShareMin(), cfg().getTraderPremiumShareMax())));
        Character trader = roles.ensure(sg, CharacterRole.LIVESTOCK_TRADER);
        ServiceCase sc = newCase(sg, CaseKind.LIVESTOCK_OFFER, trader, h.type());
        sc.setStatus(CaseStatus.AWAITING_PLAYER);
        sc.setDirection(buy ? "BUY" : "SELL");
        sc.setQuantity(quantity);
        sc.setOfferAmount(premium);
        sc.setDeadlineGameTime(sg.getCurrentGameTime() + GameTime.days(cfg().getTraderAnswerDays()));
        cases.save(sc);
        narration.request(sg, NarrationEventType.LIVESTOCK_OFFER).from(trader)
                .facts(NarrationFacts.builder().put("direction", sc.getDirection()).put("animalType", h.type())
                        .put("quantity", quantity).put("premiumPerAnimal", premium)
                        .put("answerDays", Math.round(cfg().getTraderAnswerDays()))
                        .put("deadlineMonths", cfg().getTraderDeadlineMonths()).build())
                .category(CommunicationCategory.LIVESTOCK).related(RELATED, sc.getId())
                .formLink("/contracts?case=" + sc.getId()).submit();
        return Optional.of(sc);
    }

    ServiceCase offer(Savegame sg, Long caseId) {
        ServiceCase sc = cases.findById(caseId).filter(x -> x.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("case " + caseId));
        if (sc.getKind() != CaseKind.LIVESTOCK_OFFER || sc.getStatus() != CaseStatus.AWAITING_PLAYER) {
            throw new BusinessRuleException("CASE_CLOSED", "Dieses Angebot ist nicht mehr offen.");
        }
        return sc;
    }

    /** The player agrees - the head count now decides. */
    @Transactional
    public ServiceCase accept(Savegame sg, Long caseId) {
        ServiceCase sc = offer(sg, caseId);
        Herd h = herds(sg).get(sc.getReference());
        sc.setBaselineCount(h == null ? 0 : h.count());
        sc.setStatus(CaseStatus.IN_PROGRESS);
        sc.setDeadlineGameTime(gameTime.addMonths(sg, sg.getCurrentGameTime(), cfg().getTraderDeadlineMonths()));
        diary.addAuto(sg, "LIVESTOCK", "Handel mit dem Viehhändler", ("SELL".equals(sc.getDirection()) ? "Verkauf" : "Kauf")
                + " von " + sc.getQuantity() + " Tieren (" + sc.getReference() + ") vereinbart – im Spiel erledigen.",
                RELATED, sc.getId());
        return sc;
    }

    @Transactional
    public ServiceCase decline(Savegame sg, Long caseId) {
        ServiceCase sc = offer(sg, caseId);
        sc.setStatus(CaseStatus.DECLINED);
        sc.setResolution("PLAYER");
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        return sc;
    }

    /** Animals moved in the agreed direction since the offer was accepted. */
    static int moved(ServiceCase sc, int currentCount) {
        int base = sc.getBaselineCount() == null ? 0 : sc.getBaselineCount();
        int diff = "SELL".equals(sc.getDirection()) ? base - currentCount : currentCount - base;
        return Math.max(0, Math.min(sc.getQuantity(), diff));
    }

    /** Daily: answer deadlines, fulfilment check of accepted offers against the latest export. */
    @EventListener
    @Order(72)
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        check(sg);
    }

    @Transactional
    public void check(Savegame sg) {
        long now = sg.getCurrentGameTime();
        Map<String, Herd> herds = herds(sg);
        for (ServiceCase sc : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.AWAITING_PLAYER)) {
            if (sc.getKind() == CaseKind.LIVESTOCK_OFFER && sc.getDeadlineGameTime() != null && sc.getDeadlineGameTime() < now) {
                sc.setStatus(CaseStatus.EXPIRED);
                sc.setResolution("NO_ANSWER");
                sc.setClosedAtGameTime(now);
            }
        }
        for (ServiceCase sc : cases.findBySavegameAndStatusOrderByIdAsc(sg, CaseStatus.IN_PROGRESS)) {
            if (sc.getKind() != CaseKind.LIVESTOCK_OFFER) {
                continue;
            }
            Herd h = herds.get(sc.getReference());
            int moved = moved(sc, h == null ? 0 : h.count());
            boolean done = moved >= sc.getQuantity();
            boolean late = sc.getDeadlineGameTime() != null && sc.getDeadlineGameTime() < now;
            if (done || late) {
                close(sg, sc, moved, done ? "FULFILLED" : moved > 0 ? "PARTIAL" : "LAPSED");
            }
        }
    }

    private void close(Savegame sg, ServiceCase sc, int moved, String resolution) {
        sc.setStatus(moved > 0 ? CaseStatus.SETTLED : CaseStatus.EXPIRED);
        sc.setResolution(resolution);
        sc.setClosedAtGameTime(sg.getCurrentGameTime());
        long premium = moved * sc.getOfferAmount();
        if (premium > 0) {
            sc.setPayoutAmount(premium);
            outbox.money(sg, premium, MoneyReason.LIVESTOCK_PREMIUM, "Vermittlungsprämie " + sc.getReference(),
                    new Related(RELATED, sc.getId()));
            narration.request(sg, NarrationEventType.LIVESTOCK_PREMIUM_PAID).from(sc.getCharacter())
                    .facts(NarrationFacts.builder().put("animalType", sc.getReference()).put("quantity", moved)
                            .put("premium", premium).put("direction", sc.getDirection()).build())
                    .category(CommunicationCategory.LIVESTOCK).related(RELATED, sc.getId()).submit();
            diary.addAuto(sg, "LIVESTOCK", "Vermittlungsprämie", premium + " € für " + moved + " Tiere.", RELATED, sc.getId());
        } else {
            narration.request(sg, NarrationEventType.LIVESTOCK_OFFER_LAPSED).from(sc.getCharacter())
                    .facts(NarrationFacts.builder().put("animalType", sc.getReference()).put("direction", sc.getDirection())
                            .build())
                    .category(CommunicationCategory.LIVESTOCK).related(RELATED, sc.getId()).submit();
        }
    }

    private static long round10(double v) {
        return Math.round(v / 10.0) * 10;
    }
}
