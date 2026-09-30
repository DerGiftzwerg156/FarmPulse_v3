/** Mirrors backend `de.farmpulse.rpsim.api.Views` / `Requests`. No raw formula values exist in these types. */

export interface SavegameView {
  id: number;
  savegameId: string;
  mapName: string | null;
  gameTime: number;
  gameDay: number;
  balance: number;
  tonePreset: string;
  unreadMails: number;
  pendingCalls: number;
  reputationTier: string;
  /** FS25 calendar (TODO T-08); null before the first calendar export. */
  calendar?: CalendarView | null;
  /** Installed mods with overlapping features (TODO T-09). */
  detectedMods?: string[];
  /** Weather of the last farm_facts (R2-C2); null before the first weather export. */
  weather?: WeatherView | null;
}

/** rain / groundWetness 0..1, temperature in °C (null with an older mod). */
export interface WeatherView {
  raining: boolean;
  rainFallScale: number;
  groundWetness: number;
  temperature: number | null;
}

export interface CalendarView {
  period: number;
  periodName: string | null;
  dayInPeriod: number;
  daysPerPeriod: number;
  year: number | null;
  /** Season name from the game (TODO T-21), e.g. AUTUMN. */
  season?: string | null;
}

/** Recurring contract (TODO T-20 insurance, T-22 lease / maintenance). */
export interface ContractView {
  id: number;
  kind: 'INSURANCE' | 'LEASE' | 'MAINTENANCE' | string;
  status: 'OFFERED' | 'ACTIVE' | 'DECLINED' | 'CANCELLED' | 'ENDED' | string;
  character: CharacterRef | null;
  level: string | null;
  farmlandId: number | null;
  monthlyAmount: number;
  coveragePercent: number | null;
  deductible: number | null;
  termMonths: number | null;
  startedAtGameTime: number | null;
  endsAtGameTime: number | null;
  nextDueGameTime: number | null;
  offerExpiresAtGameTime: number | null;
  missedPayments: number;
  paymentOverdue: boolean;
  endReason: string | null;
  /** Lease (TODO T-22): new monthly rent offered for a renewal. */
  renewalAmount?: number | null;
  /** Lease (TODO T-22): price at which the owner sells the leased field. */
  purchasePrice?: number | null;
}

/** Simulated incident or one-off offer of a service character (TODO T-20 / T-22). */
export interface CaseView {
  id: number;
  kind: string;
  status: 'AWAITING_PLAYER' | 'IN_PROGRESS' | 'SETTLED' | 'DECLINED' | 'EXPIRED' | string;
  character: CharacterRef | null;
  farmlandId: number | null;
  hectares: number | null;
  damageAmount: number | null;
  payoutAmount: number | null;
  costAmount: number | null;
  offerAmount: number | null;
  roundsUsed: number;
  measureAgreed: boolean;
  reference: string | null;
  gameTime: number;
  deadlineGameTime: number | null;
  resolution: string | null;
  /** Own contribution of a joint measure (wildlife damage). */
  measureCost?: number | null;
  /** Animals of a vet visit / trader offer (TODO T-20). */
  quantity?: number | null;
  /** Trader offer: SELL (player sells) or BUY (player buys). */
  direction?: 'SELL' | 'BUY' | string | null;
  /** Head count when the trader offer was accepted. */
  baselineCount?: number | null;
  /** Title of a referred vanilla contract (TODO T-22), title of a tax bill or rule of an inspection (R2-E). */
  title?: string | null;
  /** Roadmap V2 R2-E4: amounts offered for a sponsoring request. */
  tiers?: number[] | null;
}

export interface InsuranceQuoteView {
  level: string;
  monthlyPremium: number;
  coveragePercent: number;
  deductible: number;
}

/** Dashboard notice of the fact layer (TODO T-02 rewind, T-03 bookings the game did not execute). */
export interface NoticeView {
  id: number;
  kind: 'REWIND_DECISION' | 'REWIND_RESENT' | 'INSTRUCTION_FAILED' | string;
  status: string;
  gameTime: number;
  details: Record<string, unknown>;
  relatedType: string | null;
  relatedId: number | null;
}

export interface PreviewView {
  characterId: number;
  name: string;
  role: string;
  category: string;
  jobRole: string | null;
  description: string;
}

export interface OnboardingView {
  id: number;
  status: string;
  freeTextRejected: boolean;
  cast: PreviewView[];
}

export interface DetectedView {
  savegameId: string;
  mapName: string | null;
  firstSeen: string;
  lastSeen: string;
  gameTime: number;
}

