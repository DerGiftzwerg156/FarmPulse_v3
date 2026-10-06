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
  /** Roadmap V3.1 R31-A4: snow days of the running winter month and of the whole winter (winter service only). */
  snowDays?: number | null;
  snowDaysTotal?: number | null;
  /** R31-D8: module "Diebstahl" of the storm / hail insurance (null for other contracts). */
  theftCover?: boolean | null;
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

/** Roadmap V3 R3-W: one game month - rain and observed time in percent. */
export interface DroughtMonthView {
  monthIndex: number;
  month: string;
  rainPercent: number | null;
  observedPercent: number;
  rating: 'DRY' | 'WET' | 'UNKNOWN' | 'OUTSIDE' | 'RUNNING' | string;
}

export interface DroughtQuoteView {
  hectares: number;
  premiumPerHectare: number;
  monthlyPremium: number;
  payoutPerHectare: number;
  payout: number;
}

export interface DroughtView {
  id: number;
  firstMonth: string;
  lastMonth: string;
  dryMonths: number;
  declaredGameTime: number;
  crops: string[];
  priceEvents: number;
  insuranceResult: 'PAID' | 'NONE' | 'TOO_LATE' | 'COVER_SUSPENDED' | string;
  insuredHectares: number | null;
  insurancePayout: number | null;
  aidHectares: number | null;
  aidCaseId: number | null;
}

export interface DroughtStatusView {
  enabled: boolean;
  growthMonths: string[];
  minPeriods: number;
  maxRainPercent: number;
  minObservedPercent: number;
  dryMonths: number;
  seriesStartMonth: string | null;
  seriesDeclared: boolean;
  months: DroughtMonthView[];
  quote: DroughtQuoteView;
  aidPerHectare: number;
  aidDeductionPercent: number;
  droughts: DroughtView[];
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
  /** Roadmap V3 R3-T2: optional farm name (heads the chronicle). */
  farmName?: string | null;
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
  /** Roadmap V3 R3-K1: own fields offered as collateral by the player. */
  collateralFarmlandIds?: number[];
  /** Roadmap V3 R3-K1: fields the bank names for a counter offer "mit Grundschuld". */
  proposedFarmlandIds?: number[];
  collateralValue?: number | null;
  coveragePercent?: number | null;
  interestDiscountPercent?: number | null;
  collateralRequired?: boolean | null;
}

/** Roadmap V3 R3-K1: a field with a Grundschuld (status REQUESTED / PROPOSED / PLEDGED / RELEASED / REALISED). */
export interface CollateralView {
  farmlandId: number;
  collateralValue: number;
  status: string;
  /** The bank agreed to a sale in the tool (the proceeds repay the collateral value). */
  saleConsent: boolean;
  loanId: number | null;
  purpose: string | null;
  /** Roadmap V3 R3-L1: the bank agreed to lease the field out (the Grundschuld stays). */
  leaseConsent?: boolean;
}

/** Roadmap V3 R3-L1: term range of the lease-out form and the lease-out contracts (newest first). */
export interface LeaseOutView {
  termYearsMin: number;
  termYearsMax: number;
  contracts: ContractView[];
}

export interface CollateralOptionView {
  farmlandId: number;
  hectares: number;
  price: number;
  collateralValue: number;
}

export interface CollateralOverviewView {
  loanToValuePercent: number;
  requiredAboveSharePercent: number;
  maxInterestDiscountPercent: number;
  /** Loans above this amount need collateral for the part above it. */
  requiredAboveAmount: number;
  eligible: CollateralOptionView[];
  pledged: CollateralView[];
}

/** Roadmap V3 R3-K2: liquidity plan. */
export interface PlanPosting {
  /** SALARIES / LOAN / CONTRACT / RETIREMENT / TAX_PREPAYMENT */
  kind: string;
  /** LOAN: purpose; CONTRACT: kind[:farmland]; TAX_PREPAYMENT: Q1..Q4 */
  label: string | null;
  amount: number;
  /** Not fixed yet (tax prepayment after the next year change). */
  estimate: boolean;
}

export interface PlanMonth {
  monthIndex: number;
  startGameTime: number;
  period: number | null;
  year: number | null;
  postings: PlanPosting[];
  knownTotal: number;
  incomeEstimate: number | null;
  /** PREVIOUS_YEAR / AVERAGE / NONE */
  incomeSource: string;
  reserve: number;
  balanceEnd: number;
  belowZero: boolean;
  belowReserve: boolean;
}

