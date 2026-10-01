package de.farmpulse.rpsim.market;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.bridge.OutboxService;
import de.farmpulse.rpsim.bridge.OutboxService.Related;
import de.farmpulse.rpsim.character.CharacterLookup;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.diary.DiaryService;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterRole;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.ForwardContract;
import de.farmpulse.rpsim.domain.MarketEvent;
import de.farmpulse.rpsim.domain.MarketEventStatus;
import de.farmpulse.rpsim.domain.MarketEventType;
import de.farmpulse.rpsim.domain.MoneyReason;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.narration.FallbackTemplates;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.ForwardContractRepository;
import de.farmpulse.rpsim.repository.MarketEventRepository;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3 R3-M2: forward contracts (owner decisions in QUESTIONS.md). The player names fill type, sell point,
 * quantity and delivery month in the Agrarbörse; the backend names the fixed price = current price of the sell point x
 * (1 + factor-per-month x months of lead) and the player concludes it by button (no withdrawal). The contract is the
 * existing {@code PRICE_EVENT / FIXED} instruction, held back until the delivery month starts
 * ({@code gameTimeEarliest}) with its end as deadline; the mod reports the delivered quantity in
 * {@code contractReports}. A shortfall costs shortfall x fixed price x penalty-share ({@code CONTRACT_PENALTY}) and
 * trust of the land agent, a full delivery gains trust. Only one fixed price per sell point and fill type: no
 * forward contract next to an open special offer or another forward contract there, and the event engine creates no
 * special offer on a pair with an open forward contract.
 */
@Service
public class ForwardContractService {

    public static final String RELATED = "FORWARD_CONTRACT";

    private final ForwardContractRepository contracts;
    private final MarketEventRepository events;
    private final FactsService facts;
    private final OutboxService outbox;
    private final CharacterLookup lookup;
    private final TrustScoreService trust;
    private final NarrationRequestService narration;
    private final DiaryService diary;
    private final FallbackTemplates labels;
    private final GameTime gameTime;
    private final RpsimProperties props;

    public ForwardContractService(ForwardContractRepository contracts, MarketEventRepository events, FactsService facts,
                                  OutboxService outbox, CharacterLookup lookup, TrustScoreService trust,
                                  NarrationRequestService narration, DiaryService diary, FallbackTemplates labels,
                                  GameTime gameTime, RpsimProperties props) {
        this.contracts = contracts;
        this.events = events;
        this.facts = facts;
        this.outbox = outbox;
        this.lookup = lookup;
        this.trust = trust;
        this.narration = narration;
        this.diary = diary;
        this.labels = labels;
        this.gameTime = gameTime;
        this.props = props;
    }

    private RpsimProperties.ForwardContract cfg() {
        return props.getFormulas().getForwardContract();
    }

    /** The backend's offer: fixed price and delivery window for the form values. */
    public record Quote(String fillType, String sellPoint, long quantity, int leadMonths, double basePrice, long fixedPrice,
                        long deliveryStartGameTime, long deadlineGameTime, Integer deliveryPeriod, long expectedIncome) {
    }

