import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { authInterceptor } from './auth.interceptor';

const future = new Date(Date.now() + 3_600_000).toISOString();
const user = { username: 'sales', displayName: 'Sam', roles: ['SALES' as const] };

describe('AuthService + authInterceptor', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function login(): AuthService {
    const auth = TestBed.inject(AuthService);
    auth.login('sales', 'pw').subscribe();
    http.expectOne('/api/auth/login').flush({ token: 'tkn', expiresAt: future, user });
    return auth;
  }

  it('stores the session and exposes the user as signals', () => {
    const auth = login();
    expect(auth.isLoggedIn()).toBe(true);
    expect(auth.user()?.username).toBe('sales');
    expect(JSON.parse(localStorage.getItem('fulfillops.session')!).token).toBe('tkn');
  });

  it('checks roles', () => {
    const auth = login();
    expect(auth.hasAnyRole(['SALES', 'SUPERVISOR'])).toBe(true);
    expect(auth.hasAnyRole(['WAREHOUSE'])).toBe(false);
    expect(auth.hasAnyRole([])).toBe(true);
  });

  it('adds the bearer token to API calls but not to login', () => {
    login();
    TestBed.inject(HttpClient).get('/api/orders').subscribe();
    expect(http.expectOne('/api/orders').request.headers.get('Authorization')).toBe('Bearer tkn');
  });

  it('logs out and redirects when the API answers 401', () => {
    const auth = login();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    TestBed.inject(HttpClient).get('/api/orders').subscribe({ error: () => undefined });
    http.expectOne('/api/orders').flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(auth.isLoggedIn()).toBe(false);
    expect(localStorage.getItem('fulfillops.session')).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login']);
  });

  it('ignores an expired stored session', () => {
    localStorage.setItem('fulfillops.session', JSON.stringify({ token: 'old', expiresAt: '2000-01-01T00:00:00Z', user }));
    expect(TestBed.inject(AuthService).isLoggedIn()).toBe(false);
  });
});
