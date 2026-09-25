package de.farmpulse.rpsim.credit;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TonePreset;
import org.springframework.stereotype.Component;

/**
 * Technical concept "Ton-/Genre-Konfigurationsprofile": the tone preset selects an alternative formula profile
 * ONLY for the bank (stricter bank in the harsh mode). All other formulas are unaffected on purpose.
 */
@Component
public class CreditConfigResolver {

    private final RpsimProperties props;

    public CreditConfigResolver(RpsimProperties props) {
        this.props = props;
    }

    public RpsimProperties.Credit forSavegame(Savegame sg) {
        return forTone(sg.getTonePreset());
    }

    public RpsimProperties.Credit forTone(TonePreset tone) {
        return tone == TonePreset.HARSH ? props.getFormulas().getCreditHard() : props.getFormulas().getCredit();
    }
}
