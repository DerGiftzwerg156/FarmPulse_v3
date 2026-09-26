package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.api.Requests.NoticeActionRequest;
import de.farmpulse.rpsim.api.Views.NoticeView;
import de.farmpulse.rpsim.bridge.RewindService;
import de.farmpulse.rpsim.common.BusinessRuleException;
import de.farmpulse.rpsim.domain.Notice;
import de.farmpulse.rpsim.domain.NoticeKind;
import de.farmpulse.rpsim.domain.NoticeStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.notice.NoticeService;
import de.farmpulse.rpsim.savegame.SavegameContext;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard notices of the fact layer (T-02 rewind decision, T-03 bookings the mod did not execute). */
@RestController
@RequestMapping("/api/notices")
public class NoticeController {

    public static final String DISMISS = "DISMISS";

    private final SavegameContext context;
    private final NoticeService notices;
    private final RewindService rewinds;

    public NoticeController(SavegameContext context, NoticeService notices, RewindService rewinds) {
        this.context = context;
        this.notices = notices;
        this.rewinds = rewinds;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<NoticeView> open() {
        return context.findActive().map(sg -> notices.open(sg).stream().map(this::view).toList()).orElse(List.of());
    }

    @PostMapping("/{id}/resolve")
    @Transactional
    public NoticeView resolve(@PathVariable Long id, @Valid @RequestBody NoticeActionRequest r) {
        Savegame sg = context.requireActive();
        Notice n = notices.get(sg, id);
        if (n.getStatus() != NoticeStatus.OPEN) {
            throw new BusinessRuleException("NOTICE_CLOSED", "Dieser Hinweis ist bereits erledigt.");
        }
        if (n.getKind() == NoticeKind.REWIND_DECISION) {
            rewinds.decideByNotice(sg, n, r.action());
        } else if (DISMISS.equals(r.action())) {
            notices.resolve(n, DISMISS);
        } else {
            throw new BusinessRuleException("INVALID_ACTION", "Unbekannte Aktion: " + r.action());
        }
        return view(n);
    }

    NoticeView view(Notice n) {
        return new NoticeView(n.getId(), n.getKind().name(), n.getStatus().name(), n.getGameTime(), notices.details(n),
                n.getRelatedEntityType(), n.getRelatedEntityId());
    }
}
