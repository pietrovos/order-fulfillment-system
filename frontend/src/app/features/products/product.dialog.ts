import { Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { CatalogApi } from '../../core/api/catalog.api';
import { Product } from '../../core/api/models';
import { ApiError } from '../../core/http/api-error';
import { HttpErrorResponse } from '@angular/common/http';

@Component({
  selector: 'app-product-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSlideToggleModule],
  template: `
    <h2 mat-dialog-title>{{ product ? 'Edit product' : 'New product' }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()">
      <mat-dialog-content>
        <mat-form-field appearance="outline">
          <mat-label>SKU</mat-label>
          <input matInput formControlName="sku" (input)="upper()" data-testid="sku" />
          <mat-hint>Uppercase letters, digits and dashes</mat-hint>
          @if (form.controls.sku.hasError('pattern')) { <mat-error>Use A–Z, 0–9 and dashes</mat-error> }
          @if (form.controls.sku.hasError('server')) { <mat-error>{{ form.controls.sku.getError('server') }}</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Name</mat-label>
          <input matInput formControlName="name" data-testid="name" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Description</mat-label>
          <textarea matInput formControlName="description" rows="2"></textarea>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Unit price</mat-label>
          <span matTextPrefix>$&nbsp;</span>
          <input matInput type="number" step="0.01" formControlName="unitPrice" data-testid="price" />
          @if (form.controls.unitPrice.hasError('min')) { <mat-error>Cannot be negative</mat-error> }
        </mat-form-field>
        @if (product) {
          <mat-slide-toggle formControlName="active">Active (orderable)</mat-slide-toggle>
        }
        @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancel</button>
        <button mat-flat-button type="submit" [disabled]="form.invalid || saving()" data-testid="save">Save</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `mat-form-field { width: 100%; } .error { color: var(--mat-sys-error); }`,
})
export class ProductDialog {
  protected readonly product = inject<Product | null>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<ProductDialog, Product>);
  private readonly api = inject(CatalogApi);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    sku: [this.product?.sku ?? '', [Validators.required, Validators.maxLength(32), Validators.pattern(/^[A-Z0-9][A-Z0-9-]*$/)]],
    name: [this.product?.name ?? '', [Validators.required, Validators.maxLength(200)]],
    description: [this.product?.description ?? ''],
    unitPrice: [this.product?.unitPrice ?? 0, [Validators.required, Validators.min(0)]],
    active: [this.product?.active ?? true],
  });

  upper(): void {
    const c = this.form.controls.sku;
    if (c.value !== c.value.toUpperCase()) c.setValue(c.value.toUpperCase());
  }

  save(): void {
    if (this.form.invalid) return;
    const v = this.form.getRawValue();
    const cmd = { ...v, description: v.description || null };
    this.saving.set(true);
    this.error.set(null);
    (this.product ? this.api.update(this.product.id, cmd) : this.api.create(cmd)).subscribe({
      next: (p) => this.ref.close(p),
      error: (e: HttpErrorResponse) => {
        const body = e.error as ApiError | null;
        if (body?.code === 'DUPLICATE_SKU') this.form.controls.sku.setErrors({ server: body.message });
        else if (body?.fieldErrors) {
          for (const [field, msg] of Object.entries(body.fieldErrors)) {
            this.form.get(field)?.setErrors({ server: msg });
          }
        }
        this.error.set(body?.message ?? 'Could not save product');
        this.saving.set(false);
      },
    });
  }
}
