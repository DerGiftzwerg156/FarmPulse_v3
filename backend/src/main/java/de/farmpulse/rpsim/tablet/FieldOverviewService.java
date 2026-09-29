package de.farmpulse.rpsim.tablet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.farmpulse.rpsim.api.Views.FieldOverviewView;
import de.farmpulse.rpsim.api.Views.FieldRowView;
import de.farmpulse.rpsim.api.Views.RotationPreviewView;
import de.farmpulse.rpsim.authority.AuthorityService;
import de.farmpulse.rpsim.bridge.BridgeDtos;
import de.farmpulse.rpsim.bridge.BridgeDtos.FarmFacts;
import de.farmpulse.rpsim.bridge.FactsService;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.FarmlandOwnership;
import de.farmpulse.rpsim.domain.FieldRecord;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.field.FieldService;
import de.farmpulse.rpsim.repository.FarmlandOwnershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hof-Tablet app "Flurkarte": one row per field the player farms (FieldService records) with what needs doing and its
 * crop rotation, plus the premium the authority would pay if the year ended now (same rule as
 * AuthorityService.rotation, without booking or counting anything).
 */
@Service
public class FieldOverviewService {

    private final FieldService fields;
    private final AuthorityService authority;
    private final FactsService facts;
    private final FarmlandOwnershipRepository ownership;
    private final RpsimProperties props;

    public FieldOverviewService(FieldService fields, AuthorityService authority, FactsService facts,
                                FarmlandOwnershipRepository ownership, RpsimProperties props) {
        this.fields = fields;
        this.authority = authority;
        this.facts = facts;
        this.ownership = ownership;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public FieldOverviewView overview(Savegame sg) {
        FarmFacts f = facts.latest(sg).orElse(null);
        Integer year = f == null || f.calendar() == null ? null : f.calendar().year();
        BridgeDtos.FieldRules rules = f == null ? null : f.fieldRules();
        Map<Integer, BridgeDtos.Field> byFarmland = new HashMap<>();
        if (f != null && f.fields() != null) {
            f.fields().stream().filter(x -> x != null && x.farmlandId() != null).forEach(x -> byFarmland.put(x.farmlandId(), x));
        }
        Map<Integer, FarmlandOwnership> owned = new HashMap<>();
        ownership.findBySavegameOrderByFarmlandIdAsc(sg).forEach(o -> owned.put(o.getFarmlandId(), o));
        var cfg = props.getFormulas().getAuthority();

        List<FieldRowView> rows = new ArrayList<>();
        double changed = 0;
        boolean cut = false;
        List<Integer> same = new ArrayList<>();
        for (FieldRecord r : fields.records(sg)) {
            BridgeDtos.Field field = byFarmland.get(r.getFarmlandId());
            Optional<String> now = year == null ? Optional.empty() : authority.crop(sg, r.getFarmlandId(), year);
            Optional<String> before = year == null ? Optional.empty() : authority.crop(sg, r.getFarmlandId(), year - 1);
            String rotation = now.isEmpty() || before.isEmpty() ? "UNKNOWN" : now.get().equals(before.get()) ? "SAME" : "CHANGED";
            if ("CHANGED".equals(rotation)) {
                changed += r.getHectares() == null ? 0 : r.getHectares();
            } else if ("SAME".equals(rotation)) {
                same.add(r.getFarmlandId());
                cut |= r.getRotationViolations() + 1 >= 2;
            }
            boolean bare = r.getPhase().bare();
            FarmlandOwnership o = owned.get(r.getFarmlandId());
            rows.add(new FieldRowView(r.getFarmlandId(), r.getFieldName(), r.getHectares(), r.getFruitType(), r.getPhase().name(),
                    o != null && o.isLeasedToPlayer(), Integer.valueOf(r.getFarmlandId()).equals(sg.getFamilyFieldId()),
                    r.getWeedsHighSince() != null, r.getStonesHighSince() != null,
                    bare && field != null && rules != null && Boolean.TRUE.equals(rules.limeRequired())
                            && field.limeLevel() != null && field.limeLevel() == 0,
                    bare && field != null && rules != null && Boolean.TRUE.equals(rules.plowingRequired())
                            && field.plowLevel() != null && field.plowLevel() == 0,
                    before.orElse(null), now.orElse(null), rotation, r.getRotationViolations()));
        }
        RotationPreviewView preview = !cfg.isEnabled() || year == null ? null
                : new RotationPreviewView(Math.round(changed * 10) / 10.0,
                Math.round(changed * cfg.getRotationPremiumPerHa() * (cut ? 1 - cfg.getRotationCutShare() : 1)), cut, same,
                cfg.getRotationPremiumPerHa());
        return new FieldOverviewView(year, f != null && f.fields() != null, rows, preview);
    }
}
