package de.farmpulse.rpsim.contract;

import java.util.Optional;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.domain.FieldPhase;
import de.farmpulse.rpsim.domain.FieldRecord;
import de.farmpulse.rpsim.domain.NpcFieldRecord;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.field.FieldService;
import de.farmpulse.rpsim.repository.NpcFieldRecordRepository;
import org.springframework.stereotype.Component;

/**
 * Roadmap V3 R3-L1 fallback (🟡 field state at the change of hands, owner decision): a field is leased out only while
 * it is empty or harvested; it comes back only when the neighbour-field export shows it empty or harvested.
 */
@Component
public class LeaseOutPhase {

    private final FieldService fields;
    private final NpcFieldRecordRepository npcFields;

    public LeaseOutPhase(FieldService fields, NpcFieldRecordRepository npcFields) {
        this.fields = fields;
        this.npcFields = npcFields;
    }

    /** Phase of an own field (R2-C1); empty without the field export. */
    public Optional<FieldPhase> ownPhase(Savegame sg, int farmlandId) {
        return fields.records(sg).stream().filter(r -> r.getFarmlandId() == farmlandId).findFirst().map(FieldRecord::getPhase);
    }

    /** Phase of the field while the base game farms it (R3-H1 npcFields); empty without export data. */
    public Optional<FieldPhase> npcPhase(Savegame sg, int farmlandId) {
        return npcFields.findBySavegameOrderByFarmlandIdAsc(sg).stream().filter(r -> r.getFarmlandId() == farmlandId)
                .findFirst().map(NpcFieldRecord::getPhase);
    }

    /** Throws unless the own field is empty or harvested (without the field export there is nothing to check). */
    public void requireBare(Savegame sg, int farmlandId) {
        Optional<FieldPhase> p = ownPhase(sg, farmlandId);
        if (p.isPresent() && !p.get().bare()) {
            throw new BusinessRuleException("FIELD_NOT_BARE",
                    "Verpachten geht nur, wenn Feld " + farmlandId + " leer oder abgeerntet ist.");
        }
    }

    /** The return may happen: no export data, or the base game left the field empty or harvested. */
    public boolean returnable(Savegame sg, int farmlandId) {
        return npcPhase(sg, farmlandId).map(FieldPhase::bare).orElse(true);
    }
}
