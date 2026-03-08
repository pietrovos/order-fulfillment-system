import { AbstractControl, FormArray, FormControl, FormGroup, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
import { Order, OrderCommand } from '../../core/api/models';

export type LineForm = FormGroup<{
  productId: FormControl<number | null>;
  quantity: FormControl<number>;
}>;

export type OrderForm = FormGroup<{
  customerName: FormControl<string>;
  customerEmail: FormControl<string>;
  shippingAddress: FormControl<string>;
  notes: FormControl<string>;
  lines: FormArray<LineForm>;
}>;

/** Each product may appear once; the API rejects duplicates, so catch them while typing. */
export const uniqueProducts: ValidatorFn = (control: AbstractControl): ValidationErrors | null => {
  const ids = (control as FormArray<LineForm>).controls.map((c) => c.controls.productId.value).filter((v) => v != null);
  const dupes = ids.filter((id, i) => ids.indexOf(id) !== i);
  return dupes.length ? { duplicateProduct: dupes } : null;
};

export function lineForm(productId: number | null = null, quantity = 1): LineForm {
  return new FormGroup({
    productId: new FormControl<number | null>(productId, Validators.required),
    quantity: new FormControl(quantity, {
      nonNullable: true,
      validators: [Validators.required, Validators.min(1), Validators.max(100000), Validators.pattern(/^\d+$/)],
    }),
  });
}

export function orderForm(order?: Order): OrderForm {
  return new FormGroup({
    customerName: new FormControl(order?.customerName ?? '', { nonNullable: true, validators: [Validators.required, Validators.maxLength(200)] }),
    customerEmail: new FormControl(order?.customerEmail ?? '', { nonNullable: true, validators: [Validators.email, Validators.maxLength(200)] }),
    shippingAddress: new FormControl(order?.shippingAddress ?? '', { nonNullable: true, validators: [Validators.required, Validators.maxLength(1000)] }),
    notes: new FormControl(order?.notes ?? '', { nonNullable: true, validators: [Validators.maxLength(2000)] }),
    lines: new FormArray<LineForm>(
      order?.lines.length ? order.lines.map((l) => lineForm(l.productId, l.quantity)) : [lineForm()],
      { validators: [Validators.required, uniqueProducts] },
    ),
  });
}

export function toCommand(form: OrderForm): OrderCommand {
  const v = form.getRawValue();
  return {
    customerName: v.customerName.trim(),
    customerEmail: v.customerEmail.trim() || null,
    shippingAddress: v.shippingAddress.trim(),
    notes: v.notes.trim() || null,
    lines: v.lines.map((l) => ({ productId: l.productId!, quantity: Number(l.quantity) })),
  };
}
