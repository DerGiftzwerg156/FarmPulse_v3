import { TaskView } from '../core/api/models';
import { APPS, AppDef } from './apps';

/** App of a service case kind (where the case lives after the split of "Verträge & Vorgänge"). */
const CASE_APP: Record<string, string> = {
  STORM_DAMAGE: 'insurance',
  HAIL_DAMAGE: 'insurance',
  WILDLIFE_DAMAGE: 'insurance',
  LIVESTOCK_OFFER: 'stable',
  VET_VISIT: 'stable',
  BREEDING_ADVICE: 'stable',
  REPAIR: 'workshop',
  MISSION_REFERRAL: 'fields',
  COMPENSATION_CLAIM: 'fields',
  TAX_BILL: 'authorities',
  AUTHORITY_INSPECTION: 'authorities',
  SPONSORING_REQUEST: 'contacts',
  INVITATION: 'calendar',
  // Roadmap V3 R3-H
  GOODS_OFFER: 'trade',
  GOODS_REQUEST: 'trade',
  NEIGHBOR_MISSION: 'trade',
  // Roadmap V3 R3-K
  COLLATERAL_CLAIM: 'bank',
  ANNUAL_REVIEW: 'bank',
  ANNUAL_REVIEW_OFFER: 'bank',
  // Roadmap V3 R3-M3
  FARM_SHOP_ORDER: 'trade',
};

const CONTRACT_APP: Record<string, string> = {
  INSURANCE: 'insurance',
  LEASE: 'fields',
  MAINTENANCE: 'workshop',
  TAX_ADVISOR: 'authorities',
};

const TYPE_APP: Record<string, string> = {
  LEASE_RENEWAL: 'fields',
  CREDIT_COUNTER: 'bank',
  CALL: 'phone',
  NEGOTIATION: 'fields',
  MARKET_OFFER: 'market',
  POSTING: 'staff',
};

/** Where an unknown kind shows up: the task list itself. */
const FALLBACK_APP = 'tasks';

function known(id: string): string {
  return APPS.some((a) => a.id === id) ? id : FALLBACK_APP;
}

/** App id of a service case / contract kind (also used by the redirect of old `/contracts` links). */
export function caseAppId(kind: string): string {
  return known(CASE_APP[kind] ?? FALLBACK_APP);
}

export function contractAppId(kind: string): string {
  return known(CONTRACT_APP[kind] ?? FALLBACK_APP);
}

/** Id of the app a task belongs to (badge, card header and "in app" link). */
export function taskAppId(t: TaskView): string {
  if (t.type === 'CASE') return caseAppId(t.kind ?? '');
  if (t.type === 'CONTRACT_OFFER') return contractAppId(t.kind ?? '');
  return known(TYPE_APP[t.type] ?? FALLBACK_APP);
}

export function taskApp(t: TaskView): AppDef {
  const id = taskAppId(t);
  return APPS.find((a) => a.id === id)!;
}

/** Link into the app with the entry highlighted (the pages read these query parameters). */
export function taskLink(t: TaskView): { path: string; query: Record<string, number> } {
  const app = taskApp(t);
  const id = (v: { id: number } | null) => v?.id ?? 0;
  switch (t.type) {
    case 'CASE':
      return { path: app.path, query: { case: id(t.serviceCase) } };
    case 'CONTRACT_OFFER':
    case 'LEASE_RENEWAL':
      return { path: app.path, query: { contract: id(t.contract) } };
    case 'CREDIT_COUNTER':
      return { path: app.path, query: { application: id(t.application) } };
    case 'CALL':
      return { path: app.path, query: { id: id(t.call) } };
    case 'NEGOTIATION':
      return { path: app.path, query: { negotiation: id(t.negotiation) } };
    case 'MARKET_OFFER':
      return { path: app.path, query: { event: id(t.marketEvent) } };
    case 'POSTING':
      return { path: app.path, query: { posting: id(t.posting) } };
  }
}
