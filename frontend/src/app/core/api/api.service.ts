import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import * as M from './models';

/** Typed client for every backend endpoint (technical concept "Angular-REST-Endpunkte"). */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBase;

  private get<T>(path: string, params?: Record<string, string | number>): Observable<T> {
    return this.http.get<T>(this.base + path, { params });
  }

  private post<T>(path: string, body: unknown = {}): Observable<T> {
    return this.http.post<T>(this.base + path, body);
  }

  // savegame
  savegame(): Observable<M.SavegameView | null> {
    return this.get<M.SavegameView | null>('/savegame');
  }

  // contracts & service cases (TODO T-20 / T-22)
  contracts(): Observable<M.ContractView[]> {
    return this.get('/contracts');
  }
  cases(): Observable<M.CaseView[]> {
    return this.get('/cases');
  }
  insuranceQuotes(): Observable<M.InsuranceQuoteView[]> {
    return this.get('/insurance/quotes');
  }
  requestInsuranceOffer(level: string): Observable<M.ContractView> {
    return this.post('/insurance/offer', { level });
  }
  contractAction(id: number, action: 'accept' | 'decline' | 'cancel' | string, body: unknown = {}): Observable<M.ContractView> {
    return this.post(`/contracts/${id}/${action}`, body);
  }
  /** TODO T-22: ask the owner of a field for a lease; the answer is an offer or a refusal. */
  requestLease(farmlandId: number): Observable<M.ContractView> {
    return this.post(`/farmlands/${farmlandId}/lease-request`, {});
  }
  caseAction(id: number, action: string, body: unknown = {}): Observable<M.CaseView> {
    return this.post(`/cases/${id}/${action}`, body);
  }

  // notices (bridge problems / decisions)
  notices(): Observable<M.NoticeView[]> {
    return this.get('/notices');
  }
  resolveNotice(id: number, action: string): Observable<M.NoticeView> {
    return this.post(`/notices/${id}/resolve`, { action });
  }

  // onboarding
  createOnboarding(r: M.OnboardingRequest): Observable<M.OnboardingView> {
    return this.post('/onboarding', r);
  }
  onboarding(id: number): Observable<M.OnboardingView> {
    return this.get(`/onboarding/${id}`);
  }
  reroll(id: number, characterId?: number): Observable<M.OnboardingView> {
    return this.post(`/onboarding/${id}/reroll`, characterId ? { characterId } : {});
  }
  unlinkedSavegames(): Observable<M.DetectedView[]> {
    return this.get('/onboarding/unlinked-savegames');
  }
  confirmOnboarding(id: number, savegameId: string): Observable<M.OnboardingView> {
    return this.post(`/onboarding/${id}/confirm`, { savegameId });
  }

  // mails
  mails(): Observable<M.MessageView[]> {
    return this.get('/mails');
  }
  mail(id: number): Observable<M.ThreadView> {
    return this.get(`/mails/${id}`);
  }
  reply(id: number, text: string): Observable<M.MessageView> {
    return this.post(`/mails/${id}/reply`, { text });
  }

  // calls
  pendingCalls(): Observable<M.MessageView[]> {
    return this.get('/calls/pending');
  }
  callLog(): Observable<M.MessageView[]> {
    return this.get('/calls');
  }
  acceptCall(id: number): Observable<M.MessageView> {
    return this.post(`/calls/${id}/accept`);
  }
  declineCall(id: number): Observable<M.MessageView> {
    return this.post(`/calls/${id}/decline`);
  }
  sayInCall(id: number, text: string): Observable<M.MessageView> {
    return this.post(`/calls/${id}/message`, { text });
  }
  completeCall(id: number): Observable<M.MessageView> {
    return this.post(`/calls/${id}/complete`);
  }

  // credit
  applyForCredit(amount: number, purpose: string, termMonths: number): Observable<M.CreditApplicationView> {
    return this.post('/credit-applications', { amount, purpose, termMonths });
  }
  creditApplications(): Observable<M.CreditApplicationView[]> {
    return this.get('/credit-applications');
  }
  acceptCounterOffer(id: number): Observable<M.CreditApplicationView> {
    return this.post(`/credit-applications/${id}/accept-counter`);
  }
  declineCounterOffer(id: number): Observable<M.CreditApplicationView> {
    return this.post(`/credit-applications/${id}/decline-counter`);
  }
  loans(): Observable<M.LoanView[]> {
    return this.get('/loans');
  }
  requestDeferral(id: number, message: string): Observable<M.DeferralView> {
    return this.post(`/loans/${id}/stundung`, { message });
  }

  // employees
  createJobPosting(jobRole: string): Observable<M.JobPostingView> {
    return this.post('/job-postings', { jobRole });
  }
  jobPostings(): Observable<M.JobPostingView[]> {
    return this.get('/job-postings');
  }
  applications(postingId: number): Observable<M.ApplicationView[]> {
    return this.get(`/job-postings/${postingId}/applications`);
  }
  interviewQuestion(postingId: number, appId: number, question: string, channel: 'MAIL' | 'CALL'): Observable<void> {
    return this.post(`/job-postings/${postingId}/applications/${appId}/interview-question`, { question, channel });
  }
  hire(postingId: number, appId: number): Observable<M.EmployeeView> {
    return this.post(`/job-postings/${postingId}/applications/${appId}/hire`);
  }
  employees(): Observable<M.EmployeeView[]> {
    return this.get('/employees');
  }
  raise(id: number, newSalary: number): Observable<M.EmployeeView> {
    return this.post(`/employees/${id}/raise`, { newSalary });
  }
  timeOff(id: number, days: number): Observable<M.EmployeeView> {
    return this.post(`/employees/${id}/time-off`, { days });
  }
  dismiss(id: number): Observable<M.EmployeeView> {
    return this.http.delete<M.EmployeeView>(`${this.base}/employees/${id}`);
  }

  // negotiation & market
  farmlands(): Observable<M.FarmlandView[]> {
    return this.get('/farmlands');
  }
  sellOffer(farmlandId: number, askingPrice: number): Observable<M.NegotiationView[]> {
    return this.post(`/farmlands/${farmlandId}/sell-offer`, { askingPrice });
  }
  negotiations(): Observable<M.NegotiationView[]> {
    return this.get('/negotiations');
  }
  startDirectNegotiation(characterId: number, farmlandId: number): Observable<M.NegotiationView> {
    return this.post('/negotiations/direct', { characterId, farmlandId });
  }
  offer(id: number, amount: number): Observable<M.OfferResultView> {
    return this.post(`/negotiations/${id}/offer`, { amount });
  }
  withdraw(id: number): Observable<M.NegotiationView> {
    return this.post(`/negotiations/${id}/withdraw`);
  }
  marketEvents(): Observable<M.MarketEventView[]> {
    return this.get('/market-events');
  }
  participate(id: number, participate: boolean): Observable<M.MarketEventView> {
    return this.post(`/market-events/${id}/participation`, { participate });
  }

  // storage & prices
  storage(): Observable<M.StorageOverview> {
    return this.get('/storage');
  }
  currentPrices(): Observable<M.PriceView[]> {
    return this.get('/prices/current');
  }
  priceHistory(q: { fillType?: string; sellPoint?: string; from?: number; to?: number }): Observable<M.PriceSeries[]> {
    const params: Record<string, string | number> = {};
    Object.entries(q).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== '') {
        params[k] = v;
      }
    });
    return this.get('/prices/history', params);
  }

  // village
  characters(): Observable<M.CharacterView[]> {
    return this.get('/characters');
  }
  character(id: number): Observable<M.CharacterDetailView> {
    return this.get(`/characters/${id}`);
  }
  sendMessage(id: number, text: string, channel: 'MAIL' | 'CALL'): Observable<M.ProactiveView> {
    return this.post(`/characters/${id}/messages`, { text, channel });
  }
  diary(): Observable<M.DiaryView[]> {
    return this.get('/diary');
  }
  addDiaryNote(title: string, text: string): Observable<M.DiaryView> {
    return this.post('/diary/entries', { title, text });
  }
  reputation(): Observable<M.ReputationView> {
    return this.get('/village-reputation');
  }

  // settings
  aiSettings(): Observable<M.AiSettingsView> {
    return this.get('/settings/ai');
  }
  saveAiSettings(r: { provider: string; model?: string; apiKey?: string; baseUrl?: string }): Observable<M.AiSettingsView> {
    return this.http.put<M.AiSettingsView>(`${this.base}/settings/ai`, r);
  }
  gameSettings(): Observable<M.GameSettingsView> {
    return this.get('/settings/game');
  }
}
