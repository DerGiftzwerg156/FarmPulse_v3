import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { TitleStrategy, provideRouter, withComponentInputBinding } from '@angular/router';
import { I18nTitleStrategy } from './core/i18n/i18n-title.strategy';
import { routes } from './app.routes';
import { lanLoginInterceptor } from './core/lan/lan-access.store';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withFetch(), withInterceptors([lanLoginInterceptor])),
    provideRouter(routes, withComponentInputBinding()),
    { provide: TitleStrategy, useClass: I18nTitleStrategy },
  ],
};
