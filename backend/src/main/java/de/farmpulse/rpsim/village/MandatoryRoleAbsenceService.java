package de.farmpulse.rpsim.village;

import de.farmpulse.rpsim.character.CharacterGeneratorService;
import de.farmpulse.rpsim.character.CharacterGeneratorService.Spec;
import de.farmpulse.rpsim.common.RandomSource;
import de.farmpulse.rpsim.config.RpsimProperties;
import de.farmpulse.rpsim.domain.AbsenceVariant;
import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.TerminationReason;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.CharacterRepository;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.time.GameDayPassedEvent;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mandatory roles are never vacant (technical concept "Pflichtrollen-Abwesenheit"): a rare roll sends a mandatory
 * character on leave; per case either a delayed reply with absence note or a substitute character. The fact file
 * (loans etc.) stays with the role/savegame in both variants.
 */
@Service
public class MandatoryRoleAbsenceService {

    private final CharacterRepository characters;
    private final SavegameRepository savegames;
    private final CharacterGeneratorService generator;
    private final NarrationRequestService narration;
    private final RandomSource random;
    private final RpsimProperties props;

    public MandatoryRoleAbsenceService(CharacterRepository characters, SavegameRepository savegames,
                                       CharacterGeneratorService generator, NarrationRequestService narration,
                                       RandomSource random, RpsimProperties props) {
        this.characters = characters;
        this.savegames = savegames;
        this.generator = generator;
        this.narration = narration;
        this.random = random;
        this.props = props;
    }

    private RpsimProperties.Absence cfg() {
        return props.getFormulas().getAbsence();
    }

    @EventListener
    @Transactional
    public void onDay(GameDayPassedEvent e) {
        Savegame sg = savegames.findById(e.savegameId()).orElseThrow();
        endAbsences(sg);
        for (Character c : characters.findBySavegameAndCategoryAndStatus(sg, CharacterCategory.MANDATORY, CharacterStatus.ACTIVE)) {
            if (random.chance(cfg().getDailyProbability())) {
                startAbsence(sg, c, random.chance(cfg().getSubstituteProbability()) ? AbsenceVariant.SUBSTITUTE
                        : AbsenceVariant.DELAYED_REPLY);
            }
        }
    }

    @Transactional
    public void startAbsence(Savegame sg, Character c, AbsenceVariant variant) {
        int days = random.intBetween(cfg().getDurationDaysMin(), cfg().getDurationDaysMax());
        c.setStatus(CharacterStatus.ON_LEAVE);
        c.setAbsenceVariant(variant);
        c.setOnLeaveUntilGameTime(sg.getCurrentGameTime() + GameTime.days(days));
        if (variant == AbsenceVariant.SUBSTITUTE) {
            // temporary character: own personality layer, same role and (implicitly) the same fact file
            Character sub = generator.generate(sg, Spec.of(c.getRole(), CharacterCategory.SUBSTITUTE, 0), random.nextLong());
            sub.setSubstituteForId(c.getId());
            narration.request(sg, NarrationEventType.SUBSTITUTE_INTRODUCTION).from(sub)
                    .facts(NarrationFacts.builder().put("absentName", c.getName()).put("days", days).build())
                    .category(CommunicationCategory.ABSENCE).submit();
        } else {
            narration.request(sg, NarrationEventType.ABSENCE_NOTICE).from(null)
                    .facts(NarrationFacts.builder().put("absentName", c.getName()).put("role", c.getRole()).put("days", days)
                            .build())
                    .category(CommunicationCategory.ABSENCE).submit();
        }
    }

    /** Ends absences: original back to ACTIVE, substitute TERMINATED. */
    @Transactional
    public void endAbsences(Savegame sg) {
        long now = sg.getCurrentGameTime();
        for (Character c : characters.findBySavegameAndStatus(sg, CharacterStatus.ON_LEAVE)) {
            if (c.getOnLeaveUntilGameTime() != null && c.getOnLeaveUntilGameTime() <= now) {
                c.setStatus(CharacterStatus.ACTIVE);
                c.setOnLeaveUntilGameTime(null);
                c.setAbsenceVariant(null);
                characters.findBySavegameAndStatus(sg, CharacterStatus.ACTIVE).stream()
                        .filter(s -> c.getId().equals(s.getSubstituteForId()))
                        .forEach(s -> {
                            s.setStatus(CharacterStatus.TERMINATED);
                            s.setTerminationReason(TerminationReason.SUBSTITUTE_ENDED);
                            s.setLeftAtGameTime(now);
                        });
            }
        }
    }
}
