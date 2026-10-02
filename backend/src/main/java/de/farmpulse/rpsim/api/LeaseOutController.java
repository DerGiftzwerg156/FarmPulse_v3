package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.api.Requests.LeaseOutRequest;
import de.farmpulse.rpsim.api.Views.LeaseOutView;
import de.farmpulse.rpsim.api.Views.NegotiationView;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.contract.LeaseOutService;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.savegame.SavegameContext;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roadmap V3 R3-L1: leasing out own fields. The neighbours' bids are negotiations (kind LEASE_OFFER) answered through
 * {@code /api/negotiations/{id}/offer}; the renewal goes through {@code /api/contracts/{id}/renew}.
 */
@RestController
public class LeaseOutController {

    private final SavegameContext context;
    private final LeaseOutService leaseOut;
    private final ApiMapper mapper;
    private final RpsimProperties props;

    public LeaseOutController(SavegameContext context, LeaseOutService leaseOut, ApiMapper mapper, RpsimProperties props) {
        this.context = context;
        this.leaseOut = leaseOut;
        this.mapper = mapper;
        this.props = props;
    }

    @GetMapping("/api/lease-out")
    @Transactional(readOnly = true)
    public LeaseOutView overview() {
        Savegame sg = context.requireActive();
        RpsimProperties.LeaseOut cfg = props.getFormulas().getLeaseOut();
        return new LeaseOutView(cfg.getTermYearsMin(), cfg.getTermYearsMax(),
                leaseOut.list(sg).stream().map(mapper::contract).toList());
    }

    /** Flurkarte "Verpachten": the interested neighbours answer with a first bid (0..3 negotiations). */
    @PostMapping("/api/farmlands/{id}/lease-out")
    @Transactional
    public List<NegotiationView> offer(@PathVariable Integer id, @Valid @RequestBody LeaseOutRequest r) {
        return leaseOut.offer(context.requireActive(), id, r.termYears(), r.desiredRate()).stream()
                .map(mapper::negotiation).toList();
    }
}
