import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { FulfillmentApi } from '../../core/api/fulfillment.api';
import { Shipment } from '../../core/api/models';
import { errorMessage } from '../../core/http/api-error';
import { LoadStateComponent } from '../../shared/load-state.component';

@Component({
  selector: 'app-shipments',
  imports: [DatePipe, RouterLink, MatTableModule, MatButtonModule, MatButtonToggleModule, MatIconModule, LoadStateComponent],
  template: `
    <div class="header">
      <h1>Shipments</h1>
      <mat-button-toggle-group [value]="status()" (change)="status.set($event.value); load()" aria-label="Status filter">
        <mat-button-toggle value="">All</mat-button-toggle>
        <mat-button-toggle value="PENDING">Pending</mat-button-toggle>
        <mat-button-toggle value="BOOKED">Booked</mat-button-toggle>
        <mat-button-toggle value="FAILED">Failed</mat-button-toggle>
      </mat-button-toggle-group>
    </div>
    <app-load-state [loading]="loading()" [error]="error()" [empty]="rows().length === 0"
                    emptyText="No shipments yet" emptyIcon="local_shipping" (retry)="load()">
      <div class="table-wrap">
        <table mat-table [dataSource]="rows()" data-testid="shipments-table">
          <ng-container matColumnDef="order">
            <th mat-header-cell *matHeaderCellDef>Order</th>
            <td mat-cell *matCellDef="let s"><a [routerLink]="['/shipments', s.id]" class="link">{{ s.orderNumber }}</a></td>
          </ng-container>
          <ng-container matColumnDef="shipTo">
            <th mat-header-cell *matHeaderCellDef>Ship to</th>
            <td mat-cell *matCellDef="let s">{{ s.shipToName }}</td>
          </ng-container>
          <ng-container matColumnDef="status">
            <th mat-header-cell *matHeaderCellDef>Status</th>
            <td mat-cell *matCellDef="let s"><span class="st" [attr.data-status]="s.status">{{ s.status }}</span></td>
          </ng-container>
          <ng-container matColumnDef="tracking">
            <th mat-header-cell *matHeaderCellDef>Tracking</th>
            <td mat-cell *matCellDef="let s" class="mono">{{ s.trackingNumber ?? '—' }}</td>
          </ng-container>
          <ng-container matColumnDef="attempts">
            <th mat-header-cell *matHeaderCellDef class="num">Attempts</th>
            <td mat-cell *matCellDef="let s" class="num">{{ s.bookingAttempts }}</td>
          </ng-container>
          <ng-container matColumnDef="createdAt">
            <th mat-header-cell *matHeaderCellDef>Packed</th>
            <td mat-cell *matCellDef="let s">{{ s.createdAt | date: 'MMM d, HH:mm' }}</td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="columns"></tr>
          <tr mat-row *matRowDef="let row; columns: columns" class="clickable" (click)="router.navigate(['/shipments', row.id])"></tr>
        </table>
      </div>
    </app-load-state>
  `,
  styles: `
    .header { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 16px; }
    .header h1 { margin: 0; }
    .table-wrap { overflow-x: auto; border-radius: 12px; border: 1px solid var(--mat-sys-outline-variant); }
    table { width: 100%; min-width: 640px; }
    .link { font-weight: 600; color: var(--mat-sys-primary); text-decoration: none; }
    .num { text-align: right; }
    .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 13px; }
    .clickable { cursor: pointer; }
    .st { font: var(--mat-sys-label-medium); padding: 2px 10px; border-radius: 999px; background: #fff1c9; color: #6b4e00; }
    .st[data-status='BOOKED'] { background: #d7f5dd; color: #0f5223; }
    .st[data-status='FAILED'] { background: var(--mat-sys-error-container); color: var(--mat-sys-on-error-container); }
  `,
})
export class ShipmentsComponent {
  private readonly api = inject(FulfillmentApi);
  protected readonly router = inject(Router);
  protected readonly columns = ['order', 'shipTo', 'status', 'tracking', 'attempts', 'createdAt'];
  protected readonly rows = signal<Shipment[]>([]);
  protected readonly status = signal('');
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.shipments(this.status()).subscribe({
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
}
