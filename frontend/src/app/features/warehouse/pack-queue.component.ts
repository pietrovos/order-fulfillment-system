import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { FulfillmentApi } from '../../core/api/fulfillment.api';
import { PickList, Shipment } from '../../core/api/models';
import { errorMessage } from '../../core/http/api-error';
import { LoadStateComponent } from '../../shared/load-state.component';
import { Notify } from '../../shared/notify.service';
import { PackDialog } from './pack.dialog';

@Component({
  selector: 'app-pack-queue',
  imports: [DatePipe, MatCardModule, MatButtonModule, MatIconModule, LoadStateComponent],
  template: `
    <div class="header">
      <h1>Packing</h1>
      <button mat-stroked-button (click)="load()"><mat-icon>refresh</mat-icon> Refresh</button>
    </div>
    <app-load-state [loading]="loading()" [error]="error()" [empty]="rows().length === 0"
                    emptyText="Nothing to pack. Completed picks appear here." emptyIcon="package_2" (retry)="load()">
      <div class="cards">
        @for (p of rows(); track p.id) {
          <mat-card appearance="outlined" [attr.data-testid]="'pack-card-' + p.orderNumber">
            <mat-card-header>
              <mat-card-title>{{ p.orderNumber }}</mat-card-title>
              <mat-card-subtitle>Picked by {{ p.picker }} · {{ p.completedAt | date: 'HH:mm' }}</mat-card-subtitle>
            </mat-card-header>
            <mat-card-content>
              <ul>
                @for (l of p.lines; track l.lineNo) { <li>{{ l.quantity }}× {{ l.sku }} {{ l.productName }}</li> }
              </ul>
            </mat-card-content>
            <mat-card-actions align="end">
              <button mat-flat-button (click)="pack(p)" [attr.data-testid]="'pack-' + p.orderNumber">
                <mat-icon>package_2</mat-icon> Pack
              </button>
            </mat-card-actions>
          </mat-card>
        }
      </div>
    </app-load-state>
  `,
  styles: `
    .header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
    .header h1 { margin: 0; }
    .cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
    ul { margin: 0; padding-left: 18px; }
  `,
})
export class PackQueueComponent {
  private readonly api = inject(FulfillmentApi);
  private readonly dialog = inject(MatDialog);
  private readonly notify = inject(Notify);
  private readonly router = inject(Router);
  protected readonly rows = signal<PickList[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.packQueue().subscribe({
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

  pack(p: PickList): void {
    this.dialog.open<PackDialog, PickList, Shipment>(PackDialog, { data: p, width: '420px' }).afterClosed()
      .subscribe((s) => {
        if (!s) return;
        this.notify.ok(`${s.orderNumber} packed. Booking with ${s.carrier}.`);
        void this.router.navigate(['/shipments', s.id]);
      });
  }
}
