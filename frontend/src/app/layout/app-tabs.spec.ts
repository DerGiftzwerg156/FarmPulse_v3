import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { TaskView } from '../core/api/models';
import { TasksStore } from '../core/state/tasks.store';
import { AppBadges } from './app-badges';
import { AppTabRedirect } from './app-tab-redirect';
import { APP_TABS, caseTabOf, contractTabOf, defaultTab, isTab, lastTab, queryTab, rememberTab } from './app-tabs';
import { APPS } from './apps';
import { taskTabId } from './task-apps';

@Component({ template: '' })
class Blank {}

function task(over: Partial<TaskView>): TaskView {
  return { key: 'k', type: 'CASE', kind: null, deadlineGameTime: null, gameTime: 0, serviceCase: null, contract: null, application: null,
    call: null, negotiation: null, marketEvent: null, posting: null, pendingApplicants: null, ...over };
}

describe('app tabs (owner decisions 2026-10-06)', () => {
  beforeEach(() => localStorage.clear());

  it('only names apps that exist, with unique tab ids', () => {
    for (const [appId, tabs] of Object.entries(APP_TABS)) {
      expect(APPS.some((a) => a.id === appId)).toBe(true);
      expect(new Set(tabs.map((t) => t.id)).size).toBe(tabs.length);
    }
  });

  it('puts every case and contract kind into a tab of its app', () => {
    expect(caseTabOf('TAX_BILL')).toBe('finanzamt');
    expect(caseTabOf('AUTHORITY_INSPECTION')).toBe('kontrollen');
    expect(caseTabOf('FARM_SHOP_ORDER')).toBe('hofladen');
    expect(caseTabOf('BULK_ORDER')).toBe('grossauftraege');
    expect(caseTabOf('ANIMAL_OFFER')).toBe('tiere');
    expect(contractTabOf('INSURANCE', 'BASIC')).toBe('hof');
    expect(contractTabOf('INSURANCE', 'DROUGHT_INDEX')).toBe('duerre');
    expect(contractTabOf('LEASE_OUT')).toBe('pacht');
    expect(queryTab('bank', 'application')).toBe('antrag');
    expect(queryTab('workshop', 'negotiation')).toBe('gebraucht');
    expect(queryTab('fields', 'negotiation')).toBe('verhandlungen');
  });

  it('remembers the last tab per app on this device, else the first', () => {
    expect(defaultTab('bank')).toBe('kredite');
    rememberTab('bank', 'kontoauszug');
    expect(lastTab('bank')).toBe('kontoauszug');
    expect(defaultTab('bank')).toBe('kontoauszug');
    rememberTab('market', 'gibt-es-nicht');
    expect(defaultTab('market')).toBe('lager');
    expect(isTab('mail', 'x')).toBe(false);
  });

  it('counts the open tasks per tab', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const tasks = TestBed.inject(TasksStore);
    TestBed.tick(); // the store's effect clears the list without a savegame first
    tasks.items.set([
      task({ type: 'CASE', kind: 'TAX_BILL', serviceCase: { id: 1 } as never }),
      task({ type: 'CASE', kind: 'TAX_BILL', serviceCase: { id: 2 } as never }),
      task({ type: 'CREDIT_COUNTER', application: { id: 3 } as never }),
      task({ type: 'NEGOTIATION', negotiation: { id: 4, assetType: 'VEHICLE' } as never }),
    ]);
    tasks.notices.set(1);
    const badges = TestBed.inject(AppBadges);
    expect(taskTabId(task({ type: 'CALL' }))).toBeUndefined();
    expect(badges.tabCount('authorities', 'finanzamt')).toBe(2);
    expect(badges.tabCount('bank', 'antrag')).toBe(1);
    expect(badges.tabCount('workshop', 'gebraucht')).toBe(1);
    expect(badges.tabCount('tasks', 'aufgaben')).toBe(4);
    expect(badges.tabCount('tasks', 'meldungen')).toBe(1);
    expect(badges.tabCount('bank', 'kredite')).toBe(0);
  });
});

describe('AppTabRedirect', () => {
  async function open(url: string) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'bank', pathMatch: 'full', component: AppTabRedirect, data: { appId: 'bank' } },
          { path: 'aemter', pathMatch: 'full', component: AppTabRedirect, data: { appId: 'authorities' } },
          { path: 'versicherung', pathMatch: 'full', component: AppTabRedirect, data: { appId: 'insurance' } },
          { path: '**', component: Blank },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    return { router: TestBed.inject(Router), http: TestBed.inject(HttpTestingController) };
  }

  const settle = () => new Promise((r) => setTimeout(r));

  beforeEach(() => localStorage.clear());

  it('opens the tab of a mail link without a lookup', async () => {
    const { router } = await open('/bank?application=5');
    await settle();
    expect(router.url).toBe('/bank/antrag?application=5');
  });

  it('looks up the kind of a case to find its tab', async () => {
    const { router, http } = await open('/aemter?case=12');
    http.expectOne('/api/cases').flush([{ id: 12, kind: 'SOCIAL_INSURANCE_BILL' }]);
    await settle();
    expect(router.url).toBe('/aemter/berufsgenossenschaft?case=12');
  });

  it('opens the drought tab for a drought insurance contract', async () => {
    const { router, http } = await open('/versicherung?contract=2');
    http.expectOne('/api/contracts').flush([{ id: 2, kind: 'INSURANCE', level: 'DROUGHT_INDEX' }]);
    await settle();
    expect(router.url).toBe('/versicherung/duerre?contract=2');
  });

  it('opens the tab last used on this device without a deep link', async () => {
    rememberTab('bank', 'planung');
    const { router } = await open('/bank');
    await settle();
    expect(router.url).toBe('/bank/planung');
  });
});
