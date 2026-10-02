package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.api.Requests.SellOfferRequest;
import de.farmpulse.rpsim.api.Views.NegotiationView;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.VehicleDeal;
import de.farmpulse.rpsim.savegame.SavegameContext;
import de.farmpulse.rpsim.vehicle.VehicleTradeService;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roadmap V3 R3-V2 / R3-V3: used machines in the workshop app - own machines (with "Zum Verkauf anbieten") and the
 * deals with their negotiations. Offers and demands go through {@code /api/negotiations/{id}/offer}.
 */
@RestController
public class VehicleController {

    /** An own machine of the latest export; {@code saleDealId} = a running sale. */
    public record OwnVehicleView(String uniqueId, String name, long value, Double condition, Long saleDealId) {
    }

    public record VehicleDealView(Long id, String direction, String status, String sellerKind, Views.CharacterRef character,
                                  String vehicleName, String categoryName, Long listPrice, Integer ageMonths,
                                  Integer operatingHours, Double damage, Double wear, Long gamePrice, long basePrice,
                                  Long askingPrice, Long finalPrice, String vehicleId, int attempts,
                                  Long nextAttemptGameTime, String failureReason, long createdGameTime,
                                  Long closedGameTime, List<NegotiationView> negotiations) {
    }

    public record VehiclesView(boolean enabled, int maxRounds, double saleCapPercent, int spawnMaxAttempts,
                               List<OwnVehicleView> vehicles, List<VehicleDealView> deals) {
    }

    private final SavegameContext context;
    private final VehicleTradeService trade;
    private final ApiMapper mapper;
    private final RpsimProperties props;

    public VehicleController(SavegameContext context, VehicleTradeService trade, ApiMapper mapper, RpsimProperties props) {
        this.context = context;
        this.trade = trade;
        this.mapper = mapper;
        this.props = props;
    }

    @GetMapping("/api/vehicles")
    @Transactional(readOnly = true)
    public VehiclesView vehicles() {
        Savegame sg = context.requireActive();
        RpsimProperties.UsedVehicle cfg = props.getFormulas().getUsedVehicle();
        List<OwnVehicleView> own = trade.ownVehicles(sg).stream()
                .map(v -> new OwnVehicleView(v.uniqueId(), v.name(), v.value() == null ? 0 : Math.round(v.value()),
                        v.condition(), trade.onSale(sg, v.uniqueId()).map(VehicleDeal::getId).orElse(null)))
                .toList();
        return new VehiclesView(cfg.isEnabled(), props.getFormulas().getNegotiation().getMaxRounds(),
                Math.round(cfg.getSaleCap() * 1000) / 10.0, cfg.getSpawnMaxAttempts(), own,
                trade.list(sg).stream().map(d -> view(sg, d)).toList());
    }

    /** "Zum Verkauf anbieten" with an asking price. */
    @PostMapping("/api/vehicles/{vehicleId}/sale")
    @Transactional
    public VehicleDealView offerForSale(@PathVariable String vehicleId, @Valid @RequestBody SellOfferRequest r) {
        Savegame sg = context.requireActive();
        return view(sg, trade.offerForSale(sg, vehicleId, r.askingPrice()));
    }

    VehicleDealView view(Savegame sg, VehicleDeal d) {
        return new VehicleDealView(d.getId(), d.getDirection(), d.getStatus(), d.getSellerKind(), mapper.ref(d.getCharacter()),
                d.getVehicleName(), d.getCategoryName(), d.getListPrice(), d.getAgeMonths(), d.getOperatingHours(),
                d.getDamage(), d.getWear(), d.getGamePrice(), d.getBasePrice(), d.getAskingPrice(), d.getFinalPrice(),
                d.getVehicleId(), d.getAttempts(), d.getNextAttemptGameTime(), d.getFailureReason(), d.getCreatedGameTime(),
                d.getClosedGameTime(), trade.negotiationsOf(sg, d).stream().map(mapper::negotiation).toList());
    }
}
