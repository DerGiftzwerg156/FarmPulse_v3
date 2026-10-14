import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AiSettingsView, BypassSettingsView, FieldSettingsView, PromptSettingsView } from '../../core/api/models';
import { Settings } from './settings';

const ai: AiSettingsView = { provider: 'ANTHROPIC', model: null, baseUrl: null, apiKeySet: true, providers: ['NONE', 'OPENAI', 'ANTHROPIC', 'GEMINI', 'OLLAMA'], editable: true };

describe('Settings', () => {
  function setup(game: unknown = { tonePreset: 'REALISTIC', toneLabel: 'realistisch-ausgewogen' },
    fields: FieldSettingsView = { fieldHintsEnabled: true, fieldsTracked: true },
    bypass: BypassSettingsView = { reactionsEnabled: true, interestSurchargePercent: 0 },
    prompts: PromptSettingsView = { available: true, kinds: ['CALL'], allKinds: ['CALL', 'CONTRACT_OFFER', 'TAX_BILL'] },
    aiView: AiSettingsView = ai) {
    TestBed.configureTestingModule({
      imports: [Settings],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(Settings);
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/settings/ai').flush(aiView);
    const g = http.expectOne('/api/settings/game');
    if (game) g.flush(game);
    else g.flush({ code: 'NO_ACTIVE_SAVEGAME', message: 'x', fields: {} }, { status: 409, statusText: 'Conflict' });
    http.expectOne('/api/settings/fields').flush(fields);
    http.expectOne('/api/settings/farm').flush({ farmName: null, mapName: 'Erlengrund' });
    http.expectOne('/api/settings/vanilla-bypass').flush(bypass);
    http.expectOne('/api/settings/prompts').flush(prompts);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const input = (id: string, v: string) => {
      const i = el.querySelector(`[data-testid="${id}"]`) as HTMLInputElement;
      i.value = v;
      i.dispatchEvent(new Event(i.tagName === 'SELECT' ? 'change' : 'input'));
      fixture.detectChanges();
    };
    /** Tabs (owner decision 2026-10-06): KI, Hof, Ereignisse, Im Spiel, Tablet & Netzwerk. */
    const toTab = (tab: string) => {
      fixture.componentRef.setInput('tab', tab);
      fixture.detectChanges();
    };
    return { fixture, http, el, input, toTab };
  }

  it('shows the active provider and a stored key without ever showing it', () => {
    const { el } = setup();
    expect(el.textContent).toContain('Aktiv: Anthropic');
    const key = el.querySelector('[data-testid="api-key"]') as HTMLInputElement;
    expect(key.type).toBe('password');
    expect(key.value).toBe('');
    expect(key.placeholder).toContain('hinterlegt');
  });

  it('is read-only on a tablet: the gaming PC alone changes provider, key and address (review 10/2026 Phase 0.4)', () => {
    const { el } = setup(undefined, undefined, undefined, undefined, { ...ai, editable: false });
    expect(el.querySelector('[data-testid="ai-readonly"]')?.textContent).toContain('Nur am Spiele-PC');
    expect((el.querySelector('[data-testid="provider"]') as HTMLSelectElement).disabled).toBe(true);
    expect((el.querySelector('[data-testid="api-key"]') as HTMLInputElement).disabled).toBe(true);
    expect((el.querySelector('[data-testid="settings-save"] button') as HTMLButtonElement).disabled).toBe(true);
  });

  it('is editable on the gaming PC', () => {
    const { el } = setup();
    expect(el.querySelector('[data-testid="ai-readonly"]')).toBeNull();
    expect((el.querySelector('[data-testid="api-key"]') as HTMLInputElement).disabled).toBe(false);
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
    const { toTab, el } = setup();
    toTab('hof');
    expect(el.querySelector('[data-testid="tone"]')?.textContent).toContain('Realistisch');
    expect(el.querySelector('[data-testid="game-settings"] select, [data-testid="game-settings"] input')).toBeNull();
  });

  it('works without an active savegame', () => {
    const { toTab, el } = setup(null);
    toTab('hof');
    expect(el.querySelector('[data-testid="tone"]')?.textContent).toContain('–');
  });
  // Roadmap V2 R2-C6
  it('switches the field work hints off', () => {
    const { toTab, el, http, fixture } = setup();
    toTab('ereignisse');
    const box = el.querySelector('[data-testid="field-hints"]') as HTMLInputElement;
    expect(box.checked).toBe(true);
    box.checked = false;
    box.dispatchEvent(new Event('change'));
    const req = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/settings/fields');
    expect(req.request.body).toEqual({ fieldHintsEnabled: false });
    req.flush({ fieldHintsEnabled: false, fieldsTracked: false });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="fields-untracked"]')?.textContent).toContain('aktuellen Mod-Version');
  });

  // R31-B5: the absence follows the days per period; the summary stays under "Ereignisse"
  it('shows the scaled sick leave under Ereignisse', () => {
    const { toTab, el, http } = setup();
    http.expectOne('/api/settings/burdening-events').flush({ areaCheck: true, fertilizer: true, disease: true,
      sickLeave: true, nightWork: true, cropDamage: false, dieselTheft: true, tonePreset: 'REALISTIC', idyllicFactor: 0.5,
      investors: true, sickLeaveDuration: { maxDays: null, daysPerPeriod: 2, scaled: true, sicknessDaysMin: 1,
        sicknessDaysMax: 1, accidentDaysMin: 1, accidentDaysMax: 1 } });
    toTab('ereignisse');
    expect(el.querySelector('[data-testid="sick-leave-range"]')?.textContent).toContain('bei 2 Tagen pro Monat: Krankheit 1, Arbeitsunfall 1');
    expect(el.querySelector('[data-testid="sick-leave-max-days"]')).toBeNull(); // expert setting
    expect(el.querySelector('[data-testid="bypass-settings"]')).toBeNull(); // moved to "Experte"
  });

  // Expert settings (owner decision 2026-10-14): frequency and cap of sickness / accidents, the moved cards
  it('sets frequency and cap of the sick leave in the expert tab', () => {
    const { toTab, el, http, fixture } = setup();
    const sick = { enabled: true, sicknessFrequencyPercent: 100, accidentFrequencyPercent: 100, sicknessPerYear: 1.8,
      accidentPerYear: 1.08, duration: { maxDays: null, daysPerPeriod: 30, scaled: true, sicknessDaysMin: 2,
        sicknessDaysMax: 5, accidentDaysMin: 3, accidentDaysMax: 10 } };
    http.expectOne('/api/settings/sick-leave').flush(sick);
    toTab('experte');
    expect(el.querySelector('[data-testid="expert-hint"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="bypass-settings"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="mods-settings"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="sick-leave-per-year"]')?.textContent).toContain('etwa 1,8 Krankheitsfälle');
    expect(el.querySelector('[data-testid="sick-leave-off"]')).toBeNull();

    const freq = el.querySelector('[data-testid="sickness-frequency"]') as HTMLInputElement;
    freq.value = '50';
    freq.dispatchEvent(new Event('change'));
    let req = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/settings/sick-leave');
    expect(req.request.body).toEqual({ maxDays: 0, sicknessFrequencyPercent: 50, accidentFrequencyPercent: 100 });
    req.flush({ ...sick, sicknessFrequencyPercent: 50, sicknessPerYear: 0.9 });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="sick-leave-per-year"]')?.textContent).toContain('etwa 0,9 Krankheitsfälle');
    freq.value = ''; // an emptied field keeps the value, nothing is saved
    freq.dispatchEvent(new Event('change'));
    http.expectNone((r) => r.method === 'PUT' && r.url === '/api/settings/sick-leave');
    expect(freq.value).toBe('50');

    const acc = el.querySelector('[data-testid="accident-frequency"]') as HTMLInputElement;
    acc.value = '9999'; // clamped
    acc.dispatchEvent(new Event('change'));
    req = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/settings/sick-leave');
    expect(req.request.body.accidentFrequencyPercent).toBe(500);
    expect(acc.value).toBe('500');
    req.flush({ ...sick, sicknessFrequencyPercent: 50, accidentFrequencyPercent: 500 });
    fixture.detectChanges();

    const select = el.querySelector('[data-testid="sick-leave-max-days"]') as HTMLSelectElement;
    expect(select.value).toBe('0');
    select.value = '3';
    select.dispatchEvent(new Event('change'));
    req = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/settings/sick-leave');
    expect(req.request.body).toEqual({ maxDays: 3, sicknessFrequencyPercent: 50, accidentFrequencyPercent: 500 });
    req.flush({ ...sick, duration: { ...sick.duration, maxDays: 3, sicknessDaysMax: 3, accidentDaysMax: 3 } });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="sick-leave-expert-range"]')?.textContent).toContain('Krankheit 2–3, Arbeitsunfall 3');

    (el.querySelector('[data-testid="sick-leave-reset"] button') as HTMLButtonElement).click();
    req = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/settings/sick-leave');
    expect(req.request.body).toEqual({ maxDays: 0, sicknessFrequencyPercent: 100, accidentFrequencyPercent: 100 });
  });

  // Roadmap V2 R2-D
  it('switches the reactions to the game menus off', () => {
    const { toTab, el, http, fixture } = setup(undefined, undefined, { reactionsEnabled: true, interestSurchargePercent: 1 });
    toTab('experte'); // expert setting since 2026-10-14
    const box = el.querySelector('[data-testid="bypass-reactions"]') as HTMLInputElement;
    box.checked = false;
    box.dispatchEvent(new Event('change'));
    const req = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/settings/vanilla-bypass');
    expect(req.request.body).toEqual({ reactionsEnabled: false });
    req.flush({ reactionsEnabled: false, interestSurchargePercent: 0 });
    fixture.detectChanges();
    expect((el.querySelector('[data-testid="bypass-reactions"]') as HTMLInputElement).checked).toBe(false);
  });

  // Roadmap V2 R2-F2
  it('switches the occasions asked in the game one by one', () => {
    const { toTab, el, http, fixture } = setup();
    toTab('spiel');
    const boxes = () => Array.from(el.querySelectorAll('[data-testid="prompt-kind"]')) as HTMLInputElement[];
    expect(boxes().map((b) => b.checked)).toEqual([true, false, false]);
    expect(el.querySelector('[data-testid="prompt-settings"]')?.textContent).toContain('Steuerbescheide bezahlen');
    boxes()[2].checked = true;
    boxes()[2].dispatchEvent(new Event('change'));
    const req = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/settings/prompts');
    expect(req.request.body).toEqual({ kinds: ['CALL', 'TAX_BILL'] });
    req.flush({ available: true, kinds: ['CALL', 'TAX_BILL'], allKinds: ['CALL', 'CONTRACT_OFFER', 'TAX_BILL'] });
    fixture.detectChanges();
    boxes()[0].checked = false;
    boxes()[0].dispatchEvent(new Event('change'));
    expect(http.expectOne((r) => r.method === 'PUT' && r.url === '/api/settings/prompts').request.body)
      .toEqual({ kinds: ['TAX_BILL'] });
  });

  it('says when the questions in the game are switched off in the configuration', () => {
    const { toTab, el } = setup(undefined, undefined, undefined,
      { available: false, kinds: ['CALL'], allKinds: ['CALL'] });
    toTab('spiel');
    expect(el.querySelector('[data-testid="prompts-off"]')).not.toBeNull();
    expect((el.querySelector('[data-testid="prompt-kind"]') as HTMLInputElement).disabled).toBe(true);
  });

  // Roadmap V3 R3-T2
  it('saves the farm name, the map name is the placeholder', () => {
    const { toTab, el, http, input } = setup();
    toTab('hof');
    expect((el.querySelector('[data-testid="farm-name"]') as HTMLInputElement).placeholder).toBe('Erlengrund');
    input('farm-name', '  Hof Lindenhain ');
    (el.querySelector('[data-testid="farm-save"] button') as HTMLButtonElement).click();
    const req = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/settings/farm');
    expect(req.request.body).toEqual({ farmName: 'Hof Lindenhain' });
    req.flush({ farmName: 'Hof Lindenhain', mapName: 'Erlengrund' });
  });
});

