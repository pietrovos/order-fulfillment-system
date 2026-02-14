import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Movement, Page, StockLevel } from './models';

export interface MovementFilter {
  productId?: number | null;
  reference?: string | null;
  type?: string | null;
  page: number;
  size: number;
}

@Injectable({ providedIn: 'root' })
export class InventoryApi {
  private readonly http = inject(HttpClient);

  stock(q = '') {
    return this.http.get<StockLevel[]>('/api/inventory/stock', { params: { q } });
  }

  receive(productId: number, quantity: number, reason: string) {
    return this.http.post<StockLevel>(`/api/inventory/stock/${productId}/receipts`, { quantity, reason });
  }

  adjust(productId: number, delta: number, reason: string) {
    return this.http.post<StockLevel>(`/api/inventory/stock/${productId}/adjustments`, { delta, reason });
  }

  setReorderPoint(productId: number, reorderPoint: number) {
    return this.http.put<StockLevel>(`/api/inventory/stock/${productId}/reorder-point`, { reorderPoint });
  }

  movements(f: MovementFilter) {
    let params = new HttpParams().set('page', f.page).set('size', f.size);
    if (f.productId) params = params.set('productId', f.productId);
    if (f.reference) params = params.set('reference', f.reference);
    if (f.type) params = params.set('type', f.type);
    return this.http.get<Page<Movement>>('/api/inventory/movements', { params });
  }
}
