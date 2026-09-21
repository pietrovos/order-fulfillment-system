import { APIRequestContext, Browser, Page, expect } from '@playwright/test';

export const CARRIER_URL = process.env.CARRIER_URL ?? 'http://localhost:8091';
export const PASSWORD = 'fulfill123';

/** Fill an input once Angular has bound it (zoneless apps render before the first change-detection pass). */
export async function fill(page: Page, testId: string, value: string): Promise<void> {
  await page.locator(`[data-testid="${testId}"].ng-pristine, [data-testid="${testId}"].ng-dirty`).first().waitFor();
  await page.getByTestId(testId).fill(value);
}

export async function login(browser: Browser, username: string): Promise<Page> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto('/login');
  await fill(page, 'username', username);
  await fill(page, 'password', PASSWORD);
  await page.getByTestId('login').click();
  await expect(page).not.toHaveURL(/\/login/);
  return page;
}

export async function apiToken(request: APIRequestContext, username: string): Promise<string> {
  const res = await request.post('/api/auth/login', { data: { username, password: PASSWORD } });
  expect(res.ok()).toBeTruthy();
  return (await res.json()).token;
}

/** A fresh product with an exact amount of stock, so the test owns every unit it reasons about. */
export async function productWithStock(request: APIRequestContext, prefix: string, onHand: number) {
  const token = await apiToken(request, 'supervisor');
  const headers = { Authorization: `Bearer ${token}` };
  const sku = `${prefix}-${Date.now().toString(36).toUpperCase()}`;
  const product = await (await request.post('/api/products', {
    headers, data: { sku, name: `${prefix} demo item`, unitPrice: 25 },
  })).json();
  await request.post(`/api/inventory/stock/${product.id}/receipts`, {
    headers, data: { quantity: onHand, reason: 'e2e setup' },
  });
  return { id: product.id as number, sku };
}

export async function carrierFault(request: APIRequestContext, mode: string, times = 1): Promise<void> {
  const res = await request.post(`${CARRIER_URL}/admin/faults`, { data: { mode, times } });
  expect(res.ok()).toBeTruthy();
}

export async function carrierBookingsFor(request: APIRequestContext, orderNumber: string): Promise<number> {
  const all = await (await request.get(`${CARRIER_URL}/shipments`)).json();
  return all.filter((b: { reference: string }) => b.reference === orderNumber).length;
}

/** Fill the order editor up to (not including) submit. */
export async function draftOrder(page: Page, customer: string, sku: string, quantity: number): Promise<void> {
  await page.goto('/orders/new');
  await fill(page, 'customer-name', customer);
  await fill(page, 'shipping-address', '1 Demo Wharf, Halifax NS');
  await page.getByTestId('line-product-0').click();
  await page.getByRole('option', { name: new RegExp(sku) }).click();
  await fill(page, 'line-qty-0', String(quantity));
}
