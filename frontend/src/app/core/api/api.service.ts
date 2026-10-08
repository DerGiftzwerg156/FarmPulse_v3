import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import * as M from './models';

/** Typed client for every backend endpoint (technical concept "Angular-REST-Endpunkte"). */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBase;

  private get<T>(path: string, params?: Record<string, string | number | readonly string[]>): Observable<T> {
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
  /** Roadmap V3 R3-W: drought status (rain of the last months, series, drought insurance quote, droughts). */
  drought(): Observable<M.DroughtStatusView> {
    return this.get('/drought');
  }
  contractAction(id: number, action: 'accept' | 'decline' | 'cancel' | string, body: unknown = {}): Observable<M.ContractView> {
    return this.post(`/contracts/${id}/${action}`, body);
  }
  /** TODO T-22: ask the workshop for a maintenance contract. */
  requestMaintenanceOffer(): Observable<M.ContractView> {
    return this.post('/maintenance/offer', {});
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
  markMailsRead(ids: number[]): Observable<M.MarkReadView> {
    return this.post('/mails/read', { ids });
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
  /** Roadmap V3 R3-K1: {@code farmlandIds} = own fields offered as collateral (omitted when none). */
  applyForCredit(amount: number, purpose: string, termMonths: number, farmlandIds: number[] = []): Observable<M.CreditApplicationView> {
    return this.post('/credit-applications', farmlandIds.length ? { amount, purpose, termMonths, farmlandIds } : { amount, purpose, termMonths });
  }
  // Roadmap V3 R3-M: price alarms and forward contracts
  priceAlarms(): Observable<M.PriceAlarmsView> {
    return this.get('/price-alarms');
  }
  createPriceAlarm(fillType: string, sellPoint: string | null, threshold: number, direction: 'ABOVE' | 'BELOW'): Observable<M.PriceAlarmView> {
    return this.post('/price-alarms', { fillType, sellPoint, threshold, direction });
  }
  reactivatePriceAlarm(id: number): Observable<M.PriceAlarmView> {
    return this.post(`/price-alarms/${id}/reactivate`);
  }
  deletePriceAlarm(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/price-alarms/${id}`);
  }
  forwardContracts(): Observable<M.ForwardContractsView> {
    return this.get('/forward-contracts');
  }
  forwardQuote(fillType: string, sellPoint: string, quantity: number, leadMonths: number): Observable<M.ForwardQuoteView> {
    return this.post('/forward-contracts/quote', { fillType, sellPoint, quantity, leadMonths });
  }
  concludeForward(fillType: string, sellPoint: string, quantity: number, leadMonths: number): Observable<M.ForwardContractView> {
    return this.post('/forward-contracts', { fillType, sellPoint, quantity, leadMonths });
  }
  // Roadmap V3 R3-K: collateral, liquidity plan, farm report
  collateral(): Observable<M.CollateralOverviewView> {
    return this.get('/credit/collateral');
  }
  requestSaleConsent(farmlandId: number): Observable<M.CollateralView> {
    return this.post(`/credit/collateral/${farmlandId}/sale-consent`);
  }
  /** Roadmap V3 R3-L1: the bank's consent to lease out a pledged field. */
  requestLeaseConsent(farmlandId: number): Observable<M.CollateralView> {
    return this.post(`/credit/collateral/${farmlandId}/lease-consent`);
  }
  // Roadmap V3 R3-L1: leasing out own fields
  leaseOut(): Observable<M.LeaseOutView> {
    return this.get('/lease-out');
  }
  leaseOutOffer(farmlandId: number, termYears: number, desiredRate: number): Observable<M.NegotiationView[]> {
    return this.post(`/farmlands/${farmlandId}/lease-out`, { termYears, desiredRate });
  }
  liquidityPlan(): Observable<M.LiquidityPlanView> {
    return this.get('/liquidity-plan');
  }
  farmReports(): Observable<M.FarmReportView[]> {
    return this.get('/farm-reports');
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
  specialRepayment(id: number, amount: number): Observable<M.SpecialRepaymentView> {
    return this.post(`/loans/${id}/sondertilgung`, { amount });
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
  /** Roadmap V3.1 R31-B5: get-well wishes to a sick or injured employee. */
  getWell(id: number): Observable<M.EmployeeView> {
    return this.post(`/employees/${id}/get-well`, {});
  }
  trainings(): Observable<M.TrainingOfferView[]> {
    return this.get('/trainings');
  }
  bookTraining(id: number, training: string): Observable<M.EmployeeView> {
    return this.post(`/employees/${id}/training`, { training });
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
  /** Roadmap V3 R3-V: own machines and used-machine deals; offers go through offer / withdraw. */
  vehicles(): Observable<M.VehiclesView> {
    return this.get('/vehicles');
  }
  offerVehicleForSale(vehicleId: string, askingPrice: number): Observable<M.VehicleDealView> {
    return this.post(`/vehicles/${encodeURIComponent(vehicleId)}/sale`, { askingPrice });
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

  // Roadmap V2 R2-B4: farm bookkeeping
  finances(): Observable<M.FinanceOverview> {
    return this.get('/finances');
  }
  /** Booking statement of a game month (default: the latest month with entries). */
  statement(year?: number, period?: number): Observable<M.StatementView> {
    return this.get('/finances/statement', year != null && period != null ? { year, period } : undefined);
  }

  // Roadmap V2 R2-E1: tax office and tax advisor
  tax(): Observable<M.TaxOverviewView> {
    return this.get('/tax');
  }
  requestTaxAdvisorOffer(): Observable<M.ContractView> {
    return this.post('/tax/advisor/offer', {});
  }

  // Roadmap V2 R2-E3: family field chosen by the player
  markFamilyField(farmlandId: number): Observable<void> {
    return this.http.put<void>(`${this.base}/farmlands/${farmlandId}/family-field`, {});
  }
  clearFamilyField(): Observable<void> {
    return this.http.delete<void>(`${this.base}/family-field`);
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

  // Roadmap V3 R3-T: milestones and the farm chronicle
  milestones(): Observable<M.MilestoneView[]> {
    return this.get('/milestones');
  }
  /** The chronicle as Markdown file; the file name comes from Content-Disposition. */
  chronicleFile(): Observable<HttpResponse<Blob>> {
    return this.http.get(`${this.base}/diary/chronicle`, { responseType: 'blob', observe: 'response' });
  }
  chronicle(): Observable<M.ChronicleView> {
    return this.get('/diary/chronicle/view');
  }
  farmSettings(): Observable<M.FarmSettingsView> {
    return this.get('/settings/farm');
  }
  saveFarmSettings(r: { farmName: string | null }): Observable<M.FarmSettingsView> {
    return this.http.put<M.FarmSettingsView>(`${this.base}/settings/farm`, r);
  }
  reputation(): Observable<M.ReputationView> {
    return this.get('/village-reputation');
  }

  // Roadmap V3 R3-H: trade with the neighbours
  trade(): Observable<M.TradeView> {
    return this.get('/trade');
  }
  requestGoods(neighborId: number, fillType: string, amount: number): Observable<M.CaseView> {
    return this.post(`/trade/neighbors/${neighborId}/request`, { fillType, amount });
  }
  askForWork(neighborId: number): Observable<M.CaseView> {
    return this.post(`/trade/neighbors/${neighborId}/work`);
  }
  /** Roadmap V3.1 R31-A3: own stables, the neighbours' animals and prices. */
  animalTrade(): Observable<M.AnimalTradeView> {
    return this.get('/trade/animals');
  }
  requestAnimals(neighborId: number, husbandryUniqueId: string, subType: string, count: number): Observable<M.CaseView> {
    return this.post(`/trade/neighbors/${neighborId}/animals/request`, { husbandryUniqueId, subType, count });
  }
  offerAnimals(neighborId: number, husbandryUniqueId: string, subType: string, count: number): Observable<M.CaseView> {
    return this.post(`/trade/neighbors/${neighborId}/animals/offer`, { husbandryUniqueId, subType, count });
  }

  // Roadmap V3.1 R31-A1 / R31-A2: contractor work, borrowed and demo machines
  /** `works` = the ticked works; the other options are checked as an addition to them. */
  contractorQuote(farmlandId: number, works: readonly string[] = []): Observable<M.ContractorQuoteView> {
    return this.get(`/contractor-work/fields/${farmlandId}`, works.length ? { works } : undefined);
  }
  /** 1 to `maxWorks` works of one field at once, done at the end of the next game day; one case per work. */
  orderContractorWork(farmlandId: number, works: readonly string[], fruitType: string | null): Observable<M.CaseView[]> {
    return this.post('/contractor-work', { farmlandId, works, fruitType });
  }
  machineLoans(): Observable<M.MachineLoanView[]> {
    return this.get('/machine-loans');
  }
  loanChoices(neighborId: number): Observable<M.LoanChoicesView> {
    return this.get(`/machine-loans/neighbors/${neighborId}`);
  }
  borrowMachine(neighborId: number, storeXmlFilename: string, days: number): Observable<M.MachineLoanView> {
    return this.post(`/machine-loans/neighbors/${neighborId}`, { storeXmlFilename, days });
  }
  demoChoices(): Observable<M.LoanChoicesView> {
    return this.get('/machine-loans/demo');
  }
  requestDemo(storeXmlFilename: string): Observable<M.MachineLoanView> {
    return this.post('/machine-loans/demo', { storeXmlFilename });
  }

  // Roadmap V3 R3-N: tablet in the home network
  lanStatus(): Observable<M.LanStatusView> {
    return this.get('/lan/status');
  }
  saveLanSettings(enabled: boolean): Observable<M.LanStatusView> {
    return this.http.put<M.LanStatusView>(`${this.base}/lan/settings`, { enabled });
  }
  setLanPin(pin: string): Observable<M.LanStatusView> {
    return this.http.put<M.LanStatusView>(`${this.base}/lan/pin`, { pin });
  }
  removeLanPin(): Observable<M.LanStatusView> {
    return this.http.delete<M.LanStatusView>(`${this.base}/lan/pin`);
  }
  lanLogin(pin: string): Observable<M.LanLoginView> {
    return this.post('/lan/login', { pin });
  }
  lanLogout(): Observable<void> {
    return this.post('/lan/logout');
  }

  // first-open hints of the apps (owner decision 2026-10-06)
  appHints(): Observable<M.AppHintsView> {
    return this.get('/app-hints');
  }
  markAppHintSeen(appId: string): Observable<M.AppHintsView> {
    return this.http.put<M.AppHintsView>(`${this.base}/app-hints/${appId}`, {});
  }

  // settings
  aiSettings(): Observable<M.AiSettingsView> {
    return this.get('/settings/ai');
  }
  saveAiSettings(r: { provider: string; model?: string; apiKey?: string; baseUrl?: string }): Observable<M.AiSettingsView> {
    return this.http.put<M.AiSettingsView>(`${this.base}/settings/ai`, r);
  }
  helperSettings(): Observable<M.HelperSettingsView> {
    return this.get('/settings/helpers');
  }
  saveHelperSettings(r: { helperWageMode: string; strictHelperLimit: boolean }): Observable<M.HelperSettingsView> {
    return this.http.put<M.HelperSettingsView>(`${this.base}/settings/helpers`, r);
  }
  // Roadmap V2 R2-F2: questions in the game
  promptSettings(): Observable<M.PromptSettingsView> {
    return this.get('/settings/prompts');
  }
  savePromptSettings(kinds: string[]): Observable<M.PromptSettingsView> {
    return this.http.put<M.PromptSettingsView>(`${this.base}/settings/prompts`, { kinds });
  }
  bypassSettings(): Observable<M.BypassSettingsView> {
    return this.get('/settings/vanilla-bypass');
  }
  saveBypassSettings(r: { reactionsEnabled: boolean }): Observable<M.BypassSettingsView> {
    return this.http.put<M.BypassSettingsView>(`${this.base}/settings/vanilla-bypass`, r);
  }
  fieldSettings(): Observable<M.FieldSettingsView> {
    return this.get('/settings/fields');
  }
  saveFieldSettings(r: { fieldHintsEnabled: boolean }): Observable<M.FieldSettingsView> {
    return this.http.put<M.FieldSettingsView>(`${this.base}/settings/fields`, r);
  }
  /** Roadmap V3.1 R31-B: burdening events of the authorities. */
  burdenSettings(): Observable<M.BurdenSettingsView> {
    return this.get('/settings/burdening-events');
  }
  saveBurdenSettings(r: {
    areaCheck: boolean;
    fertilizer: boolean;
    disease: boolean;
    sickLeave: boolean;
    nightWork: boolean;
    cropDamage: boolean;
    dieselTheft: boolean;
    investors?: boolean;
  }): Observable<M.BurdenSettingsView> {
    return this.http.put<M.BurdenSettingsView>(`${this.base}/settings/burdening-events`, r);
  }
  /** Roadmap V3.1 R31-B1: area payment application. */
  directPayment(): Observable<M.DirectPaymentStatusView> {
    return this.get('/direct-payment');
  }
  submitDirectPayment(id: number, fields: { farmlandId: number; crop: string }[]): Observable<M.DirectPaymentView> {
    return this.post(`/direct-payment/${id}/submit`, { fields });
  }
  /** Roadmap V3.1 R31-B2: investment grant. */
  investmentGrants(): Observable<M.GrantStatusView> {
    return this.get('/investment-grants');
  }
  applyGrant(kind: string, plannedSum: number): Observable<M.GrantView> {
    return this.post('/investment-grants', { kind, plannedSum });
  }
  grantProof(id: number): Observable<M.GrantView> {
    return this.post(`/investment-grants/${id}/proof`, {});
  }
  /** Roadmap V3.1 R31-B4: animal diseases and restricted zones. */
  animalDiseases(): Observable<M.DiseaseStatusView> {
    return this.get('/animal-diseases');
  }
  /** Roadmap V3.1 R31-D1: issues of the village newspaper. */
  newspaper(): Observable<M.IssueView[]> {
    return this.get('/newspaper');
  }
  /** Roadmap V3.1 R31-D2: village chat. */
  chatGroups(): Observable<M.ChatGroupView[]> {
    return this.get('/chat/groups');
  }
  chatMessages(groupId: number): Observable<M.ChatMessageView[]> {
    return this.get(`/chat/groups/${groupId}/messages`);
  }
  postChat(groupId: number, text: string): Observable<M.ChatPostView> {
    return this.post(`/chat/groups/${groupId}/messages`, { text });
  }
  /** Roadmap V3.1 R31-D6: farm holidays. */
  farmHoliday(): Observable<M.FarmHolidayView> {
    return this.get('/farm-holiday');
  }
  setupFarmHoliday(): Observable<M.FarmHolidayView> {
    return this.post('/farm-holiday', {});
  }
  /** Roadmap V3.1 R31-D7: cooperative shares. */
  // Roadmap V3.2 R32-G: bulk orders ("Sofort liefern" / "Ablehnen" go through caseAction)
  bulkOrders(): Observable<M.BulkOrdersView> {
    return this.get('/trade/bulk-orders');
  }
  bulkOrderMonths(caseId: number): Observable<M.BulkOrderMonthView[]> {
    return this.get(`/trade/bulk-orders/${caseId}/months`);
  }
  agreeBulkOrder(caseId: number, leadMonths: number): Observable<M.BulkOrderView> {
    return this.post(`/trade/bulk-orders/${caseId}/term`, { leadMonths });
  }
  /** Roadmap V3.2 R32-I: large investors ("Bank → Investoren"). */
  investors(): Observable<M.InvestorsView> {
    return this.get('/investors');
  }
  acceptInvestorPackage(caseId: number, contractId: number): Observable<M.InvestorContractView> {
    return this.post(`/investors/offers/${caseId}/packages/${contractId}/accept`);
  }
  deliverToInvestor(obligationId: number, quantity: number, husbandryUniqueId: string | null): Observable<unknown> {
    return this.post(`/investors/obligations/${obligationId}/deliver`, { quantity, husbandryUniqueId });
  }
  investorFieldConsent(contractId: number, farmlandId: number): Observable<M.InvestorContractView> {
    return this.post(`/investors/contracts/${contractId}/field-consent`, { farmlandId });
  }
  payInvestorPayment(paymentId: number): Observable<M.InvestorPaymentView> {
    return this.post(`/investors/payments/${paymentId}/pay`);
  }
  cooperative(): Observable<M.CooperativeView> {
    return this.get('/cooperative');
  }
  buyShares(count: number): Observable<M.CooperativeView> {
    return this.post('/cooperative/shares', { count });
  }
  cancelShares(count: number): Observable<M.CooperativeView> {
    return this.post('/cooperative/notices', { count });
  }
  /** Roadmap V3.1 R31-D8: diesel theft, tank lock and the theft module of the insurance. */
  dieselTheft(): Observable<M.DieselTheftView> {
    return this.get('/diesel-theft');
  }
  buyTankLock(vehicleId: string): Observable<M.DieselTheftView> {
    return this.post('/tank-locks', { vehicleId });
  }
  theftCover(contractId: number, enabled: boolean): Observable<M.ContractView> {
    return this.post(`/contracts/${contractId}/theft-cover`, { enabled });
  }
  gameSettings(): Observable<M.GameSettingsView> {
    return this.get('/settings/game');
  }
  tasks(): Observable<M.TasksView> {
    return this.get('/tasks');
  }
  calendar(): Observable<M.CalendarOverviewView> {
    return this.get('/calendar');
  }
  stables(): Observable<M.StablesView> {
    return this.get('/stables');
  }
  /** Roadmap V3.1 R31-K1: field outlines for the map of the Flurkarte. */
  fieldMap(): Observable<M.FieldMapView> {
    return this.get('/field-map');
  }
  fieldOverview(): Observable<M.FieldOverviewView> {
    return this.get('/field-overview');
  }
}
