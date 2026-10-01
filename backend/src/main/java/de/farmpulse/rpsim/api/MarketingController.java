package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.ForwardContract;
import de.farmpulse.rpsim.domain.PriceAlarm;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.market.ForwardContractService;
import de.farmpulse.rpsim.market.PriceAlarmService;
import de.farmpulse.rpsim.savegame.SavegameContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roadmap V3 R3-M1 / R3-M2: price alarms and forward contracts of the Agrarbörse. The farm shop (R3-M3) runs as
 * service cases ({@code /api/cases}) shown in the app "Handel".
 */
@RestController
public class MarketingController {

    /** {@code sellPoint} null = best price of all sell points; {@code direction} ABOVE / BELOW. */
    public record PriceAlarmRequest(@NotBlank String fillType, String sellPoint, @NotNull @Positive Double threshold,
                                    @NotBlank String direction) {
    }

    public record PriceAlarmView(Long id, String fillType, String sellPoint, double threshold, String direction,
                                 String status, long createdGameTime, Long firedGameTime, Double firedPrice,
                                 String firedSellPoint) {
    }

    public record PriceAlarmsView(int maxActive, List<PriceAlarmView> alarms) {
    }

    public record ForwardRequest(@NotBlank String fillType, @NotBlank String sellPoint, @NotNull @Positive Long quantity,
                                 @NotNull @Min(1) @Max(24) Integer leadMonths) {
    }

    public record ForwardContractView(Long id, String fillType, String sellPoint, long quantity, long fixedPrice,
                                      double basePrice, int leadMonths, long deliveryStartGameTime, long deadlineGameTime,
                                      String status, Long deliveredQuantity, Long penalty, long createdGameTime) {
    }

    /** Limits of the form and the contracts, newest first. */
    public record ForwardContractsView(int minLeadMonths, int maxLeadMonths, long minQuantity, long maxQuantity,
                                       long quantityStep, int maxOpen, double factorPerMonthPercent,
                                       double penaltySharePercent, List<ForwardContractView> contracts) {
    }

    private final SavegameContext context;
    private final PriceAlarmService alarms;
    private final ForwardContractService forwards;
    private final RpsimProperties props;

    public MarketingController(SavegameContext context, PriceAlarmService alarms, ForwardContractService forwards,
                               RpsimProperties props) {
        this.context = context;
        this.alarms = alarms;
        this.forwards = forwards;
        this.props = props;
    }

    static PriceAlarmView view(PriceAlarm a) {
        return new PriceAlarmView(a.getId(), a.getFillType(), a.getSellPoint(), a.getThreshold(), a.getDirection(),
                a.getStatus(), a.getCreatedGameTime(), a.getFiredGameTime(), a.getFiredPrice(), a.getFiredSellPoint());
    }

    static ForwardContractView view(ForwardContract c) {
        return new ForwardContractView(c.getId(), c.getFillType(), c.getSellPoint(), c.getQuantity(), c.getFixedPrice(),
                c.getBasePrice(), c.getLeadMonths(), c.getDeliveryStartGameTime(), c.getDeadlineGameTime(), c.getStatus(),
                c.getDeliveredQuantity(), c.getPenalty(), c.getCreatedGameTime());
    }

    @GetMapping("/api/price-alarms")
    @Transactional(readOnly = true)
    public PriceAlarmsView priceAlarms() {
        return new PriceAlarmsView(props.getFormulas().getPriceAlarm().getMaxActive(),
                alarms.list(context.requireActive()).stream().map(MarketingController::view).toList());
    }

    @PostMapping("/api/price-alarms")
    @Transactional
    public PriceAlarmView createAlarm(@Valid @RequestBody PriceAlarmRequest r) {
        return view(alarms.create(context.requireActive(), r.fillType(), r.sellPoint(), r.threshold(), r.direction()));
    }

    @PostMapping("/api/price-alarms/{id}/reactivate")
    @Transactional
    public PriceAlarmView reactivate(@PathVariable Long id) {
        return view(alarms.reactivate(context.requireActive(), id));
    }

    @DeleteMapping("/api/price-alarms/{id}")
    @Transactional
    public void deleteAlarm(@PathVariable Long id) {
        alarms.delete(context.requireActive(), id);
    }

    @GetMapping("/api/forward-contracts")
    @Transactional(readOnly = true)
    public ForwardContractsView forwardContracts() {
        Savegame sg = context.requireActive();
        RpsimProperties.ForwardContract c = props.getFormulas().getForwardContract();
        return new ForwardContractsView(c.getMinLeadMonths(), c.getMaxLeadMonths(), c.getMinQuantity(), c.getMaxQuantity(),
                c.getQuantityStep(), c.getMaxOpen(), Math.round(c.getFactorPerMonth() * 1000) / 10.0,
                Math.round(c.getPenaltyShare() * 1000) / 10.0,
                forwards.list(sg).stream().map(MarketingController::view).toList());
    }

    /** The backend's fixed price for the form values (not binding). */
    @PostMapping("/api/forward-contracts/quote")
    @Transactional(readOnly = true)
    public ForwardContractService.Quote quote(@Valid @RequestBody ForwardRequest r) {
        return forwards.quote(context.requireActive(), r.fillType(), r.sellPoint(), r.quantity(), r.leadMonths());
    }

    /** "Abschließen": binding at the fixed price computed now. */
    @PostMapping("/api/forward-contracts")
    @Transactional
    public ForwardContractView conclude(@Valid @RequestBody ForwardRequest r) {
        return view(forwards.conclude(context.requireActive(), r.fillType(), r.sellPoint(), r.quantity(), r.leadMonths()));
    }
}
