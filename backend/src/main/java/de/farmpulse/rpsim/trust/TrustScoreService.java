package de.farmpulse.rpsim.trust;

import java.util.List;

import de.farmpulse.rpsim.common.Formulas;
import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.TrustEvent;
import de.farmpulse.rpsim.domain.TrustReason;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.TrustEventRepository;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Capped trust score from the append-only {@link TrustEvent} log (technical concept "TrustScoreService").
 * Trust only changes through defined, logged events - never through free AI interpretation. After
 * {@code decayStartDays} without events the score drifts towards neutral by {@code decayPerDay}.
 */
@Service
public class TrustScoreService {

    private final CharacterRepository characters;
    private final TrustEventRepository events;
    private final RpsimProperties props;

    public TrustScoreService(CharacterRepository characters, TrustEventRepository events, RpsimProperties props) {
        this.characters = characters;
        this.events = events;
        this.props = props;
    }

    private RpsimProperties.Trust cfg() {
        return props.getFormulas().getTrust();
    }

    /** Score after inactivity decay (pure function, used for replay and "current" reads). */
    public double decayed(double score, Long lastEventGameTime, long now) {
        if (lastEventGameTime == null) {
            return score;
        }
        double idleDays = GameTime.toDays(now - lastEventGameTime) - cfg().getDecayStartDays();
        if (idleDays <= 0) {
            return score;
        }
        double step = idleDays * cfg().getDecayPerDay();
        double neutral = cfg().getNeutral();
        if (score > neutral) {
            return Math.max(neutral, score - step);
        }
        return Math.min(neutral, score + step);
    }

    public double clamp(double score) {
        return Formulas.clamp(score, cfg().getMin(), cfg().getMax());
    }

    /** Current trust of a character at the savegame's current game time (decay applied, not persisted). */
    @Transactional(readOnly = true)
    public double getCurrentTrust(Long characterId) {
        Character c = characters.findById(characterId).orElseThrow(() -> new NotFoundException("character " + characterId));
        return getCurrentTrust(c);
    }

    public double getCurrentTrust(Character c) {
        return decayed(c.getTrustScore(), c.getLastTrustEventGameTime(), c.getSavegame().getCurrentGameTime());
    }

    @Transactional
    public TrustEvent recordEvent(Long characterId, double delta, TrustReason reason) {
        return recordEvent(characterId, delta, reason, null);
    }

    @Transactional
    public TrustEvent recordEvent(Long characterId, double delta, TrustReason reason, String note) {
        Character c = characters.findById(characterId).orElseThrow(() -> new NotFoundException("character " + characterId));
        return recordEvent(c, delta, reason, note);
    }

    /** Appends an event and updates the cached, capped score. */
    @Transactional
    public TrustEvent recordEvent(Character c, double delta, TrustReason reason, String note) {
        long now = c.getSavegame().getCurrentGameTime();
        double base = decayed(c.getTrustScore(), c.getLastTrustEventGameTime(), now);
        c.setTrustScore(clamp(base + delta));
        c.setLastTrustEventGameTime(now);
        TrustEvent e = new TrustEvent();
        e.setSavegame(c.getSavegame());
        e.setCharacter(c);
        e.setGameTime(now);
        e.setDelta(delta);
        e.setReason(reason);
        e.setNote(note);
        return events.save(e);
    }

    /** Replays the full history (used to verify that the cached score is consistent with the log). */
    @Transactional(readOnly = true)
    public double replay(Character c) {
        List<TrustEvent> history = events.findByCharacterOrderByGameTimeAscIdAsc(c);
        double score = cfg().getNeutral();
        Long last = null;
        for (TrustEvent e : history) {
            score = clamp(decayed(score, last, e.getGameTime()) + e.getDelta());
            last = e.getGameTime();
        }
        return score;
    }
}
