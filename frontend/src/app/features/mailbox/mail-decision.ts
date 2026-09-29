import { Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TaskView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { GameStateStore } from '../../core/state/game-state.store';
import { TasksStore } from '../../core/state/tasks.store';
import { Icon } from '../../shared/ui/icon';
import { TaskCard } from '../tasks/task-card';

/** Task keys (TaskService) a mail's form link can point to, by query parameter. */
export function taskKeysOf(queryParams: Record<string, string>): string[] {
  const keys: string[] = [];
  if (queryParams['case']) keys.push(`case-${queryParams['case']}`);
  if (queryParams['contract']) keys.push(`contract-${queryParams['contract']}`, `lease-${queryParams['contract']}`);
  if (queryParams['application']) keys.push(`credit-${queryParams['application']}`);
  if (queryParams['event']) keys.push(`market-${queryParams['event']}`);
  if (queryParams['negotiation']) keys.push(`negotiation-${queryParams['negotiation']}`);
  if (queryParams['posting']) keys.push(`posting-${queryParams['posting']}`);
  return keys;
}

/**
 * The decision a mail asks for, right below it: the same card as in "Aufgaben" (amounts only in its form fields).
 * When the decision is already made or expired, only the way into the app remains.
 */
@Component({
  selector: 'app-mail-decision',
  imports: [RouterLink, TranslatePipe, Icon, TaskCard],
  template: `
    @if (task(); as t) {
      <div class="mt-4" data-testid="mail-decision">
        <div class="mb-1.5 font-display text-[11px] font-bold uppercase tracking-[0.16em] text-warn">{{ 'mailbox.decision' | t }}</div>
        <app-task-card [task]="t" [now]="now()" (changed)="changed()" />
      </div>
    } @else {
      <div class="mt-4 flex items-center justify-between gap-3 rounded-xl border border-border bg-bg p-3" data-testid="form-link">
        <p class="font-body text-[12px] text-muted">{{ 'mailbox.decided' | t }}</p>
        <a [routerLink]="link().path" [queryParams]="link().queryParams"
          class="inline-flex shrink-0 items-center gap-1 rounded-lg border border-accent/60 px-3 py-1.5 font-display text-[11px] font-semibold uppercase tracking-[0.12em] text-accent">
          {{ 'mailbox.openInApp' | t }} <app-icon name="arrowRight" size="h-3.5 w-3.5" />
        </a>
      </div>
    }
  `,
})
export class MailDecision {
  private readonly tasks = inject(TasksStore);
  private readonly store = inject(GameStateStore);
  readonly link = input.required<{ path: string; queryParams: Record<string, string> }>();

  readonly now = computed(() => this.store.savegame()?.gameTime ?? 0);
  readonly task = computed<TaskView | null>(() => {
    const keys = taskKeysOf(this.link().queryParams);
    return this.tasks.items().find((t) => keys.includes(t.key)) ?? null;
  });

  changed(): void {
    this.tasks.reload();
    this.store.refresh();
  }
}
