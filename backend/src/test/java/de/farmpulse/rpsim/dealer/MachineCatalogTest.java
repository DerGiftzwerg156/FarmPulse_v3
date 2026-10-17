package de.farmpulse.rpsim.dealer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import de.farmpulse.rpsim.api.MachineDealerController;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.savegame.SavegameContext;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.time.GameTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap vehicle dealer RFH-K (owner decisions 2026-10-15): the catalog list (filters, sort, pages), the configurator
 * (list price = base + options as StoreItemUtil.getCosts, options set with another one, packages, delivery time of L1-L3
 * scaled to the month length, never before 9:00 of the next game day) and the cart (at most 10 positions).
 */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class MachineCatalogTest {

    static final String FENDT = "data/vehicles/fendt/vario700/vario700.xml";
    static final String CATROS = "data/vehicles/amazone/catros/catros.xml";

    static String opt(int index, String name, String extra) {
        return "{\"index\":" + index + ",\"name\":\"" + name + "\",\"price\":0,\"dailyUpkeep\":0,\"isDefault\":false,"
                + "\"isSelectable\":true,\"isYesNoOption\":false,\"dependent\":[]" + extra + "}";
    }

    static final String CATALOG = "{\"savegameId\":\"sg\",\"items\":["
            + "{\"xmlFilename\":\"" + FENDT + "\",\"name\":\"Fendt 700 Vario\",\"brand\":\"FENDT\",\"brandTitle\":\"Fendt\","
            + "\"categoryName\":\"TRACTORSL\",\"categoryTitle\":\"Große Traktoren\",\"price\":245000,\"dailyUpkeep\":120,"
            + "\"isMod\":false,\"motorized\":true,\"powerMin\":200,\"powerMax\":280,\"isBundle\":false,"
            + "\"multipleItemPurchase\":false,\"configurations\":["
            + "{\"name\":\"baseColor\",\"title\":\"Farbe\",\"options\":[" + opt(1, "Grün", ",\"isDefault\":true") + ","
            + opt(2, "Schwarz", ",\"price\":2500") + "," + opt(3, "Gold", ",\"price\":9000,\"isSelectable\":false") + "]},"
            + "{\"name\":\"motor\",\"options\":[" + opt(1, "720 Vario", ",\"isDefault\":true,\"power\":200") + ","
            + opt(2, "724 Vario", ",\"price\":18000,\"dailyUpkeep\":10,\"power\":240") + ","
            + opt(3, "728 Vario", ",\"price\":34000,\"dailyUpkeep\":20,\"power\":280") + "]},"
            + "{\"name\":\"wheel\",\"options\":[" + opt(1, "Trelleborg", ",\"isDefault\":true") + ","
            + opt(2, "Michelin Zwilling", ",\"price\":6500,\"dependent\":[{\"name\":\"wheelWeight\",\"index\":2}]") + "]},"
            + "{\"name\":\"wheelWeight\",\"options\":[" + opt(1, "ohne", "") + "," + opt(2, "Radgewichte", ",\"price\":800")
            + "]}],"
            + "\"defaultConfigurations\":{\"baseColor\":1,\"motor\":1,\"wheel\":1,\"wheelWeight\":1},"
            + "\"configurationSets\":[{\"name\":\"Profi-Paket\",\"isDefault\":false,\"configurations\":{\"motor\":2,\"wheel\":2}}]},"
            + "{\"xmlFilename\":\"" + CATROS + "\",\"name\":\"Amazone Catros\",\"brand\":\"AMAZONE\",\"brandTitle\":\"Amazone\","
            + "\"categoryName\":\"CULTIVATORS\",\"categoryTitle\":\"Grubber\",\"price\":32000,\"dailyUpkeep\":15,\"isMod\":false,"
            + "\"motorized\":false,\"isBundle\":false,\"multipleItemPurchase\":false,\"configurations\":[],"
            + "\"defaultConfigurations\":{},\"configurationSets\":[]},"
            + "{\"xmlFilename\":\"mods/FS25_Big/trailer.xml\",\"name\":\"Big Trailer\",\"brand\":\"LIZARD\","
            + "\"categoryName\":\"TRAILERS\",\"price\":41000,\"dailyUpkeep\":20,\"isMod\":true,\"isBundle\":false,"
            + "\"multipleItemPurchase\":false,\"configurations\":[],\"defaultConfigurations\":{},\"configurationSets\":[]}"
            + "]}";

    @Autowired Fixtures fx;
    @Autowired MachineDealerController controller;
    @Autowired SavegameContext context;
    @Autowired SavegameRepository savegames;
    @Autowired RpsimProperties props;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        sg.setStoreCatalogJson(CATALOG);
        calendar(30);
        context.setCurrentBridgeSavegameId(sg.getBridgeSavegameId());
    }

    private void calendar(int daysPerPeriod) {
        sg.setCalMonthIndex(0L);
        sg.setCalMonthStartGameTime(0L);
        sg.setCalDaysPerPeriod(daysPerPeriod);
        sg.setCalPeriod(1);
        savegames.save(sg);
    }

    private MachineDealerController.QuoteView configure(String xml, Map<String, Integer> choice) {
        return controller.configure(new MachineDealerController.ConfigureRequest(xml, choice));
    }

    @Test
    void withoutCatalogTheListIsNotAvailable() {
        sg.setStoreCatalogJson(null);
        savegames.save(sg);
        MachineDealerController.CatalogView v = controller.catalog(null, null, null, null, null, null, false, 0);
        assertThat(v.available()).isFalse();
        assertThat(v.page()).isNull();
    }

    @Test
    void theListIsFilteredSortedByCategoryAndNameAndPaged() {
        var page = controller.catalog(null, null, null, null, null, null, false, 0).page();
        // category display name, else the raw name: Große Traktoren, Grubber, TRAILERS
        assertThat(page.items()).extracting(MachineCatalogService.Entry::name)
                .containsExactly("Fendt 700 Vario", "Amazone Catros", "Big Trailer");
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.pageSize()).isEqualTo(50);
        assertThat(page.categories()).extracting(MachineCatalogService.FilterValue::name)
                .containsExactly("TRACTORSL", "CULTIVATORS", "TRAILERS");
        assertThat(page.brands()).extracting(MachineCatalogService.FilterValue::title).containsExactly("Amazone", "Fendt", null);
        var fendt = page.items().getFirst();
        assertThat(fendt.brandTitle()).isEqualTo("Fendt");
        assertThat(fendt.powerMin()).isEqualTo(200);
        assertThat(fendt.powerMax()).isEqualTo(280);
        assertThat(page.items().get(2).isMod()).isTrue();

        assertThat(controller.catalog("vario", null, null, null, null, null, false, 0).page().items()).hasSize(1);
        assertThat(controller.catalog(null, "cultivators", null, null, null, null, false, 0).page().items())
                .extracting(MachineCatalogService.Entry::name).containsExactly("Amazone Catros");
        assertThat(controller.catalog(null, null, "LIZARD", null, null, null, false, 0).page().total()).isEqualTo(1);
        assertThat(controller.catalog(null, null, null, 35000L, 245000L, "PRICE", true, 0).page().items())
                .extracting(MachineCatalogService.Entry::price).containsExactly(245000L, 41000L);
        assertThat(controller.catalog(null, null, null, null, null, "NAME", false, 0).page().items().getFirst().name())
                .isEqualTo("Amazone Catros");

        props.getFormulas().getMachineDealer().setCatalogPageSize(2);
        try {
            var second = controller.catalog(null, null, null, null, null, null, false, 1).page();
            assertThat(second.items()).extracting(MachineCatalogService.Entry::name).containsExactly("Big Trailer");
            assertThat(controller.catalog(null, null, null, null, null, null, false, 9).page().page()).isEqualTo(1);
        } finally {
            props.getFormulas().getMachineDealer().setCatalogPageSize(50);
        }
    }

    @Test
    void theListPriceIsTheBasePricePlusEveryOptionLikeTheShop() {
        var standard = configure(FENDT, Map.of());
        assertThat(standard.listPrice()).isEqualTo(245000);
        assertThat(standard.dailyUpkeep()).isEqualTo(120);
        assertThat(standard.configurations()).containsEntry("motor", 1).containsEntry("wheelWeight", 1);
        assertThat(standard.configurationSet()).isNull();
        assertThat(standard.deviations()).isEmpty();

        // other colour and other wheels: + 2,500 + 6,500 + the wheel weights the wheels set with them (800)
        var q = configure(FENDT, Map.of("baseColor", 2, "wheel", 2));
        assertThat(q.listPrice()).isEqualTo(245000 + 2500 + 6500 + 800);
        assertThat(q.setWith()).extracting(MachineConfigurator.TypeOption::type, MachineConfigurator.TypeOption::option)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("wheelWeight", "Radgewichte"));
        assertThat(q.deviations()).extracting(MachineConfigurator.TypeOption::type).containsExactly("baseColor", "wheel");
        assertThat(q.deviations().getFirst().title()).isEqualTo("Farbe");

        var profi = configure(FENDT, Map.of("motor", 2, "wheel", 2));
        assertThat(profi.configurationSet()).isEqualTo("Profi-Paket");
        assertThat(profi.dailyUpkeep()).isEqualTo(130);
    }

    @Test
    void unknownOrNotSelectableOptionsAreRefused() {
        assertThatThrownBy(() -> configure(FENDT, Map.of("baseColor", 3))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configure(FENDT, Map.of("baseColor", 7))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configure(FENDT, Map.of("roof", 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configure("data/none.xml", Map.of())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deliveryTimeFollowsTheGroupTheDeviationsAndTheMonthLength() {
        // 30 days per period: tractor 5-10 days; motor (3) and colour (1) deviate -> 9-14 days
        long now = sg.getCurrentGameTime() + 10 * GameTime.MS_PER_HOUR; // 10:00 of day 10
        sg.setCurrentGameTime(now);
        savegames.save(sg);
        var q = configure(FENDT, Map.of("motor", 3, "baseColor", 2));
        assertThat(q.extraDays()).isEqualTo(4);
        assertThat(q.delivery().fromGameTime()).isEqualTo(now);
        assertThat(q.delivery().minAt() - now).isEqualTo(9 * GameTime.MS_PER_DAY);
        assertThat(q.delivery().maxAt() - now).isEqualTo(14 * GameTime.MS_PER_DAY);
        // express halves it
        assertThat(q.delivery().expressMinAt() - now).isEqualTo(Math.round(4.5 * GameTime.MS_PER_DAY));
        // the package of the manufacturer counts as standard: Profi with motor 2 -> only the wheels deviate from it
        assertThat(configure(FENDT, Map.of("motor", 2, "wheel", 2)).extraDays()).isZero();
        assertThat(configure(FENDT, Map.of("motor", 2)).extraDays()).isEqualTo(2); // wheel vs Profi (2) < motor (3)
        // options set with another one add nothing: wheel 2 sets wheelWeight 2
        assertThat(configure(FENDT, Map.of("wheel", 2)).extraDays()).isEqualTo(2);
        // categories without a group: 3-7 days
        var trailer = configure("mods/FS25_Big/trailer.xml", Map.of());
        assertThat(trailer.delivery().minAt() - now).isEqualTo(3 * GameTime.MS_PER_DAY);

        // 3 days per period: 14 days -> 1.4 game days = 33.6 h; 9 days -> 21.6 h = 7:36 of day 11 -> 9:00
        calendar(3);
        var short3 = configure(FENDT, Map.of("motor", 3, "baseColor", 2));
        assertThat(short3.delivery().maxAt() - now).isEqualTo(Math.round(1.4 * GameTime.MS_PER_DAY));
        assertThat(short3.delivery().minAt()).isEqualTo(11 * GameTime.MS_PER_DAY + 9 * GameTime.MS_PER_HOUR);
        // 1 day per period: before 9:00 of the next day
        calendar(1);
        var short1 = configure(CATROS, Map.of()); // 2 days -> 1.6 h -> 9:00 of day 11
        assertThat(short1.delivery().minAt()).isEqualTo(11 * GameTime.MS_PER_DAY + 9 * GameTime.MS_PER_HOUR);
        assertThat(short1.delivery().earliestAt()).isEqualTo(short1.delivery().minAt());
    }

    @Test
    void aTimeBeforeNineMovesToNineOfTheSameDay() {
        RpsimProperties.AbsenceScaling scaling = new RpsimProperties.AbsenceScaling();
        long from = 10 * GameTime.MS_PER_DAY + 20 * GameTime.MS_PER_HOUR; // 20:00
        // 0.5 days at 30 days per period = 12 h -> 8:00 of day 11 -> 9:00
        assertThat(DeliveryTime.at(from, 0.5, 30, scaling, 9)).isEqualTo(11 * GameTime.MS_PER_DAY + 9 * GameTime.MS_PER_HOUR);
        // 1.25 days -> 2:00 of day 12 -> 9:00 of day 12
        assertThat(DeliveryTime.at(from, 1.25, 30, scaling, 9)).isEqualTo(12 * GameTime.MS_PER_DAY + 9 * GameTime.MS_PER_HOUR);
        // 1.6 days -> 10:24 of day 12 stays
        assertThat(DeliveryTime.at(from, 1.6, 30, scaling, 9))
                .isEqualTo(from + Math.round(1.6 * GameTime.MS_PER_DAY));
    }

    @Test
    void theCartHoldsAtMostTenPositionsAlsoTheSameMachineTwice() {
        var cart = controller.addToCart(new MachineDealerController.ConfigureRequest(FENDT, Map.of("baseColor", 2)));
        cart = controller.addToCart(new MachineDealerController.ConfigureRequest(FENDT, Map.of("motor", 3)));
        cart = controller.addToCart(new MachineDealerController.ConfigureRequest(CATROS, Map.of()));
        assertThat(cart.positions()).hasSize(3);
        assertThat(cart.maxPositions()).isEqualTo(10);
        assertThat(cart.listPriceSum()).isEqualTo(247500 + 279000 + 32000);
        var first = cart.positions().getFirst();
        assertThat(first.name()).isEqualTo("Fendt 700 Vario");
        assertThat(first.choice()).containsEntry("baseColor", 2);
        assertThat(first.quote().deviations()).extracting(MachineConfigurator.TypeOption::option).containsExactly("Schwarz");

        cart = controller.changeCartPosition(first.id(), new MachineDealerController.ChoiceRequest(Map.of()));
        assertThat(cart.positions().getFirst().quote().listPrice()).isEqualTo(245000);
        cart = controller.removeCartPosition(cart.positions().get(1).id());
        assertThat(cart.positions()).extracting(MachineDealerController.CartPositionView::name)
                .containsExactly("Fendt 700 Vario", "Amazone Catros");

        for (int i = 0; i < 8; i++) {
            controller.addToCart(new MachineDealerController.ConfigureRequest(CATROS, Map.of()));
        }
        assertThatThrownBy(() -> controller.addToCart(new MachineDealerController.ConfigureRequest(CATROS, Map.of())))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("höchstens 10 Positionen");
    }

    @Test
    void aPositionWhoseMachineLeftTheCatalogKeepsItsName() {
        controller.addToCart(new MachineDealerController.ConfigureRequest(CATROS, Map.of()));
        sg.setStoreCatalogJson(CATALOG.replace(CATROS, "data/vehicles/amazone/other.xml"));
        savegames.save(sg);
        var cart = controller.cart();
        assertThat(cart.positions().getFirst().name()).isEqualTo("Amazone Catros");
        assertThat(cart.positions().getFirst().quote()).isNull();
        assertThat(cart.listPriceSum()).isZero();
    }

    @Test
    void anInvalidChoiceIsNotPutIntoTheCart() {
        assertThatThrownBy(() -> controller.addToCart(new MachineDealerController.ConfigureRequest(FENDT,
                Map.of("baseColor", 3)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(controller.cart().positions()).isEmpty();
    }

    @Test
    void positionsOfAnotherSavegameAreNotFound() {
        long id = controller.addToCart(new MachineDealerController.ConfigureRequest(CATROS, Map.of()))
                .positions().getFirst().id();
        Savegame other = fx.savegame();
        other.setStoreCatalogJson(CATALOG);
        savegames.save(other);
        context.setCurrentBridgeSavegameId(other.getBridgeSavegameId());
        assertThatThrownBy(() -> controller.removeCartPosition(id)).isInstanceOf(NotFoundException.class);
    }
}