export interface LiquidityPlanView {
  available: boolean;
  journalAvailable: boolean;
  balance: number;
  reserveFactor: number;
  months: PlanMonth[];
  firstBelowZero: PlanMonth | null;
  firstBelowReserve: PlanMonth | null;
}

/** Roadmap V3 R3-K3: farm report of a finished FS25 year. */
export interface FarmReportSnapshot {
  staff: number;
  monthlyWages: number;
  animals: number;
  averageHealth: number | null;
  reputationTier: string;
  trust: { characterId: number; name: string; role: string | null; level: string }[];
}

export interface FarmReportView {
  year: number;
  months: number;
  income: { category: string; amount: number }[];
  expenses: { category: string; amount: number }[];
  totals: { operatingIncome: number; operatingExpense: number; operatingResult: number; investment: number; divestment: number; financing: number };
  tax: { status: string; profit: number | null; tax: number | null } | null;
  fields: { farmlandId: number; fruitType: string; hectares: number | null; harvested: boolean; withered: boolean; yieldLiters: number | null }[];
  rain: { period: number; rainHours: number; observedHours: number }[];
  stables: { type: string; count: number; health: number | null; productivity: number | null }[];
  welfareInspections: number;
  snapshot: FarmReportSnapshot;
  previous: FarmReportSnapshot | null;
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
  /** Roadmap V3 R3-K1: fields with a Grundschuld for this loan. */
  collateral?: CollateralView[];
  /** Roadmap V3 R3-K3: rate cuts of the annual review so far (percentage points). */
  rateCutTotalPercent?: number;
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
  /** Roadmap V3 R3-P2: end of the training of an apprentice. */
  apprenticeshipEndsAtGameTime?: number | null;
  /** Roadmap V3.1 R31-A5: end of a seasonal worker's fixed-term contract. */
  contractEndsAtGameTime?: number | null;
  /** Roadmap V3.1 R31-B5: SICKNESS / ACCIDENT while the employee is away (ON_LEAVE), its end, wishes sent. */
  absenceKind?: string | null;
  absenceUntilGameTime?: number | null;
  getWellSent?: boolean;
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
/** Roadmap V3 R3-T2: optional farm name; the map name is the fallback. */
export interface FarmSettingsView {
  farmName: string | null;
  mapName: string | null;
}

/** Roadmap V3 R3-T1: a reached milestone. */
export interface MilestoneView {
  key: 'LOAN_REPAID' | 'YEAR_WITHOUT_DELAY' | 'AREA' | 'RECORD_HARVEST' | 'CROP_ROTATION' | 'NEIGHBOR_TRADE';
  title: string | null;
  gameTime: number;
  gameDay: number;
}

/** Roadmap V3 R3-T2: the farm chronicle (same content as the Markdown file). */
export interface ChronicleView {
  farmName: string;
  gameTime: number;
  gameDay: number;
  backstory: string | null;
  milestones: { key: string; title: string; text: string | null; gameTime: number; gameDay: number }[];
  days: { gameDay: number; entries: { gameTime: number; entryType: string; title: string; text: string | null; note: boolean }[] }[];
  reports: ChronicleReport[];
}

export interface ChronicleReport {
  year: number;
  months: number;
  operatingIncome: number;
  operatingExpense: number;
  operatingResult: number;
  taxStatus: string | null;
  profit: number | null;
  tax: number | null;
  income: { label: string; amount: number }[];
  expenses: { label: string; amount: number }[];
  fields: { farmlandId: number; fruit: string; hectares: number | null; harvested: boolean; withered: boolean; yieldLiters: number | null }[];
}

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
  /** Roadmap V3 R3-K1: REQUESTED / PROPOSED / PLEDGED when the field is collateral; null otherwise. */
  collateral?: string | null;
  /** Roadmap V3 R3-K1: the bank agreed to a sale of the pledged field. */
  saleConsent?: boolean;
  /** Roadmap V3 R3-L1: leased out to a neighbour - no owner in the game, still the player's in the tool. */
  leasedOut?: boolean;
  /** Roadmap V3 R3-L1: the bank agreed to lease the pledged field out. */
  leaseConsent?: boolean;
  /** Roadmap V3 R3-L1: guide rent in € per ha and month (own fields only). */
  leaseOutGuideRate?: number | null;
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
  kind: 'AUCTION' | 'DIRECT' | 'SALE_OFFER' | 'LEASE_OFFER';
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
  /** Roadmap V3 R3-L1: term of a lease-out (LEASE_OFFER); its amounts are € per ha and month. */
  leaseTermMonths?: number | null;
}

/** Roadmap V3 R3-V: an own machine of the latest export; saleDealId = a running sale. */
export interface OwnVehicleView {
  uniqueId: string;
  name: string | null;
  value: number;
  condition: number | null;
  saleDealId: number | null;
}

