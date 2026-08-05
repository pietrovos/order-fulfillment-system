import { Routes } from '@angular/router';
import { authGuard, roleGuard } from './core/auth/auth.guards';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./features/login/login.component').then((m) => m.LoginComponent) },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./layout/shell.component').then((m) => m.ShellComponent),
    children: [
      { path: '', loadComponent: () => import('./features/home/home.component').then((m) => m.HomeComponent) },
      { path: 'orders', loadComponent: () => import('./features/orders/orders.component').then((m) => m.OrdersComponent) },
      {
        path: 'orders/new',
        canActivate: [roleGuard('SALES', 'SUPERVISOR')],
        loadComponent: () => import('./features/orders/order-editor.component').then((m) => m.OrderEditorComponent),
      },
      {
        path: 'orders/by-number/:orderNumber',
        loadComponent: () => import('./features/orders/order-detail.component').then((m) => m.OrderDetailComponent),
      },
      {
        path: 'orders/:id/edit',
        canActivate: [roleGuard('SALES', 'SUPERVISOR')],
        loadComponent: () => import('./features/orders/order-editor.component').then((m) => m.OrderEditorComponent),
      },
      { path: 'orders/:id', loadComponent: () => import('./features/orders/order-detail.component').then((m) => m.OrderDetailComponent) },
      {
        path: 'warehouse/pick',
        canActivate: [roleGuard('WAREHOUSE', 'SUPERVISOR')],
        loadComponent: () => import('./features/warehouse/pick-queue.component').then((m) => m.PickQueueComponent),
      },
      {
        path: 'warehouse/pick/:id',
        canActivate: [roleGuard('WAREHOUSE', 'SUPERVISOR')],
        loadComponent: () => import('./features/warehouse/pick-list.component').then((m) => m.PickListComponent),
      },
      {
        path: 'warehouse/pack',
        canActivate: [roleGuard('WAREHOUSE', 'SUPERVISOR')],
        loadComponent: () => import('./features/warehouse/pack-queue.component').then((m) => m.PackQueueComponent),
      },
      {
        path: 'exceptions',
        canActivate: [roleGuard('SUPERVISOR')],
        loadComponent: () => import('./features/exceptions/exceptions.component').then((m) => m.ExceptionsComponent),
      },
      { path: 'shipments', loadComponent: () => import('./features/shipments/shipments.component').then((m) => m.ShipmentsComponent) },
      {
        path: 'shipments/:id',
        loadComponent: () => import('./features/shipments/shipment-detail.component').then((m) => m.ShipmentDetailComponent),
      },
      { path: 'audit', loadComponent: () => import('./features/audit/audit.component').then((m) => m.AuditComponent) },
      { path: 'products', loadComponent: () => import('./features/products/products.component').then((m) => m.ProductsComponent) },
      { path: 'inventory', loadComponent: () => import('./features/inventory/inventory.component').then((m) => m.InventoryComponent) },
      {
        path: 'inventory/movements',
        loadComponent: () => import('./features/inventory/movements.component').then((m) => m.MovementsComponent),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
