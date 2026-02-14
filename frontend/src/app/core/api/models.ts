export interface Product {
  id: number;
  sku: string;
  name: string;
  description: string | null;
  unitPrice: number;
  active: boolean;
}

export interface ProductCommand {
  sku: string;
  name: string;
  description: string | null;
  unitPrice: number;
  active: boolean;
}

export interface StockLevel {
  productId: number;
  sku: string;
  name: string;
  onHand: number;
  reserved: number;
  available: number;
  reorderPoint: number;
  lowStock: boolean;
  updatedAt: string;
}

export type MovementType = 'RECEIPT' | 'ADJUSTMENT' | 'RESERVE' | 'RELEASE' | 'SHIP';

export interface Movement {
  id: number;
  productId: number;
  sku: string;
  type: MovementType;
  onHandDelta: number;
  reservedDelta: number;
  onHandAfter: number;
  reservedAfter: number;
  referenceType: 'MANUAL' | 'ORDER';
  referenceId: string | null;
  reason: string | null;
  actor: string;
  createdAt: string;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
