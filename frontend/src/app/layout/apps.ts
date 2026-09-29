/** Colour group of an app symbol: communication, money, farm, system. */
export type AppTone = 'com' | 'money' | 'farm' | 'sys';

export interface AppDef {
  /** Stable id; `apps.<id>` is the i18n key of the one-line subtitle in the app header. */
  id: string;
  /** i18n key of the app name (also the document title of the route). */
  label: string;
  /** Route; the old page addresses stay valid (Hof-Tablet decision: keep URLs). */
  path: string;
  icon: string;
  tone: AppTone;
}

/** Apps of the Hof-Tablet in the order of the start screen. */
export const APPS: AppDef[] = [
  { id: 'mail', label: 'nav.mailbox', path: '/mailbox', icon: 'mail', tone: 'com' },
  { id: 'phone', label: 'nav.calls', path: '/calls', icon: 'phone', tone: 'com' },
  { id: 'contacts', label: 'nav.village', path: '/village', icon: 'users', tone: 'com' },
  { id: 'tasks', label: 'nav.tasks', path: '/aufgaben', icon: 'tasks', tone: 'money' },
  { id: 'calendar', label: 'nav.calendar', path: '/kalender', icon: 'calendar', tone: 'sys' },
  { id: 'bank', label: 'nav.bank', path: '/bank', icon: 'bank', tone: 'money' },
  { id: 'authorities', label: 'nav.authorities', path: '/aemter', icon: 'landmark', tone: 'money' },
  { id: 'market', label: 'nav.market', path: '/market', icon: 'chart', tone: 'money' },
  { id: 'insurance', label: 'nav.insurance', path: '/versicherung', icon: 'shieldCheck', tone: 'money' },
  { id: 'staff', label: 'nav.employees', path: '/employees', icon: 'userCheck', tone: 'farm' },
  { id: 'fields', label: 'nav.farmland', path: '/farmland', icon: 'map', tone: 'farm' },
  { id: 'stable', label: 'nav.stable', path: '/stall', icon: 'barn', tone: 'farm' },
  { id: 'workshop', label: 'nav.workshop', path: '/werkstatt', icon: 'wrench', tone: 'farm' },
  { id: 'diary', label: 'nav.diary', path: '/diary', icon: 'book', tone: 'sys' },
  { id: 'settings', label: 'nav.settings', path: '/settings', icon: 'settings', tone: 'sys' },
];

/** Apps in the dock (start screen) and in the quick bar of every app. */
export const DOCK_IDS = ['mail', 'phone', 'tasks', 'calendar'];

export function appById(id: string): AppDef {
  const app = APPS.find((a) => a.id === id);
  if (!app) {
    throw new Error(`unknown app ${id}`);
  }
  return app;
}

/** The app a router URL belongs to (query and sub paths included); undefined for the start screen and onboarding. */
export function appForUrl(url: string): AppDef | undefined {
  const path = url.split(/[?#]/)[0];
  return APPS.find((a) => path === a.path || path.startsWith(a.path + '/'));
}

/** Tailwind text colour class of a tone. */
export function toneClass(tone: AppTone): string {
  return { com: 'text-app-com', money: 'text-app-money', farm: 'text-app-farm', sys: 'text-app-sys' }[tone];
}
