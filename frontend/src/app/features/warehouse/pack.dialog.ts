import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { FulfillmentApi } from '../../core/api/fulfillment.api';
import { PickList, Shipment } from '../../core/api/models';
import { errorMessage } from '../../core/http/api-error';

@Component({
  selector: 'app-pack-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>Pack {{ pick.orderNumber }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()">
      <mat-dialog-content>
        <p class="muted">Packing books the shipment with the carrier. The booking is retried automatically if the carrier is down.</p>
        <div class="row">
          <mat-form-field appearance="outline">
            <mat-label>Parcels</mat-label>
            <input matInput type="number" min="1" formControlName="parcels" data-testid="parcels" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Total weight</mat-label>
            <input matInput type="number" step="0.1" min="0.01" formControlName="weightKg" data-testid="weight" />
            <span matTextSuffix>kg</span>
          </mat-form-field>
        </div>
        @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancel</button>
        <button mat-flat-button type="submit" [disabled]="form.invalid || saving()" data-testid="confirm-pack">Pack &amp; book</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `.row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; } .muted { color: var(--mat-sys-on-surface-variant); }
           .error { color: var(--mat-sys-error); }`,
})
export class PackDialog {
  protected readonly pick = inject<PickList>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<PackDialog, Shipment>);
  private readonly api = inject(FulfillmentApi);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(NonNullableFormBuilder).group({
    parcels: [1, [Validators.required, Validators.min(1), Validators.max(99)]],
    weightKg: [1, [Validators.required, Validators.min(0.01), Validators.max(5000)]],
  });

  save(): void {
    if (this.form.invalid) return;
    this.saving.set(true);
    const { parcels, weightKg } = this.form.getRawValue();
    this.api.pack(this.pick.id, parcels, weightKg).subscribe({
      next: (s) => this.ref.close(s),
      error: (e) => {
        this.error.set(errorMessage(e));
        this.saving.set(false);
      },
    });
  }
}
