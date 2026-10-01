package de.farmpulse.rpsim.market;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.PriceAlarm;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.narration.FallbackTemplates;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.PriceAlarmRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3 R3-M1: price alarms of the Agrarbörse (owner decisions in QUESTIONS.md). Checked on every import of
 * {@code farm_facts.prices} after the alarm was created; a condition already met fires on the next import. "Any sell
 * point" uses the best price of all sell points. A fired alarm shows a hint in the game (NOTIFICATION, valid
 * notification-days) and the land agent (else the cooperative) writes a short mail with the stock and its value; it
 * then stays off until the player activates it again.
 */
@Service
public class PriceAlarmService {

    public static final String RELATED = "PRICE_ALARM";
    static final Set<String> DIRECTIONS = Set.of(PriceAlarm.ABOVE, PriceAlarm.BELOW);

    private final PriceAlarmRepository alarms;
    private final SavegameRepository savegames;
    private final FactsService facts;
    private final OutboxService outbox;
    private final CharacterLookup lookup;
    private final NarrationRequestService narration;
    private final FallbackTemplates labels;
    private final RpsimProperties props;

    public PriceAlarmService(PriceAlarmRepository alarms, SavegameRepository savegames, FactsService facts,
                             OutboxService outbox, CharacterLookup lookup, NarrationRequestService narration,
                             FallbackTemplates labels, RpsimProperties props) {
        this.alarms = alarms;
        this.savegames = savegames;
        this.facts = facts;
        this.outbox = outbox;
        this.lookup = lookup;
        this.narration = narration;
        this.labels = labels;
        this.props = props;
    }

    private RpsimProperties.PriceAlarm cfg() {
        return props.getFormulas().getPriceAlarm();
    }

    @Transactional
    public PriceAlarm create(Savegame sg, String fillType, String sellPoint, double threshold, String direction) {
        if (fillType == null || fillType.isBlank()) {
            throw new BusinessRuleException("ALARM_FILLTYPE", "Bitte eine Sorte wählen.");
        }
        if (!DIRECTIONS.contains(direction)) {
            throw new BusinessRuleException("ALARM_DIRECTION", "Bitte „über“ oder „unter“ wählen.");
        }
        if (threshold <= 0) {
            throw new BusinessRuleException("ALARM_THRESHOLD", "Die Schwelle muss über 0 € liegen.");
        }
        if (active(sg) >= cfg().getMaxActive()) {
            throw new BusinessRuleException("ALARM_LIMIT", "Es sind höchstens " + cfg().getMaxActive()
                    + " aktive Preisalarme möglich.");
        }
        PriceAlarm a = new PriceAlarm();
        a.setSavegame(sg);
        a.setFillType(fillType);
        a.setSellPoint(sellPoint == null || sellPoint.isBlank() ? null : sellPoint);
        a.setThreshold(threshold);
        a.setDirection(direction);
        a.setStatus(PriceAlarm.ACTIVE);
        a.setCreatedGameTime(sg.getCurrentGameTime());
        return alarms.save(a);
    }

    long active(Savegame sg) {
        return alarms.findBySavegameAndStatus(sg, PriceAlarm.ACTIVE).size();
    }

    /** A fired alarm is switched on again (counts against max-active). */
    @Transactional
    public PriceAlarm reactivate(Savegame sg, Long id) {
        PriceAlarm a = get(sg, id);
        if (PriceAlarm.ACTIVE.equals(a.getStatus())) {
            return a;
        }
        if (active(sg) >= cfg().getMaxActive()) {
            throw new BusinessRuleException("ALARM_LIMIT", "Es sind höchstens " + cfg().getMaxActive()
                    + " aktive Preisalarme möglich.");
        }
        a.setStatus(PriceAlarm.ACTIVE);
        a.setCreatedGameTime(sg.getCurrentGameTime());
        a.setFiredGameTime(null);
        a.setFiredPrice(null);
        a.setFiredSellPoint(null);
        return a;
    }

    @Transactional
    public void delete(Savegame sg, Long id) {
        alarms.delete(get(sg, id));
    }

    PriceAlarm get(Savegame sg, Long id) {
        return alarms.findById(id).filter(a -> a.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("price alarm " + id));
    }

