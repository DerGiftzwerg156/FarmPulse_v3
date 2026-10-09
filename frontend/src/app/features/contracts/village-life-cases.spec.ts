import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { CaseView } from '../../core/api/models';
import { DAY, character } from '../../../testing/fixtures';
import { CaseCard } from './case-card';

const vcase = (over: Partial<CaseView> = {}): CaseView => ({
  id: 7, kind: 'STAMMTISCH_INVITATION', status: 'AWAITING_PLAYER', character: character({ name: 'Anna Albers' }), farmlandId: null,
  hectares: null, damageAmount: null, payoutAmount: null, costAmount: null, offerAmount: null, roundsUsed: 0, measureAgreed: false,
  reference: null, gameTime: 10 * DAY, deadlineGameTime: 12 * DAY, resolution: null, measureCost: null, ...over,
});

/** Roadmap V3.1 R31-D: regulars' table, crop damage claim, school visit, general assembly and board meeting. */
describe('CaseCard village life (R31-D)', () => {
  function setup(c: CaseView) {
    TestBed.configureTestingModule({ imports: [CaseCard], providers: [provideHttpClient(), provideHttpClientTesting()] });
    const fixture = TestBed.createComponent(CaseCard);
    fixture.componentRef.setInput('c', c);
    fixture.detectChanges();
    return { el: fixture.nativeElement as HTMLElement, http: TestBed.inject(HttpTestingController) };
  }

  it('attends the regulars table or declines it', () => {
    const { el, http } = setup(vcase());
    expect(el.querySelector('[data-testid="stammtisch"]')?.textContent).toContain('Anna Albers');
    (el.querySelector('[data-testid="case-accept"] button') as HTMLButtonElement).click();
    http.expectOne('/api/cases/7/accept');
  });

  it('pays or refuses a crop damage claim', () => {
    const { el, http } = setup(vcase({ kind: 'CROP_DAMAGE_CLAIM', farmlandId: 13, offerAmount: 600, quantity: 4 }));
    expect(el.querySelector('[data-testid="crop-damage-claim"]')?.textContent).toContain('600');
    (el.querySelector('[data-testid="case-decline"] button') as HTMLButtonElement).click();
    http.expectOne('/api/cases/7/decline');
  });

  it('accepts a school visit', () => {
    const { el } = setup(vcase({ kind: 'SCHOOL_VISIT', offerAmount: 150 }));
    expect(el.querySelector('[data-testid="school-visit"]')?.textContent).toContain('150');
  });

  it('votes at the general assembly and shows the board election', () => {
    const { el, http } = setup(vcase({ kind: 'COOP_ASSEMBLY', reference: 'GRAIN_STORE', direction: 'BOARD_ELECTION' }));
    expect(el.querySelector('[data-testid="coop-assembly"]')?.textContent).toContain('Getreidelager');
    expect(el.querySelector('[data-testid="board-election"]')).not.toBeNull();
    (el.querySelector('[data-testid="case-decline"] button') as HTMLButtonElement).click();
    http.expectOne('/api/cases/7/decline');
  });

  it('shows the result of a closed assembly', () => {
    const { el } = setup(vcase({ kind: 'COOP_ASSEMBLY', status: 'SETTLED', reference: 'DIVIDEND_UP', resolution: 'ACCEPTED', quantity: 62 }));
    expect(el.querySelector('[data-testid="case-accept"]')).toBeNull();
    expect(el.querySelector('[data-testid="assembly-result"]')?.textContent).toContain('Angenommen · 62 %');
  });

  it('confirms a board meeting', () => {
    const { el, http } = setup(vcase({ kind: 'COOP_BOARD_MEETING' }));
    expect(el.querySelector('[data-testid="board-meeting"]')).not.toBeNull();
    (el.querySelector('[data-testid="case-accept"] button') as HTMLButtonElement).click();
    http.expectOne('/api/cases/7/accept');
  });
});
