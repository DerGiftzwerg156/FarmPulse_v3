import { Component, computed, input } from '@angular/core';
import { TaxCard } from '../bank/tax-card';
import { ServiceCases } from '../contracts/service-cases';
import { AnimalDiseaseCard } from './animal-disease-card';
import { DirectPaymentCard } from './direct-payment-card';
import { InvestmentGrantCard } from './investment-grant-card';

/**
 * Hof-Tablet app "Ämter" (Roadmap V2 R2-E1 / R2-E2): the tax office (estimate, assessment, bills to pay by button,
 * tax advisor) and the agricultural authority (announced inspections, their result and fines; Roadmap V3 R3-W2 the
 * drought aid, applied for by button); Roadmap V3.1 R31-A4 the winter service for the municipality; Roadmap V3.1
 * R31-B the area payment application, the investment grant with its repayments, animal diseases and the
 * Berufsgenossenschaft. One tab per office (owner decision 2026-10-06).
 */
@Component({
  selector: 'app-authorities',
  imports: [TaxCard, ServiceCases, DirectPaymentCard, InvestmentGrantCard, AnimalDiseaseCard],
  template: `
    <!-- Tabs (owner decision 2026-10-06): Finanzamt | Anträge & Förderung | Kontrollen & Tierseuchen | Berufsgenossenschaft | Gemeinde -->
    <section class="flex flex-col gap-4">
      @switch (tab()) {
        @case ('antraege') {
          <div class="grid grid-cols-1 gap-4 xl:grid-cols-2" data-testid="agri-office">
            <!-- Roadmap V3.1 R31-B1 / R31-B2: area payment application and investment grant -->
            <app-direct-payment-card [highlight]="highlightedDirectPayment()" />
            <app-investment-grant-card [highlight]="highlightedGrant()" />
          </div>
          <app-service-cases [caseKinds]="['GRANT_REPAYMENT']" openTitle="authorities.grant.repayments" [showEmpty]="false"
            [highlightCase]="highlightedCase()" testId="grant-repayments" />
          <app-service-cases [caseKinds]="['DROUGHT_AID']" openTitle="authorities.droughtAid" [highlightCase]="highlightedCase()" testId="drought-aid" />
        }
        @case ('kontrollen') {
          <app-service-cases [caseKinds]="['AUTHORITY_INSPECTION']" openTitle="authorities.inspections" [highlightCase]="highlightedCase()" testId="inspections" />
          <!-- Roadmap V3.1 R31-B4: animal diseases and restricted zones -->
          <app-animal-disease-card />
        }
        @case ('berufsgenossenschaft') {
          <!-- Roadmap V3.1 R31-B5: annual bill of the Berufsgenossenschaft -->
          <app-service-cases [caseKinds]="['SOCIAL_INSURANCE_BILL']" openTitle="authorities.socialInsurance.bills"
            [highlightCase]="highlightedCase()" testId="social-insurance-bills" />
        }
        @case ('gemeinde') {
          <!-- Roadmap V3.1 R31-A4: winter service for the municipality -->
          <app-service-cases [contractKinds]="['WINTER_SERVICE']" contractsTitle="authorities.winter.title" [showEmpty]="false" [history]="false"
            [highlightContract]="highlightedContract()" testId="winter-service" />
        }
        @default {
          <div class="grid grid-cols-1 gap-4 xl:grid-cols-2" data-testid="tax-office">
            <app-tax-card />
            <app-service-cases [caseKinds]="['TAX_BILL']" [contractKinds]="['TAX_ADVISOR']" openTitle="authorities.taxBills"
              contractsTitle="authorities.advisor" [highlightCase]="highlightedCase()" [highlightContract]="highlightedContract()" testId="tax-cases" />
          </div>
        }
      }
    </section>
  `,
})
export class Authorities {
  /** Tab of the route `/aemter/:tab`. */
  readonly tab = input<string>('finanzamt');
  readonly contract = input<string>();
  readonly case = input<string>();
  /** Roadmap V3.1 R31-B1 / R31-B2: `?directPayment=` / `?grant=` of a mail link. */
  readonly directPayment = input<string>();
  readonly grant = input<string>();
  readonly highlightedContract = computed(() => Number(this.contract()) || null);
  readonly highlightedCase = computed(() => Number(this.case()) || null);
  readonly highlightedDirectPayment = computed(() => Number(this.directPayment()) || null);
  readonly highlightedGrant = computed(() => Number(this.grant()) || null);
}