export interface OnboardingRequest {
  farmOrigin: string;
  villageRelation: string;
  freeText: string;
  startingCapitalTarget: number;
  legacyLoanAmount: number | null;
  tonePreset: string;
  initialEmployees: string[];
  /** Roadmap V2 R2-E3: family members, each switch on its own (all off = alone). */
  familyParents?: boolean;
  familyPartner?: boolean;
  familyChildren?: boolean;
}

export interface CharacterRef {
  id: number;
  name: string;
  role: string;
  status: string;
}

export interface MessageView {
  id: number;
  threadRootId: number | null;
  channel: 'MAIL' | 'CALL';
  initiatedBy: 'PLAYER' | 'CHARACTER';
  character: CharacterRef | null;
  subject: string | null;
  body: string | null;
  gameTime: number;
  read: boolean;
  category: string;
  eventType: string | null;
  formLink: string | null;
  usedFallback: boolean;
  callStatus: 'RINGING' | 'ACCEPTED' | 'DECLINED' | 'MISSED' | 'COMPLETED' | null;
  ringDeadlineGameTime: number | null;
  openTopic: boolean;
  relatedEntityType: string | null;
  relatedEntityId: number | null;
}

export interface ThreadView {
  message: MessageView;
  thread: MessageView[];
}

export interface CreditApplicationView {
  id: number;
  amount: number;
  purpose: string;
  termMonths: number;
  status: 'PROCESSING' | 'DECIDED' | 'ACCEPTED' | 'DECLINED';
  submittedAtGameTime: number;
  decisionVisibleAtGameTime: number;
  decision: 'APPROVED' | 'COUNTER_OFFER' | 'REJECTED' | null;
  reasonCategory: string | null;
  offeredAmount: number | null;
  offeredTermMonths: number | null;
  offeredInterestRatePercent: number | null;
  loanId: number | null;
}

export interface LoanPaymentView {
  gameTime: number;
  amount: number;
  type: string;
}

export interface LoanView {
  id: number;
  principal: number;
  remainingAmount: number;
  interestRatePercent: number;
  termMonths: number;
  monthlyInstallment: number;
  purpose: string;
  status: string;
  legacy: boolean;
  blocksNewCredit: boolean;
  nextDueGameTime: number;
  overdue: boolean;
  escalationLevel: number;
  missedInstallments: number;
  paidInstallments: number;
  deferredUntilGameTime: number | null;
  history: LoanPaymentView[];
  /** Installments left with the current installment (a Sondertilgung shortens the term). */
  remainingInstallments: number;
  specialRepayment: SpecialRepaymentTermsView;
}

export interface DeferralView {
  granted: boolean;
  reasonCategory: string | null;
}

/** Sondertilgung conditions: refusal = LOAN_NOT_ACTIVE / LOAN_DEFERRED / LOAN_OVERDUE when not allowed. */
export interface SpecialRepaymentTermsView {
  allowed: boolean;
  refusal: string | null;
  freeAmountLeft: number;
  feeRatePercent: number;
  payoffInterest: number;
}

export interface SpecialRepaymentView {
  amount: number;
  interest: number;
  fee: number;
  remainingAmount: number;
  remainingInstallments: number;
  paidOff: boolean;
}

export interface JobPostingView {
  id: number;
  jobRole: string;
  status: string;
  createdAtGameTime: number;
  filledEmployeeId: number | null;
}

export interface ApplicationView {
  id: number;
  applicant: CharacterRef;
  description: string | null;
  skill: number;
  expectedSalary: number;
  status: string;
  /** "Schulungen": training a machine operator applicant brings along, null = none. */
  training: string | null;
}

/** "Schulungen": one training of the catalog with its price and the FS25 shop categories it unlocks. */
export interface TrainingOfferView {
  training: string;
  cost: number;
  vehicleCategories: string[];
}

export interface NeedsView {
  payFairness: number;
  workload: number;
  appreciation: number;
  workingConditions: number;
  satisfaction: number;
  effectiveSkill: number;
}

export interface EmployeeView {
  id: number;
  character: CharacterRef;
  jobRole: string;
  skill: number;
  monthlySalary: number;
  status: string;
  needs: NeedsView;
  warningSent: boolean;
  salaryOverdue: boolean;
  timeOffUntilGameTime: number | null;
  /** Roadmap V2 R2-A5: the employee laid down work (the FS25 helper stopped, the salary keeps running). */
  onStrike: boolean;
  /** Roadmap V2 R2-A4: hours driven as FS25 helper; null without worked time from the mod or for other roles. */
  hoursThisMonth: number | null;
  hoursLastMonth: number | null;
  /** "Schulungen": finished trainings, the running one and its end (away until then, no helper). */
  trainings: string[];
  trainingInProgress: string | null;
  trainingUntilGameTime: number | null;
}

