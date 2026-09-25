package de.farmpulse.rpsim.diary;

import java.time.Instant;
import java.util.List;

import de.farmpulse.rpsim.domain.DiaryEntry;
import de.farmpulse.rpsim.domain.DiaryEntryType;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.DiaryEntryRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Diary/chronicle (functional concept "Tagebuch/Chronik"): automatic entries for important events plus free
 * player notes that have no mechanical effect whatsoever.
 */
@Service
public class DiaryService {

    /** Published for SSE live updates. */
    public record DiaryEntryCreated(Long savegameId, Long entryId) {
    }

    private final DiaryEntryRepository repo;
    private final ApplicationEventPublisher events;

    public DiaryService(DiaryEntryRepository repo, ApplicationEventPublisher events) {
        this.repo = repo;
        this.events = events;
    }

    @Transactional
    public DiaryEntry addAuto(Savegame sg, String category, String title, String text, String relatedType, Long relatedId) {
        return add(sg, DiaryEntryType.AUTO, category, title, text, relatedType, relatedId);
    }

    /** Free player note - purely narrative, never read by any formula. */
    @Transactional
    public DiaryEntry addPlayerNote(Savegame sg, String title, String text) {
        return add(sg, DiaryEntryType.PLAYER_NOTE, "NOTE", title, text, null, null);
    }

    private DiaryEntry add(Savegame sg, DiaryEntryType type, String category, String title, String text,
                           String relatedType, Long relatedId) {
        DiaryEntry e = new DiaryEntry();
        e.setSavegame(sg);
        e.setGameTime(sg.getCurrentGameTime());
        e.setEntryType(type);
        e.setCategory(category);
        e.setTitle(title);
        e.setText(text);
        e.setCreatedAt(Instant.now());
        e.setRelatedEntityType(relatedType);
        e.setRelatedEntityId(relatedId);
        repo.save(e);
        events.publishEvent(new DiaryEntryCreated(sg.getId(), e.getId()));
        return e;
    }

    public List<DiaryEntry> list(Savegame sg) {
        return repo.findBySavegameOrderByGameTimeAscIdAsc(sg);
    }
}
