import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable, catchError, forkJoin, of } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { CalendarOverviewView, FarmlandView, FinanceOverview, ReputationView, StablesView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { TasksStore } from '../../core/state/tasks.store';
import { calendarLabel } from '../../shared/format/calendar';
import { clockTime } from '../../shared/format/format';
import { weatherLong } from '../../shared/format/weather';
import { MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Icon } from '../../shared/ui/icon';
import { AppTile } from '../../layout/app-tile';
import { APPS } from '../../layout/apps';
import { TaskCardLine } from './task-line';

/**
 * Start screen of the Hof-Tablet (replaces the dashboard): game clock, widgets and every app as a symbol with badge.
 * Pure composition of existing endpoints.
 */
@Component({
  selector: 'app-home',
  imports: [RouterLink, TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, Icon, AppTile, TaskCardLine],
  templateUrl: './home.html',
})
export class Home {
  private readonly api = inject(ApiService);
  readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  readonly tasks = inject(TasksStore);
  readonly apps = APPS;
  readonly calendar = signal<CalendarOverviewView | null>(null);
  readonly reputation = signal<ReputationView | null>(null);
  readonly finances = signal<FinanceOverview | null>(null);
  readonly fields = signal<FarmlandView[]>([]);
  readonly stables = signal<StablesView | null>(null);
  readonly loaded = signal(false);

  /** The four most pressing open decisions (the list is sorted by deadline). */
  readonly topTasks = computed(() => this.tasks.items().slice(0, 4));
  /** Last complete month of the farm bookkeeping (null without journal). */
  readonly lastMonth = computed(() => {
    const f = this.finances();
    if (!f?.available) return null;
    return [...f.months].reverse().find((m) => m.complete) ?? null;
  });
  readonly ownFields = computed(() => this.fields().filter((f) => f.ownerType === 'PLAYER' || f.leased));
  readonly harvestable = computed(() => this.ownFields().filter((f) => f.phase === 'HARVESTABLE'));
  readonly withered = computed(() => this.ownFields().filter((f) => f.phase === 'WITHERED'));
  /** The barn with the lowest health (or the first one without values), for the start screen widget. */
  readonly weakestBarn = computed(() => {
    const barns = this.stables()?.barns ?? [];
    return [...barns].sort((a, b) => (a.health ?? 101) - (b.health ?? 101))[0] ?? null;
  });
  readonly stableAlarm = computed(() => {
    const s = this.stables();
    const b = this.weakestBarn();
    return !!s && !!b && ((b.health !== null && b.health < s.healthWarnBelow) || (b.food !== null && b.food < s.foodWarnBelow)
      || s.barns.some((x) => x.inspectionDeadline !== null));
  });
  readonly fieldsTracked = computed(() => this.ownFields().some((f) => f.phase !== null && f.phase !== undefined));

  constructor() {
    effect(() => {
      this.store.stateVersion();
      const sg = this.store.savegame();
      if (sg) untracked(() => this.load());
    });
  }

  load(): void {
    const safe = <T>(o: Observable<T>, fallback: T) => o.pipe(catchError(() => of(fallback)));
    forkJoin({
      calendar: safe(this.api.calendar(), null as CalendarOverviewView | null),
      reputation: safe(this.api.reputation(), null as ReputationView | null),
      finances: safe(this.api.finances(), null as FinanceOverview | null),
      fields: safe(this.api.farmlands(), [] as FarmlandView[]),
      stables: safe(this.api.stables(), null as StablesView | null),
    }).subscribe((r) => {
      this.calendar.set(r.calendar);
      this.reputation.set(r.reputation);
      this.finances.set(r.finances);
      this.fields.set(r.fields);
      this.stables.set(r.stables);
      this.loaded.set(true);
    });
  }

  clock(): string {
    return clockTime(this.store.savegame()?.gameTime);
  }

  dateLine(): string {
    const sg = this.store.savegame();
    if (!sg) return '';
    const day = this.i18n.t('common.day', { day: sg.gameDay });
    const date = sg.calendar ? `${day} · ${calendarLabel(sg.calendar, this.i18n)}` : day;
    return sg.weather ? `${date} — ${weatherLong(sg.weather, this.i18n)}` : date;
  }

  monthName(period: number): string {
    return this.i18n.t(`enums.period.${period}`);
  }
}
