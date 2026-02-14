import { Routes } from '@angular/router';
import { authGuard } from './core/auth/auth.guards';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./features/login/login.component').then((m) => m.LoginComponent) },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./layout/shell.component').then((m) => m.ShellComponent),
    children: [
      { path: '', loadComponent: () => import('./features/home/home.component').then((m) => m.HomeComponent) },
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
