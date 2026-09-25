package de.farmpulse.rpsim.onboarding;

import de.farmpulse.rpsim.domain.CommunicationCategory;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.domain.StoryHook;
import de.farmpulse.rpsim.narration.NarrationEventType;
import de.farmpulse.rpsim.narration.NarrationFacts;
import de.farmpulse.rpsim.narration.NarrationRequestService;
import de.farmpulse.rpsim.repository.SavegameRepository;
import de.farmpulse.rpsim.repository.StoryHookRepository;
import de.farmpulse.rpsim.time.GameTimeAdvancedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Fires the onboarding story hooks staggered over the first game weeks (not all at once). */
@Component
public class StoryHookScheduler {

    private final StoryHookRepository hooks;
    private final SavegameRepository savegames;
    private final NarrationRequestService narration;

    public StoryHookScheduler(StoryHookRepository hooks, SavegameRepository savegames, NarrationRequestService narration) {
        this.hooks = hooks;
        this.savegames = savegames;
        this.narration = narration;
    }

    @EventListener
    @Transactional
    public void onGameTime(GameTimeAdvancedEvent e) {
        fireDue(savegames.findById(e.savegameId()).orElseThrow());
    }

    @Transactional
    public int fireDue(Savegame sg) {
        int fired = 0;
        for (StoryHook h : hooks.findBySavegameAndFiredFalseOrderByScheduledGameTimeAsc(sg)) {
            if (h.getScheduledGameTime() > sg.getCurrentGameTime()) {
                break;
            }
            h.setFired(true);
            h.setFiredAtGameTime(sg.getCurrentGameTime());
            narration.request(sg, NarrationEventType.STORY_HOOK).from(h.getCharacter())
                    .facts(NarrationFacts.builder().put("hookKey", h.getHookKey()).put("title", h.getTitle())
                            .put("premise", h.getDescription()).build())
                    .category(CommunicationCategory.STORY).related("STORY_HOOK", h.getId()).submit();
            fired++;
        }
        return fired;
    }
}
