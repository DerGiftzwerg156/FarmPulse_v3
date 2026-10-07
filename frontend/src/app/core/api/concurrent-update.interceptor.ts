import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { TranslationService } from '../i18n/translation.service';
import { GameStateStore } from '../state/game-state.store';
import { ToastService } from '../../shared/ui/toast.service';

/** Backend code of a version conflict (technical review 10/2026, Phase 1.4). */
export const CONCURRENT_UPDATE = 'CONCURRENT_UPDATE';

/**
 * Review 10/2026 Phase 1.4 (owner decision): the data was changed in between (another device or the game) and the
 * backend saved nothing. A hint is shown and every page reloads its data (the same signal live updates use); the error
 * still reaches the caller, so the form shows it as well.
 */
export const concurrentUpdateInterceptor: HttpInterceptorFn = (req, next) => {
  const store = inject(GameStateStore);
  const toasts = inject(ToastService);
  const i18n = inject(TranslationService);
  return next(req).pipe(
    catchError((err: unknown) => {
      if (err instanceof HttpErrorResponse && err.status === 409 && err.error?.code === CONCURRENT_UPDATE) {
        toasts.show(i18n.t('common.concurrentUpdate'), 'warning', 8000);
        store.stateVersion.update((v) => v + 1);
        store.refresh();
      }
      return throwError(() => err);
    }),
  );
};
