import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { FulfillmentApi } from '../../core/api/fulfillment.api';
import { InventoryApi } from '../../core/api/inventory.api';
import { OrdersApi } from '../../core/api/orders.api';
import { AuthService } from '../../core/auth/auth.service';
import { HomeComponent } from './home.component';

function setup(roles: string[]) {
  TestBed.configureTestingModule({
    imports: [HomeComponent],
    providers: [
      provideRouter([]),
      { provide: OrdersApi, useValue: {
        counts: () => of({ RESERVED: 4, SHIPPED: 10, STOCK_EXCEPTION: 2 }),
        activity: () => of({ content: [{ id: 1, orderId: 7, orderNumber: 'SO-001007', from: 'SUBMITTED', to: 'RESERVED', actor: 'sales', note: null, at: '2026-01-01T00:00:00Z' }] }),
      } },
      { provide: InventoryApi, useValue: { stock: () => of([
        { productId: 1, sku: 'A', name: 'Low item', onHand: 3, reserved: 1, available: 2, reorderPoint: 5, lowStock: true, updatedAt: '' },
        { productId: 2, sku: 'B', name: 'Fine item', onHand: 50, reserved: 0, available: 50, reorderPoint: 5, lowStock: false, updatedAt: '' },
      ]) } },
      { provide: FulfillmentApi, useValue: { shipments: () => of([{ status: 'PENDING' }, { status: 'FAILED' }, { status: 'BOOKED' }]) } },
      { provide: AuthService, useValue: { user: () => ({ displayName: 'Sasha L' }), hasAnyRole: (r: string[]) => r.some((x) => roles.includes(x)) } },
    ],
  });
  const f = TestBed.createComponent(HomeComponent);
  f.detectChanges();
  return f.nativeElement as HTMLElement;
}

describe('HomeComponent', () => {
  it('summarises exceptions, stock and shipments', () => {
    const el = setup(['SUPERVISOR']);
    expect(el.querySelector('[data-testid=tile-exceptions]')?.textContent).toContain('2');
    expect(el.textContent).toContain('52 units available');
    expect(el.textContent).toContain('Low item');
    expect(el.textContent).not.toContain('Fine item');
    expect(el.textContent).toContain('SO-001007');
    expect(el.textContent).toContain('failed bookings');
  });

  it('only offers New order to roles that can create orders', () => {
    expect(setup(['WAREHOUSE']).textContent).not.toContain('New order');
  });
});
