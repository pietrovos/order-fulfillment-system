import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { provideRouter, Router } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { CatalogApi } from '../../core/api/catalog.api';
import { InventoryApi } from '../../core/api/inventory.api';
import { Order } from '../../core/api/models';
import { OrdersApi } from '../../core/api/orders.api';
import { OrderEditorComponent } from './order-editor.component';

const product = { id: 1, sku: 'BOX-1', name: 'Box', description: null, unitPrice: 2.5, active: true };
const created = { id: 42, orderNumber: 'SO-001042', status: 'RESERVED' } as Order;

function setup(create: OrdersApi['create']) {
  TestBed.configureTestingModule({
    imports: [OrderEditorComponent],
    providers: [
      provideRouter([]),
      { provide: CatalogApi, useValue: { list: () => of([product]) } },
      { provide: InventoryApi, useValue: { stock: () => of([{ productId: 1, available: 3 }]) } },
      { provide: OrdersApi, useValue: { create } },
    ],
  });
  vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  const f = TestBed.createComponent(OrderEditorComponent);
  f.detectChanges();
  const c = f.componentInstance as unknown as {
    form: OrderEditorComponent['lines']['parent'] & { patchValue: (v: object) => void };
    lines: OrderEditorComponent['lines'];
    idempotencyKey: string;
    total: () => number;
    shortfall: (i: number) => number;
    save: (submit: boolean) => void;
    addLine: () => void;
    removeLine: (i: number) => void;
  };
  c.form.patchValue({ customerName: 'Acme', shippingAddress: '1 Road' });
  c.lines.at(0).setValue({ productId: 1, quantity: 4 });
  return { f, c };
}

const ok = () => of(new HttpResponse({ body: created, status: 201 }));

describe('OrderEditorComponent', () => {
  it('computes totals and warns about shortfalls', () => {
    const { c } = setup(vi.fn(ok));
    expect(c.total()).toBe(10);
    expect(c.shortfall(0)).toBe(1);
  });

  it('adds and removes lines but keeps at least one', () => {
    const { c } = setup(vi.fn(ok));
    c.addLine();
    expect(c.lines.length).toBe(2);
    c.removeLine(1);
    c.removeLine(0);
    expect(c.lines.length).toBe(1);
  });

  it('sends one request for a double click', () => {
    const pending = new Subject<HttpResponse<Order>>();
    const create = vi.fn(() => pending.asObservable());
    const { c } = setup(create);
    c.save(true);
    c.save(true);
    expect(create).toHaveBeenCalledTimes(1);
    pending.next(new HttpResponse({ body: created }));
  });

  it('reuses the same idempotency key when retrying after a failure', () => {
    const create = vi.fn()
      .mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 504 })))
      .mockReturnValueOnce(of(new HttpResponse({ body: created, headers: new HttpHeaders({ 'Idempotent-Replayed': 'true' }) })));
    const { c } = setup(create);
    c.save(true);
    c.save(true);
    expect(create).toHaveBeenCalledTimes(2);
    const [, , key1] = create.mock.calls[0];
    const [, , key2] = create.mock.calls[1];
    expect(key1).toBe(c.idempotencyKey);
    expect(key2).toBe(key1);
    expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith(['/orders', 42]);
  });

  it('does not submit an invalid form', () => {
    const create = vi.fn(ok);
    const { c } = setup(create);
    c.lines.at(0).controls.quantity.setValue(0);
    c.save(true);
    expect(create).not.toHaveBeenCalled();
  });
});
