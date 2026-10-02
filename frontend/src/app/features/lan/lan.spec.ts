import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LanStatusView } from '../../core/api/models';
import { LanAccessStore, lanLoginInterceptor } from '../../core/lan/lan-access.store';
import { LanSettingsCard } from './lan-settings-card';
import { PinLogin } from './pin-login';
import { QrCode } from './qr-code';

/** Roadmap V3 R3-N: PIN login, card "Tablet & Netzwerk", QR code and the login interceptor. */
describe('Tablet in the home network', () => {
  const status = (patch: Partial<LanStatusView> = {}): LanStatusView => ({ enabled: true, pinSet: false, gamePc: true,
    authenticated: true, urls: ['http://192.168.178.20:8080'], pinMinLength: 4, pinMaxLength: 8, ...patch });

  function configure() {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([lanLoginInterceptor])), provideHttpClientTesting()],
    });
    return TestBed.inject(HttpTestingController);
  }

  function type(el: HTMLElement, selector: string, value: string) {
    const input = el.querySelector(selector) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  it('the interceptor switches to the PIN login on LAN_LOGIN_REQUIRED', () => {
    const http = configure();
    const store = TestBed.inject(LanAccessStore);
    store.loginRequired.set(false);
    TestBed.inject(HttpClient).get('/api/savegame').subscribe({ error: () => undefined });
    http.expectOne('/api/savegame').flush({ code: 'LAN_LOGIN_REQUIRED', message: 'x' }, { status: 401, statusText: 'x' });
    expect(store.loginRequired()).toBe(true);
  });

  it('logs in with the PIN and explains a wrong PIN and the lock', () => {
    const http = configure();
    const store = TestBed.inject(LanAccessStore);
    store.loginRequired.set(true);
    const fixture = TestBed.createComponent(PinLogin);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const submit = () => {
      (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
      fixture.detectChanges();
    };

    type(el, '[data-testid="pin-input"]', '0000');
    submit();
    http.expectOne('/api/lan/login').flush({ result: 'WRONG_PIN', lockedSeconds: 0 }, { status: 401, statusText: 'x' });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="pin-error"]')?.textContent).toContain('PIN falsch');

    type(el, '[data-testid="pin-input"]', '0000');
    submit();
    http.expectOne('/api/lan/login').flush({ result: 'LOCKED', lockedSeconds: 290 }, { status: 429, statusText: 'x' });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="pin-error"]')?.textContent).toContain('5 Minuten');

    type(el, '[data-testid="pin-input"]', '4711');
    submit();
    const req = http.expectOne('/api/lan/login');
    expect(req.request.body).toEqual({ pin: '4711' });
    req.flush({ result: 'OK', lockedSeconds: 0 });
    expect(store.loginRequired()).toBe(false);
    http.expectOne('/api/lan/status').flush(status());
  });

  it('the gaming PC switches the access on, sets and removes the PIN and sees the QR code', () => {
    const http = configure();
    const fixture = TestBed.createComponent(LanSettingsCard);
    fixture.detectChanges();
    http.expectOne('/api/lan/status').flush(status({ enabled: false }));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="lan-off"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="lan-pin-state"]')?.textContent).toContain('Keine PIN');
    expect(el.querySelector('[data-testid="lan-url"]')?.textContent).toContain('http://192.168.178.20:8080');
    expect(el.querySelector('[data-testid="qr-code"] path')?.getAttribute('d')).toMatch(/^M\d+ \d+h1v1h-1z/);

    const toggle = el.querySelector('[data-testid="lan-enabled"]') as HTMLInputElement;
    toggle.checked = true;
    toggle.dispatchEvent(new Event('change'));
    const put = http.expectOne('/api/lan/settings');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ enabled: true });
    put.flush(status());
    fixture.detectChanges();

    type(el, '[data-testid="lan-pin"]', '12');
    fixture.detectChanges();
    expect((el.querySelector('[data-testid="lan-pin-save"] button') as HTMLButtonElement).disabled).toBe(true);
    type(el, '[data-testid="lan-pin"]', '4711');
    fixture.detectChanges();
    expect((el.querySelector('[data-testid="lan-pin-save"] button') as HTMLButtonElement).disabled).toBe(false);
    (el.querySelector('[data-testid="lan-pin"]')?.closest('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const pin = http.expectOne('/api/lan/pin');
    expect(pin.request.method).toBe('PUT');
    expect(pin.request.body).toEqual({ pin: '4711' });
    pin.flush(status({ pinSet: true }));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="lan-pin-state"]')?.textContent).toContain('PIN gesetzt');

    (el.querySelector('[data-testid="lan-pin-remove"] button') as HTMLButtonElement).click();
    const del = http.expectOne('/api/lan/pin');
    expect(del.request.method).toBe('DELETE');
    del.flush(status());
  });

  it('a tablet sees the card read-only', () => {
    const http = configure();
    const fixture = TestBed.createComponent(LanSettingsCard);
    fixture.detectChanges();
    http.expectOne('/api/lan/status').flush(status({ gamePc: false, pinSet: true }));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="lan-readonly"]')).not.toBeNull();
    expect((el.querySelector('[data-testid="lan-enabled"]') as HTMLInputElement).disabled).toBe(true);
    expect(el.querySelector('[data-testid="lan-pin"]')).toBeNull();
    expect(el.querySelector('[data-testid="lan-pin-remove"]')).toBeNull();
  });

  it('the QR code grows with the text and is square', () => {
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(QrCode);
    fixture.componentRef.setInput('text', 'http://192.168.178.20:8080');
    fixture.detectChanges();
    const vb = (fixture.nativeElement as HTMLElement).querySelector('svg')?.getAttribute('viewBox') ?? '';
    const [, , w, h] = vb.split(' ').map(Number);
    expect(w).toBe(h);
    expect(w).toBeGreaterThanOrEqual(21); // version 1 = 21 modules
  });
});
