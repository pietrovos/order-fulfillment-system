import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService, Role } from './auth.service';

export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.isLoggedIn() ? true : inject(Router).createUrlTree(['/login']);
};

/** Route-level role check. The API enforces authorization independently. */
export const roleGuard =
  (...roles: Role[]): CanActivateFn =>
  () => {
    const auth = inject(AuthService);
    if (!auth.isLoggedIn()) return inject(Router).createUrlTree(['/login']);
    return auth.hasAnyRole(roles) ? true : inject(Router).createUrlTree(['/']);
  };
