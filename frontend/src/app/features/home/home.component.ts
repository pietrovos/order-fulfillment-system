import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { catchError, forkJoin, of } from 'rxjs';
import { FulfillmentApi } from '../../core/api/fulfillment.api';
import { InventoryApi } from '../../core/api/inventory.api';
import { ActivityEntry, OrderStatus, Shipment, StockLevel } from '../../core/api/models';
import { OrdersApi } from '../../core/api/orders.api';
import { AuthService } from '../../core/auth/auth.service';
import { errorMessage } from '../../core/http/api-error';
import { LoadStateComponent } from '../../shared/load-state.component';
import { StatusChipComponent, statusLabel } from '../../shared/status-chip.component';

const PIPELINE: OrderStatus[] = ['SUBMITTED', 'RESERVED', 'PICKING', 'PACKED', 'SHIPPED'];

@Component({
  selector: 'app-home',
  imports: [DatePipe, RouterLink, MatCardModule, MatButtonModule, MatIconModule, LoadStateComponent, StatusChipComponent],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss',
})
export class HomeComponent {
  private readonly orders = inject(OrdersApi);
  private readonly inventory = inject(InventoryApi);
  private readonly fulfillment = inject(FulfillmentApi);
  protected readonly auth = inject(AuthService);

  protected readonly pipeline = PIPELINE;
  protected readonly statusLabel = statusLabel;
  protected readonly counts = signal<Partial<Record<OrderStatus, number>>>({});
  protected readonly stock = signal<StockLevel[]>([]);
  protected readonly shipments = signal<Shipment[]>([]);
  protected readonly activity = signal<ActivityEntry[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly isSales = computed(() => this.auth.hasAnyRole(['SALES', 'SUPERVISOR']));
  protected readonly isWarehouse = computed(() => this.auth.hasAnyRole(['WAREHOUSE', 'SUPERVISOR']));
  protected readonly isSupervisor = computed(() => this.auth.hasAnyRole(['SUPERVISOR']));
  protected readonly lowStock = computed(() => this.stock().filter((s) => s.lowStock).sort((a, b) => a.available - b.available).slice(0, 6));
  protected readonly unitsAvailable = computed(() => this.stock().reduce((n, s) => n + s.available, 0));
  protected readonly unitsReserved = computed(() => this.stock().reduce((n, s) => n + s.reserved, 0));
  protected readonly pendingShipments = computed(() => this.shipments().filter((s) => s.status === 'PENDING').length);
  protected readonly failedShipments = computed(() => this.shipments().filter((s) => s.status === 'FAILED').length);
  protected readonly maxPipeline = computed(() => Math.max(1, ...PIPELINE.map((s) => this.counts()[s] ?? 0)));

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    forkJoin({
      counts: this.orders.counts(),
      stock: this.inventory.stock(),
      shipments: this.fulfillment.shipments().pipe(catchError(() => of([] as Shipment[]))),
      activity: this.orders.activity(0, 8).pipe(catchError(() => of({ content: [] as ActivityEntry[] }))),
    }).subscribe({
      next: (r) => {
        this.counts.set(r.counts);
        this.stock.set(r.stock);
        this.shipments.set(r.shipments);
        this.activity.set(r.activity.content);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }

  count(s: OrderStatus): number {
    return this.counts()[s] ?? 0;
  }
}
