import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { LoginComponent } from './login.component';

describe('LoginComponent', () => {
  const login = vi.fn();

  beforeEach(() => {
    login.mockReset();
    TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: { login } }],
    });
  });

  function fill(el: HTMLElement, user: string, pw: string) {
    const u = el.querySelector<HTMLInputElement>('[data-testid=username]')!;
    const p = el.querySelector<HTMLInputElement>('[data-testid=password]')!;
    u.value = user; u.dispatchEvent(new Event('input'));
    p.value = pw; p.dispatchEvent(new Event('input'));
  }

  it('disables submit until both fields are filled', async () => {
    const f = TestBed.createComponent(LoginComponent);
    await f.whenStable();
    const btn = f.nativeElement.querySelector('[data-testid=login]') as HTMLButtonElement;
    expect(btn.disabled).toBe(true);
    fill(f.nativeElement, 'sales', 'x');
    f.detectChanges();
    expect(btn.disabled).toBe(false);
  });

  it('navigates home on success', async () => {
    login.mockReturnValue(of({}));
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const f = TestBed.createComponent(LoginComponent);
    await f.whenStable();
    fill(f.nativeElement, 'sales', 'fulfill123');
    f.componentInstance.submit();
    expect(login).toHaveBeenCalledWith('sales', 'fulfill123');
    expect(navigate).toHaveBeenCalledWith(['/']);
  });

  it('shows the API error message on failure', async () => {
    login.mockReturnValue(throwError(() => new HttpErrorResponse({
      status: 401, error: { code: 'UNAUTHENTICATED', message: 'Invalid username or password' },
    })));
    const f = TestBed.createComponent(LoginComponent);
    await f.whenStable();
    fill(f.nativeElement, 'sales', 'bad');
    f.componentInstance.submit();
    f.detectChanges();
    expect(f.nativeElement.querySelector('[role=alert]')?.textContent).toContain('Invalid username or password');
  });
});