/** Roadmap V2 R2-A1 / R2-A3: who pays the FS25 helpers, strict helper limit. */
/** Roadmap V2 R2-D: reactions to the vanilla loan and the game's field menu. */
/** Roadmap V2 R2-F2: occasions asked in the game as a yes/no question. */
export interface PromptSettingsView {
  /** false: the backend asks nothing in the game (rpsim.bridge.ingame-prompts). */
  available: boolean;
  kinds: string[];
  allKinds: string[];
}

export interface BypassSettingsView {
  reactionsEnabled: boolean;
  /** > 0 while new credits cost more after repeated vanilla loans. */
  interestSurchargePercent: number;
}

/** Roadmap V2 R2-C6: field work hints of the cooperative. */
export interface FieldSettingsView {
  fieldHintsEnabled: boolean;
  /** The mod reports the fields (farm_facts.fields). */
  fieldsTracked: boolean;
}

export interface HelperSettingsView {
  helperWageMode: 'EMPLOYEES' | 'VANILLA';
  strictHelperLimit: boolean;
  /** The mod reports helper jobs (farm_facts.workforce). */
  workforceTracked: boolean;
}

export interface FarmlandView {
  farmlandId: number;
  hectares: number;
  referencePrice: number;
  ownerType: 'PLAYER' | 'CHARACTER' | 'UNCLAIMED';
  owner: CharacterRef | null;
  inNegotiation: boolean;
  /** false: hidden in the vanilla farmland menu (village, roads) - never traded (TODO T-11). */
  tradeable?: boolean;
  /** Leased to the player (TODO T-22): the game shows it as the player's, the owner stays the character. */
  leased?: boolean;
  /** Roadmap V2 R2-C: crop (FS25 fruit type) and growth phase of a field the player farms; null without field export. */
  fruitType?: string | null;
  phase?: FieldPhase | null;
  /** Roadmap V2 R2-E3: marked by the player as the family field. */
  familyField?: boolean;
}

export type FieldPhase = 'EMPTY' | 'GROWING' | 'HARVESTABLE' | 'HARVESTED' | 'WITHERED';

export interface OfferView {
  round: number;
  offeredBy: 'PLAYER' | 'CHARACTER';
  characterName: string | null;
  amount: number;
  result: string;
  counterAmount: number | null;
  gameTime: number;
}

export interface NegotiationView {
  id: number;
  assetType: string;
  assetId: string;
  kind: 'AUCTION' | 'DIRECT' | 'SALE_OFFER';
  direction: 'PLAYER_BUYS' | 'PLAYER_SELLS';
  initiatedBy: string;
  status: 'OPEN' | 'ACCEPTED' | 'REJECTED' | 'WITHDRAWN' | 'LOST' | 'EXPIRED';
  counterpart: CharacterRef | null;
  announcer: CharacterRef | null;
  basePrice: number;
  askingPrice: number | null;
  roundsUsed: number;
  maxRounds: number;
  lastCounterOffer: number | null;
  finalPrice: number | null;
  closesAtGameTime: number | null;
  winner: CharacterRef | null;
  offers: OfferView[];
}

export interface OfferResultView {
  result: string;
  counterAmount: number | null;
  roundsLeft: number;
  negotiation: NegotiationView;
}

export interface MarketEventView {
  id: number;
  eventType: string;
  status: string;
  fillType: string | null;
  sellPoint: string | null;
  peakMultiplier: number | null;
  fixedPrice: number | null;
  maxQuantity: number | null;
  deadlineGameTime: number | null;
  subsidyAmount: number | null;
  startGameTime: number;
  endGameTime: number | null;
  character: CharacterRef | null;
  deliveredQuantity: number | null;
  endReason: string | null;
  playerParticipation: boolean | null;
}

export interface StorageView {
  fillType: string;
  amount: number;
  capacity: number;
  bestPrice: number;
  bestSellPoint: string | null;
  value: number;
}

/** Roadmap V2 R2-B4: farm bookkeeping from the mod's booking journal (amounts signed, expenses negative). */
export type FinanceClass = 'OPERATING_INCOME' | 'OPERATING_EXPENSE' | 'INVESTMENT' | 'DIVESTMENT' | 'FINANCING' | 'IGNORE';

export interface FinanceLineView {
  category: string;
  amount: number;
  financeClass: FinanceClass;
}

export interface FinanceMonthView {
  year: number;
  period: number;
  complete: boolean;
  operatingIncome: number;
  operatingExpenses: number;
  operatingResult: number;
  investment: number;
  divestment: number;
  financing: number;
  ignored: number;
  lines: FinanceLineView[];
}

