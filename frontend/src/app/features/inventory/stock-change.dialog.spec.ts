import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { InventoryApi } from '../../core/api/inventory.api';
import { StockLevel } from '../../core/api/models';
import { StockChangeDialog, StockChangeMode } from './stock-change.dialog';

const stock: StockLevel = {
  productId: 9, sku: 'X-1', name: 'X', onHand: 10, reserved: 4, available: 6, reorderPoint: 2,
  lowStock: false, updatedAt: '',
};

function setup(mode: StockChangeMode) {
  const api = { receive: vi.fn(() => of(stock)), adjust: vi.fn(() => of(stock)), setReorderPoint: vi.fn(() => of(stock)) };
  const close = vi.fn();
  TestBed.configureTestingModule({
    imports: [StockChangeDialog],
    providers: [
      { provide: MAT_DIALOG_DATA, useValue: { mode, stock } },
      { provide: MatDialogRef, useValue: { close } },
      { provide: InventoryApi, useValue: api },
    ],
  });
  const f = TestBed.createComponent(StockChangeDialog);
  f.detectChanges();
  return { f, api, close, form: (f.componentInstance as unknown as { form: StockChangeDialog['form'] }).form };
}

describe('StockChangeDialog', () => {
  it('requires a positive receipt quantity', () => {
    const { form, f, api, close } = setup('receive');
    form.setValue({ amount: 0, reason: '' });
    expect(form.invalid).toBe(true);
    form.setValue({ amount: 12, reason: 'PO-7' });
    f.componentInstance.save();
    expect(api.receive).toHaveBeenCalledWith(9, 12, 'PO-7');
    expect(close).toHaveBeenCalledWith(stock);
  });

  it('requires a non-zero delta and a reason for adjustments', () => {
    const { form, f, api } = setup('adjust');
    form.setValue({ amount: 0, reason: 'x' });
    expect(form.invalid).toBe(true);
    form.setValue({ amount: -2, reason: '' });
    expect(form.invalid).toBe(true);
    form.setValue({ amount: -2, reason: 'Damaged' });
    f.componentInstance.save();
    expect(api.adjust).toHaveBeenCalledWith(9, -2, 'Damaged');
  });
});
