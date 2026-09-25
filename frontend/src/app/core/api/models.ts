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
}

export interface DeferralView {
  granted: boolean;
  reasonCategory: string | null;
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
}

export interface FarmlandView {
  farmlandId: number;
  hectares: number;
  referencePrice: number;
  ownerType: 'PLAYER' | 'CHARACTER' | 'UNCLAIMED';
  owner: CharacterRef | null;
  inNegotiation: boolean;
}

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
