import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PageError } from '../../core/api/api-error';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { Card } from './card';

/** Error state of a feature page; offers the onboarding when no savegame is active yet. */
@Component({
  selector: 'app-page-error',
  imports: [Card, RouterLink, TranslatePipe],
  template: `
    <app-card>
      @if (error().code === 'NO_ACTIVE_SAVEGAME') {
        <p class="text-sm text-muted" data-testid="no-savegame">{{ 'common.noSavegame' | t }}</p>
        <a routerLink="/onboarding" class="mt-2 inline-block fp-label text-accent hover:underline">{{ 'common.startOnboarding' | t }}</a>
      } @else {
        <p class="text-sm text-danger" data-testid="page-error">{{ error().message }}</p>
      }
    </app-card>
  `,
})
export class PageErrorView {
  readonly error = input.required<PageError>();
}
