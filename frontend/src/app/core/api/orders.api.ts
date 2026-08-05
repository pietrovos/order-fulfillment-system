import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { ActivityEntry, Movement, Order, OrderCommand, OrderStatus, OrderSummary, Page } from './models';

export interface OrderQuery {
  q?: string;
  statuses?: OrderStatus[];
  page: number;
  size: number;
  sort: string;
  direction: 'asc' | 'desc';
}

@Injectable({ providedIn: 'root' })
export class OrdersApi {
  private readonly http = inject(HttpClient);

  list(query: OrderQuery) {
    let params = new HttpParams()
      .set('page', query.page).set('size', query.size).set('sort', query.sort).set('direction', query.direction);
    if (query.q) params = params.set('q', query.q);
    for (const s of query.statuses ?? []) params = params.append('status', s);
    return this.http.get<Page<OrderSummary>>('/api/orders', { params });
  }

  activity(page = 0, size = 50, actor = '', q = '') {
    return this.http.get<Page<ActivityEntry>>('/api/orders/activity', {
      params: new HttpParams().set('page', page).set('size', size).set('actor', actor).set('q', q),
    });
  }

  counts() {
    return this.http.get<Record<OrderStatus, number>>('/api/orders/counts');
  }

  get(id: number) {
    return this.http.get<Order>(`/api/orders/${id}`);
  }

  getByNumber(orderNumber: string) {
    return this.http.get<Order>(`/api/orders/by-number/${encodeURIComponent(orderNumber)}`);
  }

  movements(id: number) {
    return this.http.get<Movement[]>(`/api/orders/${id}/movements`);
  }

  /**
   * Creates (and optionally submits) an order. The idempotency key identifies the logical order, not
   * the HTTP attempt: callers keep the same key across retries and double clicks.
   */
  create(cmd: OrderCommand, submit: boolean, idempotencyKey: string) {
    return this.http.post<Order>('/api/orders', cmd, {
      params: { submit },
      headers: { 'Idempotency-Key': idempotencyKey },
      observe: 'response',
    });
  }

  update(id: number, version: number, cmd: OrderCommand) {
    return this.http.put<Order>(`/api/orders/${id}`, cmd, { params: { version } });
  }

  submit(id: number) {
    return this.http.post<Order>(`/api/orders/${id}/submit`, null);
  }

  cancel(id: number, reason: string) {
    return this.http.post<Order>(`/api/orders/${id}/cancel`, { reason });
  }

  retryReservation(id: number) {
    return this.http.post<Order>(`/api/orders/${id}/retry-reservation`, null);
  }
}

export function wasReplayed(res: HttpResponse<unknown>): boolean {
  return res.headers.get('Idempotent-Replayed') === 'true';
}
