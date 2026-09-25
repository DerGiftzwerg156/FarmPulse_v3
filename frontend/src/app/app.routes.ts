import { Routes } from '@angular/router';
import { environment } from '../environments/environment';
import { Shell } from './layout/shell';
import { ComingSoon } from './layout/coming-soon';
import { NAV_ITEMS } from './layout/nav-items';

const devRoutes: Routes = environment.styleGuide
  ? [{ path: 'dev/style-guide', loadComponent: () => import('./dev/style-guide').then((m) => m.StyleGuide) }]
  : [];

const pages: Routes = NAV_ITEMS.map((item) => ({
  path: item.path === '/' ? '' : item.path.substring(1),
  pathMatch: 'full' as const,
  component: ComingSoon,
  data: { title: item.label },
}));

export const routes: Routes = [{ path: '', component: Shell, children: [...devRoutes, ...pages] }];