    @Transactional(readOnly = true)
    public Quote quote(Savegame sg, String fillType, String sellPoint, long quantity, int leadMonths) {
        RpsimProperties.ForwardContract c = cfg();
        if (leadMonths < c.getMinLeadMonths() || leadMonths > c.getMaxLeadMonths()) {
            throw new BusinessRuleException("FORWARD_LEAD", "Der Liefermonat muss " + c.getMinLeadMonths() + " bis "
                    + c.getMaxLeadMonths() + " Monate voraus liegen.");
        }
        if (quantity < c.getMinQuantity() || quantity > c.getMaxQuantity() || quantity % c.getQuantityStep() != 0) {
            throw new BusinessRuleException("FORWARD_QUANTITY", "Die Menge muss zwischen " + c.getMinQuantity() + " und "
                    + c.getMaxQuantity() + " Litern in " + c.getQuantityStep() + "er-Schritten liegen.");
        }
        FarmFacts f = facts.latest(sg).orElse(null);
        double base = f == null || f.prices() == null ? 0 : f.prices().stream()
                .filter(p -> sellPoint.equals(p.sellPoint()) && fillType.equals(p.fillType()) && p.currentPrice() != null)
                .mapToDouble(BridgeDtos.Price::currentPrice).findFirst().orElse(0);
        if (base <= 0) {
            throw new BusinessRuleException("FORWARD_NO_PRICE", "Diese Verkaufsstelle nennt für "
                    + labels.label(fillType) + " gerade keinen Preis.");
        }
        long fixed = Math.round(base * (1 + c.getFactorPerMonth() * leadMonths));
        GameTime.Anchor anchor = gameTime.anchor(sg);
        long idx = anchor.monthIndex(sg.getCurrentGameTime()) + leadMonths;
        long start = anchor.monthStart(idx);
        long deadline = anchor.monthStart(idx + 1);
        Integer period = f != null && f.calendar() != null ? anchor.periodOf(idx) : null;
        return new Quote(fillType, sellPoint, quantity, leadMonths, base, fixed, start, deadline, period,
                Math.round(quantity / 1000.0 * fixed));
    }

    /** "Abschließen": the quote becomes binding (fixed price re-computed from the current price). */
    @Transactional
    public ForwardContract conclude(Savegame sg, String fillType, String sellPoint, long quantity, int leadMonths) {
        Quote q = quote(sg, fillType, sellPoint, quantity, leadMonths);
        if (contracts.findBySavegameAndStatus(sg, ForwardContract.OPEN).size() >= cfg().getMaxOpen()) {
            throw new BusinessRuleException("FORWARD_LIMIT", "Es sind höchstens " + cfg().getMaxOpen()
                    + " offene Vorkontrakte möglich.");
        }
        if (fixedPricePairs(sg).contains(sellPoint + "|" + fillType)) {
            throw new BusinessRuleException("FORWARD_PAIR_BUSY", "Für " + labels.label(fillType) + " an dieser "
                    + "Verkaufsstelle läuft schon ein Festpreis (Vorkontrakt oder Sonderkontrakt).");
        }
        ForwardContract fc = new ForwardContract();
        fc.setSavegame(sg);
        fc.setFillType(fillType);
        fc.setSellPoint(sellPoint);
        fc.setQuantity(quantity);
        fc.setFixedPrice(q.fixedPrice());
        fc.setBasePrice(q.basePrice());
        fc.setLeadMonths(leadMonths);
        fc.setDeliveryStartGameTime(q.deliveryStartGameTime());
        fc.setDeadlineGameTime(q.deadlineGameTime());
        fc.setStatus(ForwardContract.OPEN);
        fc.setCreatedGameTime(sg.getCurrentGameTime());
        contracts.save(fc);
        var ins = outbox.priceFixed(sg, fillType, sellPoint, q.fixedPrice(), quantity, q.deadlineGameTime(),
                q.deliveryStartGameTime(), new Related(RELATED, fc.getId()));
        fc.setInstructionId(ins.getInstructionId());
        diary.addAuto(sg, "MARKET", "Vorkontrakt abgeschlossen", quantity + " l " + labels.label(fillType) + " an "
                + sellPointName(sg, sellPoint) + " zu " + q.fixedPrice() + " € je 1.000 l, Lieferung in "
                + leadMonths + " Monat(en).", RELATED, fc.getId());
        return fc;
    }

    /** "sellPoint|fillType" pairs with an open forward contract (no special offer there, R3-M2). */
    public Set<String> openPairs(Savegame sg) {
        Set<String> s = new HashSet<>();
        contracts.findBySavegameAndStatus(sg, ForwardContract.OPEN).forEach(c -> s.add(c.getSellPoint() + "|" + c.getFillType()));
        return s;
    }

