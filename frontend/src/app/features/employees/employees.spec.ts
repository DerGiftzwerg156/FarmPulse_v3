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
  warningSent: false, salaryOverdue: false, timeOffUntilGameTime: null, ...over,
});
const posting: JobPostingView = { id: 3, jobRole: 'ANIMAL_KEEPER', status: 'OPEN', createdAtGameTime: 0, filledEmployeeId: null };
const applicant: ApplicationView = {
  id: 8, applicant: character({ id: 20, name: 'Lena Voss', role: 'APPLICANT' }), description: 'Hat lange auf einem Milchhof gearbeitet.',
  skill: 64, expectedSalary: 2500, status: 'PENDING',
};

describe('satisfactionBand', () => {
  it('bands the 0-100 score', () => {
    expect(satisfactionBand(80)).toBe('high');
    expect(satisfactionBand(50)).toBe('ok');
    expect(satisfactionBand(20)).toBe('low');
  });
});

describe('Employees', () => {
  function setup(employees: EmployeeView[], postings: JobPostingView[] = [], postingParam?: string) {
    TestBed.configureTestingModule({
      imports: [Employees],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(Employees);
    if (postingParam) fixture.componentRef.setInput('posting', postingParam);
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
    expect(el.querySelector('[data-testid="former-toggle"]')?.textContent).toContain('(1)');
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
    const { el, btn, fixture, http } = setup([]);
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
    expect(el.querySelector('[data-testid="applications"]')?.textContent).toContain('ändert daran nichts');
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
    expect(el.querySelectorAll('[data-testid="employee"]').length).toBe(1);
  });
});
