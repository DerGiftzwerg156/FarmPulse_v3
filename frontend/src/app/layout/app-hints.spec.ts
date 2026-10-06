import { TestBed } from '@angular/core/testing';
import { TranslationService } from '../core/i18n/translation.service';
import { APP_HINTS } from './app-hints';
import { APPS } from './apps';

describe('first-open hints (owner decisions 2026-10-06)', () => {
  it('every app has a hint and every key is translated', () => {
    const i18n = TestBed.inject(TranslationService);
    for (const app of APPS) {
      const hint = APP_HINTS[app.id];
      expect(hint?.length, app.id).toBeGreaterThan(0);
      for (const p of hint) {
        expect(i18n.t(p.text), p.text).not.toBe(p.text);
        if (p.heading) expect(i18n.t(p.heading), p.heading).not.toBe(p.heading);
      }
    }
  });
});
