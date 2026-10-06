package de.farmpulse.rpsim.tablet;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.farmpulse.rpsim.api.Views.FieldRowView;
import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.domain.AssetType;
import de.farmpulse.rpsim.domain.CaseKind;
import de.farmpulse.rpsim.domain.CaseStatus;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.NegotiationKind;
import de.farmpulse.rpsim.domain.NegotiationStatus;
import de.farmpulse.rpsim.domain.OwnerType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.ServiceCase;
import de.farmpulse.rpsim.repository.FarmlandOwnershipRepository;
import de.farmpulse.rpsim.repository.NegotiationRepository;
import de.farmpulse.rpsim.repository.ServiceCaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roadmap V3.1 R31-K1 (owner decisions 2026-10-06): the field outlines of {@code market_context.fieldShapes} for the
 * map of the Flurkarte, each with what the map shows on it - owner (own, leased, leased out, neighbour with name,
 * free), crop and phase of a field the player farms, and the symbols: an open or running order (cases with a farmland:
 * neighbour mission, contractor, mission referral, compensation and crop damage claims), an open auction of the
 * farmland and the hints of "Meine Felder" (weeds, stones, lime, plow) plus harvestable. Empty without outlines (older
 * mod).
 */
@Service
public class FieldMapService {

    public static final String OWN = "OWN";
    public static final String NEIGHBOR = "NEIGHBOR";
    public static final String FREE = "FREE";

    static final Set<CaseKind> ORDER_KINDS = EnumSet.of(CaseKind.NEIGHBOR_MISSION, CaseKind.CONTRACTOR_WORK,
            CaseKind.MISSION_REFERRAL, CaseKind.COMPENSATION_CLAIM, CaseKind.CROP_DAMAGE_CLAIM);
    static final Set<CaseStatus> OPEN = EnumSet.of(CaseStatus.AWAITING_PLAYER, CaseStatus.IN_PROGRESS);

    /** {@code kind}: OWN (player farm, also leased), NEIGHBOR (owner is a character) or FREE; hints as codes. */
    public record MapField(int farmlandId, String name, List<BridgeDtos.ShapePoint> points, String kind, String ownerName,
                           boolean leased, boolean leasedOut, String fruitType, String phase, List<String> orders,
                           boolean auction, List<String> hints) {
    }

    public record FieldMap(Double mapSize, List<MapField> fields) {
    }

    private final FactsService facts;
    private final FarmlandOwnershipRepository ownership;
    private final ServiceCaseRepository cases;
    private final NegotiationRepository negotiations;
    private final FieldOverviewService overview;

    public FieldMapService(FactsService facts, FarmlandOwnershipRepository ownership, ServiceCaseRepository cases,
                           NegotiationRepository negotiations, FieldOverviewService overview) {
        this.facts = facts;
        this.ownership = ownership;
        this.cases = cases;
        this.negotiations = negotiations;
        this.overview = overview;
    }

    @Transactional(readOnly = true)
    public FieldMap map(Savegame sg) {
        BridgeDtos.FieldShapes shapes = facts.marketContext(sg).map(BridgeDtos.MarketContext::fieldShapes).orElse(null);
        if (shapes == null || shapes.fields() == null) {
            return new FieldMap(null, List.of());
        }
        Map<Integer, FarmlandOwnership> owners = new HashMap<>();
        ownership.findBySavegameOrderByFarmlandIdAsc(sg).forEach(o -> owners.put(o.getFarmlandId(), o));
        Map<Integer, FieldRowView> rows = new HashMap<>();
        overview.overview(sg).fields().forEach(r -> rows.put(r.farmlandId(), r));
        Map<Integer, Set<String>> orders = new HashMap<>();
        for (ServiceCase c : cases.findBySavegameAndKindInOrderByIdDesc(sg, ORDER_KINDS)) {
            if (c.getFarmlandId() != null && OPEN.contains(c.getStatus())) {
                orders.computeIfAbsent(c.getFarmlandId(), k -> new LinkedHashSet<>()).add(c.getKind().name());
            }
        }
        Set<Integer> auctions = new java.util.HashSet<>();
        negotiations.findBySavegameAndStatus(sg, NegotiationStatus.OPEN).stream()
                .filter(n -> n.getKind() == NegotiationKind.AUCTION && n.getAssetType() == AssetType.FARMLAND)
                .forEach(n -> {
                    try {
                        auctions.add(Integer.valueOf(n.getAssetId()));
                    } catch (NumberFormatException e) {
                        // not a farmland id
                    }
                });
        List<MapField> out = new ArrayList<>();
        for (BridgeDtos.FieldShape s : shapes.fields()) {
            if (s.farmlandId() == null || s.points() == null) {
                continue;
            }
            int id = s.farmlandId();
            FarmlandOwnership o = owners.get(id);
            boolean leased = o != null && o.isLeasedToPlayer();
            String kind = o == null ? FREE : o.getOwnerType() == OwnerType.PLAYER || leased ? OWN
                    : o.getOwnerType() == OwnerType.CHARACTER && o.getOwnerCharacter() != null ? NEIGHBOR : FREE;
            FieldRowView row = OWN.equals(kind) ? rows.get(id) : null;
            out.add(new MapField(id, s.name(), s.points(), kind,
                    NEIGHBOR.equals(kind) ? o.getOwnerCharacter().getName() : null, leased,
                    o != null && o.isLeasedFromPlayer(), row == null ? null : row.fruitType(),
                    row == null ? null : row.phase(), List.copyOf(orders.getOrDefault(id, Set.of())),
                    auctions.contains(id), row == null ? List.of() : hints(row)));
        }
        return new FieldMap(shapes.mapSize(), out);
    }

    static List<String> hints(FieldRowView r) {
        List<String> h = new ArrayList<>();
        if ("HARVESTABLE".equals(r.phase())) {
            h.add("HARVESTABLE");
        }
        if (r.weedsHigh()) {
            h.add("WEEDS");
        }
        if (r.stonesHigh()) {
            h.add("STONES");
        }
        if (r.needsLime()) {
            h.add("LIME");
        }
        if (r.needsPlow()) {
            h.add("PLOW");
        }
        return h;
    }
}
