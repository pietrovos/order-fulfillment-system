import { Role } from '../core/auth/auth.service';

export interface NavItem {
  label: string;
  icon: string;
  path: string;
  roles: Role[];
  /** Highlight only on an exact URL match (for items whose path prefixes another item). */
  exact?: boolean;
}

export const NAV_ITEMS: NavItem[] = [
  { label: 'Home', icon: 'dashboard', path: '/', roles: [], exact: true },
  { label: 'Inventory', icon: 'inventory_2', path: '/inventory', roles: [], exact: true },
  { label: 'Movement ledger', icon: 'history', path: '/inventory/movements', roles: [] },
  { label: 'Products', icon: 'category', path: '/products', roles: [] },
];
