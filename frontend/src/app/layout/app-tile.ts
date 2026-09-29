import { Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { Icon } from '../shared/ui/icon';
import { AppBadges } from './app-badges';
import { AppDef, toneClass } from './apps';

/** App symbol with badge; `labelled` shows the name below (start screen), otherwise it is the aria-label (dock). */
@Component({
  selector: 'app-tile',
  imports: [RouterLink, TranslatePipe, Icon],
  template: `
    <a [routerLink]="app().path" class="group flex flex-col items-center gap-2 text-text" [attr.aria-label]="labelled() ? null : (app().label | t)"
      [attr.data-testid]="'app-' + app().id">
      <span class="relative flex items-center justify-center border transition group-hover:border-accent/60"
        [class]="toneCls() + ' ' + sizeCls()" [class.bg-tile]="!active()" [class.border-border]="!active()"
        [class.bg-tile-active]="active()" [class.border-accent]="active()">
        <app-icon [name]="app().icon" [size]="iconSize()" />
        @if (badge() > 0) {
          <span class="absolute -right-1.5 -top-1.5 flex h-5 min-w-5 items-center justify-center rounded-full border-2 border-bg bg-warn px-1.5 font-display text-[11px] font-bold text-bg"
            data-testid="app-badge">{{ badge() > 99 ? '99+' : badge() }}</span>
        }
      </span>
      @if (labelled()) {
        <span class="text-center text-[12px] font-medium leading-tight text-[#D3DED9] md:text-[13px]">{{ app().label | t }}</span>
      }
    </a>
  `,
})
export class AppTile {
  private readonly badges = inject(AppBadges);
  readonly app = input.required<AppDef>();
  readonly labelled = input(true);
  readonly size = input<'lg' | 'md' | 'sm'>('lg');
  readonly active = input(false);
  readonly badge = computed(() => this.badges.count(this.app().id));
  readonly toneCls = computed(() => toneClass(this.app().tone));
  readonly sizeCls = computed(
    () => ({ lg: 'h-[58px] w-[58px] rounded-2xl md:h-[68px] md:w-[68px]', md: 'h-[54px] w-[54px] rounded-[15px]', sm: 'h-11 w-11 rounded-xl' })[this.size()],
  );
  readonly iconSize = computed(() => ({ lg: 'h-7 w-7', md: 'h-6 w-6', sm: 'h-5 w-5' })[this.size()]);
}
