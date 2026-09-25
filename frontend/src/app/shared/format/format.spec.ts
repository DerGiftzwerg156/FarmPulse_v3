import { formatGameTime, formatMoney, formatNumber, gameDay } from './format';

describe('format helpers', () => {
  it('formats money in German', () => {
    expect(formatMoney(1234567).replace(/\s/g, ' ')).toBe('1.234.567 €');
    expect(formatMoney(null)).toBe('–');
  });

  it('formats numbers and game time', () => {
    expect(formatNumber(42000)).toBe('42.000');
    expect(gameDay(86_400_000 * 12 + 5)).toBe(12);
    expect(formatGameTime(86_400_000 * 12 + 8.5 * 3_600_000)).toBe('Tag 12, 08:30');
    expect(formatGameTime(undefined)).toBe('–');
  });
});
