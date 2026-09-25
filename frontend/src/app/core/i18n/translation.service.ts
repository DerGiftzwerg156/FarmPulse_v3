import { Injectable, signal } from '@angular/core';
import de from './de.json';

interface Dictionary {
  [key: string]: string | Dictionary;
}

/**
 * Central i18n mechanism: every UI text is looked up by key (no hard-coded German strings in templates).
 * V1 ships only `de` (functional concept decision); further languages = another JSON file + registration here.
 */
@Injectable({ providedIn: 'root' })
export class TranslationService {
  private readonly dictionaries: Record<string, Dictionary> = { de: de as Dictionary };
  readonly locale = signal<'de'>('de');

  t(key: string, params?: Record<string, string | number | null | undefined>): string {
    const value = key
      .split('.')
      .reduce<string | Dictionary | undefined>(
        (node, part) => (node && typeof node === 'object' ? node[part] : undefined),
        this.dictionaries[this.locale()],
      );
    if (typeof value !== 'string') {
      return key;
    }
    return value.replace(/\{(\w+)\}/g, (_, p: string) => {
      const v = params?.[p];
      return v === null || v === undefined ? '' : String(v);
    });
  }

  has(key: string): boolean {
    return this.t(key) !== key;
  }
}
