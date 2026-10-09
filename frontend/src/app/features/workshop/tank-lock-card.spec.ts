import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DieselTheftView } from '../../core/api/models';
import { GameStateStore } from '../../core/state/game-state.store';
import { DAY, savegame } from '../../../testing/fixtures';
import { TankLockCard } from './tank-lock-card';

const view = (over: Partial<DieselTheftView> = {}): DieselTheftView => ({
  enabled: true, tankLockPrice: 250, insurancePremiumPerMonth: 8, insuranceMinDamage: 150,
  vehicles: [
    { vehicleId: 'veh_1', name: 'Fendt 724', fuelLiters: 400, fuelCapacity: 500, tankLock: false },
    { vehicleId: 'veh_2', name: 'Deutz 5105', fuelLiters: 60, fuelCapacity: 120, tankLock: true },
    { vehicleId: 'veh_3', name: 'Anhänger', fuelLiters: null, fuelCapacity: null, tankLock: false },
  ],
  thefts: [{ id: 1, vehicleId: 'veh_1', vehicleName: 'Fendt 724', status: 'DONE', stolenLiters: 150, damage: 240,
    insurancePayout: 240, closedGameTime: 12 * DAY }], ...over,
});

describe('TankLockCard (R31-D8)', () => {
  it('buys a tank lock for a vehicle with a diesel tank and lists the thefts', () => {
    TestBed.configureTestingModule({ imports: [TankLockCard], providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const fixture = TestBed.createComponent(TankLockCard);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/diesel-theft').flush(view());
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('[data-testid="tank-lock-buy"]').length).toBe(1); // locked and tankless have no button
    expect(el.querySelector('[data-testid="theft"]')?.textContent).toContain('150 l');
    (el.querySelector('[data-testid="tank-lock-buy"] button') as HTMLButtonElement).click();
    expect(http.expectOne('/api/tank-locks').request.body).toEqual({ vehicleId: 'veh_1' });
  });
});
