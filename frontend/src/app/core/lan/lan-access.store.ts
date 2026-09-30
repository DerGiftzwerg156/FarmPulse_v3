import { Injectable, inject, signal } from '@angular/core';
import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { catchError, throwError } from 'rxjs';
import { ApiService } from '../api/api.service';
import { LanStatusView } from '../api/models';

/**
 * Roadmap V3 R3-N2: whether this device must log in with the PIN before it sees the Hof-Tablet. The gaming PC
 * (loopback) never needs a PIN; a device in the home network needs it only while a PIN is set.
 */
@Injectable({ providedIn: 'root' })
export class LanAccessStore {
  private readonly api = inject(ApiService);

  /** null = not checked yet. */
  readonly loginRequired = signal<boolean | null>(null);
  readonly status = signal<LanStatusView | null>(null);

  check(): void {
    this.api.lanStatus().subscribe({
      next: (s) => {
        this.status.set(s);
        this.loginRequired.set(!s.authenticated);
      },
      // no status (older backend, network error): let the app handle the errors as before
      error: () => this.loginRequired.set(false),
    });
  }

  /** A request came back with LAN_LOGIN_REQUIRED (e.g. the session expired or the PIN was changed). */
  sessionLost(): void {
    this.loginRequired.set(true);
  }

  loggedIn(): void {
    this.loginRequired.set(false);
    this.check();
  }
}

/** Shows the PIN login as soon as the backend answers LAN_LOGIN_REQUIRED. */
export const lanLoginInterceptor: HttpInterceptorFn = (req, next) => {
  const store = inject(LanAccessStore);
  return next(req).pipe(
    catchError((err: unknown) => {
      if (err instanceof HttpErrorResponse && err.status === 401 && err.error?.code === 'LAN_LOGIN_REQUIRED') {
        store.sessionLost();
      }
      return throwError(() => err);
    }),
  );
};
