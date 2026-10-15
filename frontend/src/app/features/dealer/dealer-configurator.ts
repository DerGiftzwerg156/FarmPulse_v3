import { Component, computed, inject, input, OnInit, output, signal } from '@angular/core';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { CartView, CatalogConfiguration, CatalogItem, CatalogOption, QuoteView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { MoneyPipe } from '../../shared/format/format.pipes';
import { formatMoney } from '../../shared/format/format';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { deliveryText, expressText, typeTitle } from './dealer-format';

/**
 * Roadmap vehicle dealer RFH-K2: a machine put together like in the shop of the game. Per type the selectable options
 * with surcharge and daily upkeep (colours as colour fields), the game's default preset; list price, daily upkeep,
 * package of the manufacturer, the options set with another one and the expected delivery come from the backend.
 * Yes/no options stay a selection until the game test shows which option means "yes" (owner decision 2026-10-15).
 * {@code positionId} set = "Ändern" of a cart position ("Übernehmen"), else "In den Warenkorb".
 */
@Component({
  selector: 'app-dealer-configurator',
  imports: [TranslatePipe, MoneyPipe, Button, Card],
  template: `
    <app-card [title]="item()?.name ?? ''" data-testid="configurator">
      @if (item(); as it) {
        @if ((it.configurationSets ?? []).length > 0) {
          <p class="mb-3 text-[13px]" data-testid="package">
            <span class="fp-label">{{ 'dealer.config.package' | t }}</span>
            <span class="ml-2 text-text">{{ quote()?.configurationSet ?? ('dealer.config.individual' | t) }}</span>
          </p>
        }
        <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
          @for (c of types(); track c.name) {
            <div data-testid="config-type" [attr.data-type]="c.name">
              <div class="fp-label mb-1">{{ title(c) }}</div>
              @if (isColor(c)) {
                <div class="flex flex-wrap gap-1.5">
                  @for (o of selectable(c); track o.index) {
                    <button type="button" class="h-7 w-7 rounded-md border-2"
                      [class.border-accent]="current(c) === o.index" [class.border-border]="current(c) !== o.index"
                      [style.background-color]="rgb(o)" [attr.title]="optionLabel(c, o)" [attr.aria-label]="optionLabel(c, o)"
                      (click)="choose(c.name, o.index)" data-testid="color-option"></button>
                  }
                </div>
                <div class="mt-1 text-[12px] text-muted">{{ selectedLabel(c) }}</div>
              } @else {
                <select class="fp-input w-full" [value]="'' + current(c)" (change)="choose(c.name, +$any($event.target).value)"
                  data-testid="config-select">
                  @for (o of selectable(c); track o.index) {
                    <option [value]="'' + o.index" [selected]="current(c) === o.index">{{ optionLabel(c, o) }}</option>
                  }
                </select>
              }
            </div>
          }
        </div>
        @if (setWithText(); as s) {
          <p class="mt-3 text-[12px] text-muted" data-testid="sets-also">{{ 'dealer.config.setsAlso' | t: { list: s } }}</p>
        }
        @if (quote(); as q) {
          <dl class="mt-4 grid grid-cols-1 gap-2 text-[13px] sm:grid-cols-3">
            <div><dt class="fp-label">{{ 'dealer.config.listPrice' | t }}</dt>
              <dd class="font-mono text-text" data-testid="list-price">{{ q.listPrice | money }}</dd></div>
            <div><dt class="fp-label">{{ 'dealer.config.dailyUpkeep' | t }}</dt>
              <dd class="font-mono text-text" data-testid="daily-upkeep">{{ q.dailyUpkeep | money }}</dd></div>
            <div><dt class="fp-label">{{ 'dealer.config.delivery' | t }}</dt>
              <dd class="text-text" data-testid="delivery">{{ delivery() }}</dd>
              <dd class="text-[12px] text-muted" data-testid="express">{{ express() }}</dd></div>
          </dl>
        }
        <p class="mt-2 text-[12px] text-muted">{{ 'dealer.config.deliveryCostsLater' | t }}</p>
        @if (error(); as e) {
          <p class="mt-2 text-[12px] text-danger" data-testid="config-error">{{ e }}</p>
        }
        <div class="mt-4 flex flex-wrap gap-2">
          <app-button [disabled]="busy() || !quote()" (pressed)="save()" data-testid="config-save">
            {{ (positionId() ? 'dealer.config.apply' : 'dealer.config.addToCart') | t }}</app-button>
          <app-button variant="secondary" (pressed)="cancelled.emit()" data-testid="config-cancel">{{ 'dealer.config.cancel' | t }}</app-button>
        </div>
      }
    </app-card>
  `,
})
export class DealerConfigurator implements OnInit {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);

  readonly xmlFilename = input.required<string>();
  /** The stored choice of a cart position ("Ändern"). */
  readonly initialChoice = input<Record<string, number>>({});
  readonly positionId = input<number | null>(null);
  readonly saved = output<CartView>();
  readonly cancelled = output<void>();

  readonly item = signal<CatalogItem | null>(null);
  readonly choice = signal<Record<string, number>>({});
  readonly quote = signal<QuoteView | null>(null);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  /** Types with at least one selectable option, in the order of the catalog. */
  readonly types = computed(() => (this.item()?.configurations ?? []).filter((c) => this.selectable(c).length > 0));
  readonly setWithText = computed(() =>
    (this.quote()?.setWith ?? []).map((s) => `${typeTitle(this.i18n, s.type, s.title)} ${s.option}`).join(', '),
  );
  readonly delivery = computed(() => {
    const q = this.quote();
    return q ? deliveryText(this.i18n, q.delivery) : '';
  });
  readonly express = computed(() => {
    const q = this.quote();
    return q ? expressText(this.i18n, q.delivery) : '';
  });

  ngOnInit(): void {
    this.choice.set({ ...this.initialChoice() });
    this.api.dealerCatalogItem(this.xmlFilename()).subscribe({
      next: (it) => {
        this.item.set(it);
        this.refresh();
      },
      error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  selectable(c: CatalogConfiguration): CatalogOption[] {
    return c.options.filter((o) => o.isSelectable !== false);
  }

  title(c: CatalogConfiguration): string {
    return typeTitle(this.i18n, c.name, c.title);
  }

  isColor(c: CatalogConfiguration): boolean {
    return this.selectable(c).every((o) => o.color != null);
  }

  rgb(o: CatalogOption): string {
    const c = o.color!;
    const ch = (v: number) => Math.round(Math.min(1, Math.max(0, v)) * 255);
    return `rgb(${ch(c.r)}, ${ch(c.g)}, ${ch(c.b)})`;
  }

  /** The option of the configuration (after the options set with another one), else the choice or the default. */
  current(c: CatalogConfiguration): number | undefined {
    return this.quote()?.configurations[c.name] ?? this.choice()[c.name] ?? this.item()?.defaultConfigurations?.[c.name];
  }

  optionLabel(c: CatalogConfiguration, o: CatalogOption): string {
    const parts = [o.name];
    if (o.price) parts.push(this.i18n.t('dealer.config.surcharge', { amount: formatMoney(o.price) }));
    if (o.dailyUpkeep) parts.push(this.i18n.t('dealer.config.upkeep', { amount: formatMoney(o.dailyUpkeep) }));
    if (this.item()?.defaultConfigurations?.[c.name] === o.index) parts.push(this.i18n.t('dealer.config.standard'));
    return parts.join(' ');
  }

  selectedLabel(c: CatalogConfiguration): string {
    const o = c.options.find((x) => x.index === this.current(c));
    return o ? this.optionLabel(c, o) : '';
  }

  choose(type: string, index: number): void {
    this.choice.update((ch) => ({ ...ch, [type]: index }));
    this.refresh();
  }

  private refresh(): void {
    this.error.set(null);
    this.api.dealerConfigure(this.xmlFilename(), this.choice()).subscribe({
      next: (q) => this.quote.set(q),
      error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  save(): void {
    this.busy.set(true);
    this.error.set(null);
    const id = this.positionId();
    const call = id ? this.api.dealerCartChange(id, this.choice()) : this.api.dealerCartAdd(this.xmlFilename(), this.choice());
    call.subscribe({
      next: (cart) => {
        this.busy.set(false);
        this.saved.emit(cart);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
