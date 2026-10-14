package de.farmpulse.rpsim.api;

import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.planning.CropPlanService;
import de.farmpulse.rpsim.savegame.SavegameContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roadmap V4 section A: the tab "Anbauplanung" of the app "Feldbuch" (owner decisions 2026-10-13) - the plan per field
 * for the next season with checks, forecast and needs, the rotation suggestion, the sowing reminder and "Plan
 * besprechen".
 */
@RestController
public class CropPlanController {

    /** A1: crop ({@code BRACHE} = fallow), harvest product (null = standard), sowing month 1..12 (optional), note. */
    public record PlanRequest(@NotBlank String fruitType, String fillType, Integer sowPeriod, String note) {
    }

    /** A5: switch "Erinnerung zur Saatzeit". */
    public record RemindersRequest(@NotNull Boolean enabled) {
    }

    private final SavegameContext context;
    private final CropPlanService planner;

    public CropPlanController(SavegameContext context, CropPlanService planner) {
        this.context = context;
        this.planner = planner;
    }

    @GetMapping("/api/crop-plans")
    @Transactional(readOnly = true)
    public CropPlanService.Planning view() {
        return planner.planning(context.requireActive());
    }

    @PutMapping("/api/crop-plans/fields/{farmlandId}")
    @Transactional
    public CropPlanService.Planning plan(@PathVariable int farmlandId, @Valid @RequestBody PlanRequest r) {
        Savegame sg = context.requireActive();
        planner.plan(sg, farmlandId, r.fruitType(), r.fillType(), r.sowPeriod(), r.note());
        return planner.planning(sg);
    }

    @DeleteMapping("/api/crop-plans/fields/{farmlandId}")
    @Transactional
    public CropPlanService.Planning remove(@PathVariable int farmlandId) {
        Savegame sg = context.requireActive();
        planner.remove(sg, farmlandId);
        return planner.planning(sg);
    }

    /** A1 "Fruchtfolge vorschlagen"; 204 when no crop fits. */
    @GetMapping("/api/crop-plans/fields/{farmlandId}/suggestion")
    @Transactional(readOnly = true)
    public ResponseEntity<CropPlanService.Suggestion> suggestion(@PathVariable int farmlandId) {
        return planner.suggest(context.requireActive(), farmlandId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/api/crop-plans/reminders")
    @Transactional
    public CropPlanService.Planning reminders(@Valid @RequestBody RemindersRequest r) {
        Savegame sg = context.requireActive();
        planner.setReminders(sg, r.enabled());
        return planner.planning(sg);
    }

    /** A6: "Plan besprechen" - once per plan year, the cooperative answers the next game day. */
    @PostMapping("/api/crop-plans/discuss")
    @Transactional
    public CropPlanService.Planning discuss() {
        Savegame sg = context.requireActive();
        planner.discuss(sg);
        return planner.planning(sg);
    }
}
