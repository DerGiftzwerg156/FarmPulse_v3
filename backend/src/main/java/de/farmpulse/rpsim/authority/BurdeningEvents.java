package de.farmpulse.rpsim.authority;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TonePreset;
import org.springframework.stereotype.Component;

/**
 * Roadmap V3.1 R31-B (owner decision 2026-10-05): the burdening events of section B are switched per savegame (default
 * on). In the world mode IDYLLIC the animal disease is off and the chances, cuts and fines of the other events are
 * scaled by {@code burdening-events.idyllic-factor}; REALISTIC and HARSH use the configured values.
 */
@Component
public class BurdeningEvents {

    /** The burdening events of section B. */
    public enum Burden { AREA_CHECK, FERTILIZER, DISEASE, SICK_LEAVE }

    private final RpsimProperties props;

    public BurdeningEvents(RpsimProperties props) {
        this.props = props;
    }

    /** True when the event may happen in the savegame (switch on; the disease never in the idyllic world mode). */
    public boolean on(Savegame sg, Burden b) {
        return switch (b) {
            case AREA_CHECK -> sg.isBurdenAreaCheck();
            case FERTILIZER -> sg.isBurdenFertilizer();
            case DISEASE -> sg.isBurdenDisease() && sg.getTonePreset() != TonePreset.IDYLLIC;
            case SICK_LEAVE -> sg.isBurdenSickLeave();
        };
    }

    /** Factor for chances, cuts and fines: idyllic-factor in the idyllic world mode, otherwise 1. */
    public double factor(Savegame sg) {
        return sg.getTonePreset() == TonePreset.IDYLLIC ? props.getFormulas().getBurdeningEvents().getIdyllicFactor() : 1;
    }
}