export interface VehicleDealView {
  id: number;
  direction: 'BUY' | 'SELL';
  status: 'OPEN' | 'AGREED' | 'DONE' | 'FAILED' | 'ENDED' | string;
  sellerKind: 'WORKSHOP' | 'NEIGHBOR' | null;
  character: CharacterRef | null;
  vehicleName: string;
  categoryName: string | null;
  listPrice: number | null;
  ageMonths: number | null;
  operatingHours: number | null;
  damage: number | null;
  wear: number | null;
  gamePrice: number | null;
  basePrice: number;
  askingPrice: number | null;
  finalPrice: number | null;
  vehicleId: string | null;
  attempts: number;
  nextAttemptGameTime: number | null;
  failureReason: string | null;
  createdGameTime: number;
  closedGameTime: number | null;
  negotiations: NegotiationView[];
}

export interface VehiclesView {
  enabled: boolean;
  maxRounds: number;
  saleCapPercent: number;
  spawnMaxAttempts: number;
  vehicles: OwnVehicleView[];
  deals: VehicleDealView[];
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

/** Booking statement ("Kontoauszug"): one entry - a single booking or the daily sum of `count` bookings. */
export interface StatementEntryView {
  seq: number;
  gameTime: number;
  year: number;
  period: number;
  day: number | null;
  category: string;
  financeClass: FinanceClass;
  amount: number;
  count: number;
  single: boolean;
  liters: number | null;
  fillType: string | null;
  sellPoint: string | null;
  sellPointName: string | null;
  note: string | null;
  /** Shop vehicle purchases / sales only. */
  vehicleMatch: 'PENDING' | 'MATCHED' | 'AMBIGUOUS' | 'NONE' | null;
  vehicleNames: string | null;
}

export interface StatementMonthView {
  year: number;
  period: number;
  entries: number;
}

export interface StatementView {
  /** false: the latest export has no single bookings (older mod version). */
  available: boolean;
  year: number | null;
  period: number | null;
  months: StatementMonthView[];
  entries: StatementEntryView[];
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

/** Roadmap V3 R3-M1: price alarm; sellPoint null = best price of all sell points. */
export interface PriceAlarmView {
  id: number;
  fillType: string;
  sellPoint: string | null;
  threshold: number;
  direction: 'ABOVE' | 'BELOW';
  status: 'ACTIVE' | 'FIRED';
  createdGameTime: number;
  firedGameTime: number | null;
  firedPrice: number | null;
  firedSellPoint: string | null;
}

export interface PriceAlarmsView {
  maxActive: number;
  alarms: PriceAlarmView[];
}

/** Roadmap V3 R3-M2: forward contract (status OPEN / FULFILLED / SHORTFALL). */
export interface ForwardContractView {
  id: number;
  fillType: string;
  sellPoint: string;
  quantity: number;
  fixedPrice: number;
  basePrice: number;
  leadMonths: number;
  deliveryStartGameTime: number;
  deadlineGameTime: number;
  status: string;
  deliveredQuantity: number | null;
  penalty: number | null;
  createdGameTime: number;
}

export interface ForwardContractsView {
  minLeadMonths: number;
  maxLeadMonths: number;
  minQuantity: number;
  maxQuantity: number;
  quantityStep: number;
  maxOpen: number;
  factorPerMonthPercent: number;
  penaltySharePercent: number;
  contracts: ForwardContractView[];
}

export interface ForwardQuoteView {
  fillType: string;
  sellPoint: string;
  quantity: number;
  leadMonths: number;
  basePrice: number;
  fixedPrice: number;
  deliveryStartGameTime: number;
  deadlineGameTime: number;
  deliveryPeriod: number | null;
  expectedIncome: number;
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
  entryType: 'AUTO' | 'PLAYER_NOTE' | 'MILESTONE';
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

// ---------------------------------------------------------------------------------------- Roadmap V3.1 section A

/** R31-A1: one work of the contractor form; `reason` = why it is not possible (null = possible). */
export interface WorkOptionView {
  work: 'PLOW' | 'CULTIVATE' | 'LIME' | 'SOW' | 'HARVEST' | string;
  price: number;
  harvestLiters: number | null;
  fillType: string | null;
  reason: string | null;
}

/** R31-A1: "Lohnunternehmer beauftragen" for an own field. */
export interface ContractorQuoteView {
  farmlandId: number;
  fieldName: string | null;
  hectares: number;
  phase: string;
  options: WorkOptionView[];
  fruitTypes: string[];
  daysMin: number;
  daysMax: number;
  openOrder: CaseView | null;
}

/** R31-A2: a machine to choose from; `dailyRent` 0 for a demo. */
export interface LoanChoiceView {
  storeXmlFilename: string;
  name: string;
  categoryName: string | null;
  listPrice: number;
  dailyRent: number;
}

export interface LoanChoicesView {
  daysMin: number;
  daysMax: number;
  choices: LoanChoiceView[];
}

/** R31-A2: a borrowed (LOAN) or demo (DEMO) machine. */
export interface MachineLoanView {
  id: number;
  kind: 'LOAN' | 'DEMO' | string;
  status: string;
  lender: CharacterRef | null;
  vehicleName: string;
  categoryName: string | null;
  listPrice: number;
  days: number;
  dailyRent: number;
  vehicleId: string | null;
  deliveredGameTime: number | null;
  endsGameTime: number | null;
  rentDaysBooked: number;
  lateDays: number;
  compensation: number | null;
  endReason: string | null;
  vehicleDealId: number | null;
}

/** R31-A3: one own stable with its subtypes and free places. */
export interface StableView {
  husbandryUniqueId: string;
  type: string;
  count: number;
  freeSlots: number | null;
  subTypes: { name: string; count: number }[];
  supportedSubTypes: string[];
  valuePerAnimal: number | null;
}

export interface AnimalStockView {
  type: string;
  count: number;
  sellUnitPrice: number | null;
  buyUnitPrice: number | null;
}

export interface AnimalNeighborView {
  id: number;
  name: string;
  role: string | null;
  trustLevel: TrustLevel;
  animals: AnimalStockView[];
}

/** R31-A3: livestock trade with the neighbours; `tracked` = the mod reports the stables with subtypes. */
export interface AnimalTradeView {
  tracked: boolean;
  countMin: number;
  countMax: number;
  stables: StableView[];
  neighbors: AnimalNeighborView[];
  cases: CaseView[];
}

/** R31-B: switches of the burdening events; world mode and its factor (IDYLLIC: animal disease off). */
export interface BurdenSettingsView {
  areaCheck: boolean;
  fertilizer: boolean;
  disease: boolean;
  sickLeave: boolean;
  /** R31-D4 / D5 / D8 */
  nightWork: boolean;
  cropDamage: boolean;
  dieselTheft: boolean;
  tonePreset: string;
  idyllicFactor: number;
}

/** R31-B1: one field of an area payment application. */
export interface DirectPaymentFieldView {
  farmlandId: number;
  fieldName: string;
  hectares: number;
  declaredCrop: string;
  actualCrop: string | null;
  rotationRepeat: boolean;
}

export interface DirectPaymentFormFieldView {
  farmlandId: number;
  fieldName: string;
  hectares: number;
  suggestedCrop: string;
}

/** R31-B1: area payment application of an FS25 year (OPEN, SUBMITTED, LAPSED, PAID). */
export interface DirectPaymentView {
  id: number;
  cropYear: number;
  status: string;
  openedGameTime: number;
  deadlineGameTime: number;
  lateLimitGameTime: number;
  submittedGameTime: number | null;
  lateDays: number;
  checkStatus: string;
  deviatingHectares: number | null;
  deviationCut: number | null;
  rotationCut: number | null;
  lateCut: number | null;
  premium: number | null;
  paidAmount: number | null;
  fields: DirectPaymentFieldView[];
}

export interface DirectPaymentStatusView {
  enabled: boolean;
  premiumPerHa: number;
  lateCutPercentPerDay: number;
  lateMaxDays: number;
  crops: string[];
  form: DirectPaymentFormFieldView[];
  applications: DirectPaymentView[];
}

/** R31-B2: a machine bought with a grant (binding period). */
export interface GrantObjectView {
  vehicleUniqueId: string;
  value: number;
  soldGameTime: number | null;
  repayment: number | null;
}

/** R31-B2: investment grant (APPLIED, APPROVED, PAID, EXPIRED). */
export interface GrantView {
  id: number;
  kind: string;
  status: string;
  plannedSum: number;
  appliedGameTime: number;
  approvalDueGameTime: number;
  approvedGameTime: number | null;
  purchaseDeadlineGameTime: number | null;
  recognisedSum: number;
  grantAmount: number | null;
  paidGameTime: number | null;
  bindingEndsGameTime: number | null;
  repaidAmount: number;
  objects: GrantObjectView[];
}

export interface GrantStatusView {
  enabled: boolean;
  minSum: number;
  grantPercent: number;
  grantMax: number;
  purchaseMonths: number;
  bindingMonths: number;
  processingDays: number;
  grants: GrantView[];
}

/** R31-B4: an animal disease with its restricted zone. */
export interface DiseaseView {
  id: number;
  diseaseKey: string;
  animalTypes: string[];
  status: string;
  declaredGameTime: number;
  endsGameTime: number;
  liftedGameTime: number | null;
}

export interface DiseaseStatusView {
  possible: boolean;
  diseases: DiseaseView[];
}

// ------------------------------------------------------------------------------------------ Roadmap V3.1 R31-D

/** R31-D1: an article of the village newspaper; `pending` while the text is being written. */
export interface ArticleView {
  id: number;
  section: string;
  sectionTitle: string;
  position: number;
  headline: string | null;
  body: string | null;
  fallback: boolean;
  pending: boolean;
}

/** R31-D1: an issue of the "Dorfblatt" (newest first). */
export interface IssueView {
  id: number;
  issueNumber: number;
  midMonth: boolean;
  period: number | null;
  cropYear: number | null;
  fromGameTime: number;
  publishedGameTime: number;
  headline: string | null;
  articles: ArticleView[];
}

/** R31-D2: a message of the village chat; `character` null = the player, `pending` while written. */
export interface ChatMessageView {
  id: number;
  groupId: number;
  character: CharacterRef | null;
  kind: string;
  topic: string | null;
  text: string | null;
  tone: string | null;
  link: string | null;
  gameTime: number;
  pending: boolean;
}

export interface ChatGroupView {
  id: number;
  key: string;
  name: string;
  members: CharacterRef[];
  last: ChatMessageView | null;
}

export interface ChatPostView {
  message: ChatMessageView;
  pacingActive: boolean;
}

/** R31-D6: one month of the farm holidays. */
export interface HolidayMonthView {
  monthIndex: number;
  period: number;
  income: number;
  seasonFactor: number;
  reputationFactor: number;
  animalFactor: number;
  noise: boolean;
  smell: boolean;
  badReview: boolean;
  gameTime: number;
}

export interface HolidayPreviewView {
  period: number;
  seasonFactor: number;
  reputationFactor: number;
  animalFactor: number;
  noise: boolean;
  smell: boolean;
  badReview: boolean;
  income: number;
}

export interface FarmHolidayView {
  enabled: boolean;
  setupCost: number;
  baseIncomePerMonth: number;
  /** Game time of the setup, null = not set up. */
  since: number | null;
  preview: HolidayPreviewView;
  months: HolidayMonthView[];
}

/** R31-D7: a cancellation of cooperative shares. */
export interface CoopNoticeView {
  id: number;
  shares: number;
  noticedGameTime: number;
  dueGameTime: number;
  paidGameTime: number | null;
}

export interface CooperativeView {
  enabled: boolean;
  sharePrice: number;
  maxShares: number;
  shares: number;
  noticedShares: number;
  board: boolean;
  boardMissed: number;
  boardMinShares: number;
  dividendRatePercent: number;
  dividendBonusPercent: number;
  grainStore: boolean;
  noticeMonths: number;
  notices: CoopNoticeView[];
}

/** R31-D8: an own vehicle with its diesel and tank lock. */
export interface FuelVehicleView {
  vehicleId: string;
  name: string | null;
  fuelLiters: number | null;
  fuelCapacity: number | null;
  tankLock: boolean;
}

export interface TheftView {
  id: number;
  vehicleId: string;
  vehicleName: string | null;
  status: string;
  stolenLiters: number | null;
  damage: number | null;
  insurancePayout: number | null;
  closedGameTime: number | null;
}

export interface DieselTheftView {
  enabled: boolean;
  tankLockPrice: number;
  insurancePremiumPerMonth: number;
  insuranceMinDamage: number;
  vehicles: FuelVehicleView[];
  thefts: TheftView[];
}

// ------------------------------------------------------------------------------------------ Roadmap V3.1 R31-K

export interface ShapePoint {
  x: number;
  z: number;
}

/** R31-K1: one field outline of the map; `kind` OWN (also leased), NEIGHBOR or FREE; hints as codes. */
export interface MapFieldView {
  farmlandId: number;
  name: string;
  points: ShapePoint[];
  kind: 'OWN' | 'NEIGHBOR' | 'FREE' | string;
  ownerName: string | null;
  leased: boolean;
  leasedOut: boolean;
  fruitType: string | null;
  phase: string | null;
  orders: string[];
  auction: boolean;
  hints: string[];
}

/** R31-K1: `mapSize` null = no outlines exported (older mod). */
export interface FieldMapView {
  mapSize: number | null;
  fields: MapFieldView[];
}
