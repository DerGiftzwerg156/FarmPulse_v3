import { Component, computed, input, signal } from '@angular/core';
import { TranslatePipe } from '../../core/i18n/translate.pipe';

export interface ChartPoint {
  x: number;
  y: number;
}

export interface ChartSeries {
  key: string;
  label: string;
  points: ChartPoint[];
  /** Stable slot in the categorical palette (color follows the entity, never its rank). */
  colorIndex?: number;
}

/**
 * Categorical palette for series on the dark surface #141A17 - validated with the dataviz palette validator
 * (lightness band, chroma, CVD separation >= 8.4, contrast >= 3:1). Status colors (accent/warn/danger) are
 * deliberately NOT used for series.
 */
export const SERIES_COLORS = ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#008300'];

const W = 640;
const H = 260;
const PAD = { top: 12, right: 96, bottom: 28, left: 56 };

/**
 * Minimal SVG line chart (no chart library): one y-axis, recessive grid, 2px lines, legend for >= 2 series,
 * direct end labels for <= 4 series, crosshair + tooltip on hover, and a table view as accessible alternative.
 */
@Component({
  selector: 'app-chart-wrapper',
  imports: [TranslatePipe],
  templateUrl: './chart-wrapper.html',
})
export class ChartWrapper {
  readonly series = input<ChartSeries[]>([]);
  readonly xFormat = input<(x: number) => string>((x) => String(x));
  readonly yFormat = input<(y: number) => string>((y) => String(y));
  readonly ariaLabel = input('');

  readonly width = W;
  readonly height = H;
  readonly pad = PAD;
  readonly showTable = signal(false);
  readonly hoverX = signal<number | null>(null);

  readonly visible = computed(() => this.series().filter((s) => s.points.length > 0));

  readonly domain = computed(() => {
    const pts = this.visible().flatMap((s) => s.points);
    if (pts.length === 0) {
      return { x0: 0, x1: 1, y0: 0, y1: 1 };
    }
    let x0 = Math.min(...pts.map((p) => p.x));
    let x1 = Math.max(...pts.map((p) => p.x));
    let y0 = Math.min(...pts.map((p) => p.y));
    let y1 = Math.max(...pts.map((p) => p.y));
    if (x0 === x1) {
      x0 -= 1;
      x1 += 1;
    }
    const padY = (y1 - y0) * 0.1 || Math.abs(y1) * 0.1 || 1;
    y0 -= padY;
    y1 += padY;
    return { x0, x1, y0, y1 };
  });

  sx(x: number): number {
    const d = this.domain();
    return PAD.left + ((x - d.x0) / (d.x1 - d.x0)) * (W - PAD.left - PAD.right);
  }

  sy(y: number): number {
    const d = this.domain();
    return H - PAD.bottom - ((y - d.y0) / (d.y1 - d.y0)) * (H - PAD.top - PAD.bottom);
  }

  color(s: ChartSeries, i: number): string {
    return SERIES_COLORS[(s.colorIndex ?? i) % SERIES_COLORS.length];
  }

  readonly paths = computed(() =>
    this.visible().map((s, i) => ({
      series: s,
      color: this.color(s, i),
      d: [...s.points]
        .sort((a, b) => a.x - b.x)
        .map((p, j) => `${j === 0 ? 'M' : 'L'}${this.sx(p.x).toFixed(1)},${this.sy(p.y).toFixed(1)}`)
        .join(' '),
      last: [...s.points].sort((a, b) => a.x - b.x).at(-1)!,
    })),
  );

  readonly yTicks = computed(() => {
    const d = this.domain();
    return [0, 0.25, 0.5, 0.75, 1].map((f) => d.y0 + (d.y1 - d.y0) * f);
  });

  readonly xTicks = computed(() => {
    const d = this.domain();
    return [0, 0.5, 1].map((f) => d.x0 + (d.x1 - d.x0) * f);
  });

  /** Values of every series at the hovered x (nearest point per series). */
  readonly hoverValues = computed(() => {
    const x = this.hoverX();
    if (x === null) {
      return [];
    }
    return this.paths().map((p) => {
      const nearest = p.series.points.reduce((a, b) => (Math.abs(b.x - x) < Math.abs(a.x - x) ? b : a));
      return { label: p.series.label, color: p.color, point: nearest };
    });
  });

  onMove(event: MouseEvent): void {
    const svg = event.currentTarget as SVGSVGElement;
    const rect = svg.getBoundingClientRect();
    const px = ((event.clientX - rect.left) / (rect.width || W)) * W;
    this.setHoverPixel(px);
  }

  setHoverPixel(px: number): void {
    const d = this.domain();
    const clamped = Math.max(PAD.left, Math.min(W - PAD.right, px));
    this.hoverX.set(d.x0 + ((clamped - PAD.left) / (W - PAD.left - PAD.right)) * (d.x1 - d.x0));
  }

  readonly tableRows = computed(() => {
    const xs = [...new Set(this.visible().flatMap((s) => s.points.map((p) => p.x)))].sort((a, b) => a - b);
    return xs.map((x) => ({ x, values: this.visible().map((s) => s.points.find((p) => p.x === x)?.y ?? null) }));
  });
}
