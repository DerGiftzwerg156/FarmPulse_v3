package de.farmpulse.rpsim.notice;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import de.farmpulse.rpsim.common.NotFoundException;
import de.farmpulse.rpsim.domain.Notice;
import de.farmpulse.rpsim.domain.NoticeKind;
import de.farmpulse.rpsim.domain.NoticeStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.NoticeRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dashboard notices of the fact layer (T-02 rewind decisions, T-03 instructions the mod did not execute). Notices
 * carry raw values only; the frontend renders the texts.
 */
@Service
public class NoticeService {

    /** Published for SSE live updates. */
    public record NoticeChanged(Long savegameId, Long noticeId) {
    }

    private final NoticeRepository repo;
    private final JsonMapper json;
    private final ApplicationEventPublisher events;

    public NoticeService(NoticeRepository repo, JsonMapper json, ApplicationEventPublisher events) {
        this.repo = repo;
        this.json = json;
        this.events = events;
    }

    @Transactional
    public Notice raise(Savegame sg, NoticeKind kind, Map<String, Object> details, String relatedType, Long relatedId) {
        Notice n = new Notice();
        n.setSavegame(sg);
        n.setKind(kind);
        n.setStatus(NoticeStatus.OPEN);
        n.setGameTime(sg.getCurrentGameTime());
        n.setDetailsJson(details == null ? null : json.writeValueAsString(details));
        n.setRelatedEntityType(relatedType);
        n.setRelatedEntityId(relatedId);
        n.setCreatedAt(Instant.now());
        repo.save(n);
        events.publishEvent(new NoticeChanged(sg.getId(), n.getId()));
        return n;
    }

    @Transactional
    public Notice resolve(Notice n, String resolution) {
        n.setStatus(NoticeStatus.RESOLVED);
        n.setResolution(resolution);
        n.setResolvedAt(Instant.now());
        events.publishEvent(new NoticeChanged(n.getSavegame().getId(), n.getId()));
        return n;
    }

    public Notice get(Savegame sg, Long id) {
        return repo.findById(id).filter(n -> n.getSavegame().getId().equals(sg.getId()))
                .orElseThrow(() -> new NotFoundException("notice " + id));
    }

    public List<Notice> open(Savegame sg) {
        return repo.findBySavegameAndStatusOrderByIdDesc(sg, NoticeStatus.OPEN);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> details(Notice n) {
        return n.getDetailsJson() == null ? Map.of() : json.readValue(n.getDetailsJson(), Map.class);
    }
}
