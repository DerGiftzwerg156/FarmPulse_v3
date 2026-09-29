import { Component, OnDestroy, OnInit, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { LiveEventsService } from '../core/live/live-events.service';
import { GameStateStore } from '../core/state/game-state.store';
import { Icon } from '../shared/ui/icon';
import { Toasts } from '../shared/ui/toasts';
import { CallOverlay } from '../features/calls/call-overlay';
import { MoneyPipe } from '../shared/format/format.pipes';
import { CalendarView } from '../core/api/models';
import { TranslationService } from '../core/i18n/translation.service';
import { calendarLabel } from '../shared/format/calendar';
import { clockTime } from '../shared/format/format';
import { weatherLong, weatherShort } from '../shared/format/weather';
import { WeatherView } from '../core/api/models';
import { AppDef, appForUrl, toneClass } from './apps';
import { AppBadges } from './app-badges';
import { Dock } from './dock';

/**
 * Hof-Tablet shell: status bar (savegame, game time, balance, live state), app header with the way back to the start
 * screen, and the dock (floating on the start screen, as a quick bar inside every app).
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, TranslatePipe, Icon, MoneyPipe, Toasts, CallOverlay, Dock],
  templateUrl: './shell.html',
})
export class Shell implements OnInit, OnDestroy {
  readonly state = inject(GameStateStore);
  readonly badges = inject(AppBadges);
  private readonly live = inject(LiveEventsService);
  private readonly i18n = inject(TranslationService);
  private readonly router = inject(Router);

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map((e) => (e as NavigationEnd).urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );
  /** The app of the current page; undefined on the start screen and in the onboarding. */
  readonly app = computed<AppDef | undefined>(() => appForUrl(this.url()));
  readonly isStart = computed(() => this.url().split(/[?#]/)[0] === '/');

  ngOnInit(): void {
    this.state.refresh();
    this.live.connect();
  }

  ngOnDestroy(): void {
    this.live.disconnect();
  }

  periodLabel(c: CalendarView): string {
    return calendarLabel(c, this.i18n);
  }

  weather(w: WeatherView): string {
    return weatherShort(w, this.i18n);
  }

  weatherTitle(w: WeatherView): string {
    return weatherLong(w, this.i18n);
  }

  clock(gameTime: number): string {
    return clockTime(gameTime);
  }

  tone(a: AppDef): string {
    return toneClass(a.tone);
  }
}
