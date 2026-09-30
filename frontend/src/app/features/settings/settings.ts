import { GameStateStore } from '../../core/state/game-state.store';
import { Component, computed, inject, signal } from '@angular/core';
import { apiErrorMessage } from '../../core/api/api-error';
import { ApiService } from '../../core/api/api.service';
import {
  AiSettingsView, BypassSettingsView, FieldSettingsView, GameSettingsView, PromptSettingsView,
} from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { TranslationService } from '../../core/i18n/translation.service';
import { LabelPipe } from '../../shared/format/label.pipe';
import { Badge } from '../../shared/ui/badge';
import { Button } from '../../shared/ui/button';
import { Card } from '../../shared/ui/card';
import { LanSettingsCard } from '../lan/lan-settings-card';

/** Providers that need an API key / that talk to a configurable local endpoint. */
export const KEY_PROVIDERS = ['OPENAI', 'ANTHROPIC', 'GEMINI'];
export const URL_PROVIDERS = ['OLLAMA'];

/**
 * Settings (AP-8.11): local AI provider, model and API key. The key is only held in this form until it is sent to
 * the backend, which writes it to its git-ignored local config; it is never read back or stored in the browser.
 * The tone preset is shown read-only (fixed since the onboarding).
 */
@Component({
  selector: 'app-settings',
  imports: [TranslatePipe, LabelPipe, Card, Badge, Button, LanSettingsCard],
  templateUrl: './settings.html',
})
export class Settings {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(TranslationService);
  readonly store = inject(GameStateStore);

  readonly ai = signal<AiSettingsView | null>(null);
  readonly game = signal<GameSettingsView | null>(null);
  readonly fields = signal<FieldSettingsView | null>(null);
  readonly bypass = signal<BypassSettingsView | null>(null);
  readonly prompts = signal<PromptSettingsView | null>(null);
  readonly provider = signal('');
  readonly model = signal('');
  readonly apiKey = signal('');
  readonly baseUrl = signal('');
  readonly saving = signal(false);
  readonly saved = signal(false);
  readonly error = signal<string | null>(null);

  readonly needsKey = computed(() => KEY_PROVIDERS.includes(this.provider()));
  readonly needsUrl = computed(() => URL_PROVIDERS.includes(this.provider()));
  readonly isActive = computed(() => this.ai()?.provider === this.provider());
  readonly keyStored = computed(() => this.isActive() && !!this.ai()?.apiKeySet);

  constructor() {
    this.api.aiSettings().subscribe({
      next: (s) => this.apply(s),
      error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
    this.api.gameSettings().subscribe({ next: (g) => this.game.set(g), error: () => this.game.set(null) });
    this.api.fieldSettings().subscribe({ next: (f) => this.fields.set(f), error: () => this.fields.set(null) });
    this.api.bypassSettings().subscribe({ next: (b) => this.bypass.set(b), error: () => this.bypass.set(null) });
    this.api.promptSettings().subscribe({ next: (p) => this.prompts.set(p), error: () => this.prompts.set(null) });
  }

  /** Roadmap V2 R2-F2: switches one occasion of the questions in the game on or off. */
  togglePrompt(kind: string, on: boolean): void {
    const current = this.prompts()?.kinds ?? [];
    const kinds = on ? [...new Set([...current, kind])] : current.filter((k) => k !== kind);
    this.api.savePromptSettings(kinds).subscribe({
      next: (p) => this.prompts.set(p),
      error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  /** Roadmap V2 R2-D: switches the reactions to the vanilla loan and the game's field menu. */
  saveBypass(reactionsEnabled: boolean): void {
    this.api.saveBypassSettings({ reactionsEnabled }).subscribe({
      next: (b) => this.bypass.set(b),
      error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  /** Roadmap V2 R2-C6: switches the field work hints of the cooperative. */
  saveFields(fieldHintsEnabled: boolean): void {
    this.api.saveFieldSettings({ fieldHintsEnabled }).subscribe({
      next: (f) => this.fields.set(f),
      error: (e) => this.error.set(apiErrorMessage(e, this.i18n.t('common.error'))),
    });
  }

  private apply(s: AiSettingsView): void {
    this.ai.set(s);
    this.provider.set(s.provider);
    this.model.set(s.model ?? '');
    this.baseUrl.set(s.baseUrl ?? '');
    this.apiKey.set('');
  }

  selectProvider(p: string): void {
    this.provider.set(p);
    this.saved.set(false);
    const s = this.ai();
    const active = s?.provider === p;
    this.model.set(active ? (s?.model ?? '') : '');
    this.baseUrl.set(active ? (s?.baseUrl ?? '') : '');
    this.apiKey.set('');
  }

  save(): void {
    this.saving.set(true);
    this.saved.set(false);
    this.error.set(null);
    this.api
      .saveAiSettings({
        provider: this.provider(),
        model: this.model().trim() || undefined,
        apiKey: this.apiKey().trim() || undefined,
        baseUrl: this.baseUrl().trim() || undefined,
      })
      .subscribe({
        next: (s) => {
          this.saving.set(false);
          this.saved.set(true);
          this.apply(s);
        },
        error: (e) => {
          this.saving.set(false);
          this.error.set(apiErrorMessage(e, this.i18n.t('common.error')));
        },
      });
  }
}
