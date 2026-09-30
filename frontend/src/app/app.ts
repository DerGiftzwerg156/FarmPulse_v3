import { Component, OnInit, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { LanAccessStore } from './core/lan/lan-access.store';
import { PinLogin } from './features/lan/pin-login';

/** Root: the Hof-Tablet, or - on a device in the home network with a PIN set - the PIN login first (R3-N2). */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, PinLogin],
  template: `
    @switch (lan.loginRequired()) {
      @case (true) {
        <app-pin-login />
      }
      @case (false) {
        <router-outlet />
      }
    }
  `,
})
export class App implements OnInit {
  readonly lan = inject(LanAccessStore);

  ngOnInit(): void {
    this.lan.check();
  }
}
