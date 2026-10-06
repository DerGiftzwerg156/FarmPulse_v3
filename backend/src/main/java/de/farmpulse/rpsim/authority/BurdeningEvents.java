package de.farmpulse.rpsim.authority;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TonePreset;
import org.springframework.stereotype.Component;

/**
 * Roadmap V3.1 R31-B / R31-D (owner decisions 2026-10-05): the burdening events of sections B and D are switched per
 * savegame (default on, crop damage off). In the world mode IDYLLIC the animal disease and the diesel theft are off and
 * the chances, cuts, fines, trust losses and compensations of the other events are scaled by
 * {@code burdening-events.idyllic-factor}; REALISTIC and HARSH use the configured values.
 */
@Component
public class BurdeningEvents {

    /** The burdening events of section B. */
    public enum Burden { AREA_CHECK, FERTILIZER, DISEASE, SICK_LEAVE, NIGHT_WORK, CROP_DAMAGE, DIESEL_THEFT }

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
            case NIGHT_WORK -> sg.isBurdenNightWork(); // Roadmap V3.1 R31-D4
            case CROP_DAMAGE -> sg.isBurdenCropDamage(); // R31-D5 (off by default)
            case DIESEL_THEFT -> sg.isBurdenDieselTheft() && sg.getTonePreset() != TonePreset.IDYLLIC; // R31-D8
        };
    }

    /** Factor for chances, cuts and fines: idyllic-factor in the idyllic world mode, otherwise 1. */
    public double factor(Savegame sg) {
        return sg.getTonePreset() == TonePreset.IDYLLIC ? props.getFormulas().getBurdeningEvents().getIdyllicFactor() : 1;
    }
}
