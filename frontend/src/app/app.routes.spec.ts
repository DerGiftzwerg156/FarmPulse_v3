import { routes } from './app.routes';
import { NAV_ITEMS } from './layout/nav-items';

describe('routes', () => {
  it('has a lazily loaded feature page for every main area and the onboarding', () => {
    const children = routes[0].children ?? [];
    const paths = children.filter((r) => r.loadComponent).map((r) => r.path);
    NAV_ITEMS.forEach((item) => expect(paths).toContain(item.path === '/' ? '' : item.path.substring(1)));
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
