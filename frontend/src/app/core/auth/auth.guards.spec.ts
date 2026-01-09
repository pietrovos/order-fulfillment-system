import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';
import { signal } from '@angular/core';
import { AuthService, Role } from './auth.service';
import { authGuard, roleGuard } from './auth.guards';

function setup(roles: Role[] | null) {
  const user = signal(roles ? { username: 'u', displayName: 'U', roles } : null);
  const fake = {
    isLoggedIn: () => user() !== null,
    hasAnyRole: (rs: Role[]) => rs.length === 0 || rs.some((r) => user()?.roles.includes(r)),
  };
  TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: AuthService, useValue: fake }] });
}

const run = (guard: typeof authGuard) =>
  TestBed.runInInjectionContext(() => guard({} as never, {} as never)) as boolean | UrlTree;

describe('route guards', () => {
  it('authGuard redirects anonymous users to /login', () => {
    setup(null);
    const result = run(authGuard);
    expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/login');
  });

  it('roleGuard lets a matching role through', () => {
    setup(['SUPERVISOR']);
    expect(run(roleGuard('SUPERVISOR'))).toBe(true);
  });

  it('roleGuard sends other roles home', () => {
    setup(['SALES']);
    expect(TestBed.inject(Router).serializeUrl(run(roleGuard('WAREHOUSE')) as UrlTree)).toBe('/');
  });
});
