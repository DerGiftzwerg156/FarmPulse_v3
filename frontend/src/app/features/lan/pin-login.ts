import { Component, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ApiService } from '../../core/api/api.service';
import { LanLoginView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { LanAccessStore } from '../../core/lan/lan-access.store';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';

/** Roadmap V3 R3-N2: PIN login of a device in the home network (tablet, phone). */
@Component({
  selector: 'app-pin-login',
  imports: [TranslatePipe, Card, Button],
  template: `
    <main class="fp-grid flex min-h-screen items-center justify-center p-4">
      <app-card class="w-full max-w-sm" [title]="'lan.login.title' | t" data-testid="pin-login">
        <p class="mb-3 text-[13px] text-muted">{{ 'lan.login.intro' | t }}</p>
        <form class="space-y-3" (submit)="$event.preventDefault(); submit()">
          <input
            class="fp-input text-center font-display text-2xl tracking-[0.4em]"
            type="password"
            inputmode="numeric"
            autocomplete="current-password"
            [attr.maxlength]="store.status()?.pinMaxLength ?? 8"
            [value]="pin()"
            (input)="pin.set($any($event.target).value)"
            [attr.aria-label]="'lan.login.pin' | t"
            data-testid="pin-input"
          />
          @if (error()) {
            <p class="text-[12px] text-danger" data-testid="pin-error">{{ error() }}</p>
          }
          <app-button type="submit" [disabled]="busy() || !pin()" data-testid="pin-submit">{{ 'lan.login.submit' | t }}</app-button>
        </form>
      </app-card>
    </main>
  `,
})
export class PinLogin {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);
  readonly store = inject(LanAccessStore);
  readonly pin = signal('');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  submit(): void {
    this.busy.set(true);
    this.error.set(null);
    this.api.lanLogin(this.pin()).subscribe({
      next: () => {
        this.busy.set(false);
        this.store.loggedIn();
      },
      error: (e: unknown) => {
        this.busy.set(false);
        this.pin.set('');
        const body = e instanceof HttpErrorResponse ? (e.error as LanLoginView | null) : null;
        this.error.set(body?.result === 'LOCKED'
          ? this.i18n.t('lan.login.locked', { minutes: Math.ceil((body.lockedSeconds ?? 0) / 60) })
          : body?.result === 'WRONG_PIN' ? this.i18n.t('lan.login.wrong') : this.i18n.t('common.error'));
      },
    });
  }
}
