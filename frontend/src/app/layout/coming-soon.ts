import { Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { Card } from '../shared/ui/card';

/** Placeholder until the feature module of Phase 8 is built. */
@Component({
  selector: 'app-coming-soon',
  imports: [Card, TranslatePipe],
  template: `<app-card [title]="title | t"><p class="text-sm text-muted">{{ 'common.comingSoon' | t }}</p></app-card>`,
})
export class ComingSoon {
  readonly title = (inject(ActivatedRoute).snapshot.data['title'] as string) ?? 'app.name';
}
