import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { InventoryApi } from '../../core/api/inventory.api';
import { StockLevel } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { InventoryComponent } from './inventory.component';

const row = (sku: string, available: number, lowStock = false): StockLevel => ({
  productId: sku.length, sku, name: `Item ${sku}`, onHand: available + 1, reserved: 1, available,
  reorderPoint: 5, lowStock, updatedAt: '2026-01-01T00:00:00Z',
});

function setup(stock: () => ReturnType<InventoryApi['stock']>, roles = ['WAREHOUSE']) {
  TestBed.configureTestingModule({
    imports: [InventoryComponent],
    providers: [
      provideRouter([]),
      { provide: InventoryApi, useValue: { stock } },
      { provide: AuthService, useValue: { hasAnyRole: (rs: string[]) => rs.some((r) => roles.includes(r)) } },
    ],
  });
  const f = TestBed.createComponent(InventoryComponent);
  f.detectChanges();
  return f;
}

describe('InventoryComponent', () => {
  it('renders rows with available stock and totals', () => {
    const f = setup(() => of([row('B-2', 7), row('A-1', 3, true)]));
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="available-A-1"]')?.textContent?.trim()).toBe('3');
    expect(el.textContent).toContain('At or below reorder point');
    const firstSku = el.querySelector('tbody tr td')?.textContent?.trim();
    expect(firstSku).toBe('A-1');
  });

  it('filters to low stock only', () => {
    const f = setup(() => of([row('B-2', 7), row('A-1', 3, true)]));
    (f.nativeElement.querySelector('.kpi-button') as HTMLButtonElement).click();
    f.detectChanges();
    expect(f.nativeElement.querySelectorAll('tbody tr').length).toBe(1);
  });

  it('shows the empty state', () => {
    const f = setup(() => of([]));
    expect(f.nativeElement.textContent).toContain('No products yet');
  });

  it('shows the error state with retry', () => {
    const stock = vi.fn().mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })))
      .mockReturnValue(of([row('A-1', 1)]));
    const f = setup(stock);
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Cannot reach the server');
    (el.querySelector('[role=alert] button') as HTMLButtonElement).click();
    f.detectChanges();
    expect(stock).toHaveBeenCalledTimes(2);
    expect(el.querySelector('[data-testid="available-A-1"]')).not.toBeNull();
  });
});
