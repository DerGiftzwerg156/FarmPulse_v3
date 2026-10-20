package de.farmpulse.rpsim.tone;

import java.util.Optional;

import de.farmpulse.rpsim.common.Formulas;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.ToneClass;
import de.farmpulse.rpsim.domain.TrustEvent;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.time.GameTime;
import de.farmpulse.rpsim.trust.TrustScoreService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Feeds the tone of a player message into the trust score - capped, same logic as every other trust event. */
@Service
public class ToneTrustService {

    private final TrustScoreService trust;
    private final RpsimProperties props;

    public ToneTrustService(TrustScoreService trust, RpsimProperties props) {
        this.trust = trust;
        this.props = props;
    }

    public double delta(ToneClass tone) {
        RpsimProperties.Tone cfg = props.getFormulas().getTone();
        double raw = switch (tone) {
            case FRIENDLY -> cfg.getFriendlyDelta();
            case RUDE -> cfg.getRudeDelta();
            case NEUTRAL -> 0;
        };
        return Formulas.clamp(raw, -cfg.getCap(), cfg.getCap());
    }

    /**
     * Pacing ("Proaktive Nachricht", logic review LR-M9, owner decision 2026-10-20): the tone of a player message counts
     * for the trust at most once per character within {@code messages.pacing-cooldown-days} - shared by "Nachricht
     * verfassen", replies to mails and sentences in a call, so switching the channel farms nothing. The character
     * always answers. Returns true while the cooldown is running (nothing counted).
     */
    @Transactional
    public boolean applyPaced(Character character, ToneClass tone, long now) {
        if (character == null) {
            return false;
        }
        Long last = character.getLastPacedMessageGameTime();
        if (last != null && GameTime.toDays(now - last) < props.getFormulas().getMessages().getPacingCooldownDays()) {
            return true;
        }
        apply(character, tone);
        character.setLastPacedMessageGameTime(now);
        return false;
    }

    @Transactional
    public Optional<TrustEvent> apply(Character character, ToneClass tone) {
        double d = delta(tone);
        if (d == 0 || character == null) {
            return Optional.empty();
        }
        return Optional.of(trust.recordEvent(character, d,
                tone == ToneClass.FRIENDLY ? TrustReason.TONE_FRIENDLY : TrustReason.TONE_RUDE, "Tonfall der Nachricht"));
    }
}
