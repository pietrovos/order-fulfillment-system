import { HttpErrorResponse } from '@angular/common/http';

export interface ApiError {
  status: number;
  code: string;
  message: string;
  fieldErrors?: Record<string, string>;
}

/** Human-readable message for any HTTP failure, preferring the API's own error body. */
export function errorMessage(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    const body = err.error as Partial<ApiError> | null;
    if (body && typeof body === 'object' && body.message) return body.message;
    if (err.status === 0) return 'Cannot reach the server. Check your connection and try again.';
    return `Request failed (${err.status})`;
  }
  return 'Something went wrong';
}

export function errorCode(err: unknown): string | null {
  return err instanceof HttpErrorResponse ? ((err.error as Partial<ApiError> | null)?.code ?? null) : null;
}
