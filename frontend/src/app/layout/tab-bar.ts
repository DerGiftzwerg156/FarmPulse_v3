import { Component, computed, inject, input } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { AppBadges } from './app-badges';
import { AppDef } from './apps';
import { tabsOf } from './app-tabs';

/** Sub pages of an app below the app header; every tab has its own address (`/bank/kontoauszug`). */
@Component({
  selector: 'app-tab-bar',
  imports: [RouterLink, RouterLinkActive, TranslatePipe],
  template: `
    <nav class="flex gap-1 overflow-x-auto border-b border-border/60 px-3 md:px-8" [attr.aria-label]="app().label | t" data-testid="tab-bar">
      @for (t of tabs(); track t.id) {
        <a [routerLink]="[app().path, t.id]" routerLinkActive="!border-accent !text-text" ariaCurrentWhenActive="page"
          class="flex shrink-0 items-center gap-1.5 border-b-2 border-transparent px-3 py-2.5 font-display text-[11px] font-semibold uppercase tracking-[0.1em] text-[#8FA39A] hover:text-text"
          [attr.data-testid]="'tab-' + t.id">
          {{ t.label | t }}
          @if (count(t.id) > 0) {
            <span class="flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-warn px-1 text-[10px] font-bold text-bg" data-testid="tab-badge">
              {{ count(t.id) > 99 ? '99+' : count(t.id) }}
            </span>
          }
        </a>
      }
    </nav>
  `,
})
export class TabBar {
  private readonly badges = inject(AppBadges);
  readonly app = input.required<AppDef>();
  readonly tabs = computed(() => tabsOf(this.app().id));

  count(tab: string): number {
    return this.badges.tabCount(this.app().id, tab);
  }
}
