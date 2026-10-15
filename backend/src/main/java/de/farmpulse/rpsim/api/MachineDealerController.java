package de.farmpulse.rpsim.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.dealer.DealerCartService;
import de.farmpulse.rpsim.dealer.DeliveryTime;
import de.farmpulse.rpsim.dealer.MachineCatalogService;
import de.farmpulse.rpsim.dealer.MachineConfigurator;
import de.farmpulse.rpsim.dealer.MachineDealerService;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.savegame.SavegameContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roadmap vehicle dealer RFH-H: the app "Händler". {@code GET} shows the dealer without side effect; {@code POST .../open}
 * is sent when the app is opened and creates the dealer of an older savegame (introduction by mail and diary).
 * RFH-K: the catalog (list and one entry), the configurator ({@code POST /api/dealer/configure}: list price, daily
 * upkeep, options set with it, package, expected delivery) and the cart.
 */
@RestController
public class MachineDealerController {

    /** The dealer as contact: reference and trust level (display abstraction, never the number). */
    public record DealerContactView(Views.CharacterRef character, String trustLevel) {
    }

    /** {@code dealer} = null while an older savegame has no dealer yet (before the first opening). */
    public record DealerView(DealerContactView dealer) {
    }

    /** RFH-K1: {@code available} = false while the mod sent no catalog; {@code page} is then null. */
    public record CatalogView(boolean available, MachineCatalogService.Page page) {
    }

    /** RFH-K2: a configuration request - shop entry and the player's choice (type name -> option index). */
    public record ConfigureRequest(@NotBlank String xmlFilename, Map<String, Integer> configurations) {
    }

    /** RFH-K3: a new choice for a cart position. */
    public record ChoiceRequest(Map<String, Integer> configurations) {
    }

    /**
     * RFH-K2: the machine put together - configuration after the options set with it, list price, daily upkeep, the
     * package of the manufacturer it matches (null = "individuell"), the options set with it, the options that deviate
     * from the game's default, the L2 extra days and the expected delivery (game times, with and without express).
     */
    public record QuoteView(String xmlFilename, String name, Map<String, Integer> configurations, long listPrice,
                            long dailyUpkeep, String configurationSet, List<MachineConfigurator.TypeOption> setWith,
                            List<MachineConfigurator.TypeOption> deviations, double extraDays,
                            DeliveryTime.Estimate delivery) {
    }

    /** RFH-K3: a cart position; {@code quote} = null when the machine left the catalog or its options changed. */
    public record CartPositionView(long id, String xmlFilename, String name, Map<String, Integer> choice,
                                   QuoteView quote) {
    }

    public record CartView(List<CartPositionView> positions, int maxPositions, long listPriceSum) {
    }

    private final SavegameContext context;
    private final MachineDealerService dealers;
    private final ApiMapper mapper;
    private final MachineCatalogService catalog;
    private final DealerCartService cart;

    public MachineDealerController(SavegameContext context, MachineDealerService dealers, ApiMapper mapper,
                                   MachineCatalogService catalog, DealerCartService cart) {
        this.context = context;
        this.dealers = dealers;
        this.mapper = mapper;
        this.catalog = catalog;
        this.cart = cart;
    }

    @GetMapping("/api/dealer")
    @Transactional(readOnly = true)
    public DealerView dealer() {
        Savegame sg = context.requireActive();
        return new DealerView(dealers.dealer(sg).map(this::contact).orElse(null));
    }

    @PostMapping("/api/dealer/open")
    @Transactional
    public DealerView open() {
        Savegame sg = context.requireActive();
        return new DealerView(contact(dealers.open(sg)));
    }

    private DealerContactView contact(Character c) {
        return new DealerContactView(mapper.ref(c), mapper.trustLevel(c));
    }

    @GetMapping("/api/dealer/catalog")
    @Transactional(readOnly = true)
    public CatalogView catalog(@RequestParam(required = false) String text,
                               @RequestParam(required = false) String category,
                               @RequestParam(required = false) String brand,
                               @RequestParam(required = false) Long priceMin,
                               @RequestParam(required = false) Long priceMax,
                               @RequestParam(required = false) String sort,
                               @RequestParam(defaultValue = "false") boolean desc,
                               @RequestParam(defaultValue = "0") int page) {
        Savegame sg = context.requireActive();
        return catalog.list(sg, new MachineCatalogService.Query(text, category, brand, priceMin, priceMax, sort, desc, page))
                .map(p -> new CatalogView(true, p)).orElse(new CatalogView(false, null));
    }

    /** RFH-K2: one entry with every option (the tablet shows the selectable ones). */
    @GetMapping("/api/dealer/catalog/item")
    @Transactional(readOnly = true)
    public BridgeDtos.CatalogItem catalogItem(@RequestParam String xmlFilename) {
        return catalog.requireItem(context.requireActive(), xmlFilename);
    }

    @PostMapping("/api/dealer/configure")
    @Transactional(readOnly = true)
    public QuoteView configure(@Valid @RequestBody ConfigureRequest req) {
        Savegame sg = context.requireActive();
        BridgeDtos.CatalogItem item = catalog.requireItem(sg, req.xmlFilename());
        return quote(item, catalog.quote(sg, item, req.configurations()));
    }

    @GetMapping("/api/dealer/cart")
    @Transactional(readOnly = true)
    public CartView cart() {
        return cartView(context.requireActive());
    }

    @PostMapping("/api/dealer/cart")
    @Transactional
    public CartView addToCart(@Valid @RequestBody ConfigureRequest req) {
        Savegame sg = context.requireActive();
        cart.add(sg, req.xmlFilename(), req.configurations());
        return cartView(sg);
    }

    @PutMapping("/api/dealer/cart/{id}")
    @Transactional
    public CartView changeCartPosition(@PathVariable long id, @RequestBody ChoiceRequest req) {
        Savegame sg = context.requireActive();
        cart.update(sg, id, req.configurations());
        return cartView(sg);
    }

    @DeleteMapping("/api/dealer/cart/{id}")
    @Transactional
    public CartView removeCartPosition(@PathVariable long id) {
        Savegame sg = context.requireActive();
        cart.remove(sg, id);
        return cartView(sg);
    }

    private CartView cartView(Savegame sg) {
        List<CartPositionView> positions = cart.positions(sg).stream().map(p -> new CartPositionView(p.item().getId(),
                p.item().getXmlFilename(), p.item().getName(), p.choice(), p.quote() == null ? null
                : quote(catalog.item(sg, p.item().getXmlFilename()).orElseThrow(), p.quote()))).toList();
        long sum = positions.stream().map(CartPositionView::quote).filter(Objects::nonNull)
                .mapToLong(QuoteView::listPrice).sum();
        return new CartView(positions, cart.maxPositions(), sum);
    }

    private static QuoteView quote(BridgeDtos.CatalogItem item, MachineCatalogService.Quote q) {
        MachineConfigurator.Result r = q.result();
        return new QuoteView(item.xmlFilename(), item.name(), r.configurations(), r.listPrice(), r.dailyUpkeep(),
                r.configurationSet(), r.setWith(), r.deviations(), r.extraDays(), q.delivery());
    }
}
