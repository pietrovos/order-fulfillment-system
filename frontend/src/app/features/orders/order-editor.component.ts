import { CurrencyPipe } from '@angular/common';
import { Component, OnInit, computed, inject, input, numberAttribute, signal } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { forkJoin, map, switchMap, of, Observable } from 'rxjs';
import { CatalogApi } from '../../core/api/catalog.api';
import { InventoryApi } from '../../core/api/inventory.api';
import { Order, Product, StockLevel } from '../../core/api/models';
import { OrdersApi, wasReplayed } from '../../core/api/orders.api';
import { ApiError, errorMessage } from '../../core/http/api-error';
import { LoadStateComponent } from '../../shared/load-state.component';
import { Notify } from '../../shared/notify.service';
import { lineForm, orderForm, OrderForm, toCommand } from './order-form';

interface ProductOption extends Product {
  available: number;
}

@Component({
  selector: 'app-order-editor',
  imports: [
    ReactiveFormsModule, RouterLink, CurrencyPipe, MatCardModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatButtonModule, MatIconModule, MatTooltipModule, MatProgressBarModule, LoadStateComponent,
  ],
  templateUrl: './order-editor.component.html',
  styleUrl: './order-editor.component.scss',
})
export class OrderEditorComponent implements OnInit {
  private readonly ordersApi = inject(OrdersApi);
  private readonly catalogApi = inject(CatalogApi);
  private readonly inventoryApi = inject(InventoryApi);
  private readonly router = inject(Router);
  private readonly notify = inject(Notify);

  /** Route param for /orders/:id/edit; absent for /orders/new. */
  readonly id = input(undefined, { transform: numberAttribute });

  /**
   * One key per logical order, generated when the editor opens. A double click, a retried request after a
   * timeout, or a resubmit after a network error all reuse it, so the server creates at most one order.
   */
  protected readonly idempotencyKey = crypto.randomUUID();

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal<'draft' | 'submit' | null>(null);
  protected readonly serverError = signal<string | null>(null);
  protected readonly products = signal<ProductOption[]>([]);
  protected readonly existing = signal<Order | null>(null);
  protected form: OrderForm = orderForm();
  private readonly formTick = signal(0);

  protected readonly productById = computed(() => new Map(this.products().map((p) => [p.id, p])));

  protected readonly total = computed(() => {
    this.formTick();
    const byId = this.productById();
    return this.form.getRawValue().lines.reduce((sum, l) => {
      const p = l.productId != null ? byId.get(l.productId) : undefined;
      return sum + (p ? p.unitPrice * (Number(l.quantity) || 0) : 0);
    }, 0);
  });

  ngOnInit(): void {
    this.load();
  }

  get lines() {
    return this.form.controls.lines;
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    const id = this.id();
    const order$: Observable<Order | null> = id ? this.ordersApi.get(id) : of(null);
    forkJoin({ products: this.catalogApi.list('', true), stock: this.inventoryApi.stock(), order: order$ }).subscribe({
      next: ({ products, stock, order }) => {
        const available = new Map(stock.map((s: StockLevel) => [s.productId, s.available]));
        this.products.set(products.map((p) => ({ ...p, available: available.get(p.id) ?? 0 })));
        if (order) {
          if (order.status !== 'DRAFT') {
            void this.router.navigate(['/orders', order.id]);
            return;
          }
          this.existing.set(order);
          this.form = orderForm(order);
        }
        this.form.valueChanges.subscribe(() => this.formTick.update((n) => n + 1));
        this.formTick.update((n) => n + 1);
        this.loading.set(false);
      },
      error: (e) => {
        this.loadError.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }

  addLine(): void {
    this.lines.push(lineForm());
  }

  removeLine(i: number): void {
    if (this.lines.length > 1) this.lines.removeAt(i);
  }

  lineTotal(i: number): number {
    this.formTick();
    const l = this.lines.at(i).getRawValue();
    const p = l.productId != null ? this.productById().get(l.productId) : undefined;
    return p ? p.unitPrice * (Number(l.quantity) || 0) : 0;
  }

  shortfall(i: number): number {
    this.formTick();
    const l = this.lines.at(i).getRawValue();
    const p = l.productId != null ? this.productById().get(l.productId) : undefined;
    return p ? Math.max(0, (Number(l.quantity) || 0) - p.available) : 0;
  }

  isTaken(productId: number, index: number): boolean {
    this.formTick();
    return this.lines.controls.some((c, i) => i !== index && c.controls.productId.value === productId);
  }

  save(submit: boolean): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.saving()) return;
    this.saving.set(submit ? 'submit' : 'draft');
    this.serverError.set(null);
    const cmd = toCommand(this.form);
    const existing = this.existing();

    const done$ = existing
      ? this.ordersApi.update(existing.id, existing.version, cmd).pipe(
          switchMap((o) => (submit ? this.ordersApi.submit(o.id) : of(o))),
          map((order) => ({ order, replayed: false })),
        )
      : this.ordersApi.create(cmd, submit, this.idempotencyKey).pipe(
          map((res) => ({ order: res.body!, replayed: wasReplayed(res) })),
        );

    done$.subscribe({
      next: ({ order, replayed }) => {
        this.notify.ok(
          replayed ? `${order.orderNumber} was already created; showing it` : `${order.orderNumber} ${this.outcome(order)}`,
        );
        void this.router.navigate(['/orders', order.id]);
      },
      error: (e: HttpErrorResponse) => {
        this.saving.set(null);
        const body = e.error as ApiError | null;
        if (body?.fieldErrors) {
          for (const [field, msg] of Object.entries(body.fieldErrors)) {
            this.form.get(field)?.setErrors({ server: msg });
          }
        }
        this.serverError.set(errorMessage(e));
      },
    });
  }

  private outcome(o: Order): string {
    switch (o.status) {
      case 'RESERVED': return 'submitted and stock reserved';
      case 'STOCK_EXCEPTION': return 'submitted, but there was not enough stock. It is in the exception queue';
      case 'SUBMITTED': return 'submitted; reserving stock';
      default: return 'saved as draft';
    }
  }
}