/** Roadmap V2 R2-E1: traceable calculation of a tax assessment. */
export interface TaxAssessmentView {
  taxYear: number;
  months: number;
  operatingIncome: number;
  /** Negative. */
  operatingExpense: number;
  depreciation: number;
  interest: number;
  profit: number;
  allowance: number;
  taxable: number;
  ratePercent: number;
  advisorReduction: number;
  tax: number;
  prepayments: number;
  /** Positive = back payment, negative = refund. */
  balance: number;
  auditStatus: string | null;
}

/** Roadmap V2 R2-E1: tax overview on the bank page. */
export interface TaxOverviewView {
  currentYear: number | null;
  incomeSoFar: number;
  expenseSoFar: number;
  estimatedTax: number;
  ratePercent: number;
  allowance: number;
  nextPrepayment: number | null;
  nextPrepaymentPeriod: number | null;
  lastAssessment: TaxAssessmentView | null;
  advisorActive: boolean;
  journalAvailable: boolean;
  openBills: number;
}

export interface FinanceOverview {
  /** false: the mod exports no booking journal (older mod version). */
  available: boolean;
  months: FinanceMonthView[];
}

export interface StorageOverview {
  gameTime: number;
  totalValue: number;
  items: StorageView[];
}

export interface PriceView {
  sellPoint: string;
  sellPointName: string;
  fillType: string;
  currentPrice: number;
  /** Price trend shown by the game (TODO T-10). */
  trend?: 'CLIMBING' | 'FALLING' | 'STABLE' | null;
}

export interface PricePoint {
  gameTime: number;
  price: number;
}

export interface PriceSeries {
  sellPoint: string;
  sellPointName: string;
  fillType: string;
  points: PricePoint[];
}

export interface FarmlandShort {
  farmlandId: number;
  hectares: number;
  referencePrice: number;
  inNegotiation: boolean;
}

export type TrustLevel = 'VERY_GOOD' | 'GOOD' | 'NEUTRAL' | 'STRAINED' | 'BAD';

export interface CharacterView {
  id: number;
  name: string;
  role: string;
  category: string;
  status: string;
  trustLevel: TrustLevel;
  shortDescription: string | null;
  farmlands: FarmlandShort[];
}

export interface CharacterDetailView extends CharacterView {
  traits: string | null;
  speechStyle: string | null;
  backstory: string | null;
  pacingActive: boolean;
  openTopic: boolean;
  recentMessages: MessageView[];
}

export interface ProactiveView {
  message: MessageView;
  pacingActive: boolean;
}

export interface DiaryView {
  id: number;
  gameTime: number;
  gameDay: number;
  entryType: 'AUTO' | 'PLAYER_NOTE';
  category: string | null;
  title: string;
  text: string | null;
}

export interface ReputationView {
  tier: 'GOOD' | 'NEUTRAL' | 'CONTROVERSIAL';
  label: string;
}

/** Roadmap V3 R3-H: app "Handel" - neighbours, own silo goods and the offers, requests and contracts. */
export interface TradeStockView {
  fillType: string;
  amount: number;
  /** Price per 1000 l the neighbour asks from the player; null = no price known. */
  unitPrice: number | null;
}

export interface TradeNeighborView {
  id: number;
  name: string;
  /** DAIRY / ARABLE / MIXED */
  role: string | null;
  trustLevel: TrustLevel;
  stock: TradeStockView[];
  needs: string[];
  farmlands: number[];
}

export interface TradeSiloView {
  fillType: string;
  amount: number;
  freeCapacity: number;
}

export interface TradeView {
  /** The mod reports the own silos (else no trade: older mod). */
  silosTracked: boolean;
  /** The mod reports the neighbour fields (else no stock from harvests, no contracts). */
  fieldsTracked: boolean;
  missionLimitReached: boolean | null;
  silos: TradeSiloView[];
  neighbors: TradeNeighborView[];
  cases: CaseView[];
}

/** Roadmap V3 R3-N1..N3: home-network access (card "Tablet & Netzwerk", PIN login). */
export interface LanStatusView {
  enabled: boolean;
  pinSet: boolean;
  /** The request comes from the gaming PC (loopback): only it may change switch and PIN. */
  gamePc: boolean;
  /** This device may use the API (gaming PC, no PIN set, or a valid session). */
  authenticated: boolean;
  /** http://<private IPv4>:<port> of the gaming PC. */
  urls: string[];
  pinMinLength: number;
  pinMaxLength: number;
}

