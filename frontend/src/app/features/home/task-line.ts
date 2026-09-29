import { Component, computed, inject, input } from '@angular/core';
import { TaskView } from '../../core/api/models';
import { TranslationService } from '../../core/i18n/translation.service';
import { Icon } from '../../shared/ui/icon';
import { toneClass } from '../../layout/apps';
import { taskApp } from '../../layout/task-apps';
import { dueLabel, isUrgent, taskTitle } from '../tasks/task-groups';

/** One line of the start screen widget "Zu erledigen": app symbol, short title, app and deadline. */
@Component({
  selector: 'app-task-line',
  imports: [Icon],
  template: `
    <div class="flex items-center gap-3 rounded-xl border border-border/70 bg-chrome px-3 py-2.5" data-testid="todo">
      <span class="flex h-8 w-8 shrink-0 items-center justify-center rounded-[9px] bg-[#1A221E]" [class]="tone()">
        <app-icon [name]="app().icon" size="h-[17px] w-[17px]" />
      </span>
      <span class="min-w-0 flex-1">
        <span class="block truncate text-[14px] font-semibold text-text">{{ title() }}</span>
        <span class="block truncate text-[12px] text-[#8FA39A]">{{ appName() }}</span>
      </span>
      <span class="shrink-0 rounded-lg border px-2 font-display text-[11px] font-semibold tracking-[0.06em]"
        [class.border-warn]="urgent()" [class.text-warn]="urgent()" [class.border-border]="!urgent()" [class.text-muted]="!urgent()">{{ due() }}</span>
    </div>
  `,
})
export class TaskCardLine {
  private readonly i18n = inject(TranslationService);
  readonly task = input.required<TaskView>();
  readonly now = input.required<number>();

  readonly app = computed(() => taskApp(this.task()));
  readonly tone = computed(() => toneClass(this.app().tone));
  readonly appName = computed(() => this.i18n.t(this.app().label));
  readonly urgent = computed(() => isUrgent(this.task(), this.now()));
  readonly due = computed(() => dueLabel(this.task(), this.now(), this.i18n));
  readonly title = computed(() => taskTitle(this.task(), this.i18n));
}
