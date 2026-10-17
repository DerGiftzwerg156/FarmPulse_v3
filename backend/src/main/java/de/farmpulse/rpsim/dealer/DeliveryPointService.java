package de.farmpulse.rpsim.dealer;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeEvents;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.domain.DealerDeliveryPoint;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.DealerDeliveryPointRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap vehicle dealer RFH-O1 (owner decisions 2026-10-15): the delivery points set in the game by key. Every new
 * {@code seq} of farm_facts.deliveryPoints is taken over once; name, default and deletion live only here. A point is
 * valid exactly while its farmland belongs to the farm (own or leased: a leased farmland belongs to the player in the
 * game during the lease) - it becomes valid again when the farmland comes back. The shop place of the game is always
 * there; it is the default while no point is chosen, and again for good when the default point is deleted or becomes
 * invalid.
 */
@Service
public class DeliveryPointService {

    public static final int MAX_NAME_LENGTH = 40;

    /** A point with its validity; {@code isDefault} = the default delivery point. */
    public record Point(DealerDeliveryPoint point, boolean valid, boolean isDefault) {
    }

    /** {@code supported} = the mod exports delivery points (key registered). */
    public record Places(boolean supported, boolean shopIsDefault, List<Point> points) {
    }

    private final DealerDeliveryPointRepository points;
    private final SavegameRepository savegames;
    private final FactsService facts;

    public DeliveryPointService(DealerDeliveryPointRepository points, SavegameRepository savegames, FactsService facts) {
        this.points = points;
        this.savegames = savegames;
        this.facts = facts;
    }

    @EventListener
    @Transactional
    public void onFacts(BridgeEvents.FactsIngested e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        facts.latest(sg).ifPresent(f -> sync(sg, f));
    }

    /** Takes over the new points of the export and drops an invalid default (back to the shop place). */
    @Transactional
    public void sync(Savegame sg, BridgeDtos.FarmFacts f) {
        for (BridgeDtos.DeliveryPoint p : f.deliveryPoints() == null ? List.<BridgeDtos.DeliveryPoint>of()
                : f.deliveryPoints()) {
            if (p == null || p.seq() == null || p.x() == null || p.y() == null || p.z() == null || p.ry() == null
                    || points.existsBySavegameAndSeq(sg, p.seq())) {
                continue;
            }
            DealerDeliveryPoint d = new DealerDeliveryPoint();
            d.setSavegame(sg);
            d.setSeq(p.seq());
            d.setX(p.x());
            d.setY(p.y());
            d.setZ(p.z());
            d.setRy(p.ry());
            d.setFarmlandId(p.farmlandId());
            d.setSetDay(p.day());
            d.setSetDayTimeMs(p.dayTimeMs());
            points.save(d);
        }
        Long def = sg.getDealerDefaultPointId();
        if (def != null) {
            Set<Integer> owned = owned(f);
            points.findById(def).filter(p -> !p.isDeleted() && valid(p, owned)).ifPresentOrElse(p -> { },
                    () -> sg.setDealerDefaultPointId(null));
        }
    }

    /** The farmlands of the farm in the latest export. */
    static Set<Integer> owned(BridgeDtos.FarmFacts f) {
        if (f == null || f.assets() == null || f.assets().farmland() == null) {
            return Set.of();
        }
        return f.assets().farmland().stream().map(BridgeDtos.OwnedFarmland::farmlandId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    static boolean valid(DealerDeliveryPoint p, Set<Integer> owned) {
        return p.getFarmlandId() != null && owned.contains(p.getFarmlandId());
    }

    @Transactional(readOnly = true)
    public Places places(Savegame sg) {
        BridgeDtos.FarmFacts f = facts.latest(sg).orElse(null);
        Set<Integer> owned = owned(f);
        Long def = sg.getDealerDefaultPointId();
        List<Point> list = points.findBySavegameOrderBySeqAsc(sg).stream().filter(p -> !p.isDeleted())
                .map(p -> new Point(p, valid(p, owned), p.getId().equals(def))).toList();
        return new Places(f != null && f.deliveryPoints() != null, list.stream().noneMatch(Point::isDefault), list);
    }

    /** The point a delivery goes to: the default when valid, else empty (= shop place). */
    @Transactional(readOnly = true)
    public Optional<DealerDeliveryPoint> defaultPoint(Savegame sg) {
        return places(sg).points().stream().filter(p -> p.isDefault() && p.valid()).map(Point::point).findFirst();
    }

    @Transactional
    public void rename(Savegame sg, long id, String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must have 1-" + MAX_NAME_LENGTH + " characters");
        }
        own(sg, id).setName(n);
    }

    /** "Als Standard" for a valid point. */
    @Transactional
    public void makeDefault(Savegame sg, long id) {
        DealerDeliveryPoint p = own(sg, id);
        if (!valid(p, owned(facts.latest(sg).orElse(null)))) {
            throw new IllegalArgumentException("an invalid delivery point cannot be the default");
        }
        sg.setDealerDefaultPointId(p.getId());
    }

    /** "Als Standard" for the shop place of the game. */
    @Transactional
    public void makeShopDefault(Savegame sg) {
        sg.setDealerDefaultPointId(null);
    }

    @Transactional
    public void delete(Savegame sg, long id) {
        DealerDeliveryPoint p = own(sg, id);
        p.setDeleted(true);
        if (p.getId().equals(sg.getDealerDefaultPointId())) {
            sg.setDealerDefaultPointId(null);
        }
    }

    private DealerDeliveryPoint own(Savegame sg, long id) {
        return points.findById(id).filter(p -> p.getSavegame().getId().equals(sg.getId()) && !p.isDeleted())
                .orElseThrow(() -> new NotFoundException("Lieferort nicht gefunden: " + id));
    }
}
