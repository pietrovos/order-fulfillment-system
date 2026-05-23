import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { FulfillmentApi } from '../../core/api/fulfillment.api';
import { StockException } from '../../core/api/models';
import { OrdersApi } from '../../core/api/orders.api';
import { errorMessage } from '../../core/http/api-error';
import { LoadStateComponent } from '../../shared/load-state.component';
import { Notify } from '../../shared/notify.service';

@Component({
  selector: 'app-exceptions',
  imports: [DatePipe, RouterLink, MatCardModule, MatButtonModule, MatIconModule, LoadStateComponent],
  template: `
    <div class="header">
      <h1>Stock exceptions</h1>
      <button mat-stroked-button (click)="load()"><mat-icon>refresh</mat-icon> Refresh</button>
    </div>
    <p class="intro">Orders that could not be fully reserved. Receive stock, then retry, or cancel the order.</p>
    <app-load-state [loading]="loading()" [error]="error()" [empty]="rows().length === 0"
                    emptyText="No stock exceptions. Every submitted order is covered." emptyIcon="task_alt" (retry)="load()">
      <div class="list">
        @for (x of rows(); track x.orderId) {
          <mat-card appearance="outlined" [class.ready]="x.fulfillableNow" [attr.data-testid]="'exception-' + x.orderNumber">
            <mat-card-header>
              <mat-card-title><a [routerLink]="['/orders', x.orderId]">{{ x.orderNumber }}</a> · {{ x.customerName }}</mat-card-title>
              <mat-card-subtitle>Since {{ x.since | date: 'MMM d, HH:mm' }}</mat-card-subtitle>
            </mat-card-header>
            <mat-card-content>
              <p class="reason">{{ x.reason }}</p>
              <table>
                <thead><tr><th>SKU</th><th>Product</th><th class="num">Ordered</th><th class="num">Available</th><th class="num">Short</th></tr></thead>
                <tbody>
                  @for (l of x.lines; track l.sku) {
                    <tr [class.short]="l.shortBy > 0">
                      <td class="mono">{{ l.sku }}</td><td>{{ l.productName }}</td>
                      <td class="num">{{ l.ordered }}</td><td class="num">{{ l.available }}</td>
                      <td class="num">{{ l.shortBy || '—' }}</td>
                    </tr>
                  }
                </tbody>
              </table>
              @if (x.fulfillableNow) { <p class="ok"><mat-icon inline>check_circle</mat-icon> Enough stock now. Retry to reserve.</p> }
            </mat-card-content>
            <mat-card-actions align="end">
              <a mat-button routerLink="/inventory">Receive stock</a>
              <button mat-stroked-button class="danger" (click)="cancel(x)" [disabled]="busy() === x.orderId">Cancel order</button>
              <button mat-flat-button (click)="retry(x)" [disabled]="busy() === x.orderId" [attr.data-testid]="'retry-' + x.orderNumber">
                <mat-icon>refresh</mat-icon> Retry reservation
              </button>
            </mat-card-actions>
          </mat-card>
        }
      </div>
    </app-load-state>
  `,
  styles: `
    .header { display: flex; justify-content: space-between; align-items: center; }
    .header h1 { margin: 0; }
    .intro { color: var(--mat-sys-on-surface-variant); }
    .list { display: flex; flex-direction: column; gap: 12px; }
    mat-card { border-left: 4px solid var(--mat-sys-error); }
    mat-card.ready { border-left-color: #1b873f; }
    mat-card-title a { color: inherit; }
    .reason { font-weight: 500; }
    table { width: 100%; border-collapse: collapse; }
    th, td { padding: 6px 8px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; }
    th { font: var(--mat-sys-label-medium); color: var(--mat-sys-on-surface-variant); }
    .num { text-align: right; font-variant-numeric: tabular-nums; }
    .short td { color: var(--mat-sys-error); }
    .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 13px; }
    .ok { color: #1b873f; display: flex; gap: 6px; align-items: center; }
    .danger { color: var(--mat-sys-error); }
  `,
})
export class ExceptionsComponent {
  private readonly api = inject(FulfillmentApi);
  private readonly orders = inject(OrdersApi);
  private readonly notify = inject(Notify);
  protected readonly rows = signal<StockException[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal<number | null>(null);

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.exceptions().subscribe({
      next: (r) => {
        this.rows.set(r);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }

  retry(x: StockException): void {
    this.busy.set(x.orderId);
    this.orders.retryReservation(x.orderId).subscribe({
      next: (o) => {
        this.busy.set(null);
        if (o.status === 'RESERVED') this.notify.ok(`${o.orderNumber} reserved and sent to picking`);
        else this.notify.ok(`${o.orderNumber} is still short: ${o.exceptionReason}`);
        this.load();
      },
      error: (e) => {
        this.busy.set(null);
        this.notify.fail(e);
        this.load();
      },
    });
  }

  cancel(x: StockException): void {
    this.busy.set(x.orderId);
    this.orders.cancel(x.orderId, 'Cancelled from stock-exception queue').subscribe({
      next: (o) => {
        this.busy.set(null);
        this.notify.ok(`${o.orderNumber} cancelled`);
        this.load();
      },
      error: (e) => {
        this.busy.set(null);
        this.notify.fail(e);
      },
    });
  }
}
