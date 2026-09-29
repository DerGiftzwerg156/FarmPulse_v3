import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { GameStateStore } from '../../core/state/game-state.store';
import { savegame } from '../../../testing/fixtures';
import { Workshop } from './workshop';

describe('Workshop', () => {
  it('lists the mechanics and requests a maintenance offer', () => {
    TestBed.configureTestingModule({
      imports: [Workshop],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const fixture = TestBed.createComponent(Workshop);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.match('/api/contracts').forEach((r) => r.flush([]));
    http.match('/api/cases').forEach((r) => r.flush([]));
    http.expectOne('/api/employees').flush([
      { id: 1, character: { id: 3, name: 'Elena Iwersen', role: 'EMPLOYEE', status: 'ACTIVE' }, jobRole: 'MECHANIC', skill: 54, status: 'ACTIVE' },
      { id: 2, character: { id: 4, name: 'David Meyer', role: 'EMPLOYEE', status: 'ACTIVE' }, jobRole: 'MACHINE_OPERATOR', skill: 79, status: 'ACTIVE' },
    ]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('[data-testid="mechanic"]').length).toBe(1);
    expect(el.querySelector('[data-testid="mechanic"]')?.textContent).toContain('Elena Iwersen');
    (el.querySelector('[data-testid="request-maintenance"] button') as HTMLButtonElement).click();
    http.expectOne('/api/maintenance/offer').flush({ id: 6, kind: 'MAINTENANCE', status: 'OFFERED' });
    // the offer shows up after the reload of the contracts
    fixture.detectChanges();
    expect(http.match('/api/contracts').length).toBeGreaterThan(0);
  });
});
