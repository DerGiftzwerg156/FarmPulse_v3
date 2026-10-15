import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { DealerContactView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { GameStateStore } from '../../core/state/game-state.store';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Card } from '../../shared/ui/card';
import { TrustMeter } from '../village/trust-meter';

/**
 * Hof-Tablet app "Händler" (Roadmap vehicle dealer RFH-H2, owner decisions 2026-10-15): tabs Katalog, Warenkorb,
 * Bestellungen, Lieferorte. Until the areas K, O, B and L fill them, every tab shows the dealer as contact and the hint
 * "Folgt mit dem nächsten Ausbau". Opening the app brings the dealer into an older savegame (RFH-H1).
 */
@Component({
  selector: 'app-dealer',
  imports: [RouterLink, TranslatePipe, LabelPipe, Card, TrustMeter],
  template: `
    <section class="grid grid-cols-1 gap-4 xl:grid-cols-2" [attr.data-tab]="tab()">
      @if (dealer(); as d) {
        <app-card [title]="d.character.role | label: 'characterRole'" data-testid="dealer-contact">
          <div card-actions><app-trust-meter [level]="d.trustLevel" /></div>
          <a routerLink="/village" [queryParams]="{ character: d.character.id }"
            class="font-display text-[13px] text-text hover:text-accent" data-testid="dealer-name">{{ d.character.name }}</a>
        </app-card>
      }
      <app-card [title]="'tabs.dealer.' + tab() | t" data-testid="coming-soon">
        <p class="text-[13px] text-muted">{{ 'dealer.comingSoon' | t }}</p>
      </app-card>
    </section>
  `,
})
export class Dealer {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);

  /** Tab of the route `/haendler/:tab`. */
  readonly tab = input<string>('katalog');
  readonly dealer = signal<DealerContactView | null>(null);

  constructor() {
    effect(() => {
      this.store.stateVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.openDealer().subscribe({ next: (v) => this.dealer.set(v.dealer), error: () => this.dealer.set(null) });
  }
}
