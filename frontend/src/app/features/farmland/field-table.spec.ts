import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { FieldRowView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { savegame } from '../../../testing/fixtures';
import { FieldTable } from './field-table';

const row = (over: Partial<FieldRowView>): FieldRowView => ({
  farmlandId: 3, name: '3', hectares: 4.2, fruitType: 'WHEAT', phase: 'HARVESTABLE', leased: false, familyField: false,
  weedsHigh: false, stonesHigh: false, needsLime: false, needsPlow: false, previousCrop: 'CANOLA', currentCrop: 'WHEAT',
  rotation: 'CHANGED', rotationViolations: 0, ...over,
});

describe('FieldTable (Flurkarte)', () => {
  it('lists the fields with what needs doing and the rotation premium preview', () => {
    TestBed.configureTestingModule({ imports: [FieldTable], providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const fixture = TestBed.createComponent(FieldTable);
    fixture.detectChanges();
    TestBed.inject(HttpTestingController).expectOne('/api/field-overview').flush({
      year: 2, tracked: true,
      fields: [
        row({}),
        row({ farmlandId: 4, name: '4', fruitType: null, phase: 'EMPTY', weedsHigh: true, needsLime: true, rotation: 'UNKNOWN', previousCrop: null, currentCrop: null }),
        row({ farmlandId: 7, name: '7', fruitType: 'BARLEY', phase: 'GROWING', previousCrop: 'BARLEY', currentCrop: 'BARLEY', rotation: 'SAME', leased: true }),
      ],
      rotation: { changedHectares: 4.2, premium: 168, cut: false, sameFields: [7], premiumPerHa: 40 },
    });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const rows = el.querySelectorAll('[data-testid="field-row"]');
    expect(rows.length).toBe(3);
    expect(rows[0].querySelector('[data-testid="field-todo"]')?.textContent).toContain('Ernten');
    expect(rows[0].querySelector('[data-testid="field-rotation"]')?.textContent).toContain('✓');
    expect(rows[1].querySelector('[data-testid="field-todo"]')?.textContent).toContain('Kalken · Unkraut');
    expect(rows[2].textContent).toContain('Gepachtet');
    expect(rows[2].querySelector('[data-testid="field-rotation"]')?.textContent).toContain('!');
    expect(el.querySelector('[data-testid="rotation-premium"]')?.textContent?.replace(/\s/g, ' ')).toContain('168 €');
    expect(el.querySelector('[data-testid="rotation-same"]')?.textContent).toContain('Felder 7');
  });
});
