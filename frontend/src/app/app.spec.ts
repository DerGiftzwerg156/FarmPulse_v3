import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';

describe('App', () => {
  function setup() {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    return { fixture, http: TestBed.inject(HttpTestingController), el: fixture.nativeElement as HTMLElement };
  }
  const status = (authenticated: boolean) => ({ enabled: true, pinSet: true, gamePc: false, authenticated, urls: [],
    pinMinLength: 4, pinMaxLength: 8 });

  it('creates the root component', () => {
    expect(setup().fixture.componentInstance).toBeTruthy();
  });

  it('shows the PIN login on a device in the home network without session (R3-N2)', () => {
    const { fixture, http, el } = setup();
    http.expectOne('/api/lan/status').flush(status(false));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="pin-login"]')).not.toBeNull();
    expect(el.querySelector('router-outlet')).toBeNull();
  });

  it('shows the Hof-Tablet when the device may use the API', () => {
    const { fixture, http, el } = setup();
    http.expectOne('/api/lan/status').flush(status(true));
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="pin-login"]')).toBeNull();
    expect(el.querySelector('router-outlet')).not.toBeNull();
  });

  it('keeps the old behaviour when the status cannot be read', () => {
    const { fixture, http, el } = setup();
    http.expectOne('/api/lan/status').flush({}, { status: 500, statusText: 'x' });
    fixture.detectChanges();
    expect(el.querySelector('router-outlet')).not.toBeNull();
  });
});
