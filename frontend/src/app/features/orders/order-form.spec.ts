import { lineForm, orderForm, toCommand } from './order-form';

describe('order form model', () => {
  it('starts with one empty, invalid line', () => {
    const f = orderForm();
    expect(f.controls.lines.length).toBe(1);
    expect(f.controls.lines.at(0).valid).toBe(false);
  });

  it('flags the same product on two lines', () => {
    const f = orderForm();
    f.controls.lines.at(0).setValue({ productId: 7, quantity: 1 });
    f.controls.lines.push(lineForm(7, 2));
    expect(f.controls.lines.hasError('duplicateProduct')).toBe(true);
    f.controls.lines.at(1).controls.productId.setValue(8);
    expect(f.controls.lines.hasError('duplicateProduct')).toBe(false);
  });

  it('rejects zero, negative and fractional quantities', () => {
    const l = lineForm(1, 1);
    for (const bad of [0, -3, 1.5]) {
      l.controls.quantity.setValue(bad);
      expect(l.controls.quantity.valid).toBe(false);
    }
    l.controls.quantity.setValue(12);
    expect(l.valid).toBe(true);
  });

  it('builds a trimmed command and nulls empty optionals', () => {
    const f = orderForm();
    f.patchValue({ customerName: '  Acme  ', customerEmail: ' ', shippingAddress: ' 1 Road ', notes: '' });
    f.controls.lines.at(0).setValue({ productId: 3, quantity: 4 });
    expect(toCommand(f)).toEqual({
      customerName: 'Acme', customerEmail: null, shippingAddress: '1 Road', notes: null,
      lines: [{ productId: 3, quantity: 4 }],
    });
  });
});
