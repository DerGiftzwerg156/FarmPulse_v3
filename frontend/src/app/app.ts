import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: `<div class="fp-grid min-h-screen"><router-outlet /></div>`,
})
export class App {}
