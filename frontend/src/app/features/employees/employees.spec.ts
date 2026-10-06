import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { ApplicationView, EmployeeView, JobPostingView } from '../../core/api/models';
import { character } from '../../../testing/fixtures';
import { Employees, satisfactionBand } from './employees';

const emp = (over: Partial<EmployeeView> = {}): EmployeeView => ({
  id: 1, character: character({ id: 11, name: 'Jonas Peters', role: 'EMPLOYEE' }), jobRole: 'MECHANIC', skill: 72, monthlySalary: 2800,
  status: 'ACTIVE', needs: { payFairness: 80, workload: 55, appreciation: 30, workingConditions: 70, satisfaction: 58.8, effectiveSkill: 70 },
  warningSent: false, salaryOverdue: false, timeOffUntilGameTime: null, onStrike: false, hoursThisMonth: null,
  hoursLastMonth: null, trainings: [], trainingInProgress: null, trainingUntilGameTime: null, ...over,
});
const posting: JobPostingView = { id: 3, jobRole: 'ANIMAL_KEEPER', status: 'OPEN', createdAtGameTime: 0, filledEmployeeId: null };
const applicant: ApplicationView = {
  id: 8, applicant: character({ id: 20, name: 'Lena Voss', role: 'APPLICANT' }), description: 'Hat lange auf einem Milchhof gearbeitet.',
  skill: 64, expectedSalary: 2500, status: 'PENDING', training: null,
};

describe('satisfactionBand', () => {
  it('bands the 0-100 score', () => {
    expect(satisfactionBand(80)).toBe('high');
    expect(satisfactionBand(50)).toBe('ok');
    expect(satisfactionBand(20)).toBe('low');
  });
});