export interface LanLoginView {
  result: 'OK' | 'NO_PIN' | 'WRONG_PIN' | 'LOCKED';
  lockedSeconds: number;
}

export interface AiSettingsView {
  provider: string;
  model: string | null;
  baseUrl: string | null;
  apiKeySet: boolean;
  providers: string[];
}

export interface GameSettingsView {
  tonePreset: string;
  toneLabel: string;
}

export interface ApiError {
  code: string;
  message: string;
  fields: Record<string, string>;
}

/** Hof-Tablet "Aufgaben": one open decision of any area (type names the payload that is set). */
export type TaskType = 'CASE' | 'CONTRACT_OFFER' | 'LEASE_RENEWAL' | 'CREDIT_COUNTER' | 'CALL' | 'NEGOTIATION' | 'MARKET_OFFER' | 'POSTING';

export interface TaskView {
  key: string;
  type: TaskType;
  kind: string | null;
  deadlineGameTime: number | null;
  gameTime: number;
  serviceCase: CaseView | null;
  contract: ContractView | null;
  application: CreditApplicationView | null;
  call: MessageView | null;
  negotiation: NegotiationView | null;
  marketEvent: MarketEventView | null;
  posting: JobPostingView | null;
  pendingApplicants: number | null;
}

export interface TasksView {
  items: TaskView[];
  /** Yes/no questions waiting in the game (Alt+J). */
  waitingPrompts: number;
}

/** Hof-Tablet "Kalender". */
export interface AgendaEntryView {
  gameTime: number;
  kind: 'MONTH_START' | 'FESTIVAL' | 'TAX_ASSESSMENT' | 'TAX_PREPAYMENT' | 'LOAN_INSTALLMENT' | 'SALARIES' | 'CONTRACT_PAYMENT' | 'LEASE_END' | string;
  subKind: string | null;
  title: string | null;
  amount: number | null;
  reference: string | null;
}

export interface DebitView {
  kind: 'SALARIES' | 'LOAN' | 'CONTRACT' | 'RETIREMENT' | string;
  subKind: string | null;
  label: string | null;
  amount: number;
  count: number;
}

export interface YearEventView {
  period: number;
  kind: 'FESTIVAL' | 'TAX_ASSESSMENT' | 'TAX_PREPAYMENT' | 'ROTATION_CHECK' | 'FAMILY_BIRTHDAY' | 'FAMILY_WEDDING_DAY' | 'SCHOOL_START' | string;
  reference: string | null;
}

export interface CalendarOverviewView {
  gameTime: number;
  currentPeriod: number | null;
  year: number | null;
  daysPerPeriod: number;
  nextMonthStart: number;
  nextPeriod: number | null;
  agenda: AgendaEntryView[];
  monthStartDebits: DebitView[];
  monthStartTotal: number;
  yearEvents: YearEventView[];
}

/** Hof-Tablet "Stall": husbandries of the last farm_facts (R2-A7). */
export interface StablesView {
  /** false with an older mod: animals only, no husbandry values. */
  tracked: boolean;
  animals: number;
  keepers: number;
  animalsPerKeeper: number;
  healthWarnBelow: number;
  foodWarnBelow: number;
  waterWarnBelow: number;
  barns: BarnView[];
  vetDue: { type: string; gameTime: number }[];
}

export interface BarnView {
  husbandryUniqueId: string;
  type: string;
  count: number;
  value: number;
  /** 0..100 */
  health: number | null;
  productivity: number | null;
  /** 0..1 */
  food: number | null;
  water: number | null;
  conditions: { title: string; ratio: number }[];
  /** Announced animal welfare inspection of this husbandry (deadline). */
  inspectionDeadline: number | null;
}

/** Hof-Tablet "Flurkarte": fields the player farms with what needs doing and the crop rotation (R2-C / R2-E2). */
export interface FieldOverviewView {
  year: number | null;
  tracked: boolean;
  fields: FieldRowView[];
  rotation: RotationPreviewView | null;
}

export interface FieldRowView {
  farmlandId: number;
  name: string | null;
  hectares: number | null;
  fruitType: string | null;
  phase: FieldPhase;
  leased: boolean;
  familyField: boolean;
  weedsHigh: boolean;
  stonesHigh: boolean;
  needsLime: boolean;
  needsPlow: boolean;
  previousCrop: string | null;
  currentCrop: string | null;
  /** CHANGED, SAME (violation at the end of the year) or UNKNOWN. */
  rotation: 'CHANGED' | 'SAME' | 'UNKNOWN';
  rotationViolations: number;
}

export interface RotationPreviewView {
  changedHectares: number;
  premium: number;
  cut: boolean;
  sameFields: number[];
  premiumPerHa: number;
}
