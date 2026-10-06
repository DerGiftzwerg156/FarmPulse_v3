import { TaskView } from '../core/api/models';
import { APPS, AppDef } from './apps';
import { caseTabOf, contractTabOf, isTab } from './app-tabs';

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
  // Roadmap V3 R3-W2
  DROUGHT_AID: 'authorities',
  // Roadmap V3 R3-P2
  APPRENTICE_TAKEOVER: 'staff',
  // Roadmap V3.1 R31-A
  CONTRACTOR_WORK: 'fields',
  MACHINE_DEMO_OFFER: 'workshop',
  ANIMAL_OFFER: 'trade',
  ANIMAL_REQUEST: 'trade',
  // Roadmap V3.1 R31-B
  GRANT_REPAYMENT: 'authorities',
  SOCIAL_INSURANCE_BILL: 'authorities',
  // Roadmap V3.1 R31-D (owner decision: like the festivals in the calendar; the crop damage claim like R2-D2)
  STAMMTISCH_INVITATION: 'calendar',
  SCHOOL_VISIT: 'calendar',
  COOP_ASSEMBLY: 'calendar',
  COOP_BOARD_MEETING: 'calendar',
  CROP_DAMAGE_CLAIM: 'fields',
};

const CONTRACT_APP: Record<string, string> = {
  INSURANCE: 'insurance',
  LEASE: 'fields',
  MAINTENANCE: 'workshop',
  TAX_ADVISOR: 'authorities',
  LEASE_OUT: 'fields', // Roadmap V3 R3-L1
  WINTER_SERVICE: 'authorities', // Roadmap V3.1 R31-A4
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
  if (t.type === 'NEGOTIATION' && t.negotiation?.assetType === 'VEHICLE') return known('workshop'); // R3-V
  return known(TYPE_APP[t.type] ?? FALLBACK_APP);
}

/** Tab of the task inside its app (badge on the tab and the deep link); undefined for apps without tabs. */
export function taskTabId(t: TaskView): string | undefined {
  const appId = taskAppId(t);
  let tab: string | undefined;
  switch (t.type) {
    case 'CASE':
      tab = caseTabOf(t.kind ?? '');
      break;
    case 'CONTRACT_OFFER':
      tab = contractTabOf(t.kind ?? '', t.contract?.level);
      break;
    case 'LEASE_RENEWAL':
      tab = 'pacht';
      break;
    case 'CREDIT_COUNTER':
      tab = 'antrag';
      break;
    case 'NEGOTIATION':
      tab = t.negotiation?.assetType === 'VEHICLE' ? 'gebraucht' : 'verhandlungen';
      break;
    case 'MARKET_OFFER':
      tab = 'ereignisse';
      break;
    case 'POSTING':
      tab = 'stellen';
      break;
  }
  return isTab(appId, tab) ? tab : undefined;
}

export function taskApp(t: TaskView): AppDef {
  const id = taskAppId(t);
  return APPS.find((a) => a.id === id)!;
}

/** Link into the app's tab with the entry highlighted (the pages read these query parameters). */
export function taskLink(t: TaskView): { path: string; query: Record<string, number> } {
  const app = taskApp(t);
  const tab = taskTabId(t);
  const path = tab ? `${app.path}/${tab}` : app.path;
  const id = (v: { id: number } | null) => v?.id ?? 0;
  switch (t.type) {
    case 'CASE':
      return { path, query: { case: id(t.serviceCase) } };
    case 'CONTRACT_OFFER':
    case 'LEASE_RENEWAL':
      return { path, query: { contract: id(t.contract) } };
    case 'CREDIT_COUNTER':
      return { path, query: { application: id(t.application) } };
    case 'CALL':
      return { path, query: { id: id(t.call) } };
    case 'NEGOTIATION':
      return { path, query: { negotiation: id(t.negotiation) } };
    case 'MARKET_OFFER':
      return { path, query: { event: id(t.marketEvent) } };
    case 'POSTING':
      return { path, query: { posting: id(t.posting) } };
  }
}
