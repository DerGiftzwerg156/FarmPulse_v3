import { Routes } from '@angular/router';
import { environment } from '../environments/environment';

const devRoutes: Routes = environment.styleGuide
  ? [{ path: 'dev/style-guide', loadComponent: () => import('./dev/style-guide').then((m) => m.StyleGuide) }]
  : [];

export const routes: Routes = [...devRoutes, { path: '', pathMatch: 'full', redirectTo: environment.styleGuide ? 'dev/style-guide' : '' }];
