package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.api.Requests.DirectNegotiationRequest;
import de.farmpulse.rpsim.api.Requests.OfferRequest;
import de.farmpulse.rpsim.api.Requests.ParticipationRequest;
import de.farmpulse.rpsim.api.Requests.SellOfferRequest;
import de.farmpulse.rpsim.api.Views.FarmlandView;
import de.farmpulse.rpsim.api.Views.MarketEventView;
import de.farmpulse.rpsim.api.Views.NegotiationView;
import de.farmpulse.rpsim.api.Views.OfferResultView;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.market.MarketEventEngine;
import de.farmpulse.rpsim.negotiation.FarmlandOwnershipService;
import de.farmpulse.rpsim.negotiation.NegotiationEngine;
import de.farmpulse.rpsim.savegame.SavegameContext;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NegotiationController {

    private final SavegameContext context;
    private final FarmlandOwnershipService ownership;
    private final NegotiationEngine engine;
    private final MarketEventEngine market;
    private final ApiMapper mapper;

    public NegotiationController(SavegameContext context, FarmlandOwnershipService ownership, NegotiationEngine engine,
                                 MarketEventEngine market, ApiMapper mapper) {
        this.context = context;
        this.ownership = ownership;
        this.engine = engine;
        this.market = market;
        this.mapper = mapper;
    }

    /** Map overview of all farmlands incl. owner. */
    @GetMapping("/api/farmlands")
    @Transactional(readOnly = true)
    public List<FarmlandView> farmlands() {
        Savegame sg = context.requireActive();
        return ownership.list(sg).stream().map(o -> mapper.farmland(sg, o)).toList();
    }

    @PostMapping("/api/farmlands/{id}/sell-offer")
    @Transactional
    public List<NegotiationView> sell(@PathVariable Integer id, @Valid @RequestBody SellOfferRequest r) {
        return engine.createSaleOffer(context.requireActive(), id, r.askingPrice()).stream().map(mapper::negotiation).toList();
    }

    @GetMapping("/api/negotiations")
    @Transactional(readOnly = true)
    public List<NegotiationView> negotiations() {
        return engine.list(context.requireActive()).stream().map(mapper::negotiation).toList();
    }

    @PostMapping("/api/negotiations/direct")
    @Transactional
    public NegotiationView direct(@Valid @RequestBody DirectNegotiationRequest r) {
        return mapper.negotiation(engine.startDirect(context.requireActive(), r.characterId(), r.farmlandId()));
    }

    /** Form-based bid - never a number from free text. */
    @PostMapping("/api/negotiations/{id}/offer")
    @Transactional
    public OfferResultView offer(@PathVariable Long id, @Valid @RequestBody OfferRequest r) {
        NegotiationEngine.OfferOutcome o = engine.placeOffer(context.requireActive(), id, r.amount());
        return new OfferResultView(o.result().name(), o.counterAmount(), o.roundsLeft(), mapper.negotiation(o.negotiation()));
    }

    @PostMapping("/api/negotiations/{id}/withdraw")
    @Transactional
    public NegotiationView withdraw(@PathVariable Long id) {
        return mapper.negotiation(engine.withdraw(context.requireActive(), id));
    }

    @GetMapping("/api/market-events")
    @Transactional(readOnly = true)
    public List<MarketEventView> marketEvents() {
        return market.list(context.requireActive()).stream()
                .filter(e -> e.isAnnounced())
                .map(mapper::marketEvent).toList();
    }

    /** Player reaction to a special contract: participate or not (no free-text price negotiation). */
    @PostMapping("/api/market-events/{id}/participation")
    @Transactional
    public MarketEventView participate(@PathVariable Long id, @Valid @RequestBody ParticipationRequest r) {
        return mapper.marketEvent(market.decideParticipation(context.requireActive(), id, r.participate()));
    }
}
