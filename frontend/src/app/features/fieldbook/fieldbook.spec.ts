import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { FieldBookEntryView, FieldBookValueKey, FieldBookView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { savegame } from '../../../testing/fixtures';
import { FieldBook } from './fieldbook';

const providers = [provideHttpClient(), provideHttpClientTesting()];

function entry(
  over: Partial<FieldBookEntryView> = {},
  values: Partial<FieldBookEntryView['values']> = {},
): FieldBookEntryView {
  const v = (value: string | number | boolean | null, manual = false) => ({ value, manual });
  return {
    id: 1,
    status: 'HARVESTED',
    harvestYear: 2,
    hectares: 4.5,
    firstEntry: false,
    held: false,
    locked: false,
    litersPerHectare: 9111.1,
    sprayTypes: ['LIQUID_MANURE', 'FERTILIZER'],
    rollingNeeded: true,
    values: {
      FRUIT_TYPE: v('WHEAT'),
      FILL_TYPE: v('WHEAT'),
      LITERS: v(41000),
      FERT1: v(true),
      FERT2: v(true),
      LIMED: v(true, true),
      ROLLED: v(true),
      WEEDS: v(false),
      MULCHED: v(false),
      ...values,
    } as Record<FieldBookValueKey, { value: string | number | boolean | null; manual: boolean }>,
    ...over,
  };
}

const view = (over: Partial<FieldBookView> = {}): FieldBookView => ({
  fieldsTracked: true,
  currentYear: 2,
  closedYears: [],
  years: [2],
  showLime: true,
  showWeeds: false,
  cropsFromMap: true,
  crops: [
    { name: 'WHEAT', title: 'Weizen', fillType: 'WHEAT', products: [], needsRolling: true },
    { name: 'GRASS', title: 'Gras', fillType: 'GRASS_WINDROW', products: [], needsRolling: false },
    { name: 'SPELT', title: 'Dinkel', fillType: 'SPELT', products: [], needsRolling: true },
  ],
  fields: [
    {
      farmlandId: 12,
      name: '12',
      hectares: 4.5,
      state: 'ACTIVE',
      running: entry(
        {
          id: 2,
          status: 'RUNNING',
          harvestYear: null,
          hectares: null,
          litersPerHectare: null,
          sprayTypes: [],
          rollingNeeded: false,
        },
        {
          FRUIT_TYPE: { value: 'GRASS', manual: false },
          LITERS: { value: null, manual: false },
          LIMED: { value: false, manual: false },
        },
      ),
      entries: [entry()],
    },
  ],
  notices: [],
  ...over,
});

function page(v: FieldBookView) {
  TestBed.configureTestingModule({ imports: [FieldBook], providers });
  TestBed.inject(GameStateStore).savegame.set(savegame({}));
  const fixture = TestBed.createComponent(FieldBook);
  fixture.detectChanges();
  const http = TestBed.inject(HttpTestingController);
  http.expectOne('/api/field-book').flush(v);
  fixture.detectChanges();
  return { fixture, el: fixture.nativeElement as HTMLElement, http };
}

describe('FieldBook (R33-F)', () => {
  it('shows the running season and the harvest years with ticks, litres per hectare and fertiliser', () => {
    const { el } = page(view());
    expect(el.querySelector('[data-testid="fb-running"]')?.textContent).toContain(
      'Laufende Saison',
    );
    const row = el.querySelector('[data-testid="fb-entry"]')!;
    expect(row.textContent).toContain('2');
    expect(row.querySelector('[data-testid="fb-per-ha"]')?.textContent).toContain('9.111');
    expect(row.querySelector('[data-testid="fb-spray"]')?.textContent).toContain('Gülle');
    expect((row.querySelector('[data-testid="fb-tick-LIMED"]') as HTMLInputElement).checked).toBe(
      true,
    );
    // weeds are off in the savegame: no column; the corrected lime value is marked
    expect(el.querySelector('[data-testid="fb-tick-WEEDS"]')).toBeNull();
    expect(row.querySelector('[data-testid="fb-manual"]')?.textContent).toContain('manuell');
    // grass is not rolled
    expect(
      el.querySelector('[data-testid="fb-running"] [data-testid="fb-roll-not-needed"]'),
    ).not.toBeNull();
    expect(el.querySelector('[data-testid="fb-state"]')?.textContent).toContain('bewirtschaftet');
  });

  it('corrects a value, gives it back to the detection and enters a harvest', () => {
    const { el, http } = page(view());
    const lime = el.querySelector(
      '[data-testid="fb-running"] [data-testid="fb-tick-LIMED"]',
    ) as HTMLInputElement;
    lime.checked = true;
    lime.dispatchEvent(new Event('change'));
    expect(http.expectOne('/api/field-book/entries/2/values').request.body).toEqual({
      field: 'LIMED',
      value: 'true',
    });
    (
      el.querySelector('[data-testid="fb-entry"] [data-testid="fb-auto"]') as HTMLButtonElement
    ).click();
    expect(http.expectOne('/api/field-book/entries/1/values').request.body).toEqual({
      field: 'LIMED',
      value: null,
    });
    (el.querySelector('[data-testid="fb-harvest"] button') as HTMLButtonElement).click();
    http.expectOne('/api/field-book/fields/12/harvest').flush(view());
  });

  it('closes and reopens a harvest year; a closed year cannot be edited', () => {
    const { el, fixture, http } = page(view());
    (el.querySelector('[data-testid="fb-close"] button') as HTMLButtonElement).click();
    http
      .expectOne('/api/field-book/years/2/close')
      .flush(
        view({
          closedYears: [2],
          fields: [{ ...view().fields[0], entries: [entry({ locked: true })] }],
        }),
      );
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="fb-year-closed"]')).not.toBeNull();
    expect(
      (el.querySelector('[data-testid="fb-entry"] [data-testid="fb-liters"]') as HTMLInputElement)
        .disabled,
    ).toBe(true);
    expect(el.querySelector('[data-testid="fb-entry"] [data-testid="fb-auto"]')).toBeNull();
    (el.querySelector('[data-testid="fb-reopen"] button') as HTMLButtonElement).click();
    http.expectOne('/api/field-book/years/2/reopen').flush(view());
  });

  it('warns without fields and with the fallback crops, and lists late detections', () => {
    const { el } = page(
      view({
        fieldsTracked: false,
        cropsFromMap: false,
        notices: [{ entryId: 1, farmlandId: 12, harvestYear: 2, liters: 2000 }],
      }),
    );
    expect(el.querySelector('[data-testid="fb-no-fields"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="fb-crops-fallback"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="fb-notice"]')?.textContent).toContain('2.000 l');
  });

  it('labels a crop of a map mod with the title of the game', () => {
    const { el } = page(view());
    const options = Array.from(
      (el.querySelector('[data-testid="fb-crop"]') as HTMLSelectElement).options,
    ).map((o) => o.textContent?.trim());
    expect(options).toContain('Weizen');
    expect(options).toContain('Dinkel');
  });
});
