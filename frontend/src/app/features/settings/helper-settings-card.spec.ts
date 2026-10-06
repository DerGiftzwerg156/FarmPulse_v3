import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { HelperSettingsView } from '../../core/api/models';
import { HelperSettingsCard } from './helper-settings-card';

describe('HelperSettingsCard (app Personal)', () => {
  function setup(helpers: HelperSettingsView) {
    TestBed.configureTestingModule({ imports: [HelperSettingsCard], providers: [provideHttpClient(), provideHttpClientTesting()] });
    const fixture = TestBed.createComponent(HelperSettingsCard);
    fixture.detectChanges();
    const http = TestBed.inject(HttpTestingController);
    http.expectOne('/api/settings/helpers').flush(helpers);
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  // Roadmap V2 R2-A1 / R2-A3
  it('saves the helper switches', () => {
    const { el, http, fixture } = setup({ helperWageMode: 'EMPLOYEES', strictHelperLimit: false, workforceTracked: true });
    const strict = el.querySelector('[data-testid="helper-strict"]') as HTMLInputElement;
    strict.checked = true;
    strict.dispatchEvent(new Event('change'));
    const req = http.expectOne('/api/settings/helpers');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ helperWageMode: 'EMPLOYEES', strictHelperLimit: true });
    req.flush({ helperWageMode: 'EMPLOYEES', strictHelperLimit: true, workforceTracked: true });
    fixture.detectChanges();
    expect((el.querySelector('[data-testid="helper-strict"]') as HTMLInputElement).checked).toBe(true);
    expect(el.querySelector('[data-testid="helper-untracked"]')).toBeNull();
  });

  it('says when the mod reports no helpers yet', () => {
    const { el } = setup({ helperWageMode: 'VANILLA', strictHelperLimit: false, workforceTracked: false });
    expect((el.querySelector('[data-testid="helper-wage"]') as HTMLInputElement).checked).toBe(false);
    expect(el.querySelector('[data-testid="helper-untracked"]')?.textContent).toContain('aktuellen Mod-Version');
  });
});
