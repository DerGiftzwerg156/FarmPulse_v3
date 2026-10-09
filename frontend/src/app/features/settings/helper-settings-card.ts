import { Component, OnInit, inject, signal } from '@angular/core';
import { ApiService } from '../../core/api/api.service';
import { HelperSettingsView } from '../../core/api/models';
import { TranslatePipe } from '../../core/i18n/translate.pipe';
import { Card } from '../../shared/ui/card';

/**
 * Roadmap V2 R2-A1 / R2-A3: helper switches of the savegame (who pays the FS25 helpers, strict helper limit).
 * Lives in the settings, tab "Im Spiel" (owner decision 2026-10-06: settings only in the settings app).
 */
@Component({
  selector: 'app-helper-settings-card',
  imports: [TranslatePipe, Card],
  template: `
    <app-card [title]="'settings.helpers.title' | t" data-testid="helper-settings">
      @if (helpers(); as h) {
        <label class="flex items-start gap-2 text-[12px] text-text">
          <input type="checkbox" [checked]="h.helperWageMode === 'EMPLOYEES'" (change)="save({ helperWageMode: $any($event.target).checked ? 'EMPLOYEES' : 'VANILLA', strictHelperLimit: h.strictHelperLimit })" data-testid="helper-wage" />
          <span>{{ 'settings.helpers.wage' | t }}</span>
        </label>
        <label class="mt-2 flex items-start gap-2 text-[12px] text-text">
          <input type="checkbox" [checked]="h.strictHelperLimit" (change)="save({ helperWageMode: h.helperWageMode, strictHelperLimit: $any($event.target).checked })" data-testid="helper-strict" />
          <span>{{ 'settings.helpers.strict' | t }}</span>
        </label>
        @if (!h.workforceTracked) {
          <p class="mt-1 text-[11px] text-warn" data-testid="helper-untracked">{{ 'settings.helpers.untracked' | t }}</p>
        }
      } @else {
        <p class="text-[12px] text-muted">–</p>
      }
    </app-card>
  `,
})
export class HelperSettingsCard implements OnInit {
  private readonly api = inject(ApiService);
  readonly helpers = signal<HelperSettingsView | null>(null);

  ngOnInit(): void {
    this.api.helperSettings().subscribe({ next: (h) => this.helpers.set(h), error: () => this.helpers.set(null) });
  }

  /** Saved and sent to the mod with the employee list. */
  save(r: { helperWageMode: string; strictHelperLimit: boolean }): void {
    this.api.saveHelperSettings(r).subscribe({ next: (h) => this.helpers.set(h) });
  }
}
