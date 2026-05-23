import { DatePipe } from '@angular/common';
import { Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { forkJoin, switchMap } from 'rxjs';
import { FulfillmentApi } from '../../core/api/fulfillment.api';
import { Order, Shipment } from '../../core/api/models';
import { OrdersApi } from '../../core/api/orders.api';
import { errorMessage } from '../../core/http/api-error';
import { LoadStateComponent } from '../../shared/load-state.component';
import { StatusChipComponent } from '../../shared/status-chip.component';
import { buildTimeline } from './timeline';

@Component({
  selector: 'app-shipment-detail',
  imports: [DatePipe, RouterLink, MatCardModule, MatIconModule, MatProgressBarModule, LoadStateComponent, StatusChipComponent],
  template: `
    <app-load-state [loading]="loading()" [error]="error()" (retry)="load()">
      @if (shipment(); as s) {
        <a routerLink="/shipments" class="back"><mat-icon inline>arrow_back</mat-icon> Shipments</a>
        <h1>Shipment for <a [routerLink]="['/orders', s.orderId]">{{ s.orderNumber }}</a>
          @if (order(); as o) { <app-status-chip [status]="o.status" /> }
        </h1>
        @if (s.status === 'PENDING') {
          <div class="banner pending" role="status" data-testid="shipment-pending">
            <mat-icon>schedule</mat-icon>
            <div>Booking with {{ s.carrier }}. Attempts so far: {{ s.bookingAttempts }}.
              @if (s.lastError) { <br /><span class="small">Last error: {{ s.lastError }}</span> }
            </div>
          </div>
          <mat-progress-bar mode="indeterminate" />
        } @else if (s.status === 'BOOKED') {
          <div class="banner ok" data-testid="shipment-booked">
            <mat-icon>local_shipping</mat-icon>
            <div>Tracking <strong class="mono" data-testid="tracking-number">{{ s.trackingNumber }}</strong> · carrier ref {{ s.carrierShipmentId }}</div>
          </div>
        } @else {
          <div class="banner fail" role="alert"><mat-icon>error</mat-icon><div>Booking failed: {{ s.lastError }}</div></div>
        }
        <div class="layout">
          <mat-card appearance="outlined">
            <mat-card-header><mat-card-title>Timeline</mat-card-title></mat-card-header>
            <mat-card-content>
              <ol class="timeline" data-testid="shipment-timeline">
                @for (e of timeline(); track $index) {
                  <li [attr.data-kind]="e.kind">
                    <div class="dot"></div>
                    <div>
                      <div class="title">{{ e.title }}</div>
                      @if (e.detail) { <div class="detail">{{ e.detail }}</div> }
                      <div class="when">{{ e.at | date: 'MMM d, HH:mm:ss' }} · {{ e.actor }}</div>
                    </div>
                  </li>
                }
              </ol>
            </mat-card-content>
          </mat-card>
          <mat-card appearance="outlined">
            <mat-card-header><mat-card-title>Details</mat-card-title></mat-card-header>
            <mat-card-content>
              <dl>
                <dt>Ship to</dt><dd>{{ s.shipToName }}<br /><span class="addr">{{ s.shipToAddress }}</span></dd>
                <dt>Parcels</dt><dd>{{ s.parcels }} · {{ s.weightKg }} kg</dd>
                <dt>Carrier</dt><dd>{{ s.carrier }}</dd>
                <dt>Idempotency key</dt><dd class="mono small">{{ s.carrierIdempotencyKey }}</dd>
                <dt>Packed by</dt><dd>{{ s.packedBy }} · {{ s.createdAt | date: 'MMM d, HH:mm' }}</dd>
              </dl>
            </mat-card-content>
          </mat-card>
        </div>
      }
    </app-load-state>
  `,
  styles: `
    .back { color: var(--mat-sys-primary); text-decoration: none; display: inline-flex; gap: 4px; align-items: center; }
    h1 { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin: 8px 0 16px; }
    h1 a { color: inherit; }
    .banner { display: flex; gap: 12px; align-items: center; padding: 12px 16px; border-radius: 12px; margin-bottom: 8px; }
    .pending { background: #fff1c9; color: #6b4e00; }
    .ok { background: #d7f5dd; color: #0f5223; }
    .fail { background: var(--mat-sys-error-container); color: var(--mat-sys-on-error-container); }
    .layout { display: grid; grid-template-columns: minmax(0, 3fr) minmax(240px, 2fr); gap: 16px; margin-top: 16px; }
    @media (max-width: 899px) { .layout { grid-template-columns: 1fr; } }
    .timeline { list-style: none; margin: 0; padding: 0; }
    .timeline li { display: grid; grid-template-columns: 16px 1fr; gap: 10px; padding-bottom: 16px; position: relative; }
    .timeline li:not(:last-child)::before { content: ''; position: absolute; left: 5px; top: 14px; bottom: 0; width: 2px;
                                            background: var(--mat-sys-outline-variant); }
    .dot { width: 12px; height: 12px; border-radius: 50%; margin-top: 4px; background: var(--mat-sys-primary); }
    li[data-kind='shipment'] .dot { background: #b57a00; }
    li[data-kind='error'] .dot { background: var(--mat-sys-error); }
    li[data-kind='success'] .dot { background: #1b873f; }
    .title { font-weight: 600; }
    .detail { font: var(--mat-sys-body-small); }
    .when, .small { font: var(--mat-sys-body-small); color: var(--mat-sys-on-surface-variant); }
    dl { display: grid; grid-template-columns: auto 1fr; gap: 8px 16px; margin: 0; }
    dt { color: var(--mat-sys-on-surface-variant); }
    dd { margin: 0; overflow-wrap: anywhere; }
    .addr { white-space: pre-line; }
    .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  `,
})
export class ShipmentDetailComponent implements OnInit {
  private readonly api = inject(FulfillmentApi);
  private readonly orders = inject(OrdersApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly id = input.required<string>();
  protected readonly shipment = signal<Shipment | null>(null);
  protected readonly order = signal<Order | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly timeline = computed(() => buildTimeline(this.order(), this.shipment()));
  private timer: ReturnType<typeof setTimeout> | undefined;

  ngOnInit(): void {
    this.destroyRef.onDestroy(() => clearTimeout(this.timer));
    this.load();
  }

  load(): void {
    this.api.shipment(this.id()).pipe(
      switchMap((s) => forkJoin({ s: [s], o: this.orders.get(s.orderId) })),
    ).subscribe({
      next: ({ s, o }) => {
        this.shipment.set(s);
        this.order.set(o);
        this.loading.set(false);
        this.error.set(null);
        // While the outbox is still working on the booking, keep the timeline live.
        if (s.status === 'PENDING') this.timer = setTimeout(() => this.load(), 2000);
      },
      error: (e) => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }
}
