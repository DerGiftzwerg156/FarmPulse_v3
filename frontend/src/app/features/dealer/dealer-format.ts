import { CatalogEntry, DeliveryEstimate } from '../../core/api/models';
import { TranslationService } from '../../core/i18n/translation.service';
import { formatNumber } from '../../shared/format/format';

const MS_PER_HOUR = 60 * 60 * 1000;

/**
 * Display name of a configuration type (owner decision 2026-10-15): the title of the vehicle XML or of the game, then
 * the table of the tablet (`dealer.configTypes`), then the technical name.
 */
export function typeTitle(i18n: TranslationService, name: string, title: string | null | undefined): string {
  if (title) return title;
  const key = `dealer.configTypes.${name}`;
  return i18n.has(key) ? i18n.t(key) : name;
}

/** "150 PS", "150–200 PS" or "–" (power in hp of store_catalog.json). */
export function powerText(i18n: TranslationService, e: Pick<CatalogEntry, 'powerMin' | 'powerMax'>): string {
  if (e.powerMin == null || e.powerMax == null) return '–';
  return e.powerMin === e.powerMax
    ? i18n.t('dealer.catalog.powerValue', { power: e.powerMin })
    : i18n.t('dealer.catalog.powerRange', { min: e.powerMin, max: e.powerMax });
}

type Bound = { kind: 'earliest' } | { kind: 'hours' | 'days'; value: number };

function bound(at: number, d: DeliveryEstimate): Bound {
  if (at === d.earliestAt) return { kind: 'earliest' };
  const hours = (at - d.fromGameTime) / MS_PER_HOUR;
  return hours < 24 ? { kind: 'hours', value: Math.round(hours) } : { kind: 'days', value: Math.round(hours / 2.4) / 10 };
}

function single(i18n: TranslationService, b: Bound): string {
  if (b.kind === 'earliest') return i18n.t('dealer.config.earliest');
  return i18n.t(b.kind === 'hours' ? 'dealer.config.hour' : 'dealer.config.day', { n: formatNumber(b.value, 1) });
}

/**
 * The span of a delivery (owner decision 2026-10-15): "2–4 Spieltage", under a day in hours, a bound at 9:00 of the next
 * game day as "frühestens morgen 9 Uhr".
 */
export function spanText(i18n: TranslationService, d: DeliveryEstimate, minAt: number, maxAt: number): string {
  const lo = bound(minAt, d);
  const hi = bound(maxAt, d);
  if (lo.kind === 'earliest' && hi.kind === 'earliest') return single(i18n, lo);
  if (lo.kind !== 'earliest' && lo.kind === hi.kind) {
    const hiValue = (hi as { value: number }).value;
    if (lo.value === hiValue) return single(i18n, lo);
    return i18n.t(lo.kind === 'hours' ? 'dealer.config.hours' : 'dealer.config.days', {
      min: formatNumber(lo.value, 1),
      max: formatNumber(hiValue, 1),
    });
  }
  return `${single(i18n, lo)} – ${single(i18n, hi)}`;
}

export function deliveryText(i18n: TranslationService, d: DeliveryEstimate): string {
  return spanText(i18n, d, d.minAt, d.maxAt);
}

export function expressText(i18n: TranslationService, d: DeliveryEstimate): string {
  return i18n.t('dealer.config.express', { span: spanText(i18n, d, d.expressMinAt, d.expressMaxAt) });
}
