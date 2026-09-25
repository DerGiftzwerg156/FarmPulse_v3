import { Routes } from '@angular/router';
import { environment } from '../environments/environment';
import { Shell } from './layout/shell';
import { ComingSoon } from './layout/coming-soon';
import { NAV_ITEMS } from './layout/nav-items';

const devRoutes: Routes = environment.styleGuide
  ? [{ path: 'dev/style-guide', loadComponent: () => import('./dev/style-guide').then((m) => m.StyleGuide) }]
  : [];

/** Feature modules (Phase 8), lazily loaded; areas without an entry show the placeholder. */
const FEATURES: Record<string, Routes[number]['loadComponent']> = {
  mailbox: () => import('./features/mailbox/mailbox').then((m) => m.Mailbox),
  calls: () => import('./features/calls/calls').then((m) => m.Calls),
  bank: () => import('./features/bank/bank').then((m) => m.Bank),
  employees: () => import('./features/employees/employees').then((m) => m.Employees),
  farmland: () => import('./features/farmland/farmland').then((m) => m.Farmland),
  onboarding: () => import('./features/onboarding/onboarding-wizard').then((m) => m.OnboardingWizard),
};

const pages: Routes = NAV_ITEMS.map((item) => {
  const path = item.path === '/' ? '' : item.path.substring(1);
  const load = FEATURES[path === '' ? 'home' : path];
  return load
    ? { path, pathMatch: 'full' as const, loadComponent: load, data: { title: item.label } }
    : { path, pathMatch: 'full' as const, component: ComingSoon, data: { title: item.label } };
});

export const routes: Routes = [
  {
    path: '',
    component: Shell,
    children: [
      ...devRoutes,
      ...pages,
      { path: 'onboarding', loadComponent: FEATURES['onboarding'], data: { title: 'nav.onboarding' } },
      { path: '**', redirectTo: '' },
    ],
  },
];
