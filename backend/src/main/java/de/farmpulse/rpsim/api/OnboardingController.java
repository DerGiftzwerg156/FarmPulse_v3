package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.api.Requests.ConfirmRequest;
import de.farmpulse.rpsim.api.Requests.OnboardingRequest;
import de.farmpulse.rpsim.api.Requests.RerollRequest;
import de.farmpulse.rpsim.api.Views.DetectedView;
import de.farmpulse.rpsim.api.Views.OnboardingView;
import de.farmpulse.rpsim.api.Views.PreviewView;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.onboarding.OnboardingService;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {

    private final OnboardingService onboarding;

    public OnboardingController(OnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    OnboardingView view(Savegame sg) {
        List<PreviewView> cast = onboarding.preview(sg).stream()
                .map(p -> new PreviewView(p.characterId(), p.name(), p.role().name(), p.category().name(),
                        p.jobRole() == null ? null : p.jobRole().name(), p.shortDescription()))
                .toList();
        return new OnboardingView(sg.getId(), sg.getStatus().name(), sg.isFreeTextRejected(), cast);
    }

    @PostMapping
    public OnboardingView create(@Valid @RequestBody OnboardingRequest r) {
        Savegame sg = onboarding.create(new OnboardingService.Request(r.farmOrigin(), r.villageRelation(), r.freeText(),
                r.startingCapitalTarget(), r.legacyLoanAmount(), r.tonePreset(),
                r.initialEmployees() == null ? List.of() : r.initialEmployees()));
        return view(sg);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public OnboardingView get(@PathVariable Long id) {
        return view(onboarding.draft(id));
    }

    @PostMapping("/{id}/reroll")
    public OnboardingView reroll(@PathVariable Long id, @RequestBody(required = false) RerollRequest r) {
        return view(onboarding.reroll(id, r == null ? null : r.characterId()));
    }

    @GetMapping("/unlinked-savegames")
    public List<DetectedView> unlinked() {
        return onboarding.unlinkedSavegames().stream().map(d -> new DetectedView(d.savegameId(), d.mapName(),
                d.firstSeen().toString(), d.lastSeen().toString(), d.gameTime())).toList();
    }

    @PostMapping("/{id}/confirm")
    public OnboardingView confirm(@PathVariable Long id, @Valid @RequestBody ConfirmRequest r) {
        Savegame sg = onboarding.confirm(id, r.savegameId());
        return new OnboardingView(sg.getId(), sg.getStatus().name(), sg.isFreeTextRejected(), List.of());
    }
}
