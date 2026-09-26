import { CalendarView } from '../../core/api/models';
import { TranslationService } from '../../core/i18n/translation.service';

/**
 * "Oktober, Jahr 2" (TODO T-08). The month name comes from the game (g_i18n:formatPeriod()); without it the period
 * number is mapped to a month (FS25 period 1 = March).
 */
export function calendarLabel(c: CalendarView, i18n: TranslationService): string {
  const month = c.periodName || i18n.t(`enums.period.${c.period}`);
  const label = c.year !== null && c.year !== undefined ? i18n.t('header.calendar', { month, year: c.year }) : month;
  // TODO T-21: season as exported by the game (unknown names are left out)
  const seasonKey = `enums.season.${c.season}`;
  return c.season && i18n.has(seasonKey) ? `${label} · ${i18n.t(seasonKey)}` : label;
}
