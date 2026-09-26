import { Routes } from '@angular/router';
import { environment } from '../environments/environment';
import { Shell } from './layout/shell';

const devRoutes: Routes = environment.styleGuide
  ? [{ path: 'dev/style-guide', loadComponent: () => import('./dev/style-guide').then((m) => m.StyleGuide) }]
  : [];

/** Feature modules (Phase 8), lazily loaded; paths match NAV_ITEMS. Titles are i18n keys (I18nTitleStrategy). */
const pages: Routes = [
  { path: '', pathMatch: 'full', loadComponent: () => import('./features/home/home').then((m) => m.Home), title: 'nav.home' },
  { path: 'mailbox', loadComponent: () => import('./features/mailbox/mailbox').then((m) => m.Mailbox), title: 'nav.mailbox' },
  { path: 'calls', loadComponent: () => import('./features/calls/calls').then((m) => m.Calls), title: 'nav.calls' },
  { path: 'bank', loadComponent: () => import('./features/bank/bank').then((m) => m.Bank), title: 'nav.bank' },
  { path: 'employees', loadComponent: () => import('./features/employees/employees').then((m) => m.Employees), title: 'nav.employees' },
  { path: 'farmland', loadComponent: () => import('./features/farmland/farmland').then((m) => m.Farmland), title: 'nav.farmland' },
  { path: 'market', loadComponent: () => import('./features/market/market').then((m) => m.Market), title: 'nav.market' },
  { path: 'contracts', loadComponent: () => import('./features/contracts/contracts').then((m) => m.Contracts), title: 'nav.contracts' },
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
