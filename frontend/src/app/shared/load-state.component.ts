import { Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

/**
 * Displays loading, error and empty states for data views.
 * Renders projected content when data is ready.
 */
@Component({
  selector: 'app-load-state',
  imports: [MatProgressSpinnerModule, MatButtonModule, MatIconModule],
  template: `
    @if (loading()) {
      <div class="state" role="status" aria-live="polite"><mat-spinner diameter="36" /><span>Loading…</span></div>
    } @else if (error()) {
      <div class="state error" role="alert">
        <mat-icon>error</mat-icon>
        <span>{{ error() }}</span>
        <button mat-stroked-button (click)="retry.emit()">Try again</button>
      </div>
    } @else if (empty()) {
      <div class="state empty">
        <mat-icon>{{ emptyIcon() }}</mat-icon>
        <span>{{ emptyText() }}</span>
        <ng-content select="[empty-action]" />
      </div>
    } @else {
      <ng-content />
    }
  `,
  styles: `
    .state { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 48px 16px;
             color: var(--mat-sys-on-surface-variant); text-align: center; }
    .state mat-icon { font-size: 40px; width: 40px; height: 40px; }
    .error { color: var(--mat-sys-error); }
  `,
})
export class LoadStateComponent {
  readonly loading = input(false);
  readonly error = input<string | null>(null);
  readonly empty = input(false);
  readonly emptyText = input('Nothing here yet');
  readonly emptyIcon = input('inbox');
  readonly retry = output<void>();
}
