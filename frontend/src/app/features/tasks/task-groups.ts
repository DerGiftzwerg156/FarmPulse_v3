import { TaskView } from '../../core/api/models';
import { TranslationService } from '../../core/i18n/translation.service';
import { clockTime, gameDay } from '../../shared/format/format';
import { AppTone } from '../../layout/apps';
import { taskApp } from '../../layout/task-apps';

const DAY = 24 * 3_600_000;
/** "Diese Woche": deadline within this many game days. */
export const WEEK_DAYS = 7;

export type TaskGroup = 'today' | 'week' | 'later';
export type TaskCategory = 'money' | 'farm' | 'village';

/** Today = deadline on the current game day (or ringing call); week = within 7 game days; later = rest / none. */
export function taskGroup(t: TaskView, now: number): TaskGroup {
  if (t.type === 'CALL') return 'today';
  const d = t.deadlineGameTime;
  if (d === null) return 'later';
  if (gameDay(d) <= gameDay(now)) return 'today';
  return d - now <= WEEK_DAYS * DAY ? 'week' : 'later';
}

export function isUrgent(t: TaskView, now: number): boolean {
  const d = t.deadlineGameTime;
  return t.type === 'CALL' || (d !== null && d - now <= 2 * DAY);
}

/** Filter chips: money apps, farm apps, village (communication and calendar). */
export function taskCategory(t: TaskView): TaskCategory {
  const tone: AppTone = taskApp(t).tone;
  return tone === 'money' ? 'money' : tone === 'farm' ? 'farm' : 'village';
}

/** "Heute 18:00", "Tag 49" or "offen" (no deadline). */
export function dueLabel(t: TaskView, now: number, i18n: TranslationService): string {
  const d = t.deadlineGameTime;
  if (d === null) return i18n.t('tasks.noDeadline');
  if (gameDay(d) === gameDay(now)) return i18n.t('tasks.dueToday', { time: clockTime(d) });
  return i18n.t('common.day', { day: gameDay(d) });
}

/** Short title of a task (widget, calendar). */
export function taskTitle(t: TaskView, i18n: TranslationService): string {
  switch (t.type) {
    case 'CASE':
      return i18n.t('enums.caseKind.' + t.kind) + (t.serviceCase?.character ? ' · ' + t.serviceCase.character.name : '');
    case 'CONTRACT_OFFER':
      return i18n.t('tasks.contractOffer', { kind: i18n.t('enums.contractKind.' + t.kind) });
    case 'LEASE_RENEWAL':
      return i18n.t('tasks.leaseEnds', { id: t.contract?.farmlandId });
    case 'CREDIT_COUNTER':
      return i18n.t('tasks.counterOfferShort');
    case 'CALL':
      return i18n.t('tasks.calling', { name: t.call?.character?.name ?? '' });
    case 'NEGOTIATION':
      return i18n.t('enums.negotiationKind.' + t.kind) + ' · ' + i18n.t('farmland.fieldLabel', { id: t.negotiation?.assetId });
    case 'MARKET_OFFER':
      return i18n.t('enums.marketEventType.' + t.kind);
    case 'POSTING':
      return i18n.t('tasks.applicants', { role: i18n.t('enums.jobRole.' + t.kind), n: t.pendingApplicants });
  }
}
