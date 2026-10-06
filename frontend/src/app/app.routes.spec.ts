import { routes } from './app.routes';
import { APPS } from './layout/apps';

describe('routes', () => {
  it('has a lazily loaded feature page for every app and the onboarding', () => {
    const children = routes[0].children ?? [];
    const paths = children.filter((r) => r.loadComponent).map((r) => r.path);
    expect(paths).toContain('');
    // apps with tabs (owner decision 2026-10-06) load their page at <path>/:tab
    APPS.forEach((app) => expect(paths.some((p) => p === app.path.substring(1) || p === app.path.substring(1) + '/:tab')).toBe(true));
    expect(paths).toContain('onboarding');
  });

  it('resolves every feature component', async () => {
    const children = (routes[0].children ?? []).filter((r) => r.loadComponent && r.path !== 'dev/style-guide');
    for (const r of children) {
      const cmp = await (r.loadComponent as () => Promise<unknown>)();
      expect(cmp).toBeTruthy();
    }
  });
});
