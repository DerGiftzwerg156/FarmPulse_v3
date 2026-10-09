package de.farmpulse.rpsim.api;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import de.farmpulse.rpsim.domain.AppHintSeen;
import de.farmpulse.rpsim.repository.AppHintSeenRepository;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * First-open hints of the Hof-Tablet apps (owner decision 2026-10-06): the backend remembers which app hints were
 * confirmed, so a hint shows once per installation on any device (gaming PC, tablet, phone). Works without an active
 * savegame.
 */
@RestController
public class AppHintController {

    /** App ids of {@code layout/apps.ts}: lower-case letters only. */
    private static final Pattern APP_ID = Pattern.compile("[a-z]{1,32}");

    public record AppHintsView(List<String> seen) {
    }

    private final AppHintSeenRepository repo;

    public AppHintController(AppHintSeenRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/api/app-hints")
    @Transactional(readOnly = true)
    public AppHintsView seen() {
        return view();
    }

    /** "Verstanden": marks the hint of the app as read; repeated calls keep the first time. */
    @PutMapping("/api/app-hints/{appId}")
    @Transactional
    public AppHintsView markSeen(@PathVariable String appId) {
        if (!APP_ID.matcher(appId).matches()) {
            throw new IllegalArgumentException("invalid app id");
        }
        if (!repo.existsById(appId)) {
            AppHintSeen s = new AppHintSeen();
            s.setAppId(appId);
            s.setSeenAt(Instant.now());
            repo.save(s);
        }
        return view();
    }

    private AppHintsView view() {
        return new AppHintsView(repo.findAll(Sort.by("appId")).stream().map(AppHintSeen::getAppId).toList());
    }
}
