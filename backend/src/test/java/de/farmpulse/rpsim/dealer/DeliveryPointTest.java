package de.farmpulse.rpsim.dealer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.farmpulse.rpsim.api.MachineDealerController;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.savegame.SavegameContext;
import de.farmpulse.rpsim.support.Fixtures;
import de.farmpulse.rpsim.support.SeededRandomConfig;
import de.farmpulse.rpsim.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap vehicle dealer RFH-O1 (owner decisions 2026-10-15): the delivery points of the key are taken over once per
 * seq; rename (1-40 characters), default, delete; valid while the farmland belongs to the farm (again valid when it
 * comes back); the shop place is the default while nothing else is chosen and again for good when the default point is
 * deleted or becomes invalid.
 */
@SpringBootTest
@Transactional
@Import({Fixtures.class, SeededRandomConfig.class})
class DeliveryPointTest {

    @Autowired Fixtures fx;
    @Autowired MachineDealerController controller;
    @Autowired SavegameContext context;
    @Autowired DeliveryPointService service;
    @Autowired FactsService facts;

    Savegame sg;

    @BeforeEach
    void setUp() {
        sg = fx.savegame();
        context.setCurrentBridgeSavegameId(sg.getBridgeSavegameId());
    }

    /** An export with the farmlands {@code owned} and the delivery points {@code points} (JSON array or null). */
    private void export(String owned, String points) {
        String json = TestData.farmFacts(sg.getBridgeSavegameId(), sg.getCurrentGameTime(), 100000)
                .replace("[{ \"farmlandId\": 12, \"hectares\": 4.5, \"price\": 54000 }]", owned);
        if (points != null) {
            json = TestData.withFields(json, "\"deliveryPoints\": " + points);
        }
        fx.snapshot(sg, sg.getCurrentGameTime(), 100000, json);
        service.sync(sg, facts.latest(sg).orElseThrow());
    }

    static final String OWN_12_AND_3 = "[{ \"farmlandId\": 12, \"hectares\": 4.5, \"price\": 54000 },"
            + " { \"farmlandId\": 3, \"hectares\": 2, \"price\": 20000 }]";
    static final String OWN_12 = "[{ \"farmlandId\": 12, \"hectares\": 4.5, \"price\": 54000 }]";
    static final String TWO_POINTS = "[{ \"seq\": 1, \"x\": -412.52, \"y\": 98.2, \"z\": 233.75, \"ry\": 1.571,"
            + " \"farmlandId\": 12, \"day\": 3, \"dayTimeMs\": 36000000 },"
            + " { \"seq\": 2, \"x\": -300, \"y\": 99, \"z\": 120, \"ry\": 0, \"farmlandId\": 3, \"day\": 4, \"dayTimeMs\": 0 }]";

    @Test
    void anOlderModHasNoPointsAndTheShopPlaceIsTheDefault() {
        export(OWN_12, null);
        var v = controller.deliveryPoints();
        assertThat(v.supported()).isFalse();
        assertThat(v.shopIsDefault()).isTrue();
        assertThat(v.points()).isEmpty();
    }

    @Test
    void newPointsAreTakenOverOnceAndCanBeNamed() {
        export(OWN_12_AND_3, TWO_POINTS);
        export(OWN_12_AND_3, TWO_POINTS); // the same seq again: no duplicate
        var v = controller.deliveryPoints();
        assertThat(v.supported()).isTrue();
        assertThat(v.points()).extracting(MachineDealerController.DeliveryPointView::seq).containsExactly(1, 2);
        var first = v.points().getFirst();
        assertThat(first.name()).isNull();
        assertThat(first.farmlandId()).isEqualTo(12);
        assertThat(first.day()).isEqualTo(3);
        assertThat(first.dayTimeMs()).isEqualTo(36000000L);
        assertThat(first.valid()).isTrue();

        v = controller.renameDeliveryPoint(first.id(), new MachineDealerController.RenameRequest("  Maschinenhalle "));
        assertThat(v.points().getFirst().name()).isEqualTo("Maschinenhalle");
        assertThatThrownBy(() -> controller.renameDeliveryPoint(first.id(),
                new MachineDealerController.RenameRequest("x".repeat(41)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDefaultFallsBackToTheShopPlaceForGoodWhenItBecomesInvalid() {
        export(OWN_12_AND_3, TWO_POINTS);
        long hall = controller.deliveryPoints().points().get(1).id(); // on farmland 3
        var v = controller.defaultDeliveryPoint(hall);
        assertThat(v.shopIsDefault()).isFalse();
        assertThat(v.points().get(1).isDefault()).isTrue();
        assertThat(service.defaultPoint(sg)).get().extracting(p -> p.getSeq()).isEqualTo(2);

        // farmland 3 sold: invalid, the shop place is the default
        export(OWN_12, TWO_POINTS);
        v = controller.deliveryPoints();
        assertThat(v.points().get(1).valid()).isFalse();
        assertThat(v.shopIsDefault()).isTrue();
        assertThat(service.defaultPoint(sg)).isEmpty();
        assertThatThrownBy(() -> controller.defaultDeliveryPoint(hall)).isInstanceOf(IllegalArgumentException.class);

        // bought back: valid again, but the shop place stays the default (owner decision 2026-10-15)
        export(OWN_12_AND_3, TWO_POINTS);
        v = controller.deliveryPoints();
        assertThat(v.points().get(1).valid()).isTrue();
        assertThat(v.shopIsDefault()).isTrue();
    }

    @Test
    void deletingTheDefaultMakesTheShopPlaceTheDefaultAndTheModCannotBringThePointBack() {
        export(OWN_12_AND_3, TWO_POINTS);
        long first = controller.deliveryPoints().points().getFirst().id();
        controller.defaultDeliveryPoint(first);
        var v = controller.deleteDeliveryPoint(first);
        assertThat(v.shopIsDefault()).isTrue();
        assertThat(v.points()).extracting(MachineDealerController.DeliveryPointView::seq).containsExactly(2);
        export(OWN_12_AND_3, TWO_POINTS);
        assertThat(controller.deliveryPoints().points()).hasSize(1);
        assertThatThrownBy(() -> controller.renameDeliveryPoint(first, new MachineDealerController.RenameRequest("x")))
                .isInstanceOf(NotFoundException.class);
        // the shop place can be chosen again at any time
        long second = controller.deliveryPoints().points().getFirst().id();
        controller.defaultDeliveryPoint(second);
        assertThat(controller.defaultShopPlace().shopIsDefault()).isTrue();
    }

    @Test
    void pointsOfAnotherSavegameAreNotFound() {
        export(OWN_12_AND_3, TWO_POINTS);
        long id = controller.deliveryPoints().points().getFirst().id();
        Savegame other = fx.savegame();
        context.setCurrentBridgeSavegameId(other.getBridgeSavegameId());
        assertThatThrownBy(() -> controller.deleteDeliveryPoint(id)).isInstanceOf(NotFoundException.class);
    }
}