describe('Employees', () => {
  /** Tabs (owner decision 2026-10-06): a posting link opens "Stellen & Bewerber", otherwise "Team". */
  function setup(employees: EmployeeView[], postings: JobPostingView[] = [], postingParam?: string, tab = postingParam ? 'stellen' : 'team') {
    TestBed.configureTestingModule({
      imports: [Employees],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(Employees);
    if (postingParam) fixture.componentRef.setInput('posting', postingParam);
    fixture.componentRef.setInput('tab', tab);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    if (postingParam) http.expectOne(`/api/job-postings/${postingParam}/applications`).flush([applicant]);
    http.expectOne('/api/employees').flush(employees);
    http.expectOne('/api/job-postings').flush(postings);
    if (postingParam) http.match(`/api/job-postings/${postingParam}/applications`).forEach((r) => r.flush([applicant]));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const btn = (id: string, i = 0) => el.querySelectorAll(`[data-testid="${id}"] button`)[i] as HTMLButtonElement;
    return { fixture, http, el, btn };
  }

  it('shows staff with aggregated and per-category satisfaction', () => {
    const { el } = setup([emp({ warningSent: true }), emp({ id: 2, status: 'TERMINATED' })]);
    expect(el.querySelectorAll('[data-testid="employee"]').length).toBe(1);
    expect(el.querySelector('[data-testid="satisfaction"]')?.textContent).toContain('Geht so');
    expect(el.querySelector('[data-testid="warning"]')).not.toBeNull();
    const meters = el.querySelectorAll('[data-testid="needs"] [role="meter"]');
    expect(meters.length).toBe(4);
    expect(meters[2].getAttribute('aria-valuenow')).toBe('30');
    expect(el.querySelector('[data-testid="needs"]')?.textContent).toContain('Wertschätzung');
  });

  it('lists the former employees in their own tab', () => {
    const { el } = setup([emp(), emp({ id: 2, status: 'TERMINATED' })], [], undefined, 'ehemalige');
    expect(el.querySelector('[data-testid="employee"]')).toBeNull();
    expect(el.querySelectorAll('[data-testid="former"] li').length).toBe(1);
  });

  it('grants a raise via a number field', () => {
    const { el, btn, fixture, http } = setup([emp()]);
    btn('raise-open').click();
    fixture.detectChanges();
    const input = el.querySelector('[data-testid="panel-value"]') as HTMLInputElement;
    expect(Number(input.value)).toBe(2940);
    input.value = '3100';
    input.dispatchEvent(new Event('input'));
    btn('panel-submit').click();
    const req = http.expectOne('/api/employees/1/raise');
    expect(req.request.body).toEqual({ newSalary: 3100 });
    req.flush(emp({ monthlySalary: 3100 }));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="employees-message"]')?.textContent).toContain('Jonas Peters');
    expect(el.querySelector('[data-testid="employee"]')?.textContent?.replace(/\s/g, ' ')).toContain('3.100 €');
  });

  it('shows the trainings of machine operators and books a paid training', () => {
    const op = emp({ jobRole: 'MACHINE_OPERATOR', trainings: ['COMBINE'] });
    const { el, btn, fixture, http } = setup([op, emp({ id: 2 })]);
    const cards = el.querySelectorAll('[data-testid="employee"]');
    expect(cards[0].querySelector('[data-testid="trainings"]')?.textContent).toContain('Mähdrescher');
    expect(cards[1].querySelector('[data-testid="trainings"]')).toBeNull();
    expect(cards[1].querySelector('[data-testid="training-open"]')).toBeNull();
    btn('training-open').click();
    fixture.detectChanges();
    http.expectOne('/api/trainings').flush([
      { training: 'LARGE_TRACTOR', cost: 1500, vehicleCategories: ['TRACTORSL'] },
      { training: 'COMBINE', cost: 3000, vehicleCategories: ['HARVESTERS'] },
      { training: 'TRUCK', cost: 4000, vehicleCategories: ['TRUCKS'] },
    ]);
    fixture.detectChanges();
    const select = el.querySelector('[data-testid="training-select"]') as HTMLSelectElement;
    expect(Array.from(select.options).map((o) => o.value)).toEqual(['LARGE_TRACTOR', 'TRUCK']);
    expect(el.querySelector('[data-testid="training-hint"]')?.textContent?.replace(/\s/g, ' ')).toContain('1.500 €');
    select.value = 'TRUCK';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="training-hint"]')?.textContent?.replace(/\s/g, ' ')).toContain('4.000 €');
    btn('training-submit').click();
    const req = http.expectOne('/api/employees/1/training');
    expect(req.request.body).toEqual({ training: 'TRUCK' });
    // owner decision 2026-10-06: the training is the whole next game day
    req.flush({ ...op, trainingInProgress: 'TRUCK', trainingFromGameTime: 86_400_000, trainingUntilGameTime: 2 * 86_400_000 });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="employees-message"]')?.textContent).toContain('morgen');
    expect(el.querySelector('[data-testid="training-scheduled"]')?.textContent).toContain('LKW');
    expect(el.querySelector('[data-testid="training-scheduled"]')?.textContent).toContain('Tag 1');
    expect(el.querySelector('[data-testid="in-training"]')).toBeNull();
    expect(el.querySelector('[data-testid="training-open"]')).toBeNull();
  });

  it('shows a running training until its end', () => {
    const { el } = setup([emp({ jobRole: 'MACHINE_OPERATOR', trainingInProgress: 'TRUCK', trainingFromGameTime: 0,
      trainingUntilGameTime: 86_400_000 })]);
    expect(el.querySelector('[data-testid="in-training"]')?.textContent).toContain('LKW');
    expect(el.querySelector('[data-testid="training-scheduled"]')).toBeNull();
  });

  // owner decision 2026-10-06: a hired employee starts with the next month
  it('shows a hired employee who has not started yet without actions and cancels him with a severance', () => {
    const pending = emp({ status: 'PENDING_START', startsAtGameTime: 12 * 86_400_000, startsAtPeriod: 2, severance: 4200 });
    const { el, btn, fixture, http } = setup([pending, emp({ id: 2, status: 'TERMINATED' })]);
    const card = el.querySelector('[data-testid="employee"]')!;
    expect(card.querySelector('[data-testid="starts-at"]')?.textContent).toContain('1. April (Tag 12)');
    expect(card.querySelector('[data-testid="pending-hint"]')?.textContent).toContain('kein Gehalt');
    expect(card.querySelector('[data-testid="raise-open"]')).toBeNull();
    expect(card.querySelector('[data-testid="timeoff-open"]')).toBeNull();
    expect(card.querySelector('[data-testid="needs"]')).toBeNull();
    expect(el.textContent?.replace(/\s/g, ' ')).toContain('Lohnkosten 0 €'); // not in the payroll yet
    btn('dismiss-open').click();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="dismiss-text"]')?.textContent?.replace(/\s/g, ' ')).toContain('4.200 €');
    btn('dismiss-confirm').click();
    http.expectOne((r) => r.method === 'DELETE' && r.url === '/api/employees/1').flush({ ...pending, status: 'TERMINATED' });
    http.match('/api/savegame').forEach((r) => r.flush(null));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="employees-message"]')?.textContent).toContain('zurückgenommen');
    expect(el.querySelectorAll('[data-testid="employee"]').length).toBe(0);
  });

  it('says that more applications arrive later today', () => {
    const { el } = setup([], [{ ...posting, applicationsAwaited: true }], '3');
    // the applications endpoint only lists arrived ones
    expect(el.querySelector('[data-testid="applicant"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="more-applicants"]')?.textContent).toContain('im Laufe des Tages');
  });

  it('says that the applications arrive tomorrow', () => {
    TestBed.configureTestingModule({
      imports: [Employees],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(Employees);
    fixture.componentRef.setInput('posting', '3');
    fixture.componentRef.setInput('tab', 'stellen');
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.match('/api/job-postings/3/applications').forEach((r) => r.flush([]));
    http.expectOne('/api/employees').flush([]);
    http.expectOne('/api/job-postings').flush([{ ...posting, applicationsAwaited: true }]);
    http.match('/api/job-postings/3/applications').forEach((r) => r.flush([]));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="no-applicants"]')?.textContent).toContain('morgen');
  });

  it('shows the training an applicant brings along', () => {
    TestBed.configureTestingModule({
      imports: [Employees],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(Employees);
    fixture.componentRef.setInput('posting', '3');
    fixture.componentRef.setInput('tab', 'stellen');
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.match('/api/job-postings/3/applications').forEach((r) => r.flush([{ ...applicant, training: 'TRUCK' }]));
    http.expectOne('/api/employees').flush([]);
    http.expectOne('/api/job-postings').flush([{ ...posting, jobRole: 'MACHINE_OPERATOR' }]);
    http.match('/api/job-postings/3/applications').forEach((r) => r.flush([{ ...applicant, training: 'TRUCK' }]));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="applicant-training"]')?.textContent).toContain('LKW');
  });

  it('grants time off', () => {
    const { el, btn, fixture, http } = setup([emp()]);
    btn('timeoff-open').click();
    fixture.detectChanges();
    const input = el.querySelector('[data-testid="panel-value"]') as HTMLInputElement;
    input.value = '2';
    input.dispatchEvent(new Event('input'));
    btn('panel-submit').click();
    const req = http.expectOne('/api/employees/1/time-off');
    expect(req.request.body).toEqual({ days: 2 });
    req.flush(emp({ timeOffUntilGameTime: 3 * 86_400_000 }));
    fixture.detectChanges();
    expect(el.textContent).toContain('Frei bis Tag 3');
  });

  it('dismisses after confirmation', () => {
    const { el, btn, fixture, http } = setup([emp()]);
    btn('dismiss-open').click();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="dismiss-text"]')?.textContent).toContain('Jonas Peters');
    btn('dismiss-confirm').click();
    http.expectOne((r) => r.method === 'DELETE' && r.url === '/api/employees/1').flush(emp({ status: 'TERMINATED' }));
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="employee"]').length).toBe(0);
  });

  it('creates a posting and lists applicants with fixed skill and salary expectation', () => {
    const { el, btn, fixture, http } = setup([], [], undefined, 'stellen');
    const select = el.querySelector('[data-testid="posting-role"]') as HTMLSelectElement;
    select.value = 'ANIMAL_KEEPER';
    select.dispatchEvent(new Event('change'));
    btn('posting-create').click();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/job-postings');
    expect(req.request.body).toEqual({ jobRole: 'ANIMAL_KEEPER' });
    req.flush(posting);
    http.expectOne('/api/job-postings/3/applications').flush([applicant]);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="applicant-skill"]')?.textContent).toContain('64');
    expect(el.querySelector('[data-testid="applicant-salary"]')?.textContent?.replace(/\s/g, ' ')).toContain('2.500 €');
    // the fixed facts are explained once in the app hint (owner decision 2026-10-06)
    expect(el.querySelector('[data-testid="applications"]')?.textContent).not.toContain('ändert daran nichts');
  });

  it('sends an interview question by call and hires', () => {
    const { el, btn, fixture, http } = setup([], [posting], '3');
    btn('interview-open').click();
    fixture.detectChanges();
    const ta = el.querySelector('[data-testid="interview-text"]') as HTMLTextAreaElement;
    ta.value = 'Wie gehst du mit kranken Kälbern um?';
    ta.dispatchEvent(new Event('input'));
    (el.querySelector('[data-testid="channel-call"]') as HTMLInputElement).dispatchEvent(new Event('change'));
    fixture.detectChanges();
    btn('interview-send').click();
    const q = http.expectOne('/api/job-postings/3/applications/8/interview-question');
    expect(q.request.body).toEqual({ question: 'Wie gehst du mit kranken Kälbern um?', channel: 'CALL' });
    q.flush(null);
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="employees-message"]')?.textContent).toContain('ruft dich zurück');

    btn('hire').click();
    http.expectOne('/api/job-postings/3/applications/8/hire').flush(emp({ id: 5, character: applicant.applicant, jobRole: 'ANIMAL_KEEPER' }));
    http.expectOne('/api/employees').flush([emp({ id: 5, character: applicant.applicant, jobRole: 'ANIMAL_KEEPER' })]);
    http.expectOne('/api/job-postings').flush([{ ...posting, status: 'FILLED', filledEmployeeId: 5 }]);
    http.match('/api/job-postings/3/applications').forEach((r) => r.flush([{ ...applicant, status: 'HIRED' }]));
    http.match('/api/savegame').forEach((r) => r.flush(null));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="employees-message"]')?.textContent).toContain('Lena Voss ist jetzt im Team');
    fixture.componentRef.setInput('tab', 'team');
    fixture.detectChanges();
    expect(el.querySelectorAll('[data-testid="employee"]').length).toBe(1);
  });

  it('names the first working day after hiring', () => {
    const { el, btn, fixture, http } = setup([], [posting], '3');
    btn('hire').click();
    http.expectOne('/api/job-postings/3/applications/8/hire').flush(emp({ id: 5, character: applicant.applicant,
      status: 'PENDING_START', startsAtGameTime: 12 * 86_400_000, startsAtPeriod: 2, severance: 3750 }));
    http.match('/api/employees').forEach((r) => r.flush([]));
    http.match('/api/job-postings').forEach((r) => r.flush([posting]));
    http.match('/api/job-postings/3/applications').forEach((r) => r.flush([]));
    http.match('/api/savegame').forEach((r) => r.flush(null));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="employees-message"]')?.textContent)
      .toContain('Lena Voss ist eingestellt und fängt am 1. April (Tag 12) an.');
  });
  // Roadmap V2 R2-A: machine operators drive the FS25 helpers
  it('shows the driven hours and a strike; the helper explanation is in the app hint', () => {
    const { el } = setup([emp({ jobRole: 'MACHINE_OPERATOR', onStrike: true, hoursThisMonth: 12.5, hoursLastMonth: 30 }),
      emp({ id: 2 })]);
    expect(el.textContent).not.toContain('Helfer ohne freien Fahrer');
    // the helper switches are settings (owner decision 2026-10-06)
    expect(el.querySelector('[data-testid="helper-settings"]')).toBeNull();
    expect(el.querySelector('[data-testid="strike"]')?.textContent).toContain('Streikt');
    const hours = el.querySelectorAll('[data-testid="hours"]');
    expect(hours.length).toBe(1);
    expect(hours[0].textContent).toContain('12.5 h in diesem Monat (Vormonat 30 h)');
  });

});
