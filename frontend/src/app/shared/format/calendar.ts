import { CalendarView } from '../../core/api/models';
import { TranslationService } from '../../core/i18n/translation.service';

/**
 * "Oktober, Jahr 2" (TODO T-08). The month name comes from the game (g_i18n:formatPeriod()); without it the period
 * number is mapped to a month (FS25 period 1 = March).
 */
export function calendarLabel(c: CalendarView, i18n: TranslationService): string {
  const month = c.periodName || i18n.t(`enums.period.${c.period}`);
  return c.year !== null && c.year !== undefined ? i18n.t('header.calendar', { month, year: c.year }) : month;
}
