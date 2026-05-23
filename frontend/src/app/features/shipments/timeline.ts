import { Order, Shipment } from '../../core/api/models';
import { statusLabel } from '../../shared/status-chip.component';

export interface TimelineEntry {
  at: string;
  title: string;
  detail: string | null;
  actor: string;
  kind: 'order' | 'shipment' | 'error' | 'success';
}

const EVENT_TITLES: Record<string, string> = {
  PACKED: 'Packed',
  BOOKING_REQUESTED: 'Carrier booking requested',
  BOOKING_ATTEMPT_FAILED: 'Carrier booking attempt failed',
  BOOKED: 'Booked with carrier',
  BOOKING_FAILED: 'Carrier booking gave up',
  BOOKING_RETRY_REQUESTED: 'Booking retry requested',
};

/** Combines order status changes and shipment events in chronological order. */
export function buildTimeline(order: Order | null, shipment: Shipment | null): TimelineEntry[] {
  const entries: TimelineEntry[] = [];
  for (const h of order?.history ?? []) {
    if (h.to === 'PACKED') continue; // the shipment's PACKED event carries the parcel details
    entries.push({
      at: h.at, title: statusLabel(h.to), detail: h.note, actor: h.actor,
      kind: h.to === 'SHIPPED' ? 'success' : h.to === 'STOCK_EXCEPTION' || h.to === 'CANCELLED' ? 'error' : 'order',
    });
  }
  for (const e of shipment?.events ?? []) {
    entries.push({
      at: e.at, title: EVENT_TITLES[e.type] ?? e.type, detail: e.detail, actor: e.actor,
      kind: e.type.includes('FAILED') ? 'error' : e.type === 'BOOKED' ? 'success' : 'shipment',
    });
  }
  return entries.sort((a, b) => a.at.localeCompare(b.at));
}
