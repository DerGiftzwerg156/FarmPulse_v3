import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { OverdraftSettingsView } from '../../core/api/models';
import { OverdraftSettingsCard } from './overdraft-settings-card';

describe('OverdraftSettingsCard (owner decisions 2026-10-14)', () => {
  function setup(view: OverdraftSettingsView) {
    TestBed.configureTestingModule({ imports: [OverdraftSettingsCard], providers: [provideHttpClient(), provideHttpClientTesting()] });
    const fixture = TestBed.createComponent(OverdraftSettingsCard);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/settings/overdraft').flush(view);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const input = (id: string) => el.querySelector(`[data-testid="${id}"]`) as HTMLInputElement;
    return { fixture, http, el, input };
  }

  it('is off by default: limit and interest cannot be edited', () => {
    const { el, input } = setup({ enabled: false, limit: 0, interestPercent: 10, balance: 5000 });
    expect(input('overdraft-enabled').checked).toBe(false);
    expect(input('overdraft-limit').disabled).toBe(true);
    expect(input('overdraft-interest').value).toBe('10');
    expect(el.textContent).toContain('Gehalt nachzahlen');
    expect(el.querySelector('[data-testid="overdraft-negative"]')).toBeNull();
  });

  it('saves the overdraft with limit and interest', () => {
    const { el, http, fixture, input } = setup({ enabled: false, limit: 0, interestPercent: 10, balance: -1200 });
    input('overdraft-enabled').checked = true;
    input('overdraft-enabled').dispatchEvent(new Event('change'));
    fixture.detectChanges();
    input('overdraft-limit').value = '25000';
    input('overdraft-limit').dispatchEvent(new Event('input'));
    input('overdraft-interest').value = '8.5';
    input('overdraft-interest').dispatchEvent(new Event('input'));
    (el.querySelector('[data-testid="overdraft-save"] button') as HTMLButtonElement).click();
    const req = http.expectOne('/api/settings/overdraft');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ enabled: true, limit: 25000, interestPercent: 8.5 });
    req.flush({ enabled: true, limit: 25000, interestPercent: 8.5, balance: -1200 });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="overdraft-saved"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="overdraft-negative"]')?.textContent?.replace(/\s/g, ' ')).toContain('-1.200 €');
  });
});
