package de.farmpulse.rpsim.api;

import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.dealer.DealerOrderService;
import de.farmpulse.rpsim.domain.DealerDelivery;
import de.farmpulse.rpsim.domain.DealerOrder;
import de.farmpulse.rpsim.domain.DealerOrderItem;
import de.farmpulse.rpsim.domain.DealerTradeIn;
import de.farmpulse.rpsim.domain.Negotiation;
import de.farmpulse.rpsim.domain.NegotiationStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.negotiation.NegotiationEngine;
import de.farmpulse.rpsim.savegame.SavegameContext;
import jakarta.validation.constraints.Positive;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roadmap vehicle dealer RFH-B / L (owner decisions 2026-10-15/16): the request from the cart, the tab "Bestellungen"
 * (offer with the negotiation, order, deliveries, trade-ins), the delivery point until the date (O2), "Lieferung erneut
 * versuchen" (L7) and "Stornieren" (B6). The dealer's hidden floor price is never part of a view.
 */
@RestController
@Validated
public class DealerOrderController {

    /** A machine that may be given in trade: name and game value. */
    public record TradeInCandidateView(String uniqueId, String name, long value) {
    }

    public record ChoiceRequest(Long deliveryPointId, boolean express) {
    }

    /** "Angebot anfragen": {@code perPosition} = cart position id -> choice ("einzeln"), else {@code all}. */
    public record OrderRequest(boolean together, List<String> tradeIns, ChoiceRequest all,
                               Map<Long, ChoiceRequest> perPosition) {
    }

    public record BidRequest(@Positive long amount) {
    }

    public record PlaceRequest(Long deliveryPointId) {
    }

    /** A position: list price, express fee, delivery time span in game days (express already applied), shares. */
    public record OrderItemView(long id, int position, String name, long listPrice, long expressFee, double daysMin,
                                double daysMax, Long priceShare, Long depositShare, String status) {
    }

    /**
     * A delivery: point (null = shop place) and its name, the delivery costs (an invalid point: shop place base fee),
     * date, what it books ({@code due}, the machines' part {@code rest}), the reason text of the last failure, the refund
     * of a cancellation and what may be done now.
     */
    public record DeliveryView(long id, String status, boolean express, boolean delayed, Long deliveryPointId,
                               String placeName, long deliveryFee, Long dueGameTime, Long deliveredGameTime,
                               String deliveredPlace, long due, long rest, boolean carriesTradeIns, String lastFailure,
                               long refund, boolean placeChangeable, boolean cancellable, boolean retryable,
                               List<OrderItemView> items) {
    }

    public record TradeInView(String vehicleId, String name, long gameValue, Long offerValue, String status) {
    }

    /** The negotiation of an offer without the dealer's limits. */
    public record OfferNegotiationView(long id, String status, int roundsUsed, int maxRounds, Long lastCounterOffer,
                                       Long closesAtGameTime) {
    }

    /**
     * An order: status of the backend, list total, trade-ins, offer (price, current price of the dealer, deposit at
     * agreement, valid until), negotiation, agreement, deposit paid, open amount ("Finanzieren": offer = total, order =
     * rest) and its deliveries.
     */
    public record OrderView(long id, String status, boolean together, long requestedGameTime, long listTotal,
                            long tradeInValue, long tradeInOffer, Long offerPrice, long currentPrice,
                            long depositAtAgreement, Long validUntilGameTime, OfferNegotiationView negotiation,
                            Long agreedPrice, Long deposit, boolean depositPaid, long openAmount, String machines,
                            boolean returnedToCart, Long closedGameTime, List<TradeInView> tradeIns,
                            List<DeliveryView> deliveries) {
    }

    public record OrdersView(List<OrderView> orders, int depositPercent, long retryFee) {
    }

    /** Start screen tile (RFH-H2): the next planned delivery; both null while none is planned. */
    public record NextDeliveryView(String machines, Long dueGameTime) {
    }

    private final SavegameContext context;
    private final DealerOrderService orders;
    private final NegotiationEngine engine;
    private final RpsimProperties props;

    public DealerOrderController(SavegameContext context, DealerOrderService orders, NegotiationEngine engine,
                                 RpsimProperties props) {
        this.context = context;
        this.orders = orders;
        this.engine = engine;
        this.props = props;
    }

    private RpsimProperties.MachineDealer cfg() {
        return props.getFormulas().getMachineDealer();
    }

