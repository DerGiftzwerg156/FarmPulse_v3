import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { TranslatePipe } from '../core/i18n/translate.pipe';
import { GameStateStore } from '../core/state/game-state.store';
import { Icon } from '../shared/ui/icon';
import { Toasts } from '../shared/ui/toasts';
import { MoneyPipe } from '../shared/format/format.pipes';
import { NAV_ITEMS } from './nav-items';

/**
 * App shell in the look of the design reference: icon rail on desktop (md+), toggleable slide-over menu on narrow
 * viewports, sticky top bar with savegame context, live balance and notification indicator.
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TranslatePipe, Icon, MoneyPipe, Toasts],
  templateUrl: './shell.html',
})
export class Shell implements OnInit {
  readonly state = inject(GameStateStore);
  readonly items = NAV_ITEMS;
  readonly menuOpen = signal(false);

  ngOnInit(): void {
    this.state.refresh();
  }

  toggleMenu(): void {
    this.menuOpen.update((v) => !v);
  }
}
