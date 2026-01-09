import { Role } from '../core/auth/auth.service';

export interface NavItem {
  label: string;
  icon: string;
  path: string;
  roles: Role[];
}

export const NAV_ITEMS: NavItem[] = [
  { label: 'Home', icon: 'dashboard', path: '/', roles: [] },
];
