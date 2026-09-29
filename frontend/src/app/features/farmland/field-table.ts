import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { FieldOverviewView, FieldRowView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { GameStateStore } from '../../core/state/game-state.store';
import { MoneyPipe, NumberPipe } from '../../shared/format/format.pipes';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Card } from '../../shared/ui/card';

/**
 * "Meine Felder" in the app Flurkarte: every field the player farms with crop, phase, what needs doing and the crop
 * rotation against last year, plus the rotation premium the authority would pay if the year ended now.
 */
@Component({
  selector: 'app-field-table',
  imports: [TranslatePipe, LabelPipe, MoneyPipe, NumberPipe, Card],
  template: `
    @if (overview(); as o) {
      @if (o.fields.length) {
        <div class="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,3fr)_minmax(0,1fr)]">
          <app-card [title]="'farmland.table.title' | t" data-testid="field-table">
            <div class="overflow-x-auto">
              <table class="w-full min-w-[640px] text-[13px]">
                <thead>
                  <tr class="text-left font-display text-[10px] uppercase tracking-[0.14em] text-muted">
                    <th class="py-1.5 pr-3 font-bold">{{ 'farmland.table.field' | t }}</th>
                    <th class="py-1.5 pr-3 font-bold">{{ 'farmland.table.area' | t }}</th>
                    <th class="py-1.5 pr-3 font-bold">{{ 'farmland.table.crop' | t }}</th>
                    <th class="py-1.5 pr-3 font-bold">{{ 'farmland.table.phase' | t }}</th>
                    <th class="py-1.5 pr-3 font-bold">{{ 'farmland.table.todo' | t }}</th>
                    <th class="py-1.5 font-bold">{{ 'farmland.table.rotation' | t }}</th>
                  </tr>
                </thead>
                <tbody>
                  @for (f of o.fields; track f.farmlandId) {
                    <tr class="border-t border-border/70 align-top" data-testid="field-row">
                      <td class="py-2 pr-3"><span class="font-semibold text-text">{{ 'farmland.fieldLabel' | t: { id: f.name ?? f.farmlandId } }}</span>
                        <span class="block text-[11px] text-muted">{{ tag(f) }}</span></td>
                      <td class="py-2 pr-3 font-mono text-[12px] text-[#B9C8C2]">{{ f.hectares | num: 1 }} ha</td>
                      <td class="py-2 pr-3 text-text">{{ f.fruitType ? (f.fruitType | label: 'fillType') : '–' }}</td>
                      <td class="py-2 pr-3" [class.text-app-farm]="f.phase === 'HARVESTABLE'" [class.text-warn]="f.phase === 'WITHERED'" [class.text-muted]="f.phase !== 'HARVESTABLE' && f.phase !== 'WITHERED'">
                        {{ f.phase | label: 'fieldPhase' }}</td>
                      <td class="py-2 pr-3" [class.text-warn]="todos(f).length" [class.text-muted]="!todos(f).length" data-testid="field-todo">{{ todos(f).join(' · ') || '–' }}</td>
                      <td class="py-2" data-testid="field-rotation" [class.text-app-farm]="f.rotation === 'CHANGED'" [class.text-warn]="f.rotation === 'SAME'" [class.text-muted]="f.rotation === 'UNKNOWN'">
                        @if (f.rotation === 'UNKNOWN') { – } @else {
                          {{ f.previousCrop | label: 'fillType' }} → {{ f.currentCrop | label: 'fillType' }} {{ f.rotation === 'CHANGED' ? '✓' : '!' }}
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            @if (!o.tracked) {
              <p class="mt-2 text-[12px] text-muted">{{ 'settings.fields.untracked' | t }}</p>
            }
          </app-card>
          @if (o.rotation; as r) {
            <app-card [title]="'farmland.table.premium' | t: { year: o.year }" data-testid="rotation-preview">
              <div class="font-mono text-[22px]" [class.text-app-farm]="!r.cut" [class.text-warn]="r.cut" data-testid="rotation-premium">≈ {{ r.premium | money }}</div>
              <p class="mt-1 text-[12px] text-muted">{{ 'farmland.table.premiumHint' | t: { ha: (r.changedHectares | num: 1), perHa: (r.premiumPerHa | money) } }}</p>
              @if (r.sameFields.length) {
                <p class="mt-2 text-[12px]" [class.text-warn]="true" data-testid="rotation-same">
                  {{ (r.cut ? 'farmland.table.cut' : 'farmland.table.same') | t: { fields: r.sameFields.join(', ') } }}</p>
              }
              <p class="mt-2 text-[11px] text-muted">{{ 'farmland.table.premiumNote' | t }}</p>
            </app-card>
          }
        </div>
      }
    }
  `,
})
export class FieldTable {
  private readonly api = inject(ApiService);
  private readonly store = inject(GameStateStore);
  private readonly i18n = inject(TranslationService);
  readonly overview = signal<FieldOverviewView | null>(null);
  readonly count = computed(() => this.overview()?.fields.length ?? 0);

  constructor() {
    effect(() => {
      this.store.stateVersion();
      if (this.store.savegame()) untracked(() => this.load());
    });
  }

  load(): void {
    this.api.fieldOverview().subscribe({ next: (o) => this.overview.set(o), error: () => this.overview.set(null) });
  }

  tag(f: FieldRowView): string {
    const parts = [this.i18n.t(f.leased ? 'farmland.table.leased' : 'farmland.table.owned')];
    if (f.familyField) parts.push(this.i18n.t('farmland.familyField'));
    return parts.join(' · ');
  }

  /** What needs doing (only what the savegame has switched on). */
  todos(f: FieldRowView): string[] {
    const out: string[] = [];
    if (f.phase === 'HARVESTABLE') out.push(this.i18n.t('farmland.table.harvest'));
    if (f.needsLime) out.push(this.i18n.t('farmland.table.lime'));
    if (f.needsPlow) out.push(this.i18n.t('farmland.table.plow'));
    if (f.weedsHigh) out.push(this.i18n.t('farmland.table.weeds'));
    if (f.stonesHigh) out.push(this.i18n.t('farmland.table.stones'));
    return out;
  }
}
