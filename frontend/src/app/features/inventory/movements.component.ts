import { DatePipe } from '@angular/common';
import { Component, effect, inject, input, numberAttribute, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { InventoryApi } from '../../core/api/inventory.api';
import { Movement } from '../../core/api/models';
import { errorMessage } from '../../core/http/api-error';
import { LoadStateComponent } from '../../shared/load-state.component';

@Component({
  selector: 'app-movements',
  imports: [
    DatePipe, RouterLink, MatTableModule, MatPaginatorModule, MatSelectModule, MatFormFieldModule, MatInputModule,
    MatIconModule, MatButtonModule, LoadStateComponent,
  ],
  template: `
    <div class="header">
      <h1>Movement ledger</h1>
      <a mat-stroked-button routerLink="/inventory"><mat-icon>arrow_back</mat-icon> Inventory</a>
    </div>
    <p class="intro">Every stock change, append-only. Rows are never edited or deleted.</p>

    <div class="filters">
      @if (productId()) {
        <span class="filter-chip">Product {{ sku() ?? productId() }}
          <a routerLink="/inventory/movements" aria-label="Clear product filter"><mat-icon>close</mat-icon></a>
        </span>
      }
      <mat-form-field appearance="outline" subscriptSizing="dynamic">
        <mat-label>Type</mat-label>
        <mat-select [value]="type()" (valueChange)="type.set($event); reload(0)">
          <mat-option [value]="null">All</mat-option>
          @for (t of types; track t) { <mat-option [value]="t">{{ t }}</mat-option> }
        </mat-select>
      </mat-form-field>
      <mat-form-field appearance="outline" subscriptSizing="dynamic">
        <mat-label>Reference (order no.)</mat-label>
        <input matInput [value]="reference()" (change)="reference.set($any($event.target).value); reload(0)" />
      </mat-form-field>
    </div>

    <app-load-state [loading]="loading()" [error]="error()" [empty]="rows().length === 0"
                    emptyText="No movements match" emptyIcon="history" (retry)="reload(page())">
      <div class="table-wrap">
        <table mat-table [dataSource]="rows()">
          <ng-container matColumnDef="createdAt">
            <th mat-header-cell *matHeaderCellDef>When</th>
            <td mat-cell *matCellDef="let m">{{ m.createdAt | date: 'MMM d, HH:mm:ss' }}</td>
          </ng-container>
          <ng-container matColumnDef="sku">
            <th mat-header-cell *matHeaderCellDef>SKU</th>
            <td mat-cell *matCellDef="let m" class="mono">{{ m.sku }}</td>
          </ng-container>
          <ng-container matColumnDef="type">
            <th mat-header-cell *matHeaderCellDef>Type</th>
            <td mat-cell *matCellDef="let m"><span class="type" [attr.data-type]="m.type">{{ m.type }}</span></td>
          </ng-container>
          <ng-container matColumnDef="onHand">
            <th mat-header-cell *matHeaderCellDef class="num">On hand</th>
            <td mat-cell *matCellDef="let m" class="num">{{ delta(m.onHandDelta) }} → {{ m.onHandAfter }}</td>
          </ng-container>
          <ng-container matColumnDef="reserved">
            <th mat-header-cell *matHeaderCellDef class="num">Reserved</th>
            <td mat-cell *matCellDef="let m" class="num">{{ delta(m.reservedDelta) }} → {{ m.reservedAfter }}</td>
          </ng-container>
          <ng-container matColumnDef="reference">
            <th mat-header-cell *matHeaderCellDef>Reference</th>
            <td mat-cell *matCellDef="let m">
              @if (m.referenceType === 'ORDER') { <a [routerLink]="['/orders/by-number', m.referenceId]">{{ m.referenceId }}</a> }
              @else { <span class="muted">manual</span> }
            </td>
          </ng-container>
          <ng-container matColumnDef="reason">
            <th mat-header-cell *matHeaderCellDef>Reason</th>
            <td mat-cell *matCellDef="let m">{{ m.reason }}</td>
          </ng-container>
          <ng-container matColumnDef="actor">
            <th mat-header-cell *matHeaderCellDef>By</th>
            <td mat-cell *matCellDef="let m">{{ m.actor }}</td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="columns"></tr>
          <tr mat-row *matRowDef="let row; columns: columns"></tr>
        </table>
      </div>
    </app-load-state>
    <mat-paginator [length]="total()" [pageIndex]="page()" [pageSize]="size()" [pageSizeOptions]="[25, 50, 100]"
                   (page)="onPage($event)" />
  `,
  styles: `
    .header { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
    .header h1 { margin: 0; }
    .intro { color: var(--mat-sys-on-surface-variant); }
    .filters { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; margin-bottom: 12px; }
    .filter-chip { display: inline-flex; align-items: center; gap: 4px; padding: 4px 4px 4px 12px; border-radius: 999px;
                   background: var(--mat-sys-secondary-container); }
    .filter-chip a { display: inline-flex; color: inherit; }
    .table-wrap { overflow-x: auto; border-radius: 12px; border: 1px solid var(--mat-sys-outline-variant); }
    table { width: 100%; min-width: 860px; }
    .num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
    .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 13px; }
    .muted { color: var(--mat-sys-on-surface-variant); }
    .type { font: var(--mat-sys-label-small); padding: 2px 8px; border-radius: 6px; background: var(--mat-sys-surface-container-high); }
    .type[data-type='RECEIPT'] { background: #d7f5dd; color: #0f5223; }
    .type[data-type='SHIP'] { background: #dbe7ff; color: #1d3f86; }
    .type[data-type='RESERVE'] { background: #fff1c9; color: #6b4e00; }
    .type[data-type='ADJUSTMENT'] { background: var(--mat-sys-error-container); color: var(--mat-sys-on-error-container); }
  `,
})
export class MovementsComponent {
  private readonly api = inject(InventoryApi);

  /** Bound from the query string (?productId=&sku=). */
  readonly productId = input(undefined, { transform: numberAttribute });
  readonly sku = input<string | undefined>();

  protected readonly types = ['RECEIPT', 'ADJUSTMENT', 'RESERVE', 'RELEASE', 'SHIP'];
  protected readonly columns = ['createdAt', 'sku', 'type', 'onHand', 'reserved', 'reference', 'reason', 'actor'];
  protected readonly rows = signal<Movement[]>([]);
  protected readonly total = signal(0);
  protected readonly page = signal(0);
  protected readonly size = signal(50);
  protected readonly type = signal<string | null>(null);
  protected readonly reference = signal('');
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.productId();
      untracked(() => this.reload(0));
    });
  }

  protected delta(n: number): string {
    return n > 0 ? `+${n}` : n === 0 ? '±0' : `${n}`;
  }

  onPage(e: PageEvent): void {
    this.size.set(e.pageSize);
    this.reload(e.pageIndex);
  }

  reload(page: number): void {
    this.page.set(page);
    this.loading.set(true);
    this.error.set(null);
    const productId = this.productId();
    this.api
      .movements({
        productId: Number.isNaN(productId) ? null : productId,
        reference: this.reference(),
        type: this.type(),
        page,
        size: this.size(),
      })
      .subscribe({
        next: (p) => {
          this.rows.set(p.content);
          this.total.set(p.totalElements);
          this.loading.set(false);
        },
        error: (e) => {
          this.error.set(errorMessage(e));
          this.loading.set(false);
        },
      });
  }
}
