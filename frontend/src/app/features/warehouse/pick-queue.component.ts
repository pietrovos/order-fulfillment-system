import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { forkJoin } from 'rxjs';
import { FulfillmentApi } from '../../core/api/fulfillment.api';
import { OrderSummary, PickList } from '../../core/api/models';
import { errorMessage } from '../../core/http/api-error';
import { LoadStateComponent } from '../../shared/load-state.component';
import { Notify } from '../../shared/notify.service';

@Component({
  selector: 'app-pick-queue',
  imports: [DatePipe, RouterLink, MatCardModule, MatButtonModule, MatIconModule, LoadStateComponent],
  template: `
    <div class="header">
      <h1>Picking</h1>
      <button mat-stroked-button (click)="load()"><mat-icon>refresh</mat-icon> Refresh</button>
    </div>
    <app-load-state [loading]="loading()" [error]="error()" (retry)="load()">
      @if (inProgress().length) {
        <h2>In progress</h2>
        <div class="cards">
          @for (p of inProgress(); track p.id) {
            <mat-card appearance="outlined" class="card">
              <mat-card-header>
                <mat-card-title>{{ p.orderNumber }}</mat-card-title>
                <mat-card-subtitle>{{ picked(p) }}/{{ p.lines.length }} lines picked · {{ p.picker }}</mat-card-subtitle>
              </mat-card-header>
              <mat-card-actions align="end">
                <a mat-flat-button [routerLink]="['/warehouse/pick', p.id]" [attr.data-testid]="'continue-' + p.orderNumber">Continue</a>
              </mat-card-actions>
            </mat-card>
          }
        </div>
      }
      <h2>Ready to pick <span class="count">{{ queue().length }}</span></h2>
      @if (queue().length === 0) {
        <p class="empty">Nothing waiting. Reserved orders appear here, oldest first.</p>
      } @else {
        <div class="cards">
          @for (o of queue(); track o.id) {
            <mat-card appearance="outlined" class="card" [attr.data-testid]="'pick-card-' + o.orderNumber">
              <mat-card-header>
                <mat-card-title>{{ o.orderNumber }}</mat-card-title>
                <mat-card-subtitle>{{ o.customerName }}</mat-card-subtitle>
              </mat-card-header>
              <mat-card-content>
                <p>{{ o.lineCount }} lines · {{ o.totalUnits }} units · since {{ o.createdAt | date: 'MMM d, HH:mm' }}</p>
              </mat-card-content>
              <mat-card-actions align="end">
                <button mat-flat-button (click)="start(o)" [disabled]="starting() === o.id" [attr.data-testid]="'start-pick-' + o.orderNumber">
                  <mat-icon>play_arrow</mat-icon> Start picking
                </button>
              </mat-card-actions>
            </mat-card>
          }
        </div>
      }
    </app-load-state>
  `,
  styles: `
    .header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
    .header h1 { margin: 0; }
    h2 { margin-top: 20px; display: flex; align-items: center; gap: 8px; }
    .count { font: var(--mat-sys-label-large); padding: 2px 10px; border-radius: 999px; background: var(--mat-sys-secondary-container); }
    .cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 12px; }
    .empty { color: var(--mat-sys-on-surface-variant); }
  `,
})
export class PickQueueComponent {
  private readonly api = inject(FulfillmentApi);
  private readonly router = inject(Router);
  private readonly notify = inject(Notify);

  protected readonly queue = signal<OrderSummary[]>([]);
  protected readonly inProgress = signal<PickList[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly starting = signal<number | null>(null);

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    forkJoin({ queue: this.api.pickQueue(), open: this.api.openPickLists() }).subscribe({
      next: ({ queue, open }) => {
        this.queue.set(queue);
        this.inProgress.set(open);
        this.loading.set(false);
      },
      error: (e) => {
        this.error.set(errorMessage(e));
        this.loading.set(false);
      },
    });
  }

  picked(p: PickList): number {
    return p.lines.filter((l) => l.picked).length;
  }

  start(o: OrderSummary): void {
    this.starting.set(o.id);
    this.api.startPicking(o.id).subscribe({
      next: (p) => void this.router.navigate(['/warehouse/pick', p.id]),
      error: (e) => {
        this.starting.set(null);
        this.notify.fail(e);
        this.load();
      },
    });
  }
}
