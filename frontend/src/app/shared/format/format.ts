const MS_PER_DAY = 24 * 60 * 60 * 1000;

const money = new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR', maximumFractionDigits: 0 });
const integer = new Intl.NumberFormat('de-DE', { maximumFractionDigits: 0 });

/** 1234567 -> "1.234.567 €" */
export function formatMoney(v: number | null | undefined): string {
  return v === null || v === undefined ? '–' : money.format(v);
}

export function formatNumber(v: number | null | undefined, digits = 0): string {
  if (v === null || v === undefined) {
    return '–';
  }
  return digits === 0 ? integer.format(v) : new Intl.NumberFormat('de-DE', { maximumFractionDigits: digits }).format(v);
}

/** In-game milliseconds -> day index (game day since savegame start). */
export function gameDay(gameTime: number): number {
  return Math.floor(gameTime / MS_PER_DAY);
}

/** In-game milliseconds -> "Tag 12, 08:30". */
export function formatGameTime(gameTime: number | null | undefined): string {
  if (gameTime === null || gameTime === undefined) {
    return '–';
  }
  const inDay = gameTime % MS_PER_DAY;
  const h = Math.floor(inDay / 3_600_000);
  const m = Math.floor((inDay % 3_600_000) / 60_000);
  return `Tag ${gameDay(gameTime)}, ${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

export function hoursBetween(from: number, to: number): number {
  return (to - from) / 3_600_000;
}
