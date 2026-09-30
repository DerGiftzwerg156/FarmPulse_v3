import { Component, computed, inject, signal } from '@angular/core';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import { LanStatusView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { QrCode } from './qr-code';

/**
 * Roadmap V3 R3-N1..N3: card "Tablet & Netzwerk". The switch "Im Heimnetz erreichbar" and the optional PIN belong to
 * the installation (not the savegame) and can only be changed on the gaming PC; a tablet sees the card read-only.
 */
@Component({
  selector: 'app-lan-settings-card',
  imports: [TranslatePipe, Card, Button, QrCode],
  template: `
    <app-card [title]="'lan.title' | t" data-testid="lan-settings">
      @if (status(); as s) {
        <p class="mb-3 text-[12px] text-muted">{{ 'lan.intro' | t }}</p>
        <label class="flex items-center gap-2 text-[13px]">
          <input type="checkbox" [checked]="s.enabled" [disabled]="!s.gamePc || busy()"
            (change)="toggle($any($event.target).checked)" data-testid="lan-enabled" />
          {{ 'lan.enabled' | t }}
        </label>
        @if (!s.gamePc) {
          <p class="mt-1 text-[11px] text-warn" data-testid="lan-readonly">{{ 'lan.readOnly' | t }}</p>
        }

        <div class="mt-4">
          <div class="fp-label">{{ 'lan.pin' | t }}</div>
          <p class="mt-1 text-[12px]" [class.text-accent]="s.pinSet" [class.text-warn]="!s.pinSet" data-testid="lan-pin-state">
            {{ (s.pinSet ? 'lan.pinSet' : 'lan.pinNone') | t }}
          </p>
          @if (s.gamePc) {
            <form class="mt-2 flex flex-wrap items-center gap-2" (submit)="$event.preventDefault(); savePin()">
              <input class="fp-input w-40" type="password" inputmode="numeric" autocomplete="new-password"
                [attr.maxlength]="s.pinMaxLength" [value]="pin()" (input)="pin.set($any($event.target).value)"
                [placeholder]="'lan.pinPlaceholder' | t: { min: s.pinMinLength, max: s.pinMaxLength }" data-testid="lan-pin" />
              <app-button type="submit" [disabled]="busy() || !pinValid()" data-testid="lan-pin-save">
                {{ (s.pinSet ? 'lan.pinChange' : 'lan.pinSave') | t }}
              </app-button>
              @if (s.pinSet) {
                <app-button variant="danger" [disabled]="busy()" (pressed)="removePin()" data-testid="lan-pin-remove">
                  {{ 'lan.pinRemove' | t }}
                </app-button>
              }
            </form>
          }
        </div>

        <div class="mt-4">
          <div class="fp-label">{{ 'lan.address' | t }}</div>
          @if (!s.enabled) {
            <p class="mt-1 text-[11px] text-warn" data-testid="lan-off">{{ 'lan.off' | t }}</p>
          }
          @for (url of s.urls; track url) {
            <div class="mt-2 flex flex-wrap items-center gap-4" data-testid="lan-url">
              <app-qr-code [text]="url" />
              <span class="font-display text-lg font-semibold text-text">{{ url }}</span>
            </div>
          } @empty {
            <p class="mt-1 text-[12px] text-muted" data-testid="lan-no-address">{{ 'lan.noAddress' | t }}</p>
          }
        </div>
        @if (error()) {
          <p class="mt-2 text-[12px] text-danger" data-testid="lan-error">{{ error() }}</p>
        }
      } @else {
        <p class="text-sm text-muted">–</p>
      }
    </app-card>
  `,
})
export class LanSettingsCard {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);

  readonly status = signal<LanStatusView | null>(null);
  readonly pin = signal('');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  readonly pinValid = computed(() => {
    const s = this.status();
    const p = this.pin();
    return !!s && /^\d+$/.test(p) && p.length >= s.pinMinLength && p.length <= s.pinMaxLength;
  });

  constructor() {
    this.api.lanStatus().subscribe({ next: (s) => this.status.set(s), error: () => this.status.set(null) });
  }

  toggle(enabled: boolean): void {
    this.run(this.api.saveLanSettings(enabled));
  }

  savePin(): void {
    this.run(this.api.setLanPin(this.pin()));
  }

  removePin(): void {
    this.run(this.api.removeLanPin());
  }

  private run(call: ReturnType<ApiService['lanStatus']>): void {
    this.busy.set(true);
    this.error.set(null);
    call.subscribe({
      next: (s) => {
        this.status.set(s);
        this.pin.set('');
        this.busy.set(false);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
      },
    });
  }
}
