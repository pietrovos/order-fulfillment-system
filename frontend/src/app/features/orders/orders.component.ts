import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSortModule, Sort } from '@angular/material/sort';
import { MatTableModule } from '@angular/material/table';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { ORDER_STATUSES, OrderStatus, OrderSummary } from '../../core/api/models';
import { OrdersApi } from '../../core/api/orders.api';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/http/api-error';
import { LoadStateComponent } from '../../shared/load-state.component';
import { StatusChipComponent, statusLabel } from '../../shared/status-chip.component';

@Component({
  selector: 'app-orders',
  imports: [
    RouterLink, CurrencyPipe, DatePipe, MatTableModule, MatSortModule, MatPaginatorModule, MatFormFieldModule,
    MatInputModule, MatIconModule, MatButtonModule, MatChipsModule, LoadStateComponent, StatusChipComponent,
  ],
  templateUrl: './orders.component.html',
  styleUrl: './orders.component.scss',
})
export class OrdersComponent {
  private readonly api = inject(OrdersApi);
  private readonly auth = inject(AuthService);
  protected readonly router = inject(Router);

  protected readonly statuses = ORDER_STATUSES;
  protected readonly statusLabel = statusLabel;
  protected readonly columns = ['orderNumber', 'customerName', 'status', 'totalUnits', 'totalAmount', 'createdAt'];
  protected readonly canCreate = computed(() => this.auth.hasAnyRole(['SALES', 'SUPERVISOR']));

  protected readonly search = signal('');
  protected readonly selected = signal<OrderStatus[]>([]);
  protected readonly sort = signal<Sort>({ active: 'createdAt', direction: 'desc' });
  protected readonly page = signal(0);
  protected readonly size = signal(25);

  protected readonly rows = signal<OrderSummary[]>([]);
  protected readonly total = signal(0);
  protected readonly counts = signal<Partial<Record<OrderStatus, number>>>({});
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  private readonly debouncedSearch = toSignal(
    toObservable(this.search).pipe(debounceTime(250), distinctUntilChanged()),
    { initialValue: '' },
  );

  constructor() {
    effect(() => {
      const query = {
        q: this.debouncedSearch(),
        statuses: this.selected(),
        page: this.page(),
        size: this.size(),
        sort: this.sort().active,
        direction: (this.sort().direction || 'desc') as 'asc' | 'desc',
      };
      untracked(() => this.fetch(query));
    });
    this.api.counts().subscribe({ next: (c) => this.counts.set(c), error: () => undefined });
  }

  reload(): void {
    this.page.set(this.page());
    this.fetch({
      q: this.debouncedSearch(), statuses: this.selected(), page: this.page(), size: this.size(),
      sort: this.sort().active, direction: (this.sort().direction || 'desc') as 'asc' | 'desc',
    });
  }

  toggleStatus(s: OrderStatus): void {
    this.selected.update((cur) => (cur.includes(s) ? cur.filter((x) => x !== s) : [...cur, s]));
    this.page.set(0);
  }

  onSearch(value: string): void {
    this.search.set(value);
    this.page.set(0);
  }

  onSort(s: Sort): void {
    this.sort.set(s);
    this.page.set(0);
  }

  onPage(e: PageEvent): void {
    this.size.set(e.pageSize);
    this.page.set(e.pageIndex);
  }

  private fetch(query: Parameters<OrdersApi['list']>[0]): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.list(query).subscribe({
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
