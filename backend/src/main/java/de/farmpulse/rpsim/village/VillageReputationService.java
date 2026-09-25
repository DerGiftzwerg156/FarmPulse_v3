package de.farmpulse.rpsim.village;

import static de.farmpulse.rpsim.common.Formulas.clamp;

import java.util.List;

import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.PublicActionEvent;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.PublicActionEventRepository;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.stereotype.Service;

/**
 * Village reputation - pure read calculation, no scheduler (technical concept "Dorf-Ansehen"):
 * <pre>
 * trustAverage             = mean of all ACTIVE Character.trustScore
 * publicActionSum          = Σ PublicActionEvent.delta with decay over game time
 * villageReputationScore   = clamp(0.6·trustAverage + 0.4·publicActionSum, −100, 100)
 * baseTrustForNewCharacter = clamp(villageReputationScore · 0.2, −15, +15)
 * ≥ +25 "gut angesehen" | −25..+25 "neutral" | ≤ −25 "umstritten"
 * </pre>
 * The API only ever exposes the tier, never the raw score.
 */
@Service
public class VillageReputationService {

    public enum Tier { GOOD, NEUTRAL, CONTROVERSIAL }

    private final CharacterRepository characters;
    private final PublicActionEventRepository publicActions;
    private final TrustScoreService trust;
    private final RpsimProperties props;

    public VillageReputationService(CharacterRepository characters, PublicActionEventRepository publicActions,
                                    TrustScoreService trust, RpsimProperties props) {
        this.characters = characters;
        this.publicActions = publicActions;
        this.trust = trust;
        this.props = props;
    }

    private RpsimProperties.Reputation cfg() {
        return props.getFormulas().getReputation();
    }

    /** Villagers whose trust counts (applicants and substitutes are not part of the village). */
    List<Character> villagers(Savegame sg) {
        return characters.findBySavegameAndStatus(sg, CharacterStatus.ACTIVE).stream()
                .filter(c -> c.getCategory() != CharacterCategory.APPLICANT && c.getCategory() != CharacterCategory.SUBSTITUTE)
                .toList();
    }

    public double trustAverage(Savegame sg) {
        return villagers(sg).stream().mapToDouble(trust::getCurrentTrust).average().orElse(0);
    }

    /** Σ delta · 0.5^(age / halfLife). */
    public double publicActionSum(Savegame sg) {
        long now = sg.getCurrentGameTime();
        double sum = 0;
        for (PublicActionEvent e : publicActions.findBySavegameOrderByGameTimeAsc(sg)) {
            double age = Math.max(0, GameTime.toDays(now - e.getGameTime()));
            sum += e.getDelta() * Math.pow(0.5, age / cfg().getPublicHalfLifeDays());
        }
        return sum;
    }

    /** Internal raw score (never exposed through the API). */
    double score(Savegame sg) {
        return score(trustAverage(sg), publicActionSum(sg), cfg());
    }

    static double score(double trustAverage, double publicActionSum, RpsimProperties.Reputation cfg) {
        return clamp(cfg.getTrustWeight() * trustAverage + cfg.getPublicWeight() * publicActionSum, -100, 100);
    }

    public Tier tier(Savegame sg) {
        return tier(score(sg), cfg());
    }

    static Tier tier(double score, RpsimProperties.Reputation cfg) {
        if (score >= cfg.getGoodThreshold()) {
            return Tier.GOOD;
        }
        if (score <= cfg.getControversialThreshold()) {
            return Tier.CONTROVERSIAL;
        }
        return Tier.NEUTRAL;
    }

    /** Start trust of new characters (onboarding and rotation arrivals): the reputation precedes the player. */
    public double baseTrustForNewCharacter(Savegame sg) {
        return baseTrust(score(sg), cfg());
    }

    static double baseTrust(double score, RpsimProperties.Reputation cfg) {
        return clamp(score * cfg.getNewCharacterFactor(), -cfg.getNewCharacterCap(), cfg.getNewCharacterCap());
    }
}
