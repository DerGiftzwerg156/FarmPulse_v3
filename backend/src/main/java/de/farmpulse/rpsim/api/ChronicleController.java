package de.farmpulse.rpsim.api;

import java.nio.charset.StandardCharsets;
import java.util.List;

import de.farmpulse.rpsim.api.Views.MilestoneView;
import de.farmpulse.rpsim.diary.ChronicleService;
import de.farmpulse.rpsim.diary.MilestoneService;
import de.farmpulse.rpsim.domain.DiaryEntry;
import de.farmpulse.rpsim.domain.Milestone;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.DiaryEntryRepository;
import de.farmpulse.rpsim.savegame.SavegameContext;
import de.farmpulse.rpsim.time.GameTime;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Roadmap V3 R3-T: milestones (start screen) and the farm chronicle (diary app: download and print view). */
@RestController
public class ChronicleController {

    static final MediaType MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);

    private final SavegameContext context;
    private final MilestoneService milestones;
    private final ChronicleService chronicle;
    private final DiaryEntryRepository entries;

    public ChronicleController(SavegameContext context, MilestoneService milestones, ChronicleService chronicle,
                               DiaryEntryRepository entries) {
        this.context = context;
        this.milestones = milestones;
        this.chronicle = chronicle;
        this.entries = entries;
    }

    /** R3-T1: reached milestones in the order they were reached. */
    @GetMapping("/api/milestones")
    @Transactional(readOnly = true)
    public List<MilestoneView> milestones() {
        Savegame sg = context.requireActive();
        return milestones.list(sg).stream().map(this::view).toList();
    }

    private MilestoneView view(Milestone m) {
        String title = m.getDiaryEntryId() == null ? null
                : entries.findById(m.getDiaryEntryId()).map(DiaryEntry::getTitle).orElse(null);
        return new MilestoneView(m.getMilestoneKey().name(), title, m.getReachedGameTime(),
                GameTime.dayIndex(m.getReachedGameTime()));
    }

    /** R3-T2: the chronicle as Markdown file {@code chronik-<name>.md}. */
    @GetMapping("/api/diary/chronicle")
    @Transactional(readOnly = true)
    public ResponseEntity<String> download() {
        Savegame sg = context.requireActive();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(ChronicleService.fileName(sg), StandardCharsets.UTF_8).build().toString())
                .contentType(MARKDOWN)
                .body(chronicle.markdown(sg));
    }

    /** R3-T2: the same content as data for the print view. */
    @GetMapping("/api/diary/chronicle/view")
    @Transactional(readOnly = true)
    public ChronicleService.Chronicle view() {
        return chronicle.build(context.requireActive());
    }
}
