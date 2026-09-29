import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, savegame } from '../../../testing/fixtures';
import { Stable } from './stable';

describe('Stable', () => {
  function setup(tracked = true) {
    TestBed.configureTestingModule({
      imports: [Stable],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const fixture = TestBed.createComponent(Stable);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/stables').flush({
      tracked, animals: 162, keepers: 1, animalsPerKeeper: 80, healthWarnBelow: 40, foodWarnBelow: 0.2, waterWarnBelow: 0.2,
      vetDue: [{ type: 'COW', gameTime: 55 * DAY }],
      barns: [
        { husbandryUniqueId: 'h1', type: 'COW', count: 42, value: 96000, health: tracked ? 38 : null, productivity: tracked ? 61 : null,
          food: tracked ? 0.12 : null, water: tracked ? 0.86 : null, conditions: tracked ? [{ title: 'Wasser', ratio: 0.86 }, { title: 'Stroh', ratio: 0.4 }] : [],
          inspectionDeadline: tracked ? 50 * DAY : null },
        { husbandryUniqueId: 'h2', type: 'CHICKEN', count: 120, value: 2400, health: tracked ? 94 : null, productivity: null,
          food: tracked ? 0.7 : null, water: null, conditions: [], inspectionDeadline: null },
      ],
    });
    http.match('/api/contracts').forEach((r) => r.flush([]));
    http.match('/api/cases').forEach((r) => r.flush([]));
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('shows every stable with its values and warns about low ones', () => {
    const { el } = setup();
    const barns = el.querySelectorAll('[data-testid="barn"]');
    expect(barns.length).toBe(2);
    expect(barns[0].textContent).toContain('Rinder');
    expect(barns[0].querySelectorAll('[data-testid="barn-bar"]').length).toBe(4);
    expect(barns[0].textContent).toContain('Stroh 40 %');
    expect(barns[0].classList).toContain('border-warn/50');
    expect(barns[1].classList).not.toContain('border-warn/50');
    expect(el.querySelector('[data-testid="inspection-banner"]')?.textContent).toContain('Tag 50');
    expect(el.querySelector('[data-testid="keepers"]')?.textContent).toContain('162 Tiere je Person');
    expect(el.querySelector('[data-testid="vet-due"]')?.textContent).toContain('Tag 55');
  });

  it('explains the missing values with an older mod', () => {
    const { el } = setup(false);
    expect(el.querySelector('[data-testid="untracked"]')).not.toBeNull();
    expect(el.querySelectorAll('[data-testid="barn-bar"]').length).toBe(0);
  });
});
