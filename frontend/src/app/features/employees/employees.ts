import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { PageError, apiErrorMessage, toPageError } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { ApplicationView, EmployeeView, JobPostingView, NeedsView, TrainingOfferView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { gameDay, formatMoney } from '../../shared/format/format';
import { GameTimePipe, MoneyPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { Modal } from '../../shared/ui/modal';
import { PageErrorView } from '../../shared/ui/page-error';
import { ServiceCases } from '../contracts/service-cases';

export const JOB_ROLES = ['MACHINE_OPERATOR', 'MECHANIC', 'ANIMAL_KEEPER', 'OFFICE_CLERK', 'APPRENTICE', 'SEASONAL_WORKER'] as const;

export const NEED_KEYS = ['payFairness', 'workload', 'appreciation', 'workingConditions'] as const;
export type NeedKey = (typeof NEED_KEYS)[number];

/** Coarse satisfaction band (0-100 scale of the backend) for the aggregated display. */
export function satisfactionBand(score: number): 'high' | 'ok' | 'low' {
  return score >= 66 ? 'high' : score >= 40 ? 'ok' : 'low';
}

type PanelKind = 'raise' | 'timeOff' | 'training';
type Panel = { employeeId: number; kind: PanelKind } | null;

/**
 * Employees (AP-8.5): job postings with applicants (skill and salary expectation visible, fixed), interview
 * questions by mail or call (they never change skill/salary), hiring; staff list with aggregated and per-category
 * satisfaction, raise, time off and dismissal. "Schulungen": machine operators show their trainings and can be sent to a
 * paid training (the whole next game day away); applicants show a training they bring along. Tabs (owner decision
 * 2026-10-06): Team, Stellen & Bewerber, Ehemalige. Owner decisions 2026-10-06: applications arrive the next game day, a
 * hired employee starts with the next month (shown in the team, no actions, cancelling costs a severance).
 */
@Component({
  selector: 'app-employees',
  imports: [RouterLink, TranslatePipe, LabelPipe, MoneyPipe, GameTimePipe, Card, Badge, Button, Modal, PageErrorView,
    ServiceCases],
  templateUrl: './employees.html',
})
export class Employees {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  /** `?posting=` opens the applicants of a posting (link from an application mail). */
  readonly posting = input<string>();
  /** Tab of the route `/employees/:tab`. */
  readonly tab = input<string>('team');
  /** R3-P2: `?case=` highlights the takeover request of an apprentice. */
  readonly case = input<string>();
  readonly highlightedCase = computed(() => Number(this.case()) || null);

  readonly roles = JOB_ROLES;
  readonly needKeys = NEED_KEYS;
  readonly employees = signal<EmployeeView[] | null>(null);
  readonly postings = signal<JobPostingView[] | null>(null);
  readonly error = signal<PageError | null>(null);
  readonly openPosting = signal<number | null>(null);
  readonly applications = signal<ApplicationView[] | null>(null);
  readonly newRole = signal<string>(JOB_ROLES[0]);
  readonly message = signal<string | null>(null);
  readonly actionError = signal<string | null>(null);
  readonly panel = signal<Panel>(null);
  readonly panelValue = signal(0);
  readonly dismissTarget = signal<EmployeeView | null>(null);
  readonly interviewFor = signal<number | null>(null);
  readonly interviewText = signal('');
  readonly interviewChannel = signal<'MAIL' | 'CALL'>('MAIL');
  /** "Schulungen": catalog (price per training), loaded when the training panel opens the first time. */
  readonly trainingCatalog = signal<TrainingOfferView[] | null>(null);
  readonly panelTraining = signal<string | null>(null);

  /** The team: working employees and hired ones who start next month. */
  readonly active = computed(() => (this.employees() ?? []).filter((e) => e.status === 'ACTIVE' || e.status === 'PENDING_START'));
  readonly former = computed(() => (this.employees() ?? []).filter((e) => e.status === 'TERMINATED'));
  readonly payroll = computed(() => this.active().filter((e) => e.status === 'ACTIVE').reduce((s, e) => s + e.monthlySalary, 0));
  readonly now = computed(() => this.store.savegame()?.gameTime ?? 0);

  constructor() {
    effect(() => {
      this.store.stateVersion();
      this.store.mailVersion();
      untracked(() => this.load());
    });
    effect(() => {
      const id = Number(this.posting());
      if (id) untracked(() => this.togglePosting(id, true));
    });
  }

  load(): void {
    forkJoin({ employees: this.api.employees(), postings: this.api.jobPostings() }).subscribe({
      next: ({ employees, postings }) => {
        this.employees.set(employees);
        this.postings.set(postings);
        this.error.set(null);
        const open = this.openPosting();
        if (open !== null) this.loadApplications(open);
      },
      error: (e) => this.error.set(toPageError(e, this.i18n.t('common.error'))),
    });
  }

  needs(e: EmployeeView, key: NeedKey): number {
    return (e.needs as NeedsView)[key];
  }

  band(score: number): 'high' | 'ok' | 'low' {
    return satisfactionBand(score);
  }

  createPosting(): void {
    this.api.createJobPosting(this.newRole()).subscribe({
      next: (p) => {
        this.postings.update((list) => [p, ...(list ?? [])]);
        this.flash('employees.postingCreated');
        this.togglePosting(p.id, true);
      },
      error: (e) => this.fail(e),
    });
  }

  togglePosting(id: number, forceOpen = false): void {
    if (this.openPosting() === id && !forceOpen) {
      this.openPosting.set(null);
      return;
    }
    this.openPosting.set(id);
    this.applications.set(null);
    this.loadApplications(id);
  }

  private loadApplications(id: number): void {
    this.api.applications(id).subscribe({
      next: (a) => {
        if (this.openPosting() === id) this.applications.set(a);
      },
      error: (e) => this.fail(e),
    });
  }

  startInterview(appId: number): void {
    this.interviewFor.set(this.interviewFor() === appId ? null : appId);
    this.interviewText.set('');
  }

  sendInterview(postingId: number, a: ApplicationView): void {
    const q = this.interviewText().trim();
    if (!q) return;
    this.api.interviewQuestion(postingId, a.id, q, this.interviewChannel()).subscribe({
      next: () => {
        this.interviewFor.set(null);
        this.flash(this.interviewChannel() === 'CALL' ? 'employees.interviewSentCall' : 'employees.interviewSentMail');
      },
      error: (e) => this.fail(e),
    });
  }

  /** "1. April (Tag 12)": first working day of a hired employee who has not started yet. */
  startLabel(e: EmployeeView): string {
    if (e.startsAtGameTime === null || e.startsAtGameTime === undefined) return '';
    const day = this.i18n.t('common.day', { day: gameDay(e.startsAtGameTime) });
    return e.startsAtPeriod ? `1. ${this.i18n.t(`enums.period.${e.startsAtPeriod}`)} (${day})` : day;
  }

  pending(e: EmployeeView): boolean {
    return e.status === 'PENDING_START';
  }

  /** The booked training lies ahead (the next game day). */
  trainingScheduled(e: EmployeeView): boolean {
    return !!e.trainingInProgress && !!e.trainingFromGameTime && e.trainingFromGameTime > this.now();
  }

  trainingDay(e: EmployeeView): string {
    return e.trainingFromGameTime ? this.i18n.t('common.day', { day: gameDay(e.trainingFromGameTime) }) : '';
  }

  severance(e: EmployeeView | null): string {
    return formatMoney(e?.severance ?? 0);
  }

  hire(postingId: number, a: ApplicationView): void {
    this.api.hire(postingId, a.id).subscribe({
      next: (e) => {
        if (e.status === 'PENDING_START') this.flash('employees.hiredFrom', { name: a.applicant.name, at: this.startLabel(e) });
        else this.flash('employees.hired', { name: a.applicant.name });
        this.load();
        this.store.refresh();
      },
      error: (e) => this.fail(e),
    });
  }

  openPanel(e: EmployeeView, kind: PanelKind): void {
    const same = this.panel()?.employeeId === e.id && this.panel()?.kind === kind;
    this.panel.set(same ? null : { employeeId: e.id, kind });
    this.panelValue.set(kind === 'raise' ? Math.round(e.monthlySalary * 1.05) : 1);
    if (kind === 'training' && !same) {
      this.panelTraining.set(this.openTrainings(e)[0]?.training ?? null);
      if (this.trainingCatalog() === null) {
        this.api.trainings().subscribe({
          next: (c) => {
            this.trainingCatalog.set(c);
            if (this.panelTraining() === null) this.panelTraining.set(this.openTrainings(e)[0]?.training ?? null);
          },
          error: (err) => this.fail(err),
        });
      }
    }
  }

  /** Trainings of the catalog the employee does not have yet. */
  openTrainings(e: EmployeeView): TrainingOfferView[] {
    return (this.trainingCatalog() ?? []).filter((t) => !e.trainings.includes(t.training));
  }

  trainingCost(training: string | null): number {
    return this.trainingCatalog()?.find((t) => t.training === training)?.cost ?? 0;
  }

  submitTraining(e: EmployeeView): void {
    const training = this.panelTraining();
    if (!training) return;
    this.api.bookTraining(e.id, training).subscribe({
      next: (updated) => {
        this.replace(updated);
        this.panel.set(null);
        this.flash('employees.trainingBooked', { name: e.character.name, training: this.i18n.t(`enums.training.${training}`) });
        this.store.refresh();
      },
      error: (err) => this.fail(err),
    });
  }

  submitPanel(e: EmployeeView): void {
    const p = this.panel();
    if (p?.kind === 'training') {
      this.submitTraining(e);
      return;
    }
    const v = Number(this.panelValue());
    if (!p || !Number.isFinite(v) || v <= 0) return;
    const obs = p.kind === 'raise' ? this.api.raise(e.id, Math.round(v)) : this.api.timeOff(e.id, Math.round(v));
    obs.subscribe({
      next: (updated) => {
        this.replace(updated);
        this.panel.set(null);
        this.flash(p.kind === 'raise' ? 'employees.raised' : 'employees.timeOffGranted', { name: e.character.name });
      },
      error: (err) => this.fail(err),
    });
  }

  /** Roadmap V3.1 R31-B5: get-well wishes (once per absence). */
  getWell(e: EmployeeView): void {
    this.api.getWell(e.id).subscribe({
      next: (updated) => {
        this.replace(updated);
        this.flash('employees.absence.getWellSent', { name: e.character.name });
      },
      error: (err) => this.fail(err),
    });
  }

  confirmDismiss(): void {
    const e = this.dismissTarget();
    if (!e) return;
    this.api.dismiss(e.id).subscribe({
      next: (updated) => {
        this.replace(updated);
        this.dismissTarget.set(null);
        this.flash(this.pending(e) ? 'employees.cancelled' : 'employees.dismissed', { name: e.character.name });
        this.store.refresh();
      },
      error: (err) => {
        this.dismissTarget.set(null);
        this.fail(err);
      },
    });
  }

  private replace(e: EmployeeView): void {
    this.employees.update((list) => (list ?? []).map((x) => (x.id === e.id ? e : x)));
  }

  private flash(key: string, params?: Record<string, string>): void {
    this.actionError.set(null);
    this.message.set(this.i18n.t(key, params));
  }

  private fail(e: unknown): void {
    this.message.set(null);
    this.actionError.set(apiErrorMessage(e, this.i18n.t('common.error')));
  }
}
