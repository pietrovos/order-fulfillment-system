import { Order, Shipment } from '../../core/api/models';
import { buildTimeline } from './timeline';

describe('buildTimeline', () => {
  it('merges order history and shipment events chronologically', () => {
    const order = {
      history: [
        { from: null, to: 'DRAFT', actor: 'sales', note: null, at: '2026-01-01T10:00:00Z' },
        { from: 'PICKING', to: 'PACKED', actor: 'wh', note: 'x', at: '2026-01-01T10:05:00Z' },
        { from: 'PACKED', to: 'SHIPPED', actor: 'system', note: null, at: '2026-01-01T10:07:00Z' },
      ],
    } as unknown as Order;
    const shipment = {
      events: [
        { type: 'PACKED', detail: '1 parcel', actor: 'wh', at: '2026-01-01T10:05:00Z' },
        { type: 'BOOKING_ATTEMPT_FAILED', detail: 'HTTP 503', actor: 'system', at: '2026-01-01T10:06:00Z' },
        { type: 'BOOKED', detail: 'TRK1', actor: 'system', at: '2026-01-01T10:06:30Z' },
      ],
    } as unknown as Shipment;
    const t = buildTimeline(order, shipment);
    expect(t.map((e) => e.title)).toEqual([
      'Draft', 'Packed', 'Carrier booking attempt failed', 'Booked with carrier', 'Shipped',
    ]);
    expect(t[2].kind).toBe('error');
    expect(t[4].kind).toBe('success');
  });
});
