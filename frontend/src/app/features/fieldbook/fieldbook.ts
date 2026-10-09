import { NgTemplateOutlet } from '@angular/common';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { PageError, apiErrorMessage, toPageError } from '../../core/api/api-error';
import {
  FieldBookCropView,
  FieldBookEntryView,
  FieldBookFieldView,
  FieldBookValueKey,
  FieldBookView,
} from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge, BadgeVariant } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { PageErrorView } from '../../shared/ui/page-error';
import { Observable } from 'rxjs';

/** The ticks of an entry in table order; lime and weeds only while the soil rule is on (owner decision 2026-10-08). */
const TICKS: { key: FieldBookValueKey; col: string; rule?: 'lime' | 'weeds' }[] = [
  { key: 'FERT1', col: 'fert1' },
  { key: 'FERT2', col: 'fert2' },
  { key: 'LIMED', col: 'limed', rule: 'lime' },
  { key: 'ROLLED', col: 'rolled' },
  { key: 'WEEDS', col: 'weeds', rule: 'weeds' },
  { key: 'MULCHED', col: 'mulched' },
];

const STATE_BADGE: Record<string, BadgeVariant> = {
  ACTIVE: 'positive',
  LEASED_OUT: 'neutral',
  LEASE_ENDED: 'neutral',
  SOLD: 'neutral',
};

/**
 * Roadmap V3.3 R33-F4 / F5: app "Feldbuch", tab "Dokumentation". Per field the running season and the entries per
 * harvest year; every value can be corrected ("manuell") and given back to the detection (↺); "Ernte eintragen" when
 * the game does not detect a harvest; closing and reopening a harvest year. The tab "Auswertung" follows with E.
 */
@Component({
  selector: 'app-fieldbook',
  imports: [
    NgTemplateOutlet,
    TranslatePipe,
    LabelPipe,
    NumberPipe,
    Badge,
    Button,
    Card,
    PageErrorView,
  ],
  templateUrl: './fieldbook.html',
})
export class FieldBook {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);
  /** Tab of the route `/feldbuch/:tab`. */
  readonly tab = input<string>('dokumentation');

  readonly view = signal<FieldBookView | null>(null);
  readonly error = signal<PageError | null>(null);
  readonly actionError = signal<string | null>(null);
  readonly busy = signal(false);
  readonly year = signal<number | null>(null);

  readonly ticks = computed(() => {
    const v = this.view();
    return TICKS.filter((t) => !t.rule || (t.rule === 'lime' ? v?.showLime : v?.showWeeds));
  });
  /** Years to choose from: ended and closed years plus the current FS25 year, newest first. */
  readonly yearOptions = computed(() => {
    const v = this.view();
    if (!v) return [];
    const all = new Set<number>([...v.years, ...v.closedYears]);
    if (v.currentYear !== null) all.add(v.currentYear);
    return [...all].sort((a, b) => b - a);
  });
  readonly selectedYear = computed(() => this.year() ?? this.yearOptions()[0] ?? null);
  readonly selectedClosed = computed(() => {
    const y = this.selectedYear();
    return y !== null && (this.view()?.closedYears.includes(y) ?? false);
  });

  constructor() {
    effect(() => {
      this.store.stateVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.fieldBook().subscribe({
      next: (v) => {
        this.view.set(v);
        this.error.set(null);
      },
      error: (e) => this.error.set(toPageError(e, this.i18n.t('common.error'))),
    });
  }

  private run(call: Observable<FieldBookView>): void {
    this.busy.set(true);
    call.subscribe({
      next: (v) => {
        this.view.set(v);
        this.actionError.set(null);
        this.busy.set(false);
      },
      error: (e) => {
        this.actionError.set(apiErrorMessage(e, this.i18n.t('common.error')));
        this.busy.set(false);
      },
    });
  }

  set(entry: FieldBookEntryView, key: FieldBookValueKey, value: string | null): void {
    this.run(this.api.setFieldBookValue(entry.id, key, value));
  }

  tick(entry: FieldBookEntryView, key: FieldBookValueKey, event: Event): void {
    this.set(entry, key, String((event.target as HTMLInputElement).checked));
  }

  liters(entry: FieldBookEntryView, event: Event): void {
    const raw = (event.target as HTMLInputElement).value.trim();
    if (raw === '') return;
    this.set(entry, 'LITERS', raw);
  }

  select(entry: FieldBookEntryView, key: FieldBookValueKey, event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    if (value) this.set(entry, key, value);
  }

  harvest(field: FieldBookFieldView): void {
    this.run(this.api.harvestFieldBook(field.farmlandId));
  }

  closeYear(): void {
    const y = this.selectedYear();
    if (y !== null) this.run(this.api.closeFieldBookYear(y));
  }

  reopenYear(): void {
    const y = this.selectedYear();
    if (y !== null) this.run(this.api.reopenFieldBookYear(y));
  }

  chooseYear(event: Event): void {
    this.year.set(Number((event.target as HTMLSelectElement).value));
  }

  editable(entry: FieldBookEntryView): boolean {
    return !entry.locked && !entry.held && !this.busy();
  }

  /** German label of a crop: FarmPulse label, else the title of the game, else the name. */
  cropLabel(name: string | null): string {
    if (!name) return '–';
    const key = `enums.fillType.${name}`;
    if (this.i18n.has(key)) return this.i18n.t(key);
    return this.view()?.crops.find((c) => c.name === name)?.title ?? name;
  }

  /** Products of the crop of an entry: standard product, converter products and the value it has. */
  products(entry: FieldBookEntryView): string[] {
    const crop: FieldBookCropView | undefined = this.view()?.crops.find(
      (c) => c.name === entry.values.FRUIT_TYPE.value,
    );
    const list = [
      crop?.fillType,
      ...(crop?.products ?? []),
      entry.values.FILL_TYPE.value as string | null,
    ];
    return [...new Set(list.filter((p): p is string => !!p))];
  }

  sprayType(kind: string): string {
    const key = `fieldBook.sprayType.${kind}`;
    return this.i18n.has(key) ? this.i18n.t(key) : kind;
  }

  stateBadge(state: string): BadgeVariant {
    return STATE_BADGE[state] ?? 'neutral';
  }

  fieldName(farmlandId: number): string {
    return this.view()?.fields.find((f) => f.farmlandId === farmlandId)?.name ?? String(farmlandId);
  }

  hasCrop(name: unknown): boolean {
    return this.view()?.crops.some((c) => c.name === name) ?? false;
  }

  bool(entry: FieldBookEntryView, key: FieldBookValueKey): boolean {
    return entry.values[key].value === true;
  }
}
