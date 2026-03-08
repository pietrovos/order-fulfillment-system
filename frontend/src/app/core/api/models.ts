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

export type OrderStatus =
  | 'DRAFT' | 'SUBMITTED' | 'RESERVED' | 'PICKING' | 'PACKED' | 'SHIPPED' | 'CANCELLED' | 'STOCK_EXCEPTION';

export const ORDER_STATUSES: OrderStatus[] = [
  'DRAFT', 'SUBMITTED', 'RESERVED', 'PICKING', 'PACKED', 'SHIPPED', 'STOCK_EXCEPTION', 'CANCELLED',
];

export interface OrderLine {
  lineNo: number;
  productId: number;
  sku: string;
  productName: string;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
}

export interface StatusChange {
  from: OrderStatus | null;
  to: OrderStatus;
  actor: string;
  note: string | null;
  at: string;
}

export interface Order {
  id: number;
  orderNumber: string;
  status: OrderStatus;
  customerName: string;
  customerEmail: string | null;
  shippingAddress: string;
  notes: string | null;
  totalAmount: number;
  exceptionReason: string | null;
  cancelReason: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  submittedAt: string | null;
  version: number;
  lines: OrderLine[];
  history: StatusChange[];
  nextStatuses: OrderStatus[];
}

export interface OrderSummary {
  id: number;
  orderNumber: string;
  status: OrderStatus;
  customerName: string;
  lineCount: number;
  totalUnits: number;
  totalAmount: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  exceptionReason: string | null;
}

export interface OrderCommand {
  customerName: string;
  customerEmail: string | null;
  shippingAddress: string;
  notes: string | null;
  lines: { productId: number; quantity: number }[];
}