    /** Pairs that already carry a fixed price: open forward contracts and offered or running special offers. */
    Set<String> fixedPricePairs(Savegame sg) {
        Set<String> s = openPairs(sg);
        for (MarketEvent ev : events.findBySavegameAndStatusIn(sg, MarketEventEngine.OPEN)) {
            if (ev.getEventType() == MarketEventType.SPECIAL_OFFER && ev.getStatus() != MarketEventStatus.PLANNED) {
                s.add(ev.getSellPoint() + "|" + ev.getFillType());
            }
        }
        return s;
    }

    /** The mod reports the end of the FIXED contract with the delivered quantity. */
    @EventListener
    @Transactional
    public void onContractReported(BridgeEvents.ContractReported r) {
        ForwardContract fc = contracts.findByInstructionId(r.instructionId()).orElse(null);
        if (fc == null || !ForwardContract.OPEN.equals(fc.getStatus())) {
            return;
        }
        Savegame sg = fc.getSavegame();
        long delivered = Math.min(r.deliveredQuantity(), fc.getQuantity());
        long shortfall = fc.getQuantity() - delivered;
        fc.setDeliveredQuantity(delivered);
        fc.setEndReason(r.endReason());
        Character agent = lookup.firstActive(sg, CharacterRole.LAND_AGENT, CharacterRole.COOPERATIVE).orElse(null);
        String what = labels.label(fc.getFillType());
        NarrationFacts.Builder f = NarrationFacts.builder().put("fillType", fc.getFillType())
                .put("sellPoint", sellPointName(sg, fc.getSellPoint())).put("quantity", fc.getQuantity())
                .put("delivered", delivered).put("fixedPrice", fc.getFixedPrice());
        if (shortfall <= 0) {
            fc.setStatus(ForwardContract.FULFILLED);
            if (agent != null) {
                trust.recordEvent(agent, cfg().getFulfilledTrustDelta(), TrustReason.FORWARD_CONTRACT_FULFILLED, what);
            }
            narration.request(sg, NarrationEventType.FORWARD_CONTRACT_FULFILLED).from(agent).facts(f.build())
                    .category(CommunicationCategory.MARKET).related(RELATED, fc.getId()).submit();
            diary.addAuto(sg, "MARKET", "Vorkontrakt erfüllt", fc.getQuantity() + " l " + what + " vollständig zum "
                    + "Festpreis geliefert.", RELATED, fc.getId());
            return;
        }
        long penalty = Math.round(shortfall / 1000.0 * fc.getFixedPrice() * cfg().getPenaltyShare());
        fc.setStatus(ForwardContract.SHORTFALL);
        fc.setPenalty(penalty);
        if (penalty > 0) {
            outbox.money(sg, -penalty, MoneyReason.CONTRACT_PENALTY, "Fehlmenge Vorkontrakt " + what,
                    new Related(RELATED, fc.getId()));
        }
        if (agent != null) {
            trust.recordEvent(agent, cfg().getShortfallTrustDelta(), TrustReason.FORWARD_CONTRACT_SHORTFALL, what);
        }
        narration.request(sg, NarrationEventType.FORWARD_CONTRACT_SHORTFALL).from(agent)
                .facts(f.put("shortfall", shortfall).put("penalty", penalty).build())
                .category(CommunicationCategory.MARKET).related(RELATED, fc.getId()).submit();
        diary.addAuto(sg, "MARKET", "Vorkontrakt nicht erfüllt", delivered + " von " + fc.getQuantity() + " l " + what
                + " geliefert, Strafe " + penalty + " €.", RELATED, fc.getId());
    }

    public List<ForwardContract> list(Savegame sg) {
        return contracts.findBySavegameOrderByIdDesc(sg);
    }

    /** Open contracts (liquidity plan K2: expected income in the delivery month). */
    public List<ForwardContract> open(Savegame sg) {
        return contracts.findBySavegameAndStatus(sg, ForwardContract.OPEN);
    }

    private String sellPointName(Savegame sg, String id) {
        return facts.marketContext(sg).flatMap(c -> c.sellPoints().stream().filter(s -> s.id().equals(id)).findFirst())
                .map(BridgeDtos.SellPoint::name).orElse(id);
    }
}