    @GetMapping("/api/dealer/trade-in-candidates")
    @Transactional(readOnly = true)
    public List<TradeInCandidateView> tradeInCandidates() {
        return orders.tradeInCandidates(context.requireActive()).stream()
                .map(v -> new TradeInCandidateView(v.uniqueId(), v.name() == null ? v.uniqueId() : v.name(),
                        v.value() == null ? 0 : Math.round(v.value()))).toList();
    }

    @GetMapping("/api/dealer/orders")
    @Transactional
    public OrdersView list() {
        Savegame sg = context.requireActive();
        orders.sync(sg);
        return view(sg);
    }

    @GetMapping("/api/dealer/next-delivery")
    @Transactional(readOnly = true)
    public NextDeliveryView nextDelivery() {
        Savegame sg = context.requireActive();
        return orders.planned(sg).stream().filter(d -> DealerDelivery.PLANNED.equals(d.getStatus()))
                .min(java.util.Comparator.comparingLong(DealerDelivery::getDueGameTime))
                .map(d -> new NextDeliveryView(orders.names(orders.itemsOf(d)), d.getDueGameTime()))
                .orElse(new NextDeliveryView(null, null));
    }

    @PostMapping("/api/dealer/orders")
    @Transactional
    public OrdersView request(@RequestBody OrderRequest req) {
        Savegame sg = context.requireActive();
        Map<Long, DealerOrderService.DeliveryChoice> per = req.perPosition() == null ? null
                : req.perPosition().entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                        e -> choice(e.getValue())));
        orders.request(sg, new DealerOrderService.RequestInput(req.together(), req.tradeIns(),
                req.all() == null ? null : choice(req.all()), per));
        return view(sg);
    }

    private static DealerOrderService.DeliveryChoice choice(ChoiceRequest c) {
        return new DealerOrderService.DeliveryChoice(c.deliveryPointId(), c.express());
    }

    /** "Bieten": an offer of the player on the net price (form value only). */
    @PostMapping("/api/dealer/orders/{id}/bid")
    @Transactional
    public OrdersView bid(@PathVariable long id, @RequestBody BidRequest req) {
        Savegame sg = context.requireActive();
        DealerOrder o = offered(sg, id);
        engine.placeOffer(sg, o.getNegotiationId(), req.amount());
        orders.sync(sg);
        return view(sg);
    }

    /** "Zum Angebotspreis annehmen": the current price of the dealer. */
    @PostMapping("/api/dealer/orders/{id}/accept")
    @Transactional
    public OrdersView accept(@PathVariable long id) {
        Savegame sg = context.requireActive();
        DealerOrder o = offered(sg, id);
        engine.placeOffer(sg, o.getNegotiationId(), DealerOrderService.currentPrice(o, orders.negotiationOf(o).orElse(null)));
        orders.sync(sg);
        return view(sg);
    }

    /** "Ablehnen": the negotiation ends without agreement. */
    @PostMapping("/api/dealer/orders/{id}/decline")
    @Transactional
    public OrdersView decline(@PathVariable long id) {
        Savegame sg = context.requireActive();
        DealerOrder o = offered(sg, id);
        engine.withdraw(sg, o.getNegotiationId());
        orders.sync(sg);
        return view(sg);
    }

    @PostMapping("/api/dealer/orders/{id}/back-to-cart")
    @Transactional
    public OrdersView backToCart(@PathVariable long id) {
        Savegame sg = context.requireActive();
        orders.backToCart(sg, id);
        return view(sg);
    }

    @PutMapping("/api/dealer/deliveries/{id}/place")
    @Transactional
    public OrdersView changePlace(@PathVariable long id, @RequestBody PlaceRequest req) {
        Savegame sg = context.requireActive();
        orders.changePlace(sg, id, req.deliveryPointId());
        return view(sg);
    }

    @PostMapping("/api/dealer/deliveries/{id}/retry")
    @Transactional
    public OrdersView retry(@PathVariable long id) {
        Savegame sg = context.requireActive();
        orders.retry(sg, id);
        return view(sg);
    }

    @PostMapping("/api/dealer/deliveries/{id}/cancel")
    @Transactional
    public OrdersView cancel(@PathVariable long id) {
        Savegame sg = context.requireActive();
        orders.cancel(sg, id);
        return view(sg);
    }

    private DealerOrder offered(Savegame sg, long id) {
        DealerOrder o = orders.list(sg).stream().filter(x -> x.getId() == id).findFirst()
                .orElseThrow(() -> new de.farmpulse.rpsim.common.NotFoundException("Bestellung nicht gefunden: " + id));
        if (!DealerOrder.OFFERED.equals(o.getStatus()) || o.getNegotiationId() == null) {
            throw new de.farmpulse.rpsim.common.BusinessRuleException("NEGOTIATION_CLOSED",
                    "Diese Verhandlung ist bereits beendet.");
        }
        return o;
    }

    private OrdersView view(Savegame sg) {
        double factor = orders.dayFactor(sg);
        return new OrdersView(orders.list(sg).stream().map(o -> order(sg, o, factor)).toList(),
                (int) Math.round(cfg().getDepositShare() * 100), cfg().getRetryFee());
    }

    private OrderView order(Savegame sg, DealerOrder o, double factor) {
        Negotiation n = orders.negotiationOf(o).orElse(null);
        long current = DealerOrderService.currentPrice(o, n);
        long priceForAmount = o.getAgreedPrice() != null ? o.getAgreedPrice() : current;
        boolean depositPaid = DealerOrder.ORDERED.equals(o.getStatus()) || DealerOrder.COMPLETED.equals(o.getStatus())
                || DealerOrder.CANCELLED.equals(o.getStatus()) && o.getAgreedPrice() != null;
        List<DeliveryView> ds = orders.deliveriesOf(o).stream().map(d -> delivery(sg, o, d, factor)).toList();
        return new OrderView(o.getId(), o.getStatus(), o.isTogether(), o.getRequestedGameTime(), o.getListTotal(),
                o.getTradeInValue(), o.getTradeInOffer(), o.getOfferPrice(), current,
                Math.round(current * cfg().getDepositShare()), o.getValidUntilGameTime(),
                n == null ? null : new OfferNegotiationView(n.getId(), n.getStatus().name(), n.getRoundsUsed(),
                        n.getMaxRounds(), n.getStatus() == NegotiationStatus.OPEN || n.getStatus() == NegotiationStatus.REJECTED
                        ? n.getLastCounterOffer() : null, n.getClosesAtGameTime()),
                o.getAgreedPrice(), o.getDeposit(), depositPaid, orders.openAmount(sg, o, priceForAmount),
                orders.names(orders.itemsOf(o)), o.isReturnedToCart(), o.getClosedGameTime(),
                orders.tradeInsOf(o).stream().map(DealerOrderController::tradeIn).toList(), ds);
    }

    private static TradeInView tradeIn(DealerTradeIn t) {
        return new TradeInView(t.getVehicleId(), t.getName(), t.getGameValue(), t.getOfferValue(), t.getStatus());
    }

    private DeliveryView delivery(Savegame sg, DealerOrder o, DealerDelivery d, double factor) {
        List<DealerOrderItem> its = orders.itemsOf(o).stream().filter(i -> i.getDeliveryId() == d.getId()).toList();
        double express = d.isExpress() ? cfg().getExpressFactor() : 1;
        List<OrderItemView> items = its.stream().map(i -> new OrderItemView(i.getId(), i.getPosition(), i.getName(),
                i.getListPrice(), i.getExpressFee(), i.getDaysMin() * factor * express, i.getDaysMax() * factor * express,
                i.getPriceShare(), i.getDepositShare(), i.getStatus())).toList();
        boolean beforeSend = List.of(DealerDelivery.OPEN, DealerDelivery.PLANNED, DealerDelivery.WAITING).contains(d.getStatus());
        boolean ordered = DealerOrder.ORDERED.equals(o.getStatus());
        boolean open = DealerDelivery.PLANNED.equals(d.getStatus()) || DealerDelivery.WAITING.equals(d.getStatus());
        boolean active = beforeSend && !DealerOrder.REJECTED.equals(o.getStatus()) && !DealerOrder.EXPIRED.equals(o.getStatus())
                && !DealerOrder.FAILED.equals(o.getStatus()) && !DealerOrder.CANCELLED.equals(o.getStatus());
        return new DeliveryView(d.getId(), d.getStatus(), d.isExpress(), d.isDelayed(), d.getDeliveryPointId(),
                orders.placeName(d.getDeliveryPointId()), orders.effectiveFee(sg, d), d.getDueGameTime(),
                d.getDeliveredGameTime(), d.getPlaceName(), open ? orders.due(sg, d) : 0, open ? orders.rest(sg, d) : 0,
                d.isCarriesTradeIns(), DealerDelivery.WAITING.equals(d.getStatus()) ? orders.reasonText(sg, d) : null,
                orders.refund(d, orders.itemsOf(d)), active, ordered && open,
                ordered && DealerDelivery.WAITING.equals(d.getStatus()), items);
    }
}
