import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSort, MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { debounceTime } from 'rxjs';
import { InventoryApi } from '../../core/api/inventory.api';
import { StockLevel } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/http/api-error';
import { LoadStateComponent } from '../../shared/load-state.component';
import { Notify } from '../../shared/notify.service';
import { StockChangeData, StockChangeDialog, StockChangeMode } from './stock-change.dialog';

type SortKey = keyof Pick<StockLevel, 'sku' | 'name' | 'onHand' | 'reserved' | 'available' | 'reorderPoint'>;

@Component({
  selector: 'app-inventory',
  imports: [
    RouterLink, MatTableModule, MatSortModule, MatFormFieldModule, MatInputModule, MatIconModule,
    MatButtonModule, MatMenuModule, MatChipsModule, MatTooltipModule, LoadStateComponent,
  ],
  templateUrl: './inventory.component.html',
  styleUrl: './inventory.component.scss',
})
export class InventoryComponent {
  private readonly api = inject(InventoryApi);
  private readonly dialog = inject(MatDialog);
  private readonly notify = inject(Notify);
  protected readonly auth = inject(AuthService);

  protected readonly rows = signal<StockLevel[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly search = signal('');
  protected readonly lowOnly = signal(false);
  protected readonly sort = signal<Sort>({ active: 'sku', direction: 'asc' });
  private readonly debouncedSearch = toSignal(toObservable(this.search).pipe(debounceTime(150)), { initialValue: '' });

  protected readonly columns = ['sku', 'name', 'onHand', 'reserved', 'available', 'reorderPoint', 'actions'];
  protected readonly canReceive = computed(() => this.auth.hasAnyRole(['WAREHOUSE', 'SUPERVISOR']));
  protected readonly isSupervisor = computed(() => this.auth.hasAnyRole(['SUPERVISOR']));

  protected readonly view = computed(() => {
    const q = this.debouncedSearch().trim().toLowerCase();
    const { active, direction } = this.sort();
    const key = active as SortKey;
    const dir = direction === 'desc' ? -1 : 1;
    return this.rows()
      .filter((r) => !this.lowOnly() || r.lowStock)
      .filter((r) => !q || r.sku.toLowerCase().includes(q) || r.name.toLowerCase().includes(q))
      .sort((a, b) => (direction ? (a[key] < b[key] ? -dir : a[key] > b[key] ? dir : 0) : 0));
  });

  protected readonly totals = computed(() => {
    const rows = this.rows();
    return {
      skus: rows.length,
      onHand: rows.reduce((s, r) => s + r.onHand, 0),
      reserved: rows.reduce((s, r) => s + r.reserved, 0),
      low: rows.filter((r) => r.lowStock).length,
    };
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.stock().subscribe({
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

  open(mode: StockChangeMode, stock: StockLevel): void {
    this.dialog
      .open<StockChangeDialog, StockChangeData, StockLevel>(StockChangeDialog, { data: { mode, stock }, width: '440px' })
      .afterClosed()
      .subscribe((updated) => {
        if (!updated) return;
        this.rows.update((rows) => rows.map((r) => (r.productId === updated.productId ? updated : r)));
        this.notify.ok(`${updated.sku}: on hand ${updated.onHand}, available ${updated.available}`);
      });
  }
}
