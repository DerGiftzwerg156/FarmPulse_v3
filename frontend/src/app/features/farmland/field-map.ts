import { Component, computed, inject, input, output } from '@angular/core';
import { FieldMapView, MapFieldView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';

/**
 * 🟡 Roadmap V3.1 R31-K1 (owner decision 2026-10-06): like the in-game map, x points to the right and z downwards
 * (north up). Set to true when the play test shows the map mirrored (manual test plan 21.7 / 25.2).
 */
export const MIRROR_Z = false;

/** Fill colour of an own field by its phase (owner decision 2026-10-06); without field data the accent colour. */
export const PHASE_COLORS: Record<string, string> = {
  EMPTY: '#9CA3AF',
  GROWING: '#4ADE80',
  HARVESTABLE: '#EAB308',
  HARVESTED: '#A16207',
  WITHERED: '#DC2626',
};
const ACCENT = '#38B000';
const PALE = '#7C9088';

interface DrawnField {
  f: MapFieldView;
  points: string;
  cx: number;
  cy: number;
  fill: string;
  title: string;
}

/**
 * Roadmap V3.1 R31-K1: the fields of the map in their real outline as SVG (no map library). Own fields coloured by
 * phase and labelled with their number, leased ones hatched, leased-out ones with a thick border; neighbour fields
 * pale with the owner's name, free fields pale and dashed. Symbols: order (A), auction (V), hint (!). A click selects
 * the farmland (the field card with its actions opens below).
 */
@Component({
  selector: 'app-field-map',
  imports: [TranslatePipe],
  template: `
    <div class="overflow-hidden rounded-md border border-border bg-bg" data-testid="field-map-svg">
      <svg [attr.viewBox]="viewBox()" class="block aspect-square w-full" role="img" [attr.aria-label]="'farmland.fieldMap.label' | t">
        <defs>
          <pattern id="fp-hatch" patternUnits="userSpaceOnUse" [attr.width]="unit() * 1.6" [attr.height]="unit() * 1.6" patternTransform="rotate(45)">
            <line x1="0" y1="0" x2="0" [attr.y2]="unit() * 1.6" stroke="#0B0F0D" [attr.stroke-width]="unit() * 0.5" />
          </pattern>
        </defs>
        @for (d of drawn(); track d.f.farmlandId + '-' + d.f.name) {
          <g class="cursor-pointer" (click)="fieldSelected.emit(d.f.farmlandId)" data-testid="map-field" [attr.data-farmland]="d.f.farmlandId"
            [attr.data-kind]="d.f.kind" [attr.data-phase]="d.f.phase">
            <title>{{ d.title }}</title>
            <polygon [attr.points]="d.points" [attr.fill]="d.fill" [attr.fill-opacity]="d.f.kind === 'OWN' ? 0.75 : 0.15"
              [attr.stroke]="selected() === d.f.farmlandId ? '#E2ECE9' : d.f.kind === 'OWN' ? '#0B0F0D' : '#7C9088'"
              [attr.stroke-width]="unit() * (selected() === d.f.farmlandId ? 0.6 : d.f.leasedOut ? 0.8 : 0.2)"
              [attr.stroke-dasharray]="d.f.kind === 'FREE' ? unit() + ' ' + unit() : null" />
            @if (d.f.leased) {
              <polygon [attr.points]="d.points" fill="url(#fp-hatch)" fill-opacity="0.45" stroke="none" data-testid="map-hatch" />
            }
            @if (d.f.kind === 'OWN') {
              <text [attr.x]="d.cx" [attr.y]="d.cy" text-anchor="middle" dominant-baseline="middle" [attr.font-size]="unit() * 2.2"
                fill="#0B0F0D" font-weight="700">{{ d.f.name }}</text>
            } @else if (d.f.kind === 'NEIGHBOR') {
              <text [attr.x]="d.cx" [attr.y]="d.cy" text-anchor="middle" dominant-baseline="middle" [attr.font-size]="unit() * 1.6"
                fill="#B9C8C2" data-testid="map-owner">{{ d.f.ownerName }}</text>
            }
            @for (s of symbols(d.f); track s.key; let i = $index) {
              <g data-testid="map-symbol" [attr.data-symbol]="s.key">
                <circle [attr.cx]="d.cx + (i - 1) * unit() * 2.4" [attr.cy]="d.cy + unit() * 2.6" [attr.r]="unit() * 1.05"
                  [attr.fill]="s.color" stroke="#0B0F0D" [attr.stroke-width]="unit() * 0.2" />
                <text [attr.x]="d.cx + (i - 1) * unit() * 2.4" [attr.y]="d.cy + unit() * 2.65" text-anchor="middle"
                  dominant-baseline="middle" [attr.font-size]="unit() * 1.3" fill="#0B0F0D" font-weight="700">{{ s.glyph }}</text>
              </g>
            }
          </g>
        }
      </svg>
    </div>
    <div class="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-muted" data-testid="map-legend">
      @for (p of phases; track p) {
        <span class="flex items-center gap-1.5"><span class="h-2.5 w-2.5 rounded-sm" [style.background]="phaseColor(p)"></span>{{ ('enums.fieldPhase.' + p) | t }}</span>
      }
      <span class="flex items-center gap-1.5"><span class="h-2.5 w-2.5 rounded-sm bg-accent/70 [background-image:repeating-linear-gradient(45deg,#0B0F0D_0_2px,transparent_2px_5px)]"></span>{{ 'farmland.fieldMap.leased' | t }}</span>
      <span class="flex items-center gap-1.5"><span class="h-2.5 w-2.5 rounded-sm border-2 border-text"></span>{{ 'farmland.fieldMap.leasedOut' | t }}</span>
      <span class="flex items-center gap-1.5"><span class="h-2.5 w-2.5 rounded-sm border border-muted bg-muted/20"></span>{{ 'farmland.fieldMap.neighbor' | t }}</span>
      <span class="flex items-center gap-1.5"><span class="h-2.5 w-2.5 rounded-sm border border-dashed border-muted"></span>{{ 'farmland.fieldMap.free' | t }}</span>
      <span>A = {{ 'farmland.fieldMap.order' | t }} · V = {{ 'farmland.fieldMap.auction' | t }} · ! = {{ 'farmland.fieldMap.hint' | t }}</span>
    </div>
  `,
})
export class FieldMap {
  private readonly i18n = inject(TranslationService);

  readonly map = input.required<FieldMapView>();
  readonly selected = input<number | null>(null);
  readonly fieldSelected = output<number>();

  readonly phases = Object.keys(PHASE_COLORS);

  /** Size unit of strokes, labels and symbols: 1/100 of the map. */
  readonly unit = computed(() => (this.map().mapSize ?? 1000) / 100);

  readonly viewBox = computed(() => {
    const size = this.map().mapSize ?? 1000;
    return `${-size / 2} ${-size / 2} ${size} ${size}`;
  });

  readonly drawn = computed<DrawnField[]>(() =>
    this.map().fields.map((f) => {
      const pts = f.points.map((p) => ({ x: p.x, y: MIRROR_Z ? -p.z : p.z }));
      const cx = pts.reduce((s, p) => s + p.x, 0) / pts.length;
      const cy = pts.reduce((s, p) => s + p.y, 0) / pts.length;
      return {
        f,
        points: pts.map((p) => `${p.x},${p.y}`).join(' '),
        cx,
        cy,
        fill: f.kind === 'OWN' ? (f.phase ? PHASE_COLORS[f.phase] ?? ACCENT : ACCENT) : PALE,
        title: this.title(f),
      };
    }),
  );

  phaseColor(p: string): string {
    return PHASE_COLORS[p];
  }

  symbols(f: MapFieldView): { key: string; glyph: string; color: string }[] {
    const out: { key: string; glyph: string; color: string }[] = [];
    if (f.orders.length) out.push({ key: 'ORDER', glyph: 'A', color: '#8CC8EA' });
    if (f.auction) out.push({ key: 'AUCTION', glyph: 'V', color: '#FF9F1C' });
    if (f.hints.length) out.push({ key: 'HINT', glyph: '!', color: '#FFB547' });
    return out;
  }

  /** Tooltip: field, owner, crop and phase, the orders, the auction and the hints. */
  title(f: MapFieldView): string {
    const t = (k: string, p?: Record<string, string | number>) => this.i18n.t(k, p);
    const lines = [t('farmland.fieldLabel', { id: f.name })];
    if (f.kind === 'NEIGHBOR' && f.ownerName) lines.push(f.ownerName);
    if (f.kind === 'FREE') lines.push(t('farmland.fieldMap.free'));
    if (f.leased) lines.push(t('farmland.fieldMap.leased'));
    if (f.leasedOut) lines.push(t('farmland.fieldMap.leasedOut'));
    if (f.phase) {
      lines.push((f.fruitType ? this.label('fillType', f.fruitType) + ' · ' : '') + this.label('fieldPhase', f.phase));
    }
    f.orders.forEach((o) => lines.push(t('farmland.fieldMap.order') + ': ' + this.label('caseKind', o)));
    if (f.auction) lines.push(t('farmland.fieldMap.auctionRunning'));
    f.hints.forEach((h) => lines.push(t('farmland.fieldMap.hints.' + h)));
    return lines.join('\n');
  }

  private label(group: string, value: string): string {
    const key = `enums.${group}.${value}`;
    return this.i18n.has(key) ? this.i18n.t(key) : value;
  }
}
