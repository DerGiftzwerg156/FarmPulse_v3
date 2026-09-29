import { WeatherView } from '../../core/api/models';
import { TranslationService } from '../../core/i18n/translation.service';

/** "12 °C" (one decimal only when needed), '' without a temperature (older mod). */
export function temperatureLabel(w: WeatherView | null | undefined): string {
  if (w?.temperature === null || w?.temperature === undefined) return '';
  return `${new Intl.NumberFormat('de-DE', { maximumFractionDigits: 1 }).format(w.temperature)} °C`;
}

/** Ground wetness as a word: dry below 0.3, moist below 0.6, wet above. */
export function groundLabel(w: WeatherView, i18n: TranslationService): string {
  const key = w.groundWetness >= 0.6 ? 'wet' : w.groundWetness >= 0.3 ? 'moist' : 'dry';
  return i18n.t('weather.ground.' + key);
}

/** "Regen · 12 °C" / "Trocken · 16 °C" for the status bar. */
export function weatherShort(w: WeatherView, i18n: TranslationService): string {
  const t = temperatureLabel(w);
  const state = i18n.t(w.raining ? 'weather.rain' : 'weather.dry');
  return t ? `${state} · ${t}` : state;
}

/** "Regen, 12 °C, Boden nass" for the start screen. */
export function weatherLong(w: WeatherView, i18n: TranslationService): string {
  return [i18n.t(w.raining ? 'weather.rain' : 'weather.dry'), temperatureLabel(w), groundLabel(w, i18n)].filter((s) => s).join(', ');
}
