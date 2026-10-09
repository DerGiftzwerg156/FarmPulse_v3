/** A sub page of an app: `id` is the URL segment (`/bank/kontoauszug`), `label` the i18n key. */
export interface TabDef {
  id: string;
  label: string;
}

/**
 * Sub pages ("Tabs") of the apps (owner decisions 2026-10-06 in QUESTIONS.md). Every tab has its own address
 * `<app path>/<tab id>`; apps without an entry are single pages. The first tab is the default of a fresh device.
 */
export const APP_TABS: Record<string, TabDef[]> = {
  tasks: [
    { id: 'aufgaben', label: 'tabs.tasks.aufgaben' },
    { id: 'meldungen', label: 'tabs.tasks.meldungen' },
  ],
  calendar: [
    { id: 'tage', label: 'tabs.calendar.tage' },
    { id: 'einladungen', label: 'tabs.calendar.einladungen' },
    { id: 'jahr', label: 'tabs.calendar.jahr' },
  ],
  contacts: [
    { id: 'kontakte', label: 'tabs.contacts.kontakte' },
    { id: 'vereine', label: 'tabs.contacts.vereine' },
  ],
  bank: [
    { id: 'kredite', label: 'tabs.bank.kredite' },
    { id: 'antrag', label: 'tabs.bank.antrag' },
    { id: 'kontoauszug', label: 'tabs.bank.kontoauszug' },
    { id: 'ergebnis', label: 'tabs.bank.ergebnis' },
    { id: 'planung', label: 'tabs.bank.planung' },
    { id: 'investoren', label: 'tabs.bank.investoren' },
  ],
  authorities: [
    { id: 'finanzamt', label: 'tabs.authorities.finanzamt' },
    { id: 'antraege', label: 'tabs.authorities.antraege' },
    { id: 'kontrollen', label: 'tabs.authorities.kontrollen' },
    { id: 'berufsgenossenschaft', label: 'tabs.authorities.berufsgenossenschaft' },
    { id: 'gemeinde', label: 'tabs.authorities.gemeinde' },
  ],
  market: [
    { id: 'lager', label: 'tabs.market.lager' },
    { id: 'verlauf', label: 'tabs.market.verlauf' },
    { id: 'ereignisse', label: 'tabs.market.ereignisse' },
    { id: 'alarme', label: 'tabs.market.alarme' },
    { id: 'vorkontrakte', label: 'tabs.market.vorkontrakte' },
  ],
  insurance: [
    { id: 'hof', label: 'tabs.insurance.hof' },
    { id: 'duerre', label: 'tabs.insurance.duerre' },
    { id: 'schaeden', label: 'tabs.insurance.schaeden' },
  ],
  staff: [
    { id: 'team', label: 'tabs.staff.team' },
    { id: 'stellen', label: 'tabs.staff.stellen' },
    { id: 'ehemalige', label: 'tabs.staff.ehemalige' },
  ],
  fields: [
    { id: 'karte', label: 'tabs.fields.karte' },
    { id: 'felder', label: 'tabs.fields.felder' },
    { id: 'verhandlungen', label: 'tabs.fields.verhandlungen' },
    { id: 'pacht', label: 'tabs.fields.pacht' },
    { id: 'vorgaenge', label: 'tabs.fields.vorgaenge' },
  ],
  // Roadmap V3.3 R33-F4 (owner decision 2026-10-09): "Auswertung" follows with E
  fieldbook: [{ id: 'dokumentation', label: 'tabs.fieldbook.dokumentation' }],
  stable: [
    { id: 'staelle', label: 'tabs.stable.staelle' },
    { id: 'handel', label: 'tabs.stable.handel' },
  ],
  workshop: [
    { id: 'wartung', label: 'tabs.workshop.wartung' },
    { id: 'gebraucht', label: 'tabs.workshop.gebraucht' },
    { id: 'leihen', label: 'tabs.workshop.leihen' },
    { id: 'tankschloesser', label: 'tabs.workshop.tankschloesser' },
  ],
  trade: [
    { id: 'nachbarn', label: 'tabs.trade.nachbarn' },
    { id: 'tiere', label: 'tabs.trade.tiere' },
    { id: 'hofladen', label: 'tabs.trade.hofladen' },
    { id: 'grossauftraege', label: 'tabs.trade.grossauftraege' }, // Roadmap V3.2 R32-G
    { id: 'ferienwohnung', label: 'tabs.trade.ferienwohnung' },
    { id: 'genossenschaft', label: 'tabs.trade.genossenschaft' },
  ],
  settings: [
    { id: 'ki', label: 'tabs.settings.ki' },
    { id: 'hof', label: 'tabs.settings.hof' },
    { id: 'ereignisse', label: 'tabs.settings.ereignisse' },
    { id: 'spiel', label: 'tabs.settings.spiel' },
    { id: 'netzwerk', label: 'tabs.settings.netzwerk' },
  ],
};

