import { Component, computed, input } from '@angular/core';
import { OrderStatus } from '../core/api/models';

const LABELS: Record<OrderStatus, string> = {
  DRAFT: 'Draft',
  SUBMITTED: 'Submitted',
  RESERVED: 'Reserved',
  PICKING: 'Picking',
  PACKED: 'Packed',
  SHIPPED: 'Shipped',
  CANCELLED: 'Cancelled',
  STOCK_EXCEPTION: 'Stock exception',
};

export function statusLabel(s: OrderStatus): string {
  return LABELS[s];
}

@Component({
  selector: 'app-status-chip',
  template: `<span class="chip" [attr.data-status]="status()" [attr.data-testid]="'status-' + status()">{{ label() }}</span>`,
  styles: `
    .chip { display: inline-block; padding: 2px 10px; border-radius: 999px; font: var(--mat-sys-label-medium);
            white-space: nowrap; background: var(--mat-sys-surface-container-high); color: var(--mat-sys-on-surface); }
    [data-status='SUBMITTED'] { background: #e3e8ff; color: #23308a; }
    [data-status='RESERVED'] { background: #dbeafe; color: #1e3a8a; }
    [data-status='PICKING'] { background: #fff1c9; color: #6b4e00; }
    [data-status='PACKED'] { background: #fde7d4; color: #7a3a00; }
    [data-status='SHIPPED'] { background: #d7f5dd; color: #0f5223; }
    [data-status='CANCELLED'] { background: var(--mat-sys-surface-container-highest); color: var(--mat-sys-on-surface-variant); text-decoration: line-through; }
    [data-status='STOCK_EXCEPTION'] { background: var(--mat-sys-error-container); color: var(--mat-sys-on-error-container); }
  `,
})
export class StatusChipComponent {
  readonly status = input.required<OrderStatus>();
  protected readonly label = computed(() => LABELS[this.status()]);
}
