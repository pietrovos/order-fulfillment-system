import { expect, test } from '@playwright/test';
import { carrierBookingsFor, carrierFault, draftOrder, fill, login, productWithStock } from './support';

/**
 * The FulfillOps demo, end to end through the browser:
 *  1. Two sales sessions order 7 of the same 10 units at the same instant: one order is reserved, the other
 *     lands in the supervisor's stock-exception queue.
 *  2. The reserved order is picked and packed while the carrier fails once and then books the shipment but
 *     drops the connection: the outbox retries with the same idempotency key and exactly one shipment exists.
 */
test.describe.serial('demo: concurrency and carrier recovery', () => {
  let sku: string;
  let productId: number;
  let winner = '';
  let loser = '';

  test.beforeAll(async ({ request }) => {
    ({ sku, id: productId } = await productWithStock(request, 'DEMO', 10));
  });

  test('two concurrent orders for 7 of 10 units: one reserved, one in the exception queue', async ({ browser }) => {
    const alice = await login(browser, 'sales');
    const bob = await login(browser, 'supervisor');
    await draftOrder(alice, 'Harbourview Seafood Ltd', sku, 7);
    await draftOrder(bob, 'Tidewater Brewing Co', sku, 7);

    // Both press "Submit order" at the same moment.
    await Promise.all([alice.getByTestId('submit-order').click(), bob.getByTestId('submit-order').click()]);
    await Promise.all([alice.waitForURL(/\/orders\/\d+$/), bob.waitForURL(/\/orders\/\d+$/)]);

    const results = [];
    for (const page of [alice, bob]) {
      const number = (await page.getByTestId('order-number').textContent())!.trim();
      const status = await page.locator('h1 app-status-chip span').getAttribute('data-status');
      results.push({ number, status, page });
    }
    expect(results.map((r) => r.status).sort()).toEqual(['RESERVED', 'STOCK_EXCEPTION']);
    winner = results.find((r) => r.status === 'RESERVED')!.number;
    loser = results.find((r) => r.status === 'STOCK_EXCEPTION')!.number;

    const loserPage = results.find((r) => r.status === 'STOCK_EXCEPTION')!.page;
    await expect(loserPage.getByTestId('exception-reason')).toContainText('ordered 7, available 3');

    // The supervisor sees it in the exception queue, short by 4.
    await bob.goto('/exceptions');
    const card = bob.getByTestId(`exception-${loser}`);
    await expect(card).toBeVisible();
    await expect(card).toContainText('Insufficient stock');

    // Inventory: 10 on hand, 7 reserved, 3 available.
    await bob.goto('/inventory');
    await bob.getByTestId('stock-search').fill(sku);
    await expect(bob.getByTestId(`available-${sku}`)).toHaveText('3');
    await alice.context().close();
    await bob.context().close();
  });

  test('carrier fails, then drops the response after booking: recovery yields a single shipment', async ({ browser, request }) => {
    expect(winner, 'previous test must have produced a reserved order').not.toBe('');
    const warehouse = await login(browser, 'warehouse');

    await warehouse.goto('/warehouse/pick');
    await warehouse.getByTestId(`start-pick-${winner}`).click();
    await warehouse.waitForURL(/\/warehouse\/pick\/\d+/);
    await warehouse.getByTestId('pick-line-1').click();
    await expect(warehouse.locator('li.done')).toHaveCount(1);
    await warehouse.getByTestId('complete-pick').click();
    await warehouse.waitForURL(/\/warehouse\/pack/);

    // The next booking attempt gets a 503; the one after is booked by the carrier but the connection is
    // reset before we see the response.
    await carrierFault(request, 'FAIL', 1);
    await carrierFault(request, 'DROP_AFTER_SUCCESS', 1);

    await warehouse.getByTestId(`pack-${winner}`).click();
    await fill(warehouse, 'parcels', '1');
    await fill(warehouse, 'weight', '8.5');
    await warehouse.getByTestId('confirm-pack').click();
    await warehouse.waitForURL(/\/shipments\/[0-9a-f-]+$/);

    // The shipment page polls while the outbox retries; wait for the booking to land.
    await expect(warehouse.getByTestId('shipment-booked')).toBeVisible({ timeout: 30_000 });
    const timeline = warehouse.getByTestId('shipment-timeline');
    await expect(timeline).toContainText('Carrier booking attempt failed');
    await expect(timeline).toContainText('HTTP 503');
    await expect(timeline).toContainText('No response from carrier');
    await expect(timeline).toContainText('carrier returned the booking it already had for this key');
    await expect(timeline).toContainText('Shipped');
    await expect(warehouse.locator('h1 app-status-chip span')).toHaveAttribute('data-status', 'SHIPPED');

    // Exactly one shipment exists at the carrier for this order.
    expect(await carrierBookingsFor(request, winner)).toBe(1);

    // Stock: the 7 units left the building; nothing is reserved any more.
    await warehouse.goto('/inventory');
    await warehouse.getByTestId('stock-search').fill(sku);
    await expect(warehouse.getByTestId(`available-${sku}`)).toHaveText('3');
    const row = warehouse.locator('tr', { hasText: sku });
    await expect(row.locator('td').nth(2)).toHaveText('3'); // on hand
    await expect(row.locator('td').nth(3)).toHaveText('0'); // reserved
    expect(productId).toBeGreaterThan(0);
    await warehouse.context().close();
  });
});
