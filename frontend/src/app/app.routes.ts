import { Routes } from '@angular/router';
import { environment } from '../environments/environment';
import { Shell } from './layout/shell';

const devRoutes: Routes = environment.styleGuide
  ? [{ path: 'dev/style-guide', loadComponent: () => import('./dev/style-guide').then((m) => m.StyleGuide) }]
  : [];

/** Feature modules (Phase 8), lazily loaded; paths match APPS (layout/apps.ts). Titles are i18n keys (I18nTitleStrategy). */
const pages: Routes = [
  { path: '', pathMatch: 'full', loadComponent: () => import('./features/home/home').then((m) => m.Home), title: 'nav.home' },
  { path: 'mailbox', loadComponent: () => import('./features/mailbox/mailbox').then((m) => m.Mailbox), title: 'nav.mailbox' },
  { path: 'calls', loadComponent: () => import('./features/calls/calls').then((m) => m.Calls), title: 'nav.calls' },
  { path: 'aufgaben', loadComponent: () => import('./features/tasks/tasks').then((m) => m.Tasks), title: 'nav.tasks' },
  { path: 'kalender', loadComponent: () => import('./features/calendar/calendar').then((m) => m.CalendarApp), title: 'nav.calendar' },
  { path: 'bank', loadComponent: () => import('./features/bank/bank').then((m) => m.Bank), title: 'nav.bank' },
  { path: 'employees', loadComponent: () => import('./features/employees/employees').then((m) => m.Employees), title: 'nav.employees' },
  { path: 'farmland', loadComponent: () => import('./features/farmland/farmland').then((m) => m.Farmland), title: 'nav.farmland' },
  { path: 'market', loadComponent: () => import('./features/market/market').then((m) => m.Market), title: 'nav.market' },
  { path: 'aemter', loadComponent: () => import('./features/authorities/authorities').then((m) => m.Authorities), title: 'nav.authorities' },
  { path: 'versicherung', loadComponent: () => import('./features/insurance/insurance').then((m) => m.Insurance), title: 'nav.insurance' },
  { path: 'stall', loadComponent: () => import('./features/stable/stable').then((m) => m.Stable), title: 'nav.stable' },
  { path: 'werkstatt', loadComponent: () => import('./features/workshop/workshop').then((m) => m.Workshop), title: 'nav.workshop' },
  { path: 'handel', loadComponent: () => import('./features/trade/trade').then((m) => m.Trade), title: 'nav.trade' },
  /** Old address of "Verträge & Vorgänge" (links in stored mails): forwards to the app of the entry. */
  { path: 'contracts', loadComponent: () => import('./features/contracts/contracts-redirect').then((m) => m.ContractsRedirect) },
  { path: 'village', loadComponent: () => import('./features/village/village').then((m) => m.Village), title: 'nav.village' },
  { path: 'diary', loadComponent: () => import('./features/diary/diary').then((m) => m.Diary), title: 'nav.diary' },
  { path: 'settings', loadComponent: () => import('./features/settings/settings').then((m) => m.Settings), title: 'nav.settings' },
  {
    path: 'onboarding',
    loadComponent: () => import('./features/onboarding/onboarding-wizard').then((m) => m.OnboardingWizard),
    title: 'nav.onboarding',
  },
];

export const routes: Routes = [
  { path: '', component: Shell, children: [...devRoutes, ...pages, { path: '**', redirectTo: '' }] },
];
