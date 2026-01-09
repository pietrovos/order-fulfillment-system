import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { map } from 'rxjs';
import { AuthService } from '../core/auth/auth.service';
import { NAV_ITEMS } from './nav';

@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet, RouterLink, RouterLinkActive,
    MatSidenavModule, MatToolbarModule, MatListModule, MatIconModule, MatButtonModule, MatMenuModule,
  ],
  template: `
    <mat-sidenav-container class="container">
      <mat-sidenav #drawer [mode]="handset() ? 'over' : 'side'" [opened]="!handset()" class="sidenav">
        <div class="brand">FulfillOps</div>
        <mat-nav-list>
          @for (item of nav(); track item.path) {
            <a mat-list-item [routerLink]="item.path" routerLinkActive="active"
               [routerLinkActiveOptions]="{ exact: item.path === '/' }"
               (click)="handset() && drawer.close()">
              <mat-icon matListItemIcon>{{ item.icon }}</mat-icon>
              <span matListItemTitle>{{ item.label }}</span>
            </a>
          }
        </mat-nav-list>
      </mat-sidenav>
      <mat-sidenav-content>
        <mat-toolbar class="toolbar">
          @if (handset()) {
            <button mat-icon-button (click)="drawer.toggle()" aria-label="Toggle navigation">
              <mat-icon>menu</mat-icon>
            </button>
          }
          <span class="spacer"></span>
          <button mat-button [matMenuTriggerFor]="userMenu" data-testid="user-menu">
            <mat-icon>account_circle</mat-icon>
            {{ auth.user()?.displayName }}
          </button>
          <mat-menu #userMenu="matMenu">
            <button mat-menu-item (click)="auth.logout()" data-testid="logout">
              <mat-icon>logout</mat-icon> Sign out
            </button>
          </mat-menu>
        </mat-toolbar>
        <main class="content">
          <router-outlet />
        </main>
      </mat-sidenav-content>
    </mat-sidenav-container>
  `,
  styles: `
    .container { height: 100vh; }
    .sidenav { width: 232px; }
    .brand { font: var(--mat-sys-title-large); font-weight: 600; padding: 20px 16px 12px; }
    .toolbar { position: sticky; top: 0; z-index: 2; background: var(--mat-sys-surface-container); }
    .spacer { flex: 1; }
    .content { padding: 16px; max-width: 1400px; margin: 0 auto; }
    .active { background: var(--mat-sys-secondary-container); }
    @media (min-width: 960px) { .content { padding: 24px; } }
  `,
})
export class ShellComponent {
  protected readonly auth = inject(AuthService);
  protected readonly handset = toSignal(
    inject(BreakpointObserver).observe([Breakpoints.XSmall, Breakpoints.Small]).pipe(map((r) => r.matches)),
    { initialValue: false },
  );
  protected readonly nav = computed(() => {
    this.auth.user();
    return NAV_ITEMS.filter((i) => this.auth.hasAnyRole(i.roles));
  });
}
