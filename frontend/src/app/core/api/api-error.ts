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

/** Backend error code (`ApiError.code`), or null for network/unknown errors. */
export function apiErrorCode(err: unknown): string | null {
  if (err instanceof HttpErrorResponse) {
    const body = err.error as Partial<ApiError> | null;
    return body && typeof body.code === 'string' ? body.code : null;
  }
  return null;
}

export interface PageError {
  message: string;
  code: string | null;
}

export function toPageError(err: unknown, fallback: string): PageError {
  return { message: apiErrorMessage(err, fallback), code: apiErrorCode(err) };
}
