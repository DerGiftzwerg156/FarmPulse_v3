import { Component, computed, input } from '@angular/core';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TaxCard } from '../bank/tax-card';
import { ServiceCases } from '../contracts/service-cases';

/**
 * Hof-Tablet app "Ämter" (Roadmap V2 R2-E1 / R2-E2): the tax office (estimate, assessment, bills to pay by button,
 * tax advisor) and the agricultural authority (announced inspections, their result and fines; Roadmap V3 R3-W2 the
 * drought aid, applied for by button); Roadmap V3.1 R31-A4 the winter service for the municipality.
 */
@Component({
  selector: 'app-authorities',
  imports: [TranslatePipe, TaxCard, ServiceCases],
  template: `
    <section class="flex flex-col gap-6">
      <div data-testid="tax-office">
        <h2 class="mb-3 font-display text-[12px] font-bold uppercase tracking-[0.18em] text-app-money">{{ 'authorities.taxOffice' | t }}</h2>
        <div class="grid grid-cols-1 gap-4 xl:grid-cols-2">
          <app-tax-card />
          <app-service-cases [caseKinds]="['TAX_BILL']" [contractKinds]="['TAX_ADVISOR']" openTitle="authorities.taxBills"
            contractsTitle="authorities.advisor" [highlightCase]="highlightedCase()" [highlightContract]="highlightedContract()" testId="tax-cases" />
        </div>
      </div>
      <div data-testid="agri-office">
        <h2 class="mb-3 font-display text-[12px] font-bold uppercase tracking-[0.18em] text-app-sys">{{ 'authorities.agriOffice' | t }}</h2>
        <app-service-cases [caseKinds]="['AUTHORITY_INSPECTION']" openTitle="authorities.inspections" [highlightCase]="highlightedCase()" testId="inspections">
          <p open-intro class="mb-3 text-[12px] text-muted">{{ 'authorities.inspectionsIntro' | t }}</p>
        </app-service-cases>
        <div class="mt-4">
          <app-service-cases [caseKinds]="['DROUGHT_AID']" openTitle="authorities.droughtAid" [highlightCase]="highlightedCase()" testId="drought-aid">
            <p open-intro class="mb-3 text-[12px] text-muted">{{ 'authorities.droughtAidIntro' | t }}</p>
          </app-service-cases>
        </div>
      </div>
      <div data-testid="municipality">
        <!-- Roadmap V3.1 R31-A4: winter service for the municipality -->
        <h2 class="mb-3 font-display text-[12px] font-bold uppercase tracking-[0.18em] text-app-sys">{{ 'authorities.municipality' | t }}</h2>
        <app-service-cases [contractKinds]="['WINTER_SERVICE']" contractsTitle="authorities.winter.title" [showEmpty]="false" [history]="false"
          [highlightContract]="highlightedContract()" testId="winter-service">
          <p contracts-intro class="mb-3 text-[12px] text-muted">{{ 'authorities.winter.intro' | t }}</p>
        </app-service-cases>
      </div>
    </section>
  `,
})
export class Authorities {
  readonly contract = input<string>();
  readonly case = input<string>();
  readonly highlightedContract = computed(() => Number(this.contract()) || null);
  readonly highlightedCase = computed(() => Number(this.case()) || null);
}
