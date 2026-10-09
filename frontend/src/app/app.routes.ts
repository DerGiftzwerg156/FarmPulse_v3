import { Type } from '@angular/core';
import { CanMatchFn, Routes } from '@angular/router';
import { environment } from '../environments/environment';
import { AppTabRedirect } from './layout/app-tab-redirect';
import { isTab } from './layout/app-tabs';
import { Shell } from './layout/shell';

const devRoutes: Routes = environment.styleGuide
  ? [{ path: 'dev/style-guide', loadComponent: () => import('./dev/style-guide').then((m) => m.StyleGuide) }]
  : [];

/** Only the tabs of the app match `<path>/:tab`; anything else falls through to the start screen. */
function tabOf(appId: string): CanMatchFn {
  return (_route, segments) => isTab(appId, segments[1]?.path);
}

/**
 * An app with tabs (owner decisions 2026-10-06): `<path>` opens the tab of a deep link (`?case=`, `?application=` …)
 * or the last used one; `<path>/<tab>` is the page itself and gets the tab as input `tab`.
 */
function tabbed(appId: string, path: string, load: () => Promise<Type<unknown>>, title: string): Routes {
  return [
    { path, pathMatch: 'full', component: AppTabRedirect, data: { appId }, title },
    { path: `${path}/:tab`, canMatch: [tabOf(appId)], loadComponent: load, title },
  ];
}

/** Feature modules (Phase 8), lazily loaded; paths match APPS (layout/apps.ts). Titles are i18n keys (I18nTitleStrategy). */
const pages: Routes = [
  { path: '', pathMatch: 'full', loadComponent: () => import('./features/home/home').then((m) => m.Home), title: 'nav.home' },
  { path: 'mailbox', loadComponent: () => import('./features/mailbox/mailbox').then((m) => m.Mailbox), title: 'nav.mailbox' },
  { path: 'calls', loadComponent: () => import('./features/calls/calls').then((m) => m.Calls), title: 'nav.calls' },
  ...tabbed('tasks', 'aufgaben', () => import('./features/tasks/tasks').then((m) => m.Tasks), 'nav.tasks'),
  ...tabbed('calendar', 'kalender', () => import('./features/calendar/calendar').then((m) => m.CalendarApp), 'nav.calendar'),
  ...tabbed('bank', 'bank', () => import('./features/bank/bank').then((m) => m.Bank), 'nav.bank'),
  ...tabbed('staff', 'employees', () => import('./features/employees/employees').then((m) => m.Employees), 'nav.employees'),
  ...tabbed('fields', 'farmland', () => import('./features/farmland/farmland').then((m) => m.Farmland), 'nav.farmland'),
  // Roadmap V3.3 R33-F
  ...tabbed('fieldbook', 'feldbuch', () => import('./features/fieldbook/fieldbook').then((m) => m.FieldBook), 'nav.fieldBook'),
  ...tabbed('market', 'market', () => import('./features/market/market').then((m) => m.Market), 'nav.market'),
  ...tabbed('authorities', 'aemter', () => import('./features/authorities/authorities').then((m) => m.Authorities), 'nav.authorities'),
  ...tabbed('insurance', 'versicherung', () => import('./features/insurance/insurance').then((m) => m.Insurance), 'nav.insurance'),
  ...tabbed('stable', 'stall', () => import('./features/stable/stable').then((m) => m.Stable), 'nav.stable'),
  ...tabbed('workshop', 'werkstatt', () => import('./features/workshop/workshop').then((m) => m.Workshop), 'nav.workshop'),
  ...tabbed('trade', 'handel', () => import('./features/trade/trade').then((m) => m.Trade), 'nav.trade'),
  /** Old address of "Verträge & Vorgänge" (links in stored mails): forwards to the app of the entry. */
  { path: 'contracts', loadComponent: () => import('./features/contracts/contracts-redirect').then((m) => m.ContractsRedirect) },
  ...tabbed('contacts', 'village', () => import('./features/village/village').then((m) => m.Village), 'nav.village'),
  /** Roadmap V3.1 R31-D1 / R31-D2 */
  { path: 'dorfblatt', loadComponent: () => import('./features/newspaper/newspaper').then((m) => m.Newspaper), title: 'nav.newspaper' },
  { path: 'dorfchat', loadComponent: () => import('./features/chat/chat').then((m) => m.Chat), title: 'nav.chat' },
  /** Roadmap V3 R3-T2: print view of the farm chronicle (PDF via the browser's print function). */
  { path: 'diary/chronik', loadComponent: () => import('./features/diary/chronicle-print').then((m) => m.ChroniclePrint), title: 'diary.print' },
  { path: 'diary', loadComponent: () => import('./features/diary/diary').then((m) => m.Diary), title: 'nav.diary' },
  ...tabbed('settings', 'settings', () => import('./features/settings/settings').then((m) => m.Settings), 'nav.settings'),
  {
    path: 'onboarding',
    loadComponent: () => import('./features/onboarding/onboarding-wizard').then((m) => m.OnboardingWizard),
    title: 'nav.onboarding',
  },
];

export const routes: Routes = [
  { path: '', component: Shell, children: [...devRoutes, ...pages, { path: '**', redirectTo: '' }] },
];
