import { CurrencyPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTableModule } from '@angular/material/table';
import { CatalogApi } from '../../core/api/catalog.api';
import { Product } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/http/api-error';
import { LoadStateComponent } from '../../shared/load-state.component';
import { Notify } from '../../shared/notify.service';
import { ProductDialog } from './product.dialog';

@Component({
  selector: 'app-products',
  imports: [CurrencyPipe, MatTableModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, LoadStateComponent],
  template: `
    <div class="header">
      <h1>Products</h1>
      @if (isSupervisor()) {
        <button mat-flat-button (click)="edit(null)" data-testid="new-product"><mat-icon>add</mat-icon> New product</button>
      }
    </div>
    <mat-form-field appearance="outline" class="search" subscriptSizing="dynamic">
      <mat-icon matPrefix>search</mat-icon>
      <mat-label>Search SKU or name</mat-label>
      <input matInput [value]="search()" (input)="search.set($any($event.target).value)" />
    </mat-form-field>
    <app-load-state [loading]="loading()" [error]="error()" [empty]="view().length === 0"
                    emptyText="No products found" emptyIcon="category" (retry)="load()">
      <div class="table-wrap">
        <table mat-table [dataSource]="view()">
          <ng-container matColumnDef="sku">
            <th mat-header-cell *matHeaderCellDef>SKU</th>
            <td mat-cell *matCellDef="let p" class="mono">{{ p.sku }}</td>
          </ng-container>
          <ng-container matColumnDef="name">
            <th mat-header-cell *matHeaderCellDef>Name</th>
            <td mat-cell *matCellDef="let p">
              {{ p.name }}
              @if (!p.active) { <span class="inactive">inactive</span> }
              @if (p.description) { <div class="desc">{{ p.description }}</div> }
            </td>
          </ng-container>
          <ng-container matColumnDef="unitPrice">
            <th mat-header-cell *matHeaderCellDef class="num">Unit price</th>
            <td mat-cell *matCellDef="let p" class="num">{{ p.unitPrice | currency }}</td>
          </ng-container>
          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef></th>
            <td mat-cell *matCellDef="let p" class="actions">
              @if (isSupervisor()) {
                <button mat-icon-button (click)="edit(p)" [attr.aria-label]="'Edit ' + p.sku"><mat-icon>edit</mat-icon></button>
              }
            </td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="columns"></tr>
          <tr mat-row *matRowDef="let row; columns: columns" [class.dim]="!row.active"></tr>
        </table>
      </div>
    </app-load-state>
  `,
  styles: `
    .header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
    .header h1 { margin: 0; }
    .search { width: 100%; max-width: 420px; margin-bottom: 12px; }
    .table-wrap { overflow-x: auto; border-radius: 12px; border: 1px solid var(--mat-sys-outline-variant); }
    table { width: 100%; min-width: 520px; }
    .num { text-align: right; font-variant-numeric: tabular-nums; }
    .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 13px; }
    .desc { font: var(--mat-sys-body-small); color: var(--mat-sys-on-surface-variant); }
    .inactive { margin-left: 8px; font: var(--mat-sys-label-small); color: var(--mat-sys-on-surface-variant); }
    .dim { opacity: 0.6; }
    .actions { width: 56px; text-align: right; }
  `,
})
export class ProductsComponent {
  private readonly api = inject(CatalogApi);
  private readonly dialog = inject(MatDialog);
  private readonly notify = inject(Notify);
  private readonly auth = inject(AuthService);

  protected readonly isSupervisor = computed(() => this.auth.hasAnyRole(['SUPERVISOR']));
  protected readonly columns = ['sku', 'name', 'unitPrice', 'actions'];
  protected readonly rows = signal<Product[]>([]);
  protected readonly search = signal('');
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly view = computed(() => {
    const q = this.search().trim().toLowerCase();
    return this.rows().filter((p) => !q || p.sku.toLowerCase().includes(q) || p.name.toLowerCase().includes(q));
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.list().subscribe({
      next: (rows) => {
        this.rows.set(rows);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }

  edit(product: Product | null): void {
    this.dialog
      .open<ProductDialog, Product | null, Product>(ProductDialog, { data: product, width: '480px' })
      .afterClosed()
      .subscribe((saved) => {
        if (!saved) return;
        this.notify.ok(`Saved ${saved.sku}`);
        this.load();
      });
  }
}
