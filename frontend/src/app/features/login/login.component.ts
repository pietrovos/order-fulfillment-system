import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/http/api-error';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, MatCardModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatProgressBarModule],
  template: `
    <div class="wrap">
      <mat-card class="card">
        @if (submitting()) {
          <mat-progress-bar mode="indeterminate" />
        }
        <mat-card-header>
          <mat-card-title>Sign in to FulfillOps</mat-card-title>
          <mat-card-subtitle>Stock, orders and shipments</mat-card-subtitle>
        </mat-card-header>
        <form [formGroup]="form" (ngSubmit)="submit()">
          <mat-card-content>
            <mat-form-field appearance="outline">
              <mat-label>Username</mat-label>
              <input matInput formControlName="username" autocomplete="username" data-testid="username" />
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Password</mat-label>
              <input matInput type="password" formControlName="password" autocomplete="current-password" data-testid="password" />
            </mat-form-field>
            @if (error()) {
              <p class="error" role="alert">{{ error() }}</p>
            }
            <p class="hint">Demo accounts: <code>sales</code>, <code>warehouse</code>, <code>supervisor</code> / <code>fulfill123</code></p>
          </mat-card-content>
          <mat-card-actions align="end">
            <button mat-flat-button type="submit" [disabled]="form.invalid || submitting()" data-testid="login">Sign in</button>
          </mat-card-actions>
        </form>
      </mat-card>
    </div>
  `,
  styles: `
    .wrap { min-height: 100vh; display: grid; place-items: center; padding: 16px; box-sizing: border-box; }
    .card { width: 100%; max-width: 400px; }
    mat-form-field { width: 100%; }
    form { padding-top: 16px; }
    .error { color: var(--mat-sys-error); margin: 0 0 8px; }
    .hint { font: var(--mat-sys-body-small); color: var(--mat-sys-on-surface-variant); }
  `,
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(NonNullableFormBuilder).group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  submit(): void {
    if (this.form.invalid) return;
    this.submitting.set(true);
    this.error.set(null);
    const { username, password } = this.form.getRawValue();
    this.auth.login(username, password).subscribe({
      next: () => void this.router.navigate(['/']),
      error: (err) => {
        this.error.set(errorMessage(err));
        this.submitting.set(false);
      },
    });
  }
}
