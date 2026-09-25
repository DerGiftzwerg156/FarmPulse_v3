import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { PageError, apiErrorMessage, toPageError } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { ApplicationView, EmployeeView, JobPostingView, NeedsView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { GameTimePipe, MoneyPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { Modal } from '../../shared/ui/modal';
import { PageErrorView } from '../../shared/ui/page-error';

export const JOB_ROLES = ['MACHINE_OPERATOR', 'MECHANIC', 'ANIMAL_KEEPER', 'OFFICE_CLERK'] as const;
export const NEED_KEYS = ['payFairness', 'workload', 'appreciation', 'workingConditions'] as const;
export type NeedKey = (typeof NEED_KEYS)[number];

/** Coarse satisfaction band (0-100 scale of the backend) for the aggregated display. */
export function satisfactionBand(score: number): 'high' | 'ok' | 'low' {
  return score >= 66 ? 'high' : score >= 40 ? 'ok' : 'low';
}

type Panel = { employeeId: number; kind: 'raise' | 'timeOff' } | null;

/**
 * Employees (AP-8.5): job postings with applicants (skill and salary expectation visible, fixed), interview
 * questions by mail or call (they never change skill/salary), hiring; staff list with aggregated and per-category
 * satisfaction, raise, time off and dismissal.
 */
@Component({
  selector: 'app-employees',
  imports: [RouterLink, TranslatePipe, LabelPipe, MoneyPipe, GameTimePipe, Card, Badge, Button, Modal, PageErrorView],
  templateUrl: './employees.html',
})
export class Employees {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);

  /** `?posting=` opens the applicants of a posting (link from an application mail). */
  readonly posting = input<string>();

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
  readonly showFormer = signal(false);

  readonly active = computed(() => (this.employees() ?? []).filter((e) => e.status === 'ACTIVE'));
  readonly former = computed(() => (this.employees() ?? []).filter((e) => e.status !== 'ACTIVE'));
  readonly payroll = computed(() => this.active().reduce((s, e) => s + e.monthlySalary, 0));

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

  hire(postingId: number, a: ApplicationView): void {
    this.api.hire(postingId, a.id).subscribe({
      next: () => {
        this.flash('employees.hired', { name: a.applicant.name });
        this.load();
        this.store.refresh();
      },
      error: (e) => this.fail(e),
    });
  }

  openPanel(e: EmployeeView, kind: 'raise' | 'timeOff'): void {
    const same = this.panel()?.employeeId === e.id && this.panel()?.kind === kind;
    this.panel.set(same ? null : { employeeId: e.id, kind });
    this.panelValue.set(kind === 'raise' ? Math.round(e.monthlySalary * 1.05) : 1);
  }

  submitPanel(e: EmployeeView): void {
    const p = this.panel();
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

  confirmDismiss(): void {
    const e = this.dismissTarget();
    if (!e) return;
    this.api.dismiss(e.id).subscribe({
      next: (updated) => {
        this.replace(updated);
        this.dismissTarget.set(null);
        this.flash('employees.dismissed', { name: e.character.name });
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
