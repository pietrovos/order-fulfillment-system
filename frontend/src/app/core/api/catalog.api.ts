import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Product, ProductCommand } from './models';

@Injectable({ providedIn: 'root' })
export class CatalogApi {
  private readonly http = inject(HttpClient);

  list(q = '', activeOnly = false) {
    return this.http.get<Product[]>('/api/products', { params: new HttpParams().set('q', q).set('activeOnly', activeOnly) });
  }

  create(cmd: ProductCommand) {
    return this.http.post<Product>('/api/products', cmd);
  }

  update(id: number, cmd: ProductCommand) {
    return this.http.put<Product>(`/api/products/${id}`, cmd);
  }
}
