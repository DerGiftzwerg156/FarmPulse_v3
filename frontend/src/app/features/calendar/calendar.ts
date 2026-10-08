import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { AgendaEntryView, CalendarOverviewView, DebitView, TaskView, YearEventView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { TasksStore } from '../../core/state/tasks.store';
import { clockTime, formatMoney, gameDay } from '../../shared/format/format';
import { MoneyPipe } from '../../shared/format/format.pipes';
import { contractAppId, taskApp, taskLink } from '../../layout/task-apps';
import { APPS } from '../../layout/apps';
import { taskTitle } from '../tasks/task-groups';
import { ServiceCases } from '../contracts/service-cases';

const DAY = 24 * 3_600_000;
const AGENDA_DAYS = 7;

export type EventTone = 'farm' | 'money' | 'com' | 'sys';

export interface AgendaLine {
  gameTime: number;
  text: string;
  app: string;
  tone: EventTone;
  link: string | null;
  query: Record<string, number> | null;
}

export interface AgendaDay {
  day: number;
  period: number | null;
  lines: AgendaLine[];
}

/** Colour of a year / agenda entry: festivals green, money amber, family blue, authority grey. */
export function yearTone(kind: string): EventTone {
  if (kind === 'FESTIVAL') return 'farm';
  if (kind.startsWith('TAX_') || kind === 'LOAN_INSTALLMENT' || kind === 'SALARIES' || kind === 'CONTRACT_PAYMENT' || kind === 'MONTH_START') return 'money';
  if (kind.startsWith('FAMILY_') || kind === 'SCHOOL_START' || kind === 'STAMMTISCH') return 'com';
  if (kind.startsWith('COOP_')) return 'money'; // Roadmap V3.1 R31-D7: cooperative
  if (kind.startsWith('BULK_ORDER_')) return 'farm'; // Roadmap V3.2 R32-G3: delivery month of a bulk order
  if (kind === 'INVESTOR_DELIVERY') return 'farm'; // Roadmap V3.2 R32-I4
  if (kind === 'INVESTOR_REPAYMENT') return 'money'; // Roadmap V3.2 R32-I5
  return 'sys';
}

/**
 * Hof-Tablet app "Kalender": the next game days (fixed dates plus the deadlines of open tasks), what is debited at
 * the coming month start and the FS25 year with festivals, tax dates and family occasions.
 */
@Component({
  selector: 'app-calendar',
  imports: [RouterLink, TranslatePipe, MoneyPipe, ServiceCases],
  templateUrl: './calendar.html',
})
export class CalendarApp {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);
  readonly store = inject(GameStateStore);
  private readonly tasks = inject(TasksStore);

  /** `?case=` highlights an invitation (links from mails and "Aufgaben"). */
  /** Tab of the route `/kalender/:tab` (owner decision 2026-10-06): Nächste Tage, Einladungen, Jahr. */
  readonly tab = input<string>('tage');
  readonly case = input<string>();
  readonly highlightedCase = computed(() => Number(this.case()) || null);
  readonly overview = signal<CalendarOverviewView | null>(null);
  readonly failed = signal(false);
  readonly now = computed(() => this.overview()?.gameTime ?? this.store.savegame()?.gameTime ?? 0);
  readonly periods = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];

  constructor() {
    effect(() => {
      this.store.stateVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.calendar().subscribe({
      next: (c) => {
        this.overview.set(c);
        this.failed.set(false);
      },
      error: () => this.failed.set(true),
    });
  }

  /** FS25 period of a game time, derived from the current month and the month length. */
  periodAt(t: number): number | null {
    const o = this.overview();
    if (!o || o.currentPeriod === null) return null;
    if (t < o.nextMonthStart) return o.currentPeriod;
    const monthMs = Math.max(1, o.daysPerPeriod) * DAY;
    const after = Math.floor((t - o.nextMonthStart) / monthMs) + 1;
    return ((o.currentPeriod - 1 + after) % 12) + 1;
  }

  monthName(period: number | null): string {
    return period === null ? '' : this.i18n.t(`enums.period.${period}`);
  }

  private fillType(value: string | null): string {
    if (!value) return '';
    const key = `enums.fillType.${value}`;
    return this.i18n.has(key) ? this.i18n.t(key) : value;
  }

  private entryLine(e: AgendaEntryView): AgendaLine {
    const amount = formatMoney(e.amount);
    const texts: Record<string, () => string> = {
      MONTH_START: () => this.i18n.t('calendar.agenda.monthStart', { month: this.monthName(e.reference ? Number(e.reference) : null) }),
      FESTIVAL: () => this.i18n.t('enums.festival.' + e.reference),
      TAX_ASSESSMENT: () => this.i18n.t('calendar.agenda.taxAssessment'),
      TAX_PREPAYMENT: () => this.i18n.t('calendar.agenda.taxPrepayment'),
      LOAN_INSTALLMENT: () => this.i18n.t('calendar.agenda.loan', { amount, title: e.title ?? '' }),
      SALARIES: () => this.i18n.t('calendar.agenda.salaries', { amount }),
      CONTRACT_PAYMENT: () => this.i18n.t('calendar.agenda.contract', { kind: this.i18n.t('enums.contractKind.' + e.subKind), amount }),
      LEASE_END: () => this.i18n.t('calendar.agenda.leaseEnd', { id: e.reference ?? '' }),
      // Roadmap V3.1 R31-D3 / R31-D7
      STAMMTISCH: () => this.i18n.t('calendar.agenda.stammtisch'),
      COOP_ASSEMBLY: () => this.i18n.t('calendar.agenda.coopAssembly'),
      COOP_BOARD_MEETING: () => this.i18n.t('calendar.agenda.coopBoardMeeting'),
      // Roadmap V3.2 R32-G3: delivery month of a bulk order (title = sell point, reference = fill type)
      BULK_ORDER_START: () => this.i18n.t('calendar.agenda.bulkOrderStart', { fillType: this.fillType(e.reference),
        sellPoint: e.title ?? '', amount }),
      BULK_ORDER_END: () => this.i18n.t('calendar.agenda.bulkOrderEnd', { fillType: this.fillType(e.reference),
        sellPoint: e.title ?? '', quantity: (e.amount ?? 0).toLocaleString('de-DE') }),
      // Roadmap V3.2 R32-I4 / I5: open delivery to an investor (subKind = type), buy-back / repayment
      INVESTOR_DELIVERY: () => this.i18n.t('calendar.agenda.investorDelivery', { name: e.title ?? '',
        remaining: (e.amount ?? 0).toLocaleString('de-DE'), unit: e.subKind === 'A1' ? this.i18n.t('investors.animals') : 'l',
        what: this.fillType(e.reference) }),
      INVESTOR_REPAYMENT: () => this.i18n.t('calendar.agenda.investorRepayment', { name: e.title ?? '', amount }),
    };
    const contractApp = e.subKind ? APPS.find((a) => a.id === contractAppId(e.subKind!)) : undefined;
    const apps: Record<string, [string, string | null]> = {
      MONTH_START: ['nav.bank', '/bank'], FESTIVAL: ['calendar.clubs', null], TAX_ASSESSMENT: ['calendar.taxOffice', '/aemter'],
      TAX_PREPAYMENT: ['calendar.taxOffice', '/aemter'], LOAN_INSTALLMENT: ['nav.bank', '/bank'], SALARIES: ['nav.employees', '/employees'],
      CONTRACT_PAYMENT: [contractApp?.label ?? 'nav.bank', contractApp?.path ?? null], LEASE_END: ['nav.farmland', '/farmland'],
      STAMMTISCH: ['nav.village', null], COOP_ASSEMBLY: ['nav.trade', '/handel'], COOP_BOARD_MEETING: ['nav.trade', '/handel'],
      BULK_ORDER_START: ['nav.trade', '/handel/grossauftraege'], BULK_ORDER_END: ['nav.trade', '/handel/grossauftraege'],
      INVESTOR_DELIVERY: ['nav.bank', '/bank/investoren'], INVESTOR_REPAYMENT: ['nav.bank', '/bank/investoren'],
    };
    const [app, link] = apps[e.kind] ?? ['nav.calendar', null];
    return { gameTime: e.gameTime, text: (texts[e.kind] ?? (() => e.kind))(), app: this.i18n.t(app), tone: yearTone(e.kind), link, query: null };
  }

  private taskLine(t: TaskView): AgendaLine {
    const app = taskApp(t);
    const l = taskLink(t);
    return {
      gameTime: t.deadlineGameTime!, text: this.i18n.t('calendar.agenda.deadline', { what: taskTitle(t, this.i18n) }),
      app: this.i18n.t(app.label), tone: app.tone === 'money' ? 'money' : app.tone === 'farm' ? 'farm' : app.tone === 'com' ? 'com' : 'sys',
      link: l.path, query: l.query,
    };
  }

  /** Next game days: fixed dates of the calendar plus the task deadlines inside the window, grouped by day. */
  readonly agenda = computed<AgendaDay[]>(() => {
    const o = this.overview();
    if (!o) return [];
    const until = o.gameTime + AGENDA_DAYS * DAY;
    const lines = [
      ...o.agenda.map((e) => this.entryLine(e)),
      ...this.tasks.items().filter((t) => t.type !== 'LEASE_RENEWAL' && t.deadlineGameTime !== null && t.deadlineGameTime <= until)
        .map((t) => this.taskLine(t)),
    ].sort((a, b) => a.gameTime - b.gameTime);
    const days: AgendaDay[] = [];
    for (const l of lines) {
      const day = gameDay(Math.max(l.gameTime, o.gameTime));
      let d = days.find((x) => x.day === day);
      if (!d) {
        d = { day, period: this.periodAt(l.gameTime), lines: [] };
        days.push(d);
      }
      d.lines.push(l);
    }
    return days;
  });

  day(t: number): number {
    return gameDay(t);
  }

  isToday(day: number): boolean {
    return day === gameDay(this.now());
  }

  time(t: number): string {
    return clockTime(t);
  }

  debitLabel(d: DebitView): string {
    switch (d.kind) {
      case 'SALARIES': return this.i18n.t('calendar.debits.salaries', { n: d.count });
      case 'LOAN': return this.i18n.t('calendar.debits.loan', { title: d.label ?? '' });
      case 'CONTRACT':
        return this.i18n.t('enums.contractKind.' + d.subKind) + (d.subKind === 'LEASE' && d.label ? ' · ' + this.i18n.t('contracts.field', { id: d.label }) : '');
      case 'RETIREMENT': return this.i18n.t('calendar.debits.retirement');
      default: return d.kind;
    }
  }

  readonly balanceAfter = computed(() => {
    const o = this.overview();
    const sg = this.store.savegame();
    return o && sg ? sg.balance - o.monthStartTotal : null;
  });

  /** Tax bills are paid by button, not debited: shown separately under the month start. */
  readonly selfPay = computed(() => this.tasks.items().filter((t) => t.type === 'CASE' && t.kind === 'TAX_BILL'));

  yearEvents(period: number): YearEventView[] {
    return (this.overview()?.yearEvents ?? []).filter((e) => e.period === period);
  }

  yearText(e: YearEventView): string {
    switch (e.kind) {
      case 'FESTIVAL': return this.i18n.t('enums.festival.' + e.reference);
      case 'FAMILY_BIRTHDAY': return this.i18n.t('calendar.year.birthday', { name: e.reference ?? '' });
      case 'FAMILY_WEDDING_DAY': return this.i18n.t('calendar.year.weddingDay');
      case 'SCHOOL_START': return this.i18n.t('calendar.year.schoolStart', { name: e.reference ?? '' });
      default: return this.i18n.t('calendar.year.' + e.kind);
    }
  }

  tone(kind: string): EventTone {
    return yearTone(kind);
  }
}
