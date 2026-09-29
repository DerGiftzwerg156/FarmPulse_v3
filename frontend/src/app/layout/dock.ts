import { Component, computed, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { filter, map } from 'rxjs';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { Icon } from '../shared/ui/icon';
import { AppTile } from './app-tile';
import { APPS, DOCK_IDS, appForUrl } from './apps';

/**
 * Dock of the Hof-Tablet: floating on the start screen, a quick bar (start button + dock apps) at the bottom of every
 * app so the most used apps stay one tap away.
 */
@Component({
  selector: 'app-dock',
  imports: [RouterLink, TranslatePipe, Icon, AppTile],
  template: `
    @if (variant() === 'floating') {
      <nav class="fixed bottom-3 left-1/2 z-20 flex -translate-x-1/2 items-center gap-3 rounded-3xl border border-border bg-[#131916] px-4 py-2.5 shadow-[0_10px_30px_rgba(0,0,0,0.45)] md:bottom-5 md:gap-3.5"
        [attr.aria-label]="'tablet.dock' | t" data-testid="dock">
        @for (a of apps; track a.id) {
          <app-tile [app]="a" [labelled]="false" size="md" />
        }
      </nav>
    } @else {
      <nav class="fixed inset-x-0 bottom-0 z-20 flex h-16 items-center justify-center gap-2.5 border-t border-border/60 bg-chrome"
        [attr.aria-label]="'tablet.quickBar' | t" data-testid="quick-bar">
        <a routerLink="/" class="flex h-11 w-11 items-center justify-center rounded-xl border border-border text-app-sys hover:border-accent/60"
          [attr.aria-label]="'nav.start' | t" data-testid="quick-start">
          <app-icon name="home" size="h-5 w-5" />
        </a>
        <span class="h-7 w-px bg-border"></span>
        @for (a of apps; track a.id) {
          <app-tile [app]="a" [labelled]="false" size="sm" [active]="current()?.id === a.id" />
        }
      </nav>
    }
  `,
})
export class Dock {
  private readonly router = inject(Router);
  readonly variant = input<'floating' | 'bar'>('floating');
  readonly apps = APPS.filter((a) => DOCK_IDS.includes(a.id));
  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map((e) => (e as NavigationEnd).urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );
  readonly current = computed(() => appForUrl(this.url()));
}
