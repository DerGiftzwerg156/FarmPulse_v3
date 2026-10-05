package de.farmpulse.rpsim.authority;

import java.util.Optional;

import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.AnimalDisease;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.AnimalDiseaseRepository;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.stereotype.Component;

/**
 * Roadmap V3.1 R31-B4: read side of the animal diseases for the trade. While a restricted zone is active the livestock
 * trade with the neighbours (A3) and the offers of the livestock trader are blocked for its animal types; after the
 * lifting the prices of the neighbour trade start at price-factor-after and return to 1 linearly within
 * price-recovery-months. The game's own animal prices are not changed.
 */
@Component
public class DiseaseZones {

    private final AnimalDiseaseRepository diseases;
    private final RpsimProperties props;
    private final GameTime gameTime;

    public DiseaseZones(AnimalDiseaseRepository diseases, RpsimProperties props, GameTime gameTime) {
        this.diseases = diseases;
        this.props = props;
        this.gameTime = gameTime;
    }

    private RpsimProperties.AnimalDisease cfg() {
        return props.getFormulas().getAnimalDisease();
    }

    /** The active disease covering the animal type, if any. */
    public Optional<AnimalDisease> active(Savegame sg, String animalType) {
        return diseases.findBySavegameOrderByIdDesc(sg).stream()
                .filter(d -> AnimalDisease.ACTIVE.equals(d.getStatus()) && d.types().contains(animalType)).findFirst();
    }

    public boolean blocked(Savegame sg, String animalType) {
        return active(sg, animalType).isPresent();
    }

    /** Refuses a trade of the animal type inside a restricted zone. */
    public void requireOpen(Savegame sg, String animalType) {
        if (blocked(sg, animalType)) {
            throw new BusinessRuleException("ANIMAL_DISEASE", "Sperrzone wegen einer Tierseuche: Der Handel mit dieser "
                    + "Tierart ist derzeit verboten.");
        }
    }

    /** Price factor of the neighbour trade after a lifting (1 outside the recovery time). */
    public double priceFactor(Savegame sg, String animalType) {
        long now = sg.getCurrentGameTime();
        double factor = 1;
        for (AnimalDisease d : diseases.findBySavegameOrderByIdDesc(sg)) {
            if (!AnimalDisease.LIFTED.equals(d.getStatus()) || d.getLiftedGameTime() == null || !d.types().contains(animalType)) {
                continue;
            }
            factor = Math.min(factor, factor(cfg(), (now - d.getLiftedGameTime()) / (double) gameTime.msPerMonth(sg)));
        }
        return factor;
    }

    /** price-factor-after at the lifting, linearly back to 1 after price-recovery-months. */
    public static double factor(RpsimProperties.AnimalDisease cfg, double monthsSinceLifting) {
        if (monthsSinceLifting < 0 || cfg.getPriceRecoveryMonths() <= 0 || monthsSinceLifting >= cfg.getPriceRecoveryMonths()) {
            return 1;
        }
        return cfg.getPriceFactorAfter() + (1 - cfg.getPriceFactorAfter()) * monthsSinceLifting / cfg.getPriceRecoveryMonths();
    }
}
