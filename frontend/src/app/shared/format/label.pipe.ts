import { Pipe, PipeTransform, inject } from '@angular/core';
import { TranslationService } from '../../core/i18n/translation.service';

/** `{{ 'MECHANIC' | label: 'jobRole' }}` -> German label from `enums.jobRole.MECHANIC`, raw value as fallback. */
@Pipe({ name: 'label' })
export class LabelPipe implements PipeTransform {
  private readonly i18n = inject(TranslationService);

  transform(value: string | null | undefined, group: string): string {
    if (!value) {
      return '–';
    }
    const key = `enums.${group}.${value}`;
    return this.i18n.has(key) ? this.i18n.t(key) : value;
  }
}
