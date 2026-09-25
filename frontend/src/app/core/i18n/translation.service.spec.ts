import { TestBed } from '@angular/core/testing';
import { TranslationService } from './translation.service';
import { TranslatePipe } from './translate.pipe';

describe('TranslationService', () => {
  it('resolves nested keys and interpolates params', () => {
    const s = TestBed.inject(TranslationService);
    expect(s.t('app.name')).toBe('FarmPulse');
    expect(s.t('unknown.key')).toBe('unknown.key');
    expect(s.has('app.subtitle')).toBe(true);
  });

  it('pipe delegates to the service', () => {
    TestBed.runInInjectionContext(() => {
      const pipe = new TranslatePipe();
      expect(pipe.transform('styleGuide.title')).toBe('Style Guide');
    });
  });
});
