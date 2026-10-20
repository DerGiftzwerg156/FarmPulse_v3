package de.farmpulse.rpsim.dealer;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.DealerCartItem;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.DealerCartItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Roadmap vehicle dealer RFH-K3 (owner decisions 2026-10-15): the cart of the dealer, stored per savegame in the
 * backend, at most {@code machine-dealer.max-positions} positions; the same machine may stand several times with other
 * options. Asking for the offer ("zusammen" / "einzeln") comes with area B.
 */
@Service
public class DealerCartService {

    /** A position with the machine put together; {@code quote} = null when the machine left the catalog. */
    public record Position(DealerCartItem item, Map<String, Integer> choice, MachineCatalogService.Quote quote) {
    }

    private static final TypeReference<TreeMap<String, Integer>> CHOICE = new TypeReference<>() {
    };

    private final DealerCartItemRepository items;
    private final MachineCatalogService catalog;
    private final RpsimProperties props;
    private final JsonMapper json;

    public DealerCartService(DealerCartItemRepository items, MachineCatalogService catalog, RpsimProperties props,
                             JsonMapper json) {
        this.items = items;
        this.catalog = catalog;
        this.props = props;
        this.json = json;
    }

    public int maxPositions() {
        return props.getFormulas().getMachineDealer().getMaxPositions();
    }

    @Transactional(readOnly = true)
    public List<Position> positions(Savegame sg) {
        return items.findBySavegameOrderByIdAsc(sg).stream().map(i -> position(sg, i)).toList();
    }

    /** Puts a machine with the player's choice into the cart; refused when the cart is full or the choice invalid. */
    @Transactional
    public Position add(Savegame sg, String xmlFilename, Map<String, Integer> choice) {
        int max = maxPositions();
        if (items.countBySavegame(sg) >= max) {
            throw new BusinessRuleException("DEALER_CART_FULL",
                    "Der Warenkorb ist voll (höchstens " + max + " Positionen).");
        }
        BridgeDtos.CatalogItem item = catalog.requireItem(sg, xmlFilename);
        catalog.quote(sg, item, choice);
        DealerCartItem i = new DealerCartItem();
        i.setSavegame(sg);
        i.setXmlFilename(item.xmlFilename());
        i.setName(name(item));
        i.setConfigurationsJson(json.writeValueAsString(new TreeMap<>(choice == null ? Map.of() : choice)));
        i.setCreatedGameTime(sg.getCurrentGameTime());
        return position(sg, items.save(i));
    }

    /** "Ändern": a new choice for a position. */
    @Transactional
    public Position update(Savegame sg, long id, Map<String, Integer> choice) {
        DealerCartItem i = own(sg, id);
        BridgeDtos.CatalogItem item = catalog.requireItem(sg, i.getXmlFilename());
        catalog.quote(sg, item, choice);
        i.setConfigurationsJson(json.writeValueAsString(new TreeMap<>(choice == null ? Map.of() : choice)));
        return position(sg, i);
    }

    /** "Entfernen". */
    @Transactional
    public void remove(Savegame sg, long id) {
        items.delete(own(sg, id));
    }

    private DealerCartItem own(Savegame sg, long id) {
        return items.findById(id).filter(i -> i.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("Position nicht im Warenkorb: " + id));
    }

    private Position position(Savegame sg, DealerCartItem i) {
        Map<String, Integer> choice = json.readValue(i.getConfigurationsJson(), CHOICE);
        MachineCatalogService.Quote quote = catalog.item(sg, i.getXmlFilename()).map(item -> {
            try {
                return catalog.quote(sg, item, choice);
            } catch (IllegalArgumentException e) {
                return null; // the options of the machine changed (mod update): the position has to be changed
            }
        }).orElse(null);
        return new Position(i, choice, quote);
    }

    private static String name(BridgeDtos.CatalogItem item) {
        String n = item.name() == null ? item.xmlFilename() : item.name();
        return n.length() > 200 ? n.substring(0, 200) : n;
    }
}
