import { Component, inject, OnInit, output, signal } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { CartView, CatalogEntry, CatalogFilterValue, CatalogQuery, CatalogView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { MoneyPipe } from '../../shared/format/format.pipes';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { DealerConfigurator } from './dealer-configurator';
import { powerText } from './dealer-format';

type SortKey = 'CATEGORY' | 'NAME' | 'PRICE' | 'POWER';

/**
 * Roadmap vehicle dealer RFH-K1 (owner decisions 2026-10-15): the whole vehicle catalog of the shop - search by name,
 * filter by category, brand and price range; sorted by category and name, name / base price / power on click; 50 per
 * page. "Konfigurieren" opens the configurator (RFH-K2).
 */
@Component({
  selector: 'app-dealer-catalog',
  imports: [TranslatePipe, MoneyPipe, Badge, Button, Card, DealerConfigurator],
  template: `
    @if (configuring(); as xml) {
      <app-dealer-configurator [xmlFilename]="xml" (saved)="onSaved($event)" (cancelled)="configuring.set(null)" />
    } @else {
      <app-card data-testid="catalog">
        @if (view(); as v) {
          @if (!v.available) {
            <p class="text-[13px] text-muted" data-testid="catalog-missing">{{ 'dealer.catalog.missing' | t }}</p>
          } @else if (v.page; as p) {
            <div class="mb-3 grid grid-cols-1 gap-2 sm:grid-cols-2 xl:grid-cols-5">
              <input class="fp-input" type="search" [placeholder]="'dealer.catalog.search' | t" [value]="text()"
                (input)="text.set($any($event.target).value); reload(0)" data-testid="catalog-search" />
              <label class="flex flex-col gap-1"><span class="fp-label">{{ 'dealer.catalog.category' | t }}</span>
                <select class="fp-input" [value]="category()" (change)="category.set($any($event.target).value); reload(0)"
                  data-testid="catalog-category">
                  <option value="">{{ 'dealer.catalog.all' | t }}</option>
                  @for (c of p.categories; track c.name) {
                    <option [value]="c.name" [selected]="category() === c.name">{{ label(c) }}</option>
                  }
                </select></label>
              <label class="flex flex-col gap-1"><span class="fp-label">{{ 'dealer.catalog.brand' | t }}</span>
                <select class="fp-input" [value]="brand()" (change)="brand.set($any($event.target).value); reload(0)"
                  data-testid="catalog-brand">
                  <option value="">{{ 'dealer.catalog.all' | t }}</option>
                  @for (b of p.brands; track b.name) {
                    <option [value]="b.name" [selected]="brand() === b.name">{{ label(b) }}</option>
                  }
                </select></label>
              <label class="flex flex-col gap-1"><span class="fp-label">{{ 'dealer.catalog.priceFrom' | t }}</span>
                <input class="fp-input" type="number" min="0" [value]="priceMin()"
                  (change)="priceMin.set($any($event.target).value); reload(0)" data-testid="catalog-price-min" /></label>
              <label class="flex flex-col gap-1"><span class="fp-label">{{ 'dealer.catalog.priceTo' | t }}</span>
                <input class="fp-input" type="number" min="0" [value]="priceMax()"
                  (change)="priceMax.set($any($event.target).value); reload(0)" data-testid="catalog-price-max" /></label>
            </div>
            <div class="overflow-x-auto">
              <table class="w-full text-left text-[13px]">
                <thead>
                  <tr class="fp-label">
                    <th class="py-1 pr-3">{{ 'dealer.catalog.category' | t }}</th>
                    <th class="py-1 pr-3">{{ 'dealer.catalog.brand' | t }}</th>
                    <th class="py-1 pr-3"><button type="button" class="hover:text-text" (click)="sortBy('NAME')" data-testid="sort-name">
                      {{ 'dealer.catalog.name' | t }}{{ arrow('NAME') }}</button></th>
                    <th class="py-1 pr-3 text-right"><button type="button" class="hover:text-text" (click)="sortBy('PRICE')" data-testid="sort-price">
                      {{ 'dealer.catalog.basePrice' | t }}{{ arrow('PRICE') }}</button></th>
                    <th class="py-1 pr-3 text-right"><button type="button" class="hover:text-text" (click)="sortBy('POWER')" data-testid="sort-power">
                      {{ 'dealer.catalog.power' | t }}{{ arrow('POWER') }}</button></th>
                    <th class="py-1 pr-3 text-right">{{ 'dealer.catalog.dailyUpkeep' | t }}</th>
                    <th class="py-1"></th>
                  </tr>
                </thead>
                <tbody>
                  @for (e of p.items; track e.xmlFilename) {
                    <tr class="border-t border-border" data-testid="catalog-entry">
                      <td class="py-1.5 pr-3 text-muted">{{ e.categoryTitle ?? e.categoryName ?? '–' }}</td>
                      <td class="py-1.5 pr-3 text-muted">{{ e.brandTitle ?? e.brand ?? '–' }}</td>
                      <td class="py-1.5 pr-3 text-text">{{ e.name }}
                        @if (e.isMod) { <app-badge data-testid="mod-badge">{{ 'dealer.catalog.mod' | t }}</app-badge> }</td>
                      <td class="py-1.5 pr-3 text-right font-mono">{{ e.price | money }}</td>
                      <td class="py-1.5 pr-3 text-right font-mono" data-testid="power">{{ power(e) }}</td>
                      <td class="py-1.5 pr-3 text-right font-mono">{{ e.dailyUpkeep | money }}</td>
                      <td class="py-1.5 text-right">
                        <app-button variant="secondary" (pressed)="configuring.set(e.xmlFilename)" data-testid="configure">
                          {{ 'dealer.catalog.configure' | t }}</app-button></td>
                    </tr>
                  } @empty {
                    <tr><td colspan="7" class="py-3 text-muted" data-testid="catalog-empty">{{ 'dealer.catalog.empty' | t }}</td></tr>
                  }
                </tbody>
              </table>
            </div>
            @if (p.total > p.pageSize) {
              <div class="mt-3 flex items-center justify-end gap-2">
                <app-button variant="secondary" [disabled]="p.page === 0" (pressed)="reload(p.page - 1)" data-testid="page-prev">
                  {{ 'dealer.catalog.prev' | t }}</app-button>
                <span class="fp-label">{{ p.page + 1 }} / {{ pages(p.total, p.pageSize) }}</span>
                <app-button variant="secondary" [disabled]="p.page + 1 >= pages(p.total, p.pageSize)" (pressed)="reload(p.page + 1)"
                  data-testid="page-next">{{ 'dealer.catalog.next' | t }}</app-button>
              </div>
            }
          }
        }
      </app-card>
    }
  `,
})
export class DealerCatalog implements OnInit {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);

  /** A machine was put into the cart. */
  readonly added = output<CartView>();

  readonly view = signal<CatalogView | null>(null);
  readonly configuring = signal<string | null>(null);
  readonly text = signal('');
  readonly category = signal('');
  readonly brand = signal('');
  readonly priceMin = signal('');
  readonly priceMax = signal('');
  readonly sort = signal<SortKey>('CATEGORY');
  readonly desc = signal(false);

  ngOnInit(): void {
    this.reload(0);
  }

  reload(page: number): void {
    const q: CatalogQuery = {
      text: this.text() || undefined,
      category: this.category() || undefined,
      brand: this.brand() || undefined,
      priceMin: this.priceMin() === '' ? undefined : Number(this.priceMin()),
      priceMax: this.priceMax() === '' ? undefined : Number(this.priceMax()),
      sort: this.sort(),
      desc: this.desc(),
      page,
    };
    this.api.dealerCatalog(q).subscribe({ next: (v) => this.view.set(v), error: () => this.view.set(null) });
  }

  sortBy(key: SortKey): void {
    if (this.sort() === key) {
      this.desc.update((d) => !d);
    } else {
      this.sort.set(key);
      this.desc.set(false);
    }
    this.reload(0);
  }

  arrow(key: SortKey): string {
    return this.sort() === key ? (this.desc() ? ' ↓' : ' ↑') : '';
  }

  label(v: CatalogFilterValue): string {
    return v.title ?? v.name;
  }

  power(e: CatalogEntry): string {
    return powerText(this.i18n, e);
  }

  pages(total: number, size: number): number {
    return Math.max(1, Math.ceil(total / size));
  }

  onSaved(cart: CartView): void {
    this.configuring.set(null);
    this.added.emit(cart);
  }
}
