import { Component, DestroyRef, ElementRef, afterNextRender, computed, inject, input, signal } from '@angular/core';
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

const DEFAULT_W = 640;
const H = 260;
const PAD = { top: 12, bottom: 28, left: 56 };
/** Approximate advance of one 10px Barlow character, used to reserve room for direct end labels. */
const LABEL_CHAR_PX = 5.6;

/**
 * Minimal SVG line chart (no chart library): one y-axis, recessive grid, 2px lines, legend for >= 2 series,
 * direct end labels for <= 4 series, crosshair + tooltip on hover, and a table view as accessible alternative.
 */
@Component({
  selector: 'app-chart-wrapper',
  imports: [TranslatePipe],
  templateUrl: './chart-wrapper.html',
  host: { class: 'block w-full' },
})
export class ChartWrapper {
  readonly series = input<ChartSeries[]>([]);
  readonly xFormat = input<(x: number) => string>((x) => String(x));
  readonly yFormat = input<(y: number) => string>((y) => String(y));
  readonly ariaLabel = input('');

  /** Rendered pixel width (measured), so the viewBox is 1:1 and text keeps its real size on every screen. */
  readonly measured = signal(DEFAULT_W);
  readonly height = H;
  get width(): number {
    return this.measured();
  }
  /** Direct end labels only for <= 4 series and when there is room (the legend always carries identity). */
  readonly directLabels = computed(() => this.visible().length > 0 && this.visible().length <= 4 && this.measured() >= 480);
  readonly padRight = computed(() => {
    const series = this.visible();
    if (!this.directLabels()) return 16;
    const longest = Math.max(...series.map((s) => s.label.length));
    return Math.min(180, Math.round(20 + longest * LABEL_CHAR_PX));
  });
  get pad(): { top: number; right: number; bottom: number; left: number } {
    return { ...PAD, right: this.padRight() };
  }

  constructor() {
    const host = inject(ElementRef<HTMLElement>).nativeElement as HTMLElement;
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      if (typeof ResizeObserver === 'undefined') return;
      const ro = new ResizeObserver((entries) => {
        const w = Math.round(entries[0]?.contentRect.width ?? 0);
        if (w > 0) this.measured.set(Math.max(280, w));
      });
      ro.observe(host);
      destroyRef.onDestroy(() => ro.disconnect());
    });
  }
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

  /** First tick left-aligned, last right-aligned so labels never run off the plot. */
  tickAnchor(i: number, n: number): 'start' | 'middle' | 'end' {
    return i === 0 ? 'start' : i === n - 1 ? 'end' : 'middle';
  }

  sx(x: number): number {
    const d = this.domain();
    return PAD.left + ((x - d.x0) / (d.x1 - d.x0)) * (this.width - PAD.left - this.padRight());
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
    return (this.measured() < 480 ? [0, 1] : [0, 0.5, 1]).map((f) => d.x0 + (d.x1 - d.x0) * f);
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
    const px = ((event.clientX - rect.left) / (rect.width || this.width)) * this.width;
    this.setHoverPixel(px);
  }

  setHoverPixel(px: number): void {
    const d = this.domain();
    const clamped = Math.max(PAD.left, Math.min(this.width - this.padRight(), px));
    this.hoverX.set(d.x0 + ((clamped - PAD.left) / (this.width - PAD.left - this.padRight())) * (d.x1 - d.x0));
  }

  readonly tableRows = computed(() => {
    const xs = [...new Set(this.visible().flatMap((s) => s.points.map((p) => p.x)))].sort((a, b) => a - b);
    return xs.map((x) => ({ x, values: this.visible().map((s) => s.points.find((p) => p.x === x)?.y ?? null) }));
  });
}
