package de.farmpulse.rpsim.api;

import java.util.List;

import de.farmpulse.rpsim.api.Views.CalendarOverviewView;
import de.farmpulse.rpsim.api.Views.TasksView;
import de.farmpulse.rpsim.savegame.SavegameContext;
import de.farmpulse.rpsim.tablet.CalendarPlanService;
import de.farmpulse.rpsim.tablet.TaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Hof-Tablet apps that combine several areas: "Aufgaben" (open decisions) and "Kalender" (dates). */
@RestController
public class TabletController {

    private final SavegameContext context;
    private final TaskService tasks;
    private final CalendarPlanService calendar;

    public TabletController(SavegameContext context, TaskService tasks, CalendarPlanService calendar) {
        this.context = context;
        this.tasks = tasks;
        this.calendar = calendar;
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
}