export function tabsOf(appId: string): TabDef[] {
  return APP_TABS[appId] ?? [];
}

export function isTab(appId: string, tab: string | undefined | null): boolean {
  return !!tab && tabsOf(appId).some((t) => t.id === tab);
}

/** Tab of a service case kind inside its app (see CASE_APP in task-apps.ts). */
const CASE_TAB: Record<string, string> = {
  STORM_DAMAGE: 'schaeden',
  HAIL_DAMAGE: 'schaeden',
  WILDLIFE_DAMAGE: 'schaeden',
  LIVESTOCK_OFFER: 'handel',
  VET_VISIT: 'handel',
  BREEDING_ADVICE: 'handel',
  REPAIR: 'wartung',
  MISSION_REFERRAL: 'vorgaenge',
  COMPENSATION_CLAIM: 'vorgaenge',
  CROP_DAMAGE_CLAIM: 'vorgaenge',
  CONTRACTOR_WORK: 'vorgaenge',
  TAX_BILL: 'finanzamt',
  AUTHORITY_INSPECTION: 'kontrollen',
  DROUGHT_AID: 'antraege',
  GRANT_REPAYMENT: 'antraege',
  SOCIAL_INSURANCE_BILL: 'berufsgenossenschaft',
  SPONSORING_REQUEST: 'vereine',
  INVITATION: 'einladungen',
  STAMMTISCH_INVITATION: 'einladungen',
  SCHOOL_VISIT: 'einladungen',
  COOP_ASSEMBLY: 'einladungen',
  COOP_BOARD_MEETING: 'einladungen',
  GOODS_OFFER: 'nachbarn',
  GOODS_REQUEST: 'nachbarn',
  NEIGHBOR_MISSION: 'nachbarn',
  ANIMAL_OFFER: 'tiere',
  ANIMAL_REQUEST: 'tiere',
  FARM_SHOP_ORDER: 'hofladen',
  BULK_ORDER: 'grossauftraege', // Roadmap V3.2 R32-G1
  COLLATERAL_CLAIM: 'kredite',
  ANNUAL_REVIEW: 'kredite',
  ANNUAL_REVIEW_OFFER: 'kredite',
  // Roadmap V3.2 R32-I
  INVESTOR_OFFER: 'investoren',
  INVESTOR_REMINDER: 'investoren',
  INVESTOR_CLAIM: 'investoren',
  INVESTOR_PURCHASE: 'investoren',
  INVESTOR_VISIT: 'investoren',
  APPRENTICE_TAKEOVER: 'team',
  MACHINE_DEMO_OFFER: 'leihen',
};

/** Tab of a contract kind inside its app (see CONTRACT_APP in task-apps.ts). */
const CONTRACT_TAB: Record<string, string> = {
  LEASE: 'pacht',
  LEASE_OUT: 'pacht',
  MAINTENANCE: 'wartung',
  TAX_ADVISOR: 'finanzamt',
  WINTER_SERVICE: 'gemeinde',
};

/** Tab of the insurance contract kinds depends on the level: the drought index has its own tab. */
export function contractTabOf(kind: string, level?: string | null): string | undefined {
  if (kind === 'INSURANCE') return level === 'DROUGHT_INDEX' ? 'duerre' : 'hof';
  return CONTRACT_TAB[kind];
}

export function caseTabOf(kind: string): string | undefined {
  return CASE_TAB[kind];
}

/**
 * Tab of a query parameter of a deep link that does not need a lookup (mail `formLink`s, task links); `case` and
 * `contract` need the kind of the entry and are resolved by `AppTabRedirect`.
 */
export function queryTab(appId: string, key: string): string | undefined {
  const byKey: Record<string, Record<string, string>> = {
    bank: { application: 'antrag' },
    authorities: { directPayment: 'antraege', grant: 'antraege' },
    market: { event: 'ereignisse' },
    staff: { posting: 'stellen' },
    fields: { negotiation: 'verhandlungen' },
    workshop: { negotiation: 'gebraucht' },
    contacts: { character: 'kontakte' },
    trade: { neighbor: 'nachbarn' },
  };
  return byKey[appId]?.[key];
}

const LAST_TAB_KEY = 'fp.tab.';

/** The tab last used on this device (owner decision: per browser); storage may be unavailable. */
export function lastTab(appId: string): string | undefined {
  try {
    const t = localStorage.getItem(LAST_TAB_KEY + appId);
    return isTab(appId, t) ? t! : undefined;
  } catch {
    return undefined;
  }
}

export function rememberTab(appId: string, tab: string): void {
  try {
    localStorage.setItem(LAST_TAB_KEY + appId, tab);
  } catch {
    // private mode or blocked storage: the app then opens on its first tab
  }
}

/** Tab to open without a deep link: the last used one, else the first. */
export function defaultTab(appId: string): string {
  return lastTab(appId) ?? tabsOf(appId)[0]?.id ?? '';
}
