import { Component, computed, input } from '@angular/core';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { ServiceCases } from '../contracts/service-cases';

/**
 * Hof-Tablet app "Stall": everything around the animals - trader offers, vet visits and breeding advice.
 */
@Component({
  selector: 'app-stable',
  imports: [TranslatePipe, ServiceCases],
  template: `
    <section class="flex flex-col gap-4">
      <app-service-cases [caseKinds]="['LIVESTOCK_OFFER', 'VET_VISIT', 'BREEDING_ADVICE']" openTitle="stable.trade"
        [highlightCase]="highlightedCase()" testId="stable-cases">
        <p open-intro class="mb-3 text-[12px] text-muted">{{ 'contracts.livestockHint' | t }}</p>
      </app-service-cases>
    </section>
  `,
})
export class Stable {
  readonly case = input<string>();
  readonly highlightedCase = computed(() => Number(this.case()) || null);
}
