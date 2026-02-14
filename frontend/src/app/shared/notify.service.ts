import { Injectable, inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { errorMessage } from '../core/http/api-error';

@Injectable({ providedIn: 'root' })
export class Notify {
  private readonly snack = inject(MatSnackBar);

  ok(message: string): void {
    this.snack.open(message, 'OK', { duration: 3500 });
  }

  fail(err: unknown): void {
    this.snack.open(errorMessage(err), 'Dismiss', { duration: 7000, panelClass: 'snack-error' });
  }
}
