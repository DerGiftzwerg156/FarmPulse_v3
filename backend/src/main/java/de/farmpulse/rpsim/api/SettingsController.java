package de.farmpulse.rpsim.api;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import de.farmpulse.rpsim.ai.AiProviderRegistry;
import de.farmpulse.rpsim.ai.AiSettingsService;
import de.farmpulse.rpsim.api.Requests.AiSettingsRequest;
import de.farmpulse.rpsim.api.Views.AiSettingsView;
import de.farmpulse.rpsim.api.Views.GameSettingsView;
import de.farmpulse.rpsim.bypass.VanillaBypassService;
import de.farmpulse.rpsim.domain.HelperWageMode;
import de.farmpulse.rpsim.domain.PromptKind;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.employee.WorkforceService;
import de.farmpulse.rpsim.prompt.PromptService;
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
    private final VanillaBypassService bypass;
    private final PromptService prompts;

    public SettingsController(AiSettingsService settings, AiProviderRegistry registry, SavegameContext context,
                              WorkforceService workforce, VanillaBypassService bypass, PromptService prompts) {
        this.settings = settings;
        this.registry = registry;
        this.context = context;
        this.workforce = workforce;
        this.bypass = bypass;
        this.prompts = prompts;
    }

    /** Roadmap V2 R2-F2: which decisions are asked in the game as a yes/no question (default: only calls). */
    @GetMapping("/api/settings/prompts")
    @Transactional(readOnly = true)
    public Views.PromptSettingsView prompts() {
        return promptView(context.requireActive());
    }

    @PutMapping("/api/settings/prompts")
    @Transactional
    public Views.PromptSettingsView savePrompts(@Valid @RequestBody Requests.PromptSettingsRequest r) {
        Savegame sg = context.requireActive();
        Set<PromptKind> kinds = EnumSet.noneOf(PromptKind.class);
        r.kinds().forEach(k -> kinds.add(PromptKind.valueOf(k)));
        prompts.setEnabledKinds(sg, kinds);
        return promptView(sg);
    }

    private Views.PromptSettingsView promptView(Savegame sg) {
        return new Views.PromptSettingsView(prompts.globallyEnabled(),
                prompts.enabledKinds(sg).stream().map(Enum::name).toList(),
                Arrays.stream(PromptKind.values()).map(Enum::name).toList());
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

    /** Roadmap V2 R2-D: reactions to the vanilla loan and the game's field menu. */
    @GetMapping("/api/settings/vanilla-bypass")
    @Transactional(readOnly = true)
    public Views.BypassSettingsView bypass() {
        return bypassView(context.requireActive());
    }

    @PutMapping("/api/settings/vanilla-bypass")
    @Transactional
    public Views.BypassSettingsView saveBypass(@Valid @RequestBody Requests.BypassSettingsRequest r) {
        Savegame sg = context.requireActive();
        sg.setVanillaBypassEnabled(r.reactionsEnabled());
        return bypassView(sg);
    }

    private Views.BypassSettingsView bypassView(Savegame sg) {
        return new Views.BypassSettingsView(sg.isVanillaBypassEnabled(),
                Math.round(bypass.interestSurcharge(sg) * 10000) / 100.0);
    }

    /** Roadmap V2 R2-C6: field work hints of the cooperative. */
    @GetMapping("/api/settings/fields")
    @Transactional(readOnly = true)
    public Views.FieldSettingsView fields() {
        Savegame sg = context.requireActive();
        return new Views.FieldSettingsView(sg.isFieldHintsEnabled(), sg.isFieldsTracked());
    }

    @PutMapping("/api/settings/fields")
    @Transactional
    public Views.FieldSettingsView saveFields(@Valid @RequestBody Requests.FieldSettingsRequest r) {
        Savegame sg = context.requireActive();
        sg.setFieldHintsEnabled(r.fieldHintsEnabled());
        return new Views.FieldSettingsView(sg.isFieldHintsEnabled(), sg.isFieldsTracked());
    }

    /** Roadmap V3 R3-T2: optional farm name, heads the chronicle (without it the map name). */
    @GetMapping("/api/settings/farm")
    @Transactional(readOnly = true)
    public Views.FarmSettingsView farm() {
        Savegame sg = context.requireActive();
        return new Views.FarmSettingsView(sg.getFarmName(), sg.getMapName());
    }

    @PutMapping("/api/settings/farm")
    @Transactional
    public Views.FarmSettingsView saveFarm(@Valid @RequestBody Requests.FarmSettingsRequest r) {
        Savegame sg = context.requireActive();
        sg.setFarmName(r.farmName() == null || r.farmName().isBlank() ? null : r.farmName().strip());
        return new Views.FarmSettingsView(sg.getFarmName(), sg.getMapName());
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
