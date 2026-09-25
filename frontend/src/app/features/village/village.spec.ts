import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { CharacterDetailView, CharacterView } from '../../core/api/models';
import { message } from '../../../testing/fixtures';
import { Village } from './village';

const heike: CharacterView = { id: 1, name: 'Heike Brandt', role: 'BANK_ADVISOR', category: 'MANDATORY', status: 'ACTIVE', trustLevel: 'GOOD', shortDescription: 'Genau, aber fair.', farmlands: [] };
const gerd: CharacterView = {
  id: 4, name: 'Gerd Albers', role: 'NEIGHBOR_FARMER', category: 'DYNAMIC', status: 'ACTIVE', trustLevel: 'STRAINED', shortDescription: null,
  farmlands: [{ farmlandId: 2, hectares: 5.5, referencePrice: 165000, inNegotiation: false }],
};
const gone: CharacterView = { ...heike, id: 9, name: 'Otto Kruse', category: 'DYNAMIC', status: 'TERMINATED', trustLevel: 'NEUTRAL' };
const detail = (c: CharacterView, over: Partial<CharacterDetailView> = {}): CharacterDetailView => ({
  ...c, traits: 'stur, hilfsbereit, misstrauisch', speechStyle: 'knapp, plattdeutsch gefärbt', backstory: 'Bewirtschaftet den Hof seit 40 Jahren.',
  pacingActive: false, openTopic: false, recentMessages: [], ...over,
});

describe('Village', () => {
  function setup(characterParam?: string) {
    TestBed.configureTestingModule({
      imports: [Village],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(Village);
    if (characterParam) fixture.componentRef.setInput('character', characterParam);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/characters').flush([heike, gerd, gone]);
    http.expectOne('/api/village-reputation').flush({ tier: 'CONTROVERSIAL', label: 'umstritten' });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const btn = (id: string, i = 0) => el.querySelectorAll(`[data-testid="${id}"] button`)[i] as HTMLButtonElement;
    return { fixture, http, el, btn };
  }

  it('shows reputation only as a tier and trust only abstractly', () => {
    const { el } = setup();
    expect(el.querySelector('[data-testid="reputation"]')?.textContent).toContain('Umstritten');
    expect(el.querySelector('[data-testid="reputation"]')?.textContent).not.toMatch(/\d/);
    const meters = el.querySelectorAll('[data-testid="character-list"] [data-testid="trust-meter"]');
    expect(meters.length).toBe(2);
    expect(meters[1].textContent).toContain('Angespannt');
    expect(meters[1].textContent).not.toMatch(/\d/);
  });

  it('hides former characters until requested', () => {
    const { el, fixture } = setup();
    expect(el.querySelectorAll('[data-testid="character-row"]').length).toBe(2);
    (el.querySelector('[data-testid="former-toggle"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="character-row"]').length).toBe(3);
  });

  it('opens the detail with personality information', () => {
    const { el, fixture, http } = setup();
    (el.querySelectorAll('[data-testid="character-row"]')[1] as HTMLButtonElement).click();
    http.expectOne('/api/characters/4').flush(detail(gerd));
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="traits"] span').length).toBe(3);
    expect(el.querySelector('[data-testid="speech-style"]')?.textContent).toContain('plattdeutsch');
    expect(el.querySelector('[data-testid="backstory"]')?.textContent).toContain('40 Jahren');
  });

  it('sends a message and shows the pacing hint without blocking', () => {
    const { el, fixture, http, btn } = setup('1');
    http.expectOne('/api/characters/1').flush(detail(heike, { pacingActive: true, openTopic: true }));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="pacing-hint"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="open-topic"]')).not.toBeNull();
    const ta = el.querySelector('[data-testid="compose-text"]') as HTMLTextAreaElement;
    ta.value = 'Danke für die schnelle Hilfe!';
    ta.dispatchEvent(new Event('input'));
    (el.querySelector('[data-testid="compose-call"]') as HTMLInputElement).dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(btn('compose-send').disabled).toBe(false);
    btn('compose-send').click();
    const req = http.expectOne('/api/characters/1/messages');
    expect(req.request.body).toEqual({ text: 'Danke für die schnelle Hilfe!', channel: 'CALL' });
    req.flush({ message: message({ id: 50, initiatedBy: 'PLAYER', channel: 'CALL', body: 'Danke für die schnelle Hilfe!' }), pacingActive: true });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="village-info"]')?.textContent).toContain('zählt nicht zusätzlich');
    expect(el.querySelectorAll('[data-testid="recent-messages"] li').length).toBe(1);
  });

  it('starts a direct negotiation for a land owner', () => {
    const { el, fixture, http, btn } = setup('4');
    const nav = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    http.expectOne('/api/characters/4').flush(detail(gerd));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="character-fields"]')?.textContent).toContain('Feld 2');
    btn('negotiate').click();
    const req = http.expectOne('/api/negotiations/direct');
    expect(req.request.body).toEqual({ characterId: 4, farmlandId: 2 });
    req.flush({ id: 33 });
    expect(nav).toHaveBeenCalledWith(['/farmland'], { queryParams: { negotiation: 33 } });
  });
});
