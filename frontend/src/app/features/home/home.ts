import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable, catchError, forkJoin, of } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { FarmlandView, FinanceOverview, LoanView, MessageView, ReputationView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { calendarLabel } from '../../shared/format/calendar';
import { clockTime } from '../../shared/format/format';
import { MoneyPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Icon } from '../../shared/ui/icon';
import { AppTile } from '../../layout/app-tile';
import { APPS } from '../../layout/apps';
import { NoticesCard } from './notices-card';

/**
 * Start screen of the Hof-Tablet (replaces the dashboard): game clock, widgets and every app as a symbol with badge.
 * Pure composition of existing endpoints.
 */
@Component({
  selector: 'app-home',
  imports: [RouterLink, TranslatePipe, LabelPipe, MoneyPipe, Icon, AppTile, NoticesCard],
  templateUrl: './home.html',
})
export class Home {
  private readonly api = inject(ApiService);
  readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  readonly apps = APPS;
  readonly mails = signal<MessageView[]>([]);
  readonly loans = signal<LoanView[]>([]);
  readonly reputation = signal<ReputationView | null>(null);
  readonly finances = signal<FinanceOverview | null>(null);
  readonly fields = signal<FarmlandView[]>([]);
  readonly loaded = signal(false);

  readonly unreadMails = computed(() => this.mails().filter((m) => !m.read && m.initiatedBy === 'CHARACTER').slice(0, 3));
  readonly activeLoans = computed(() => this.loans().filter((l) => l.status === 'ACTIVE'));
  readonly debt = computed(() => this.activeLoans().reduce((s, l) => s + l.remainingAmount, 0));
  /** Last complete month of the farm bookkeeping (null without journal). */
  readonly lastMonth = computed(() => {
    const f = this.finances();
    if (!f?.available) return null;
    return [...f.months].reverse().find((m) => m.complete) ?? null;
  });
  readonly ownFields = computed(() => this.fields().filter((f) => f.ownerType === 'PLAYER' || f.leased));
  readonly harvestable = computed(() => this.ownFields().filter((f) => f.phase === 'HARVESTABLE'));
  readonly withered = computed(() => this.ownFields().filter((f) => f.phase === 'WITHERED'));
  readonly fieldsTracked = computed(() => this.ownFields().some((f) => f.phase !== null && f.phase !== undefined));

  constructor() {
    effect(() => {
      this.store.mailVersion();
      this.store.stateVersion();
      const sg = this.store.savegame();
      if (sg) untracked(() => this.load());
    });
  }

  load(): void {
    const safe = <T>(o: Observable<T>, fallback: T) => o.pipe(catchError(() => of(fallback)));
    forkJoin({
      mails: safe(this.api.mails(), [] as MessageView[]),
      loans: safe(this.api.loans(), [] as LoanView[]),
      reputation: safe(this.api.reputation(), null as ReputationView | null),
      finances: safe(this.api.finances(), null as FinanceOverview | null),
      fields: safe(this.api.farmlands(), [] as FarmlandView[]),
    }).subscribe((r) => {
      this.mails.set(r.mails);
      this.loans.set(r.loans);
      this.reputation.set(r.reputation);
      this.finances.set(r.finances);
      this.fields.set(r.fields);
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
    return sg.calendar ? `${day} · ${calendarLabel(sg.calendar, this.i18n)}` : day;
  }

  monthName(period: number): string {
    return this.i18n.t(`enums.period.${period}`);
  }
}
