export interface NavItem {
  path: string;
  label: string; // i18n key
  icon: string;
}

/** Main areas (AP-7.2). */
export const NAV_ITEMS: NavItem[] = [
  { path: '/', label: 'nav.home', icon: 'dashboard' },
  { path: '/mailbox', label: 'nav.mailbox', icon: 'mail' },
  { path: '/calls', label: 'nav.calls', icon: 'phone' },
  { path: '/bank', label: 'nav.bank', icon: 'bank' },
  { path: '/employees', label: 'nav.employees', icon: 'users' },
  { path: '/farmland', label: 'nav.farmland', icon: 'map' },
  { path: '/market', label: 'nav.market', icon: 'wheat' },
  { path: '/village', label: 'nav.village', icon: 'home' },
  { path: '/diary', label: 'nav.diary', icon: 'book' },
  { path: '/settings', label: 'nav.settings', icon: 'settings' },
];
