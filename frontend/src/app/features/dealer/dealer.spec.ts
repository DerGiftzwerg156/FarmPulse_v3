import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { GameStateStore } from '../../core/state/game-state.store';
import { savegame } from '../../../testing/fixtures';
import { Dealer } from './dealer';

describe('Dealer (RFH-H2)', () => {
  function setup(tab: string) {
    TestBed.configureTestingModule({
      imports: [Dealer],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(GameStateStore).savegame.set(savegame({}));
    const fixture = TestBed.createComponent(Dealer);
    fixture.componentRef.setInput('tab', tab);
    fixture.detectChanges();
    return { fixture, http: TestBed.inject(HttpTestingController), el: fixture.nativeElement as HTMLElement };
  }

  it('opening the app brings the dealer and shows the contact with the hint of the tab', () => {
    const { fixture, http, el } = setup('warenkorb');
    const req = http.expectOne('/api/dealer/open');
    expect(req.request.method).toBe('POST');
    req.flush({ dealer: { character: { id: 7, name: 'Rieke Lüders', role: 'MACHINE_DEALER', status: 'ACTIVE' }, trustLevel: 'NEUTRAL' } });
    fixture.detectChanges();
    const contact = el.querySelector('[data-testid="dealer-contact"]');
    expect(contact?.textContent).toContain('Landmaschinenhändler:in');
    expect(el.querySelector('[data-testid="dealer-name"]')?.textContent).toContain('Rieke Lüders');
    expect(el.querySelector('[data-testid="dealer-name"]')?.getAttribute('href')).toBe('/village?character=7');
    expect(el.querySelector('[data-testid="trust-meter"]')?.getAttribute('data-level')).toBe('NEUTRAL');
    const hint = el.querySelector('[data-testid="coming-soon"]')?.textContent;
    expect(hint).toContain('Warenkorb');
    expect(hint).toContain('Folgt mit dem nächsten Ausbau');
  });

  it('every tab shows the hint, also when the dealer cannot be loaded', () => {
    const { fixture, http, el } = setup('lieferorte');
    http.expectOne('/api/dealer/open').flush({ message: 'kein Spielstand' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="dealer-contact"]')).toBeNull();
    expect(el.querySelector('[data-testid="coming-soon"]')?.textContent).toContain('Lieferorte');
  });
});
