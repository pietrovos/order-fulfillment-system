import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { OrderSummary, PickList, Shipment, StockException } from './models';

@Injectable({ providedIn: 'root' })
export class FulfillmentApi {
  private readonly http = inject(HttpClient);

  pickQueue() {
    return this.http.get<OrderSummary[]>('/api/fulfillment/pick-queue');
  }

  openPickLists() {
    return this.http.get<PickList[]>('/api/fulfillment/pick-lists');
  }

  packQueue() {
    return this.http.get<PickList[]>('/api/fulfillment/pack-queue');
  }

  startPicking(orderId: number) {
    return this.http.post<PickList>(`/api/fulfillment/orders/${orderId}/pick-list`, null);
  }

  pickList(id: number) {
    return this.http.get<PickList>(`/api/fulfillment/pick-lists/${id}`);
  }

  confirmLine(id: number, lineNo: number, picked: boolean) {
    return this.http.post<PickList>(`/api/fulfillment/pick-lists/${id}/lines/${lineNo}`, { picked });
  }

  completePick(id: number) {
    return this.http.post<PickList>(`/api/fulfillment/pick-lists/${id}/complete`, null);
  }

  pack(id: number, parcels: number, weightKg: number) {
    return this.http.post<Shipment>(`/api/fulfillment/pick-lists/${id}/pack`, { parcels, weightKg });
  }

  exceptions() {
    return this.http.get<StockException[]>('/api/fulfillment/exceptions');
  }

  shipments(status = '', q = '') {
    return this.http.get<Shipment[]>('/api/shipments', { params: new HttpParams().set('status', status).set('q', q) });
  }

  shipment(id: string) {
    return this.http.get<Shipment>(`/api/shipments/${id}`);
  }

  shipmentForOrder(orderId: number) {
    return this.http.get<Shipment>(`/api/fulfillment/orders/${orderId}/shipment`);
  }
}
