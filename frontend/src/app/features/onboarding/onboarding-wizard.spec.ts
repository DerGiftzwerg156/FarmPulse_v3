import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { OnboardingView } from '../../core/api/models';
import { OnboardingWizard } from './onboarding-wizard';

const DRAFT: OnboardingView = {
  id: 7,
  status: 'DRAFT',
  freeTextRejected: false,
  cast: [
    { characterId: 1, name: 'Heike Brandt', role: 'BANK_ADVISOR', category: 'MANDATORY', jobRole: null, description: 'Genau, aber fair.' },
    { characterId: 2, name: 'Jonas Peters', role: 'EMPLOYEE', category: 'EMPLOYEE', jobRole: 'MECHANIC', description: 'Schraubt gern.' },
  ],
};

describe('OnboardingWizard', () => {
  function setup() {
    TestBed.configureTestingModule({
      imports: [OnboardingWizard],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(OnboardingWizard);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const http = TestBed.inject(HttpTestingController);
    const click = (id: string, index = 0) => {
      (el.querySelectorAll(`[data-testid="${id}"] button, button[data-testid="${id}"]`)[index] as HTMLButtonElement).click();
      fixture.detectChanges();
    };
    return { fixture, el, http, click, cmp: fixture.componentInstance };
  }

  function toCast(s: ReturnType<typeof setup>, freeTextRejected = false) {
    s.click('next-1');
    s.click('generate');
    s.http.expectOne('/api/onboarding').flush({ ...DRAFT, freeTextRejected });
    s.fixture.detectChanges();
  }

  it('step 1: shows backstory blocks and the default hint for an empty free text', () => {
    const { el, cmp, fixture } = setup();
    expect(el.querySelector('[data-testid="step-1"]')).not.toBeNull();
    expect(el.querySelectorAll('[data-testid="farm-origin"] option').length).toBe(4);
    expect(el.querySelector('[data-testid="default-hint"]')).not.toBeNull();
    cmp.form.controls.freeText.setValue('Mein Großvater hat den Hof gegründet.');
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="default-hint"]')).toBeNull();
  });

  it('step 1: blocks a negative starting capital but not an empty free text', () => {
    const { el, cmp, fixture, click } = setup();
    cmp.form.controls.startingCapitalTarget.setValue(-5);
    click('next-1');
    expect(el.querySelector('[data-testid="step-1"]')).not.toBeNull();
    cmp.form.controls.startingCapitalTarget.setValue(80000);
    fixture.detectChanges();
    click('next-1');
    expect(el.querySelector('[data-testid="step-2"]')).not.toBeNull();
  });

  it('step 2: manages count and roles of initial employees and sends a typed request', () => {
    const s = setup();
    s.cmp.form.patchValue({ startingCapitalTarget: 120000, withLegacyLoan: true, legacyLoanAmount: 30000, tonePreset: 'HARSH' });
    s.click('next-1');
    s.click('add-employee');
    s.click('add-employee');
    expect(s.el.querySelector('[data-testid="employee-count"]')?.textContent).toContain('2 Mitarbeiter');
    s.cmp.setEmployeeRole(1, 'ANIMAL_KEEPER');
    s.click('remove-employee', 0);
    expect(s.cmp.employees()).toEqual(['ANIMAL_KEEPER']);
    s.click('generate');
    const req = s.http.expectOne('/api/onboarding');
    expect(req.request.body).toEqual({
      farmOrigin: 'INHERITED', villageRelation: 'UNKNOWN', freeText: '', startingCapitalTarget: 120000,
      legacyLoanAmount: 30000, tonePreset: 'HARSH', initialEmployees: ['ANIMAL_KEEPER'],
    });
    req.flush(DRAFT);
    s.fixture.detectChanges();
    expect(s.el.querySelector('[data-testid="step-3"]')).not.toBeNull();
  });

  it('step 2: caps the number of employees', () => {
    const s = setup();
    for (let i = 0; i < 12; i++) s.cmp.addEmployee();
    expect(s.cmp.employees().length).toBe(10);
    expect(s.cmp.canAddEmployee()).toBe(false);
  });

  it('step 3: previews the cast and rerolls single characters and the whole cast', () => {
    const s = setup();
    toCast(s);
    expect(s.el.querySelectorAll('[data-testid="cast-member"]').length).toBe(2);
    expect(s.el.textContent).toContain('Heike Brandt');
    expect(s.el.textContent).toContain('Mechaniker:in');
    s.click('reroll-one', 1);
    const one = s.http.expectOne('/api/onboarding/7/reroll');
    expect(one.request.body).toEqual({ characterId: 2 });
    one.flush({ ...DRAFT, cast: [DRAFT.cast[0], { ...DRAFT.cast[1], characterId: 3, name: 'Lena Voss' }] });
    s.fixture.detectChanges();
    expect(s.el.textContent).toContain('Lena Voss');
    s.click('reroll-all');
    const all = s.http.expectOne('/api/onboarding/7/reroll');
    expect(all.request.body).toEqual({});
    all.flush(DRAFT);
  });

  it('step 3: tells the player when the free text was rejected', () => {
    const s = setup();
    toCast(s, true);
    expect(s.el.querySelector('[data-testid="free-text-rejected"]')).not.toBeNull();
  });

  it('shows backend errors instead of advancing', () => {
    const s = setup();
    s.click('next-1');
    s.click('generate');
    s.http.expectOne('/api/onboarding').flush(
      { code: 'INVALID_CAPITAL', message: 'Das Startkapital darf nicht negativ sein.', fields: {} },
      { status: 409, statusText: 'Conflict' },
    );
    s.fixture.detectChanges();
    expect(s.el.querySelector('[data-testid="wizard-error"]')?.textContent).toContain('Startkapital');
    expect(s.el.querySelector('[data-testid="step-2"]')).not.toBeNull();
  });

  it('steps 4 and 5: FS25 hint, then link a detected savegame and go home', () => {
    const s = setup();
    const nav = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    toCast(s);
    s.click('next-3');
    expect(s.el.querySelector('[data-testid="step-4"]')?.textContent).toContain('Farming Simulator 25');
    s.click('next-4');
    s.http.expectOne('/api/onboarding/unlinked-savegames').flush([]);
    s.fixture.detectChanges();
    expect(s.el.querySelector('[data-testid="no-detected"]')).not.toBeNull();
    expect((s.el.querySelector('[data-testid="confirm"] button') as HTMLButtonElement).disabled).toBe(true);

    s.click('reload-detected');
    s.http.expectOne('/api/onboarding/unlinked-savegames').flush([
      { savegameId: 'sg-a', mapName: 'Riverbend Springs', firstSeen: '2026-09-25T10:00:00', lastSeen: '2026-09-25T10:05:00', gameTime: 3_600_000 },
      { savegameId: 'sg-b', mapName: null, firstSeen: '2026-09-25T09:00:00', lastSeen: '2026-09-25T09:30:00', gameTime: 0 },
    ]);
    s.fixture.detectChanges();
    expect(s.el.textContent).toContain('Riverbend Springs');
    (s.el.querySelectorAll('[data-testid="detected-savegame"]')[1] as HTMLInputElement).dispatchEvent(new Event('change'));
    s.fixture.detectChanges();
    s.click('confirm');
    const confirm = s.http.expectOne('/api/onboarding/7/confirm');
    expect(confirm.request.body).toEqual({ savegameId: 'sg-b' });
    confirm.flush({ ...DRAFT, status: 'ACTIVE' });
    s.http.expectOne('/api/savegame').flush(null);
    expect(nav).toHaveBeenCalledWith('/');
  });
});
