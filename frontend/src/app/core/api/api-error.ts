import { HttpErrorResponse } from '@angular/common/http';
import { ApiError } from './models';

/** Extracts the user-facing (German) message of a backend `ApiError`; falls back to `fallback`. */
export function apiErrorMessage(err: unknown, fallback: string): string {
  if (err instanceof HttpErrorResponse) {
    const body = err.error as Partial<ApiError> | null;
    if (body && typeof body.message === 'string' && body.message) {
      const fields = body.fields ? Object.values(body.fields).filter(Boolean) : [];
      return fields.length ? `${body.message}: ${fields.join(', ')}` : body.message;
    }
  }
  return fallback;
}
