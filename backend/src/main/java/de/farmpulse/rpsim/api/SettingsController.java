package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.ai.AiProviderRegistry;
import de.farmpulse.rpsim.ai.AiSettingsService;
import de.farmpulse.rpsim.api.Requests.AiSettingsRequest;
import de.farmpulse.rpsim.api.Views.AiSettingsView;
import de.farmpulse.rpsim.api.Views.GameSettingsView;
import de.farmpulse.rpsim.domain.HelperWageMode;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.employee.WorkforceService;
import de.farmpulse.rpsim.savegame.SavegameContext;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local AI provider configuration. The API key is written only to the local, git-ignored config file and is never
 * returned (only "apiKeySet").
 */
@RestController
public class SettingsController {

    private final AiSettingsService settings;
    private final AiProviderRegistry registry;
    private final SavegameContext context;
    private final WorkforceService workforce;

    public SettingsController(AiSettingsService settings, AiProviderRegistry registry, SavegameContext context,
                              WorkforceService workforce) {
        this.settings = settings;
        this.registry = registry;
        this.context = context;
        this.workforce = workforce;
    }

    /** Roadmap V2 R2-A1 / R2-A3: who pays the FS25 helpers and the strict helper limit. */
    @GetMapping("/api/settings/helpers")
    @Transactional(readOnly = true)
    public Views.HelperSettingsView helpers() {
        Savegame sg = context.requireActive();
        return new Views.HelperSettingsView(sg.getHelperWageMode().name(), sg.isStrictHelperLimit(), sg.isWorkforceTracked());
    }

    @PutMapping("/api/settings/helpers")
    @Transactional
    public Views.HelperSettingsView saveHelpers(@Valid @RequestBody Requests.HelperSettingsRequest r) {
        Savegame sg = context.requireActive();
        sg.setHelperWageMode(HelperWageMode.valueOf(r.helperWageMode()));
        sg.setStrictHelperLimit(r.strictHelperLimit());
        workforce.sync(sg);
        return new Views.HelperSettingsView(sg.getHelperWageMode().name(), sg.isStrictHelperLimit(), sg.isWorkforceTracked());
    }

    private AiSettingsView view(AiSettingsService.View v) {
        List<String> providers = registry.ids().stream().filter(id -> !"FAKE".equals(id)).toList();
        return new AiSettingsView(v.provider(), v.model(), v.baseUrl(), v.apiKeySet(), providers);
    }

    @GetMapping("/api/settings/ai")
    public AiSettingsView ai() {
        return view(settings.view());
    }

    @PutMapping("/api/settings/ai")
    public AiSettingsView save(@Valid @RequestBody AiSettingsRequest r) {
        registry.byId(r.provider()); // validates the id
        return view(settings.save(r.provider(), r.model(), r.apiKey(), r.baseUrl()));
    }

    /** Tone preset is fixed since the onboarding (read-only). */
    @GetMapping("/api/settings/game")
    @Transactional(readOnly = true)
    public GameSettingsView game() {
        Savegame sg = context.requireActive();
        return new GameSettingsView(sg.getTonePreset().name(), switch (sg.getTonePreset()) {
            case IDYLLIC -> "idyllisch-entspannt";
            case REALISTIC -> "realistisch-ausgewogen";
            case HARSH -> "hart-dramatisch";
        });
    }
}
