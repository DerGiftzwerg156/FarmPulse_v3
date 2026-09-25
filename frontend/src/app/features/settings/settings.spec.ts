import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AiSettingsView } from '../../core/api/models';
import { Settings } from './settings';

const ai: AiSettingsView = { provider: 'ANTHROPIC', model: null, baseUrl: null, apiKeySet: true, providers: ['NONE', 'OPENAI', 'ANTHROPIC', 'GEMINI', 'OLLAMA'] };

describe('Settings', () => {
  function setup(game: unknown = { tonePreset: 'REALISTIC', toneLabel: 'realistisch-ausgewogen' }) {
    TestBed.configureTestingModule({
      imports: [Settings],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(Settings);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/settings/ai').flush(ai);
    const g = http.expectOne('/api/settings/game');
    if (game) g.flush(game);
    else g.flush({ code: 'NO_ACTIVE_SAVEGAME', message: 'x', fields: {} }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const input = (id: string, v: string) => {
      const i = el.querySelector(`[data-testid="${id}"]`) as HTMLInputElement;
      i.value = v;
      i.dispatchEvent(new Event(i.tagName === 'SELECT' ? 'change' : 'input'));
      fixture.detectChanges();
    };
    return { fixture, http, el, input };
  }

  it('shows the active provider and a stored key without ever showing it', () => {
    const { el } = setup();
    expect(el.textContent).toContain('Aktiv: Anthropic');
    const key = el.querySelector('[data-testid="api-key"]') as HTMLInputElement;
    expect(key.type).toBe('password');
    expect(key.value).toBe('');
    expect(key.placeholder).toContain('hinterlegt');
  });

  it('saves provider, model and key, then clears the key field', () => {
    const { el, http, fixture, input } = setup();
    input('provider', 'OPENAI');
    expect((el.querySelector('[data-testid="api-key"]') as HTMLInputElement).placeholder).toContain('eingeben');
    input('model', 'gpt-test');
    input('api-key', 'sk-local-123');
    (el.querySelector('[data-testid="settings-save"] button') as HTMLButtonElement).click();
    const req = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/settings/ai');
    expect(req.request.body).toEqual({ provider: 'OPENAI', model: 'gpt-test', apiKey: 'sk-local-123', baseUrl: undefined });
    req.flush({ ...ai, provider: 'OPENAI', model: 'gpt-test', apiKeySet: true });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="settings-saved"]')).not.toBeNull();
    expect((el.querySelector('[data-testid="api-key"]') as HTMLInputElement).value).toBe('');
  });

  it('never persists the key in browser storage', () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem');
    const { el, input, http } = setup();
    input('api-key', 'sk-secret');
    (el.querySelector('[data-testid="settings-save"] button') as HTMLButtonElement).click();
    http.expectOne('/api/settings/ai').flush(ai);
    expect(spy).not.toHaveBeenCalled();
    spy.mockRestore();
  });

  it('asks for a base URL for Ollama and nothing for "no AI"', () => {
    const { el, input } = setup();
    input('provider', 'OLLAMA');
    expect(el.querySelector('[data-testid="base-url"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="api-key"]')).toBeNull();
    input('provider', 'NONE');
    expect(el.querySelector('[data-testid="none-hint"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="model"]')).toBeNull();
  });

  it('shows the tone preset read-only', () => {
    const { el } = setup();
    expect(el.querySelector('[data-testid="tone"]')?.textContent).toContain('Realistisch');
    expect(el.querySelector('[data-testid="game-settings"] select, [data-testid="game-settings"] input')).toBeNull();
  });

  it('works without an active savegame', () => {
    const { el } = setup(null);
    expect(el.querySelector('[data-testid="tone"]')?.textContent).toContain('–');
  });
});
