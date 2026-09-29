import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { ContractsRedirect } from './contracts-redirect';

@Component({ template: '' })
class Blank {}

describe('ContractsRedirect (old /contracts links in stored mails)', () => {
  async function open(url: string) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'contracts', component: ContractsRedirect }, { path: '**', component: Blank }]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    return { router: TestBed.inject(Router), harness, http: TestBed.inject(HttpTestingController) };
  }

  it('forwards a case to the app it belongs to now', async () => {
    const { router, http } = await open('/contracts?case=7');
    http.expectOne('/api/cases').flush([{ id: 7, kind: 'TAX_BILL' }]);
    await new Promise((r) => setTimeout(r));
    expect(router.url).toBe('/aemter?case=7');
  });

  it('forwards a lease contract to the field map', async () => {
    const { router, http } = await open('/contracts?contract=3');
    http.expectOne('/api/contracts').flush([{ id: 3, kind: 'LEASE' }]);
    await new Promise((r) => setTimeout(r));
    expect(router.url).toBe('/farmland?contract=3');
  });

  it('forwards a link without entry to the tasks', async () => {
    const { router } = await open('/contracts');
    await new Promise((r) => setTimeout(r));
    expect(router.url).toBe('/aufgaben');
  });
});
