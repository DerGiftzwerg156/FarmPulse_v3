import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { DiaryView, TaskView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { GameStateStore } from '../../core/state/game-state.store';
import { TasksStore } from '../../core/state/tasks.store';
import { gameDay } from '../../shared/format/format';
import { Card } from '../../shared/ui/card';
import { NoticesCard } from '../home/notices-card';
import { TaskCard } from './task-card';
import { TaskCategory, TaskGroup, taskCategory, taskGroup } from './task-groups';

type Filter = 'all' | 'today' | TaskCategory;

/**
 * Hof-Tablet app "Aufgaben": every open decision of all apps in one list, grouped by deadline (today, this week,
 * later); the notices of the hof system, the questions waiting in the game and today's diary in the tab "Meldungen".
 */
@Component({
  selector: 'app-tasks',
  imports: [RouterLink, TranslatePipe, Card, NoticesCard, TaskCard],
  templateUrl: './tasks.html',
})
export class Tasks {
  private readonly api = inject(ApiService);
  readonly store = inject(GameStateStore);
  readonly tasks = inject(TasksStore);

  /** Tab of the route `/aufgaben/:tab` (owner decision 2026-10-06): Aufgaben, Meldungen. */
  readonly tab = input<string>('aufgaben');
  readonly filter = signal<Filter>('all');
  readonly diary = signal<DiaryView[]>([]);
  readonly now = computed(() => this.store.savegame()?.gameTime ?? 0);

  readonly filters: Filter[] = ['all', 'today', 'money', 'farm', 'village'];
  readonly groups: TaskGroup[] = ['today', 'week', 'later'];

  private matches(t: TaskView, f: Filter): boolean {
    if (f === 'all') return true;
    if (f === 'today') return taskGroup(t, this.now()) === 'today';
    return taskCategory(t) === f;
  }

  count(f: Filter): number {
    return this.tasks.items().filter((t) => this.matches(t, f)).length;
  }

  readonly shown = computed(() => this.tasks.items().filter((t) => this.matches(t, this.filter())));

  inGroup(g: TaskGroup): TaskView[] {
    return this.shown().filter((t) => taskGroup(t, this.now()) === g);
  }

  /** Diary entries of the current game day ("what happened today"). */
  readonly today = computed(() => this.diary().filter((d) => gameDay(d.gameTime) === gameDay(this.now())).slice(0, 6));

  constructor() {
    effect(() => {
      this.store.diaryVersion();
      this.store.stateVersion();
      if (this.store.savegame()) untracked(() => this.api.diary().subscribe({ next: (d) => this.diary.set(d), error: () => this.diary.set([]) }));
    });
  }

  changed(): void {
    this.tasks.reload();
    this.store.refresh();
  }
}
