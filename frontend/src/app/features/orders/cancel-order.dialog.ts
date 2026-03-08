import { Component, inject } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Order } from '../../core/api/models';

@Component({
  selector: 'app-cancel-order-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>Cancel {{ order.orderNumber }}?</h2>
    <mat-dialog-content>
      @if (order.status === 'RESERVED' || order.status === 'PICKING') {
        <p>The reserved stock goes back to available immediately.</p>
      }
      <mat-form-field appearance="outline" class="full">
        <mat-label>Reason</mat-label>
        <input matInput [formControl]="reason" data-testid="cancel-reason" />
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Keep order</button>
      <button mat-flat-button class="danger" [mat-dialog-close]="reason.value" [disabled]="reason.invalid" data-testid="confirm-cancel">
        Cancel order
      </button>
    </mat-dialog-actions>
  `,
  styles: `.full { width: 100%; } .danger { background: var(--mat-sys-error); color: var(--mat-sys-on-error); }`,
})
export class CancelOrderDialog {
  protected readonly order = inject<Order>(MAT_DIALOG_DATA);
  protected readonly reason = new FormControl('', { nonNullable: true, validators: [Validators.maxLength(500)] });
}
