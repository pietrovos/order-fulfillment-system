import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';

export type Role = 'SALES' | 'WAREHOUSE' | 'SUPERVISOR';

export interface CurrentUser {
  username: string;
  displayName: string;
  roles: Role[];
}

interface LoginResponse {
  token: string;
  expiresAt: string;
  user: CurrentUser;
}

interface StoredSession {
  token: string;
  expiresAt: string;
  user: CurrentUser;
}

const STORAGE_KEY = 'fulfillops.session';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly session = signal<StoredSession | null>(this.restore());

  readonly user = computed(() => this.session()?.user ?? null);
  readonly isLoggedIn = computed(() => this.session() !== null);
  readonly token = computed(() => this.session()?.token ?? null);

  login(username: string, password: string): Observable<LoginResponse> {
    return this.http.post<LoginResponse>('/api/auth/login', { username, password }).pipe(
      tap((res) => {
        const s: StoredSession = { token: res.token, expiresAt: res.expiresAt, user: res.user };
        this.persist(s);
        this.session.set(s);
      }),
    );
  }

  logout(): void {
    this.persist(null);
    this.session.set(null);
    void this.router.navigate(['/login']);
  }

  hasAnyRole(roles: readonly Role[]): boolean {
    const mine = this.user()?.roles ?? [];
    return roles.length === 0 || roles.some((r) => mine.includes(r));
  }

  private restore(): StoredSession | null {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return null;
      const s = JSON.parse(raw) as StoredSession;
      return new Date(s.expiresAt).getTime() > Date.now() ? s : null;
    } catch {
      return null;
    }
  }

  private persist(s: StoredSession | null): void {
    try {
      if (s) localStorage.setItem(STORAGE_KEY, JSON.stringify(s));
      else localStorage.removeItem(STORAGE_KEY);
    } catch {
      /* storage unavailable: session lives in memory only */
    }
  }
}
