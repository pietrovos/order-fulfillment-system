import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { ActivityEntry } from '../../core/api/models';
import { OrdersApi } from '../../core/api/orders.api';
import { errorMessage } from '../../core/http/api-error';
import { LoadStateComponent } from '../../shared/load-state.component';
import { StatusChipComponent } from '../../shared/status-chip.component';

@Component({
  selector: 'app-audit',
  imports: [
    DatePipe, RouterLink, MatTableModule, MatPaginatorModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatIconModule, LoadStateComponent, StatusChipComponent,
  ],
  template: `
    <h1>Audit log</h1>
    <p class="intro">Every order status change: who, when and why. Stock changes live in the
      <a routerLink="/inventory/movements">movement ledger</a>.</p>
    <div class="filters">
      <mat-form-field appearance="outline" subscriptSizing="dynamic">
        <mat-label>Actor</mat-label>
        <mat-select [value]="actor()" (valueChange)="actor.set($event); load(0)">
          <mat-option value="">Everyone</mat-option>
          @for (a of actors; track a) { <mat-option [value]="a">{{ a }}</mat-option> }
        </mat-select>
      </mat-form-field>
      <mat-form-field appearance="outline" subscriptSizing="dynamic">
        <mat-icon matPrefix>search</mat-icon>
        <mat-label>Order number</mat-label>
        <input matInput [value]="q()" (change)="q.set($any($event.target).value); load(0)" />
      </mat-form-field>
    </div>
    <app-load-state [loading]="loading()" [error]="error()" [empty]="rows().length === 0" emptyText="No matching activity"
                    emptyIcon="manage_search" (retry)="load(page())">
      <div class="table-wrap">
        <table mat-table [dataSource]="rows()">
          <ng-container matColumnDef="at">
            <th mat-header-cell *matHeaderCellDef>When</th>
            <td mat-cell *matCellDef="let a" class="nowrap">{{ a.at | date: 'MMM d, HH:mm:ss' }}</td>
          </ng-container>
          <ng-container matColumnDef="order">
            <th mat-header-cell *matHeaderCellDef>Order</th>
            <td mat-cell *matCellDef="let a"><a [routerLink]="['/orders', a.orderId]">{{ a.orderNumber }}</a></td>
          </ng-container>
          <ng-container matColumnDef="change">
            <th mat-header-cell *matHeaderCellDef>Change</th>
            <td mat-cell *matCellDef="let a" class="change">
              @if (a.from) { <app-status-chip [status]="a.from" /> <mat-icon inline>arrow_forward</mat-icon> }
              <app-status-chip [status]="a.to" />
            </td>
          </ng-container>
          <ng-container matColumnDef="actor">
            <th mat-header-cell *matHeaderCellDef>By</th>
            <td mat-cell *matCellDef="let a">{{ a.actor }}</td>
          </ng-container>
          <ng-container matColumnDef="note">
            <th mat-header-cell *matHeaderCellDef>Note</th>
            <td mat-cell *matCellDef="let a">{{ a.note }}</td>
          </ng-container>
          <tr mat-header-row *matHeaderRowDef="columns"></tr>
          <tr mat-row *matRowDef="let row; columns: columns"></tr>
        </table>
      </div>
    </app-load-state>
    <mat-paginator [length]="total()" [pageIndex]="page()" [pageSize]="size()" [pageSizeOptions]="[25, 50, 100]" (page)="onPage($event)" />
  `,
  styles: `
    .intro { color: var(--mat-sys-on-surface-variant); }
    .filters { display: flex; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
    .table-wrap { overflow-x: auto; border-radius: 12px; border: 1px solid var(--mat-sys-outline-variant); }
    table { width: 100%; min-width: 760px; }
    .nowrap { white-space: nowrap; }
    .change { white-space: nowrap; }
    .change mat-icon { vertical-align: middle; margin: 0 4px; }
    td a { color: var(--mat-sys-primary); font-weight: 600; text-decoration: none; }
  `,
})
export class AuditComponent {
  private readonly api = inject(OrdersApi);
  protected readonly actors = ['sales', 'warehouse', 'supervisor', 'system'];
  protected readonly columns = ['at', 'order', 'change', 'actor', 'note'];
  protected readonly rows = signal<ActivityEntry[]>([]);
  protected readonly total = signal(0);
  protected readonly page = signal(0);
  protected readonly size = signal(50);
  protected readonly actor = signal('');
  protected readonly q = signal('');
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.load(0);
  }

  onPage(e: PageEvent): void {
    this.size.set(e.pageSize);
    this.load(e.pageIndex);
  }

  load(page: number): void {
    this.page.set(page);
    this.loading.set(true);
    this.error.set(null);
    this.api.activity(page, this.size(), this.actor(), this.q()).subscribe({
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
