package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.api.Views.CalendarOverviewView;
import de.farmpulse.rpsim.api.Views.FieldOverviewView;
import de.farmpulse.rpsim.api.Views.StablesView;
import de.farmpulse.rpsim.api.Views.TasksView;
import de.farmpulse.rpsim.savegame.SavegameContext;
import de.farmpulse.rpsim.tablet.CalendarPlanService;
import de.farmpulse.rpsim.tablet.FieldMapService;
import de.farmpulse.rpsim.tablet.FieldOverviewService;
import de.farmpulse.rpsim.tablet.StableService;
import de.farmpulse.rpsim.tablet.TaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Hof-Tablet apps that combine several areas: "Aufgaben" (open decisions), "Kalender" (dates), "Stall" and the field table of "Flurkarte". */
@RestController
public class TabletController {

    private final SavegameContext context;
    private final TaskService tasks;
    private final CalendarPlanService calendar;
    private final StableService stables;
    private final FieldOverviewService fields;
    private final FieldMapService fieldMap;

    public TabletController(SavegameContext context, TaskService tasks, CalendarPlanService calendar, StableService stables,
                            FieldOverviewService fields,
                            FieldMapService fieldMap) {
        this.fieldMap = fieldMap;
        this.context = context;
        this.tasks = tasks;
        this.calendar = calendar;
        this.stables = stables;
        this.fields = fields;
    }

    /** Open decisions of every area, sorted by deadline; empty without an active savegame. */
    @GetMapping("/api/tasks")
    public TasksView tasks() {
        return context.findActive().map(tasks::tasks).orElse(new TasksView(List.of(), 0));
    }

    @GetMapping("/api/calendar")
    public CalendarOverviewView calendar() {
        return calendar.overview(context.requireActive());
    }

    /** Stables with their values, announced animal welfare inspections, vet routine and keeper load. */
    @GetMapping("/api/stables")
    public StablesView stables() {
        return stables.stables(context.requireActive());
    }

    /** Fields the player farms with what needs doing and the crop rotation / premium preview of the running year. */
    @GetMapping("/api/field-overview")
    public FieldOverviewView fieldOverview() {
        return fields.overview(context.requireActive());
    }

    /** Roadmap V3.1 R31-K1: the field outlines with owner, phase and symbols for the map of the Flurkarte. */
    @GetMapping("/api/field-map")
    public FieldMapService.FieldMap fieldMap() {
        return fieldMap.map(context.requireActive());
    }
}
