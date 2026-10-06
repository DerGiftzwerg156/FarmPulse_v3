import { Component, OnDestroy, OnInit, computed, effect, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { LiveEventsService } from '../core/live/live-events.service';
import { AppHintsStore } from '../core/state/app-hints.store';
import { GameStateStore } from '../core/state/game-state.store';
import { Button } from '../shared/ui/button';
import { Modal } from '../shared/ui/modal';
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
import { hintsOf } from './app-hints';
import { isTab, rememberTab, tabsOf } from './app-tabs';
import { TabBar } from './tab-bar';

/**
 * Hof-Tablet shell: status bar (savegame, game time, balance, live state), app header with the way back to the start
 * screen, and the dock (floating on the start screen, as a quick bar inside every app). Below the app header the tabs
 * of the app; the first-open hint of an app opens as a dialog until it is confirmed (once per installation) and again
 * with the "?" button (owner decisions 2026-10-06).
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, TranslatePipe, Icon, MoneyPipe, Toasts, CallOverlay, Dock, TabBar, Modal, Button],
  templateUrl: './shell.html',
})
export class Shell implements OnInit, OnDestroy {
  readonly state = inject(GameStateStore);
  readonly badges = inject(AppBadges);
  private readonly hints = inject(AppHintsStore);
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
  readonly hasTabs = computed(() => !!this.app() && tabsOf(this.app()!.id).length > 0);

  /** Hints closed without "Verstanden" in this visit (they come back after a reload). */
  private readonly dismissed = signal<ReadonlySet<string>>(new Set());
  /** App whose hint was opened again with "?". */
  private readonly reopened = signal<string | null>(null);
  readonly hintKeys = computed(() => (this.app() ? hintsOf(this.app()!.id) : []));
  readonly hintOpen = computed(() => {
    const a = this.app();
    if (!a || this.hintKeys().length === 0) return false;
    if (this.reopened() === a.id) return true;
    return this.hints.loaded() && !this.hints.isSeen(a.id) && !this.dismissed().has(a.id);
  });

  constructor() {
    // the tab last used on this device (owner decision 2026-10-06)
    effect(() => {
      const a = this.app();
      const tab = this.url().split(/[?#]/)[0].split('/')[2];
      if (a && isTab(a.id, tab)) rememberTab(a.id, tab);
    });
  }

  ngOnInit(): void {
    this.hints.load();
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

  openHint(a: AppDef): void {
    this.reopened.set(a.id);
  }

  /** "Verstanden": the hint does not open again by itself, on no device. */
  confirmHint(a: AppDef): void {
    this.reopened.set(null);
    if (!this.hints.isSeen(a.id)) this.hints.markSeen(a.id);
  }

  closeHint(a: AppDef): void {
    this.reopened.set(null);
    this.dismissed.update((d) => new Set([...d, a.id]));
  }

  tone(a: AppDef): string {
    return toneClass(a.tone);
  }
}
