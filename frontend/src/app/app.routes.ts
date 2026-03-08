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
