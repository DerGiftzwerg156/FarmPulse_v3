import { Component, Type, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Badge } from './badge';
import { Button } from './button';
import { Card } from './card';
import { ChartSeries, ChartWrapper, SERIES_COLORS } from './chart-wrapper';
import { Icon } from './icon';
import { ListItem } from './list-item';
import { Modal } from './modal';
import { Stat } from './stat';
import { ToastService } from './toast.service';
import { Toasts } from './toasts';

function render<T>(type: Type<T>, inputs: Record<string, unknown> = {}) {
  TestBed.configureTestingModule({ imports: [type], providers: [provideRouter([])] });
  const fixture = TestBed.createComponent(type);
  Object.entries(inputs).forEach(([k, v]) => fixture.componentRef.setInput(k, v));
  fixture.detectChanges();
  return { fixture, el: fixture.nativeElement as HTMLElement };
}

describe('Card', () => {
  it('renders title and highlight border', () => {
    const { el } = render(Card, { title: 'Kredite', highlight: true });
    expect(el.textContent).toContain('Kredite');
    expect(el.querySelector('section')?.className).toContain('border-accent');
  });
});

describe('Badge', () => {
  it('maps variants to token colors', () => {
    for (const [variant, cls] of [['positive', 'text-accent'], ['negative', 'text-danger'], ['warning', 'text-warn'], ['neutral', 'text-muted']]) {
      TestBed.resetTestingModule();
      const { el } = render(Badge, { variant });
      expect(el.querySelector('span')?.className).toContain(cls);
    }
  });
});

describe('Button', () => {
  it('emits pressed and respects disabled', () => {
    const { fixture, el } = render(Button, { variant: 'primary' });
    let clicks = 0;
    fixture.componentInstance.pressed.subscribe(() => clicks++);
    el.querySelector('button')!.click();
    expect(clicks).toBe(1);
    expect(el.querySelector('button')!.className).toContain('bg-accent');
    fixture.componentRef.setInput('disabled', true);
    fixture.detectChanges();
    expect(el.querySelector('button')!.disabled).toBe(true);
  });
});

describe('Modal', () => {
  it('opens, closes via backdrop and close button', () => {
    const { fixture, el } = render(Modal, { open: true, title: 'Gebot' });
    let closed = 0;
    fixture.componentInstance.closed.subscribe(() => closed++);
    expect(el.querySelector('[role="dialog"]')).not.toBeNull();
    (el.querySelector('[data-testid="modal-backdrop"]') as HTMLElement).click();
    (el.querySelector('[data-testid="modal-close"]') as HTMLElement).click();
    expect(closed).toBe(2);
    fixture.componentRef.setInput('open', false);
    fixture.detectChanges();
    expect(el.querySelector('[role="dialog"]')).toBeNull();
  });
});

describe('Toasts', () => {
  it('shows and dismisses toasts', () => {
    const { fixture, el } = render(Toasts);
    const service = TestBed.inject(ToastService);
    const id = service.show('Neue Mail von Frau Berger', 'success', 0);
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="toast"]').length).toBe(1);
    service.dismiss(id);
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="toast"]').length).toBe(0);
  });
});

describe('Stat', () => {
  it('shows label and mono value', () => {
    const { el } = render(Stat, { label: 'Kontostand', value: '245.000 €', hint: '+2 %', tone: 'positive' });
    expect(el.querySelector('[data-testid="stat-value"]')?.textContent?.trim()).toBe('245.000 €');
    expect(el.textContent).toContain('+2 %');
  });
});

describe('ListItem', () => {
  it('marks unread items', () => {
    const { el } = render(ListItem, { title: 'Kreditantrag', subtitle: 'Frau Berger', meta: 'Tag 3', unread: true });
    expect(el.querySelector('[data-testid="unread-dot"]')).not.toBeNull();
    expect(el.textContent).toContain('Frau Berger');
  });
});

describe('Icon', () => {
  it('renders known icons and falls back for unknown ones', () => {
    const { el } = render(Icon, { name: 'mail' });
    expect(el.querySelectorAll('path').length).toBe(2);
    expect(Icon.names()).toContain('phone');
  });
});

@Component({
  imports: [ChartWrapper],
  template: `<app-chart-wrapper [series]="series()" />`,
})
class ChartHost {
  readonly series = signal<ChartSeries[]>([]);
}

describe('ChartWrapper', () => {
  const two: ChartSeries[] = [
    { key: 'a', label: 'Mühle Nord', points: [{ x: 0, y: 200 }, { x: 10, y: 230 }], colorIndex: 0 },
    { key: 'b', label: 'Landhandel', points: [{ x: 0, y: 210 }, { x: 10, y: 190 }], colorIndex: 1 },
  ];

  it('renders one line per series with legend and direct labels', () => {
    const { fixture, el } = render(ChartHost);
    fixture.componentInstance.series.set(two);
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="chart-line"]').length).toBe(2);
    expect(el.querySelector('[data-testid="chart-legend"]')?.textContent).toContain('Landhandel');
    expect(el.querySelectorAll('[data-testid="chart-direct-label"]').length).toBe(2);
    expect(el.querySelector('[data-testid="chart-line"]')?.getAttribute('stroke')).toBe(SERIES_COLORS[0]);
  });

  it('drops direct labels and the middle x tick on narrow widths (legend keeps identity)', () => {
    const { fixture, el } = render(ChartHost);
    fixture.componentInstance.series.set(two);
    fixture.detectChanges();
    const chart = fixture.debugElement.children[0].componentInstance as ChartWrapper;
    chart.measured.set(360);
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="chart-direct-label"]').length).toBe(0);
    expect(chart.xTicks().length).toBe(2);
    expect(chart.padRight()).toBe(16);
    expect(el.querySelector('[data-testid="chart-legend"]')).not.toBeNull();
  });

  it('has no legend for a single series and an empty state without data', () => {
    const { fixture, el } = render(ChartHost);
    expect(el.textContent).toContain('Noch keine Daten');
    fixture.componentInstance.series.set([two[0]]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="chart-legend"]')).toBeNull();
  });

  it('shows crosshair tooltip on hover and a table view', () => {
    const { fixture, el } = render(ChartHost);
    fixture.componentInstance.series.set(two);
    fixture.detectChanges();
    const chart = fixture.debugElement.children[0].componentInstance as ChartWrapper;
    chart.setHoverPixel(10_000); // far right -> last points
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="chart-crosshair"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="chart-tooltip"]')?.textContent).toContain('230');
    (el.querySelector('[data-testid="chart-toggle"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="chart-table"] tbody tr').length).toBe(2);
  });

  it('keeps colors bound to the entity, not the rank', () => {
    const { fixture, el } = render(ChartHost);
    fixture.componentInstance.series.set([two[1]]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="chart-line"]')?.getAttribute('stroke')).toBe(SERIES_COLORS[1]);
  });
});
