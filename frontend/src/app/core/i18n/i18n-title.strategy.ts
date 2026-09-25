import { Injectable, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { TranslationService } from './translation.service';

/** Route titles are i18n keys; the document title becomes "<page> · FarmPulse". */
@Injectable()
export class I18nTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);
  private readonly i18n = inject(TranslationService);

  override updateTitle(snapshot: RouterStateSnapshot): void {
    const key = this.buildTitle(snapshot);
    const app = this.i18n.t('app.name');
    this.title.setTitle(key && key !== 'nav.home' ? `${this.i18n.t(key)} · ${app}` : app);
  }
}