    public List<PriceAlarm> list(Savegame sg) {
        return alarms.findBySavegameOrderByIdDesc(sg);
    }

    /** The price that decides: the given sell point, or the best price of all sell points. */
    record Quote(String sellPoint, double price) {
    }

    static Optional<Quote> quote(FarmFacts f, String fillType, String sellPoint) {
        if (f == null || f.prices() == null) {
            return Optional.empty();
        }
        return f.prices().stream()
                .filter(p -> fillType.equals(p.fillType()) && p.currentPrice() != null && p.currentPrice() > 0)
                .filter(p -> sellPoint == null || sellPoint.equals(p.sellPoint()))
                .max(java.util.Comparator.comparingDouble(BridgeDtos.Price::currentPrice))
                .map(p -> new Quote(p.sellPoint(), p.currentPrice()));
    }

    static boolean reached(PriceAlarm a, double price) {
        return PriceAlarm.ABOVE.equals(a.getDirection()) ? price >= a.getThreshold() : price <= a.getThreshold();
    }

    /** Every import of farm_facts.prices: check the active alarms created before it. */
    @EventListener
    @Order(30)
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        List<PriceAlarm> list = alarms.findBySavegameAndStatus(sg, PriceAlarm.ACTIVE);
        if (list.isEmpty()) {
            return;
        }
        FarmFacts f = facts.latest(sg).orElse(null);
        for (PriceAlarm a : list) {
            if (a.getCreatedGameTime() > e.gameTime()) {
                continue; // a sample older than the alarm (reload without saving)
            }
            quote(f, a.getFillType(), a.getSellPoint()).filter(q -> reached(a, q.price())).ifPresent(q -> fire(sg, f, a, q));
        }
    }

    void fire(Savegame sg, FarmFacts f, PriceAlarm a, Quote q) {
        long now = sg.getCurrentGameTime();
        a.setStatus(PriceAlarm.FIRED);
        a.setFiredGameTime(now);
        a.setFiredPrice(q.price());
        a.setFiredSellPoint(q.sellPoint());
        String sellPoint = sellPointName(sg, q.sellPoint());
        long price = Math.round(q.price());
        String fill = labels.label(a.getFillType());
        outbox.notification(sg, "FarmPulse: Preisalarm " + fill + " – " + sellPoint + " zahlt " + price + " € je 1.000 l",
                "INFO", now + GameTime.days(cfg().getNotificationDays()), new Related(RELATED, a.getId()));
        double stock = f == null || f.assets() == null || f.assets().storage() == null ? 0
                : f.assets().storage().stream().filter(s -> a.getFillType().equals(s.fillType()) && s.amount() != null)
                        .mapToDouble(BridgeDtos.StorageEntry::amount).sum();
        long liters = Math.round(stock);
        narration.request(sg, NarrationEventType.PRICE_ALARM)
                .from(lookup.firstActive(sg, CharacterRole.LAND_AGENT, CharacterRole.COOPERATIVE).orElse(null))
                .facts(NarrationFacts.builder().put("fillType", a.getFillType()).put("sellPoint", sellPoint)
                        .put("price", price).put("alarmPrice", Math.round(a.getThreshold()))
                        .put("direction", a.getDirection()).put("stockLiters", liters)
                        .put("stockValue", Math.round(stock / 1000.0 * q.price()))
                        .put("stockNote", liters > 0 ? "Bei dir liegen " + NumberFormat.getIntegerInstance(Locale.GERMANY)
                                .format(liters) + " Liter, das sind gerade rund " + NumberFormat.getIntegerInstance(Locale.GERMANY)
                                .format(Math.round(stock / 1000.0 * q.price())) + " € wert." : "")
                        .build())
                .category(CommunicationCategory.MARKET).related(RELATED, a.getId()).formLink("/market").submit();
    }

    private String sellPointName(Savegame sg, String id) {
        return facts.marketContext(sg).flatMap(c -> c.sellPoints().stream().filter(s -> s.id().equals(id)).findFirst())
                .map(BridgeDtos.SellPoint::name).orElse(id);
    }
}
