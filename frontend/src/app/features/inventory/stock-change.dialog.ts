import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Observable } from 'rxjs';
import { InventoryApi } from '../../core/api/inventory.api';
import { StockLevel } from '../../core/api/models';
import { errorMessage } from '../../core/http/api-error';

export type StockChangeMode = 'receive' | 'adjust' | 'reorder';

export interface StockChangeData {
  mode: StockChangeMode;
  stock: StockLevel;
}

const TITLES: Record<StockChangeMode, string> = {
  receive: 'Receive stock',
  adjust: 'Adjust on-hand',
  reorder: 'Set reorder point',
};

@Component({
  selector: 'app-stock-change-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>{{ title }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()">
      <mat-dialog-content>
        <p class="product"><strong>{{ data.stock.sku }}</strong> {{ data.stock.name }}</p>
        <p class="position">On hand {{ data.stock.onHand }} · Reserved {{ data.stock.reserved }} · Available {{ data.stock.available }}</p>
        <mat-form-field appearance="outline">
          <mat-label>{{ amountLabel }}</mat-label>
          <input matInput type="number" formControlName="amount" data-testid="amount" />
          @if (data.mode === 'adjust') {
            <mat-hint>Negative to remove (damage, shrinkage), positive to add (count correction)</mat-hint>
          }
          @if (form.controls.amount.hasError('min')) {
            <mat-error>Must be at least {{ data.mode === 'receive' ? 1 : 0 }}</mat-error>
          }
        </mat-form-field>
        @if (data.mode !== 'reorder') {
          <mat-form-field appearance="outline">
            <mat-label>{{ data.mode === 'receive' ? 'Reference (PO, ASN…)' : 'Reason' }}</mat-label>
            <input matInput formControlName="reason" data-testid="reason" />
            @if (form.controls.reason.hasError('required')) {
              <mat-error>A reason is required for adjustments</mat-error>
            }
          </mat-form-field>
        }
        @if (error()) {
          <p class="error" role="alert">{{ error() }}</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancel</button>
        <button mat-flat-button type="submit" [disabled]="form.invalid || saving()" data-testid="save">Save</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    mat-form-field { width: 100%; }
    .product { margin: 0; }
    .position { margin: 4px 0 16px; color: var(--mat-sys-on-surface-variant); font: var(--mat-sys-body-small); }
    .error { color: var(--mat-sys-error); }
  `,
})
export class StockChangeDialog {
  protected readonly data = inject<StockChangeData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<StockChangeDialog, StockLevel>);
  private readonly api = inject(InventoryApi);

  protected readonly title = TITLES[this.data.mode];
  protected readonly amountLabel =
    this.data.mode === 'receive' ? 'Quantity received' : this.data.mode === 'adjust' ? 'Change (+/-)' : 'Reorder point';
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    amount: [
      this.data.mode === 'reorder' ? this.data.stock.reorderPoint : 0,
      this.data.mode === 'receive' ? [Validators.required, Validators.min(1)]
        : this.data.mode === 'reorder' ? [Validators.required, Validators.min(0)]
        : [Validators.required, (c: { value: number }) => (c.value === 0 ? { zero: true } : null)],
    ],
    reason: ['', this.data.mode === 'adjust' ? [Validators.required] : []],
  });

  save(): void {
    if (this.form.invalid) return;
    const { amount, reason } = this.form.getRawValue();
    const id = this.data.stock.productId;
    const call: Observable<StockLevel> =
      this.data.mode === 'receive' ? this.api.receive(id, amount, reason)
      : this.data.mode === 'adjust' ? this.api.adjust(id, amount, reason)
      : this.api.setReorderPoint(id, amount);
    this.saving.set(true);
    call.subscribe({
      next: (s) => this.ref.close(s),
      error: (e) => {
        this.error.set(errorMessage(e));
        this.saving.set(false);
      },
    });
  }
}
