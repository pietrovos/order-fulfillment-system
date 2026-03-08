import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, input, numberAttribute, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Observable } from 'rxjs';
import { Movement, Order } from '../../core/api/models';
import { OrdersApi } from '../../core/api/orders.api';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/http/api-error';
import { LoadStateComponent } from '../../shared/load-state.component';
import { Notify } from '../../shared/notify.service';
import { StatusChipComponent, statusLabel } from '../../shared/status-chip.component';
import { CancelOrderDialog } from './cancel-order.dialog';

@Component({
  selector: 'app-order-detail',
  imports: [
    RouterLink, CurrencyPipe, DatePipe, MatCardModule, MatButtonModule, MatIconModule, MatTableModule, MatTooltipModule,
    LoadStateComponent, StatusChipComponent,
  ],
  templateUrl: './order-detail.component.html',
  styleUrl: './order-detail.component.scss',
})
export class OrderDetailComponent implements OnInit {
  private readonly api = inject(OrdersApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly notify = inject(Notify);
  private readonly router = inject(Router);

  readonly id = input(undefined, { transform: numberAttribute });
  /** Set when reached via /orders/by-number/:orderNumber (links from the movement ledger). */
  readonly orderNumber = input<string>();

  protected readonly order = signal<Order | null>(null);
  protected readonly movements = signal<Movement[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly lineColumns = ['sku', 'productName', 'quantity', 'unitPrice', 'lineTotal'];
  protected readonly statusLabel = statusLabel;

  private readonly isSales = computed(() => this.auth.hasAnyRole(['SALES', 'SUPERVISOR']));
  private readonly isSupervisor = computed(() => this.auth.hasAnyRole(['SUPERVISOR']));

  protected readonly canEdit = computed(() => this.isSales() && this.order()?.status === 'DRAFT');
  protected readonly canCancel = computed(() => {
    const o = this.order();
    if (!o || !o.nextStatuses.includes('CANCELLED')) return false;
    return o.status === 'PICKING' ? this.isSupervisor() : this.isSales();
  });
  protected readonly canRetry = computed(() => this.isSupervisor() && this.order()?.status === 'STOCK_EXCEPTION');

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    const id = this.id();
    const source = id ? this.api.get(id) : this.api.getByNumber(this.orderNumber() ?? '');
    source.subscribe({
      next: (o) => {
        if (!id) {
          void this.router.navigate(['/orders', o.id], { replaceUrl: true });
        }
        this.order.set(o);
        this.loading.set(false);
        this.api.movements(o.id).subscribe({ next: (m) => this.movements.set(m), error: () => this.movements.set([]) });
      },
      error: (e) => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }

  submit(): void {
    this.act(this.api.submit(this.order()!.id), (o) => `${o.orderNumber}: ${statusLabel(o.status)}`);
  }

  retry(): void {
    this.act(this.api.retryReservation(this.order()!.id), (o) =>
      o.status === 'RESERVED' ? `${o.orderNumber}: stock reserved` : `${o.orderNumber}: still short. ${o.exceptionReason}`,
    );
  }

  cancel(): void {
    const o = this.order()!;
    this.dialog
      .open(CancelOrderDialog, { data: o, width: '440px' })
      .afterClosed()
      .subscribe((reason: string | undefined) => {
        if (reason !== undefined) this.act(this.api.cancel(o.id, reason), (x) => `${x.orderNumber} cancelled`);
      });
  }

  private act(call: Observable<Order>, message: (o: Order) => string): void {
    this.busy.set(true);
    call.subscribe({
      next: (o) => {
        this.order.set(o);
        this.busy.set(false);
        this.notify.ok(message(o));
        this.api.movements(o.id).subscribe((m) => this.movements.set(m));
      },
      error: (e) => {
        this.busy.set(false);
        this.notify.fail(e);
        this.load();
      },
    });
  }
}
