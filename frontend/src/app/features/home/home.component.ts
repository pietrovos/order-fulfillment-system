import { Component, inject } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-home',
  template: `
    <h1>Welcome, {{ auth.user()?.displayName }}</h1>
    <p>Signed in as <strong>{{ auth.user()?.roles?.join(', ') }}</strong>.</p>
  `,
})
export class HomeComponent {
  protected readonly auth = inject(AuthService);
}
