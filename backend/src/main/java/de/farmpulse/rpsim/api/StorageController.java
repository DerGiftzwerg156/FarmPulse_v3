package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.api.Views.PriceSeries;
import de.farmpulse.rpsim.api.Views.PriceView;
import de.farmpulse.rpsim.api.Views.StorageOverview;
import de.farmpulse.rpsim.bridge.PriceQueryService;
import de.farmpulse.rpsim.savegame.SavegameContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StorageController {

    private final SavegameContext context;
    private final PriceQueryService prices;

    public StorageController(SavegameContext context, PriceQueryService prices) {
        this.context = context;
        this.prices = prices;
    }

    @GetMapping("/api/storage")
    @Transactional(readOnly = true)
    public StorageOverview storage() {
        return prices.storage(context.requireActive());
    }

    @GetMapping("/api/prices/current")
    @Transactional(readOnly = true)
    public List<PriceView> current() {
        return prices.current(context.requireActive());
    }

    @GetMapping("/api/prices/history")
    @Transactional(readOnly = true)
    public List<PriceSeries> history(@RequestParam(required = false) String fillType,
                                     @RequestParam(required = false) String sellPoint,
                                     @RequestParam(required = false) Long from, @RequestParam(required = false) Long to) {
        return prices.history(context.requireActive(), fillType, sellPoint, from, to);
    }
}
