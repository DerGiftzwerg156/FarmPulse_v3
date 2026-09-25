package de.farmpulse.rpsim.village;

import de.farmpulse.rpsim.domain.PublicActionEvent;
import de.farmpulse.rpsim.domain.PublicActionType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.PublicActionEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Savegame-wide log of public actions that feed the village reputation. */
@Service
public class PublicActionService {

    private final PublicActionEventRepository repo;

    public PublicActionService(PublicActionEventRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public PublicActionEvent record(Savegame sg, PublicActionType type, double delta, String note) {
        PublicActionEvent e = new PublicActionEvent();
        e.setSavegame(sg);
        e.setGameTime(sg.getCurrentGameTime());
        e.setType(type);
        e.setDelta(delta);
        e.setNote(note);
        return repo.save(e);
    }
}
