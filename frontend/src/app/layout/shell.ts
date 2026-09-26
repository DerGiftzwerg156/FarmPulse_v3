import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { LiveEventsService } from '../core/live/live-events.service';
import { GameStateStore } from '../core/state/game-state.store';
import { Icon } from '../shared/ui/icon';
import { Toasts } from '../shared/ui/toasts';
import { CallOverlay } from '../features/calls/call-overlay';
import { MoneyPipe } from '../shared/format/format.pipes';
import { NAV_ITEMS } from './nav-items';
import { CalendarView } from '../core/api/models';
import { TranslationService } from '../core/i18n/translation.service';
import { calendarLabel } from '../shared/format/calendar';

/**
 * App shell in the look of the design reference: icon rail on desktop (md+), toggleable slide-over menu on narrow
 * viewports, sticky top bar with savegame context, live balance and notification indicator.
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TranslatePipe, Icon, MoneyPipe, Toasts, CallOverlay],
  templateUrl: './shell.html',
})
export class Shell implements OnInit, OnDestroy {
  readonly state = inject(GameStateStore);
  private readonly live = inject(LiveEventsService);
  readonly items = NAV_ITEMS;
  readonly menuOpen = signal(false);
  private readonly i18n = inject(TranslationService);

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

  toggleMenu(): void {
    this.menuOpen.update((v) => !v);
  }
}
