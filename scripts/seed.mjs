#!/usr/bin/env node
// Seeds demo data through the public API, including ledger entries and carrier bookings.
//
//   node scripts/seed.mjs [baseUrl]        default http://localhost:8080
//
// Safe to re-run: it stops if the catalog already contains the seed products.

const BASE = process.argv[2] ?? process.env.API_URL ?? 'http://localhost:8080';
const PASSWORD = process.env.DEMO_PASSWORD ?? 'fulfill123';

const PRODUCTS = [
  // sku, name, price, on hand, reorder point
  ['BOX-S-2525', 'Corrugated box 25x25x25 cm (bundle of 25)', 18.5, 240, 60],
  ['BOX-M-4030', 'Corrugated box 40x30x30 cm (bundle of 25)', 27.0, 180, 50],
  ['BOX-L-6040', 'Corrugated box 60x40x40 cm (bundle of 10)', 24.0, 75, 30],
  ['BOX-DW-6060', 'Double-wall box 60x60x60 cm (bundle of 10)', 46.0, 18, 20],
  ['TAPE-48-CLR', 'Packing tape 48 mm clear (36 rolls)', 54.0, 120, 40],
  ['TAPE-48-FRG', 'Printed "FRAGILE" tape 48 mm (36 rolls)', 71.0, 22, 25],
  ['TAPE-GUN-48', 'Tape dispenser gun 48 mm', 16.9, 35, 10],
  ['WRAP-500-23', 'Stretch film 500 mm x 23 µm (4 rolls)', 89.0, 64, 20],
  ['WRAP-HAND-100', 'Hand stretch film 100 mm (12 rolls)', 32.5, 40, 12],
  ['BUBL-1200-50', 'Bubble wrap 1200 mm x 50 m', 42.0, 30, 10],
  ['VOID-PILLOW', 'Air pillows 200x100 mm (box of 1000)', 38.0, 55, 15],
  ['PAL-EUR-1208', 'Euro pallet 1200x800 mm', 14.0, 150, 40],
  ['PAL-CA-4840', 'Standard pallet 48x40 in', 16.5, 90, 40],
  ['PAL-HT-4840', 'Heat-treated export pallet 48x40 in', 21.0, 12, 15],
  ['STRAP-PP-12', 'PP strapping 12 mm x 3000 m', 58.0, 26, 8],
  ['STRAP-SEAL-12', 'Strapping seals 12 mm (box of 1000)', 24.0, 40, 10],
  ['STRAP-TOOL', 'Manual strapping tensioner', 129.0, 6, 2],
  ['EDGE-50-1200', 'Edge protector 50x50x1200 mm (bundle of 50)', 36.0, 45, 15],
  ['LBL-4X6-500', 'Thermal shipping labels 4x6 in (500/roll)', 12.5, 300, 80],
  ['LBL-PRNT-4', 'Desktop thermal label printer', 349.0, 4, 2],
  ['GLV-NIT-L', 'Nitrile gloves size L (box of 100)', 11.0, 200, 50],
  ['GLV-CUT-9', 'Cut-resistant work gloves size 9 (12 pairs)', 48.0, 28, 10],
  ['VEST-HV-L', 'High-visibility vest size L', 9.5, 60, 15],
  ['KNF-SAFE', 'Safety box cutter with auto-retract', 7.25, 90, 25],
  ['HTRK-2500', 'Hand pallet truck 2500 kg', 489.0, 3, 1],
  ['DRUM-200-STL', 'Steel drum 200 L open-head', 48.0, 16, 6],
  ['IBC-1000', 'IBC tote 1000 L (reconditioned)', 215.0, 5, 2],
  ['BIN-STK-60', 'Stackable storage bin 600x400 mm', 13.0, 110, 30],
  ['SHELF-RIVET', 'Boltless rivet shelving 72x36x18 in', 159.0, 8, 3],
  ['MAT-ANTI-35', 'Anti-fatigue mat 3x5 ft', 64.0, 14, 5],
];

const CUSTOMERS = [
  ['Harbourview Seafood Ltd', 'orders@harbourview.example', '14 Wharf Rd\nLunenburg NS B0J 2C0'],
  ['Tidewater Brewing Co', 'receiving@tidewater.example', '220 Water St\nSt. John\'s NL A1C 1A9'],
  ['Fundy Coast Hardware', 'buyer@fundycoast.example', '3 King St\nSaint John NB E2L 1G5'],
  ['Annapolis Valley Orchards', 'ops@avorchards.example', '881 Highway 1\nWolfville NS B4P 2R3'],
  ['Northumberland Print & Pack', 'purchasing@nppack.example', '55 Pictou Rd\nTruro NS B2N 2S7'],
  ['Cabot Trail Outfitters', null, '12 Main St\nBaddeck NS B0E 1B0'],
  ['Island Potato Growers Co-op', 'logistics@ipg.example', '400 Read Dr\nSummerside PE C1N 5A9'],
  ['Halifax Medical Supply', 'supply@hfxmed.example', '1550 Bedford Hwy\nBedford NS B4A 1E6'],
  ['Miramichi Timber Products', 'shipping@miramichi.example', '78 Newcastle Blvd\nMiramichi NB E1V 2L9'],
  ['Bluenose Chemicals', 'orders@bluenose.example', '9 Harbour Rd\nYarmouth NS B5A 1A1'],
  ['Gros Morne Adventure Gear', null, '2 Pond Rd\nRocky Harbour NL A0K 4N0'],
  ['Moncton Distribution Hub', 'dock@mdh.example', '600 Ellerdale St\nMoncton NB E1A 3L9'],
];

const tokens = {};
async function login(user) {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ username: user, password: PASSWORD }),
  });
  if (!res.ok) throw new Error(`login ${user}: ${res.status}`);
  tokens[user] = (await res.json()).token;
}

async function call(user, method, path, body) {
  const headers = { authorization: `Bearer ${tokens[user]}`, 'content-type': 'application/json' };
  if (method === 'POST' && path.startsWith('/api/orders?')) headers['Idempotency-Key'] = crypto.randomUUID();
  const res = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${text}`);
  return text ? JSON.parse(text) : null;
}

// Fixed random seed keeps the generated data reproducible.
let state = 42;
const rnd = () => ((state = (state * 1103515245 + 12345) % 2 ** 31) / 2 ** 31);
const pick = (arr) => arr[Math.floor(rnd() * arr.length)];
const between = (lo, hi) => lo + Math.floor(rnd() * (hi - lo + 1));

async function main() {
  for (const u of ['sales', 'warehouse', 'supervisor']) await login(u);

  const existing = await call('supervisor', 'GET', '/api/products?q=BOX-S-2525');
  if (existing.some((p) => p.sku === 'BOX-S-2525')) {
    console.log('Seed data already present; nothing to do.');
    return;
  }

  console.log(`Seeding ${BASE}`);
  const products = [];
  for (const [sku, name, unitPrice, onHand, reorderPoint] of PRODUCTS) {
    const p = await call('supervisor', 'POST', '/api/products', { sku, name, unitPrice });
    if (onHand > 0) {
      await call('warehouse', 'POST', `/api/inventory/stock/${p.id}/receipts`, { quantity: onHand, reason: `PO-${between(4100, 4999)} opening stock` });
    }
    await call('supervisor', 'PUT', `/api/inventory/stock/${p.id}/reorder-point`, { reorderPoint });
    products.push({ ...p, onHand });
  }
  console.log(`  ${products.length} products with opening stock`);

  // A couple of real-life ledger corrections.
  await call('supervisor', 'POST', `/api/inventory/stock/${products[0].id}/adjustments`, { delta: -4, reason: 'Water damage, dock door 3' });
  await call('supervisor', 'POST', `/api/inventory/stock/${products[11].id}/adjustments`, { delta: 6, reason: 'Cycle count correction' });

  const order = async (submit, lines, customer = pick(CUSTOMERS), notes = null) => {
    const [customerName, customerEmail, shippingAddress] = customer;
    return call('sales', 'POST', `/api/orders?submit=${submit}`, {
      customerName, customerEmail, shippingAddress, notes,
      lines: lines.map(([p, quantity]) => ({ productId: p.id, quantity })),
    });
  };
  const someLines = (maxLines = 3, maxQty = 6) => {
    const chosen = new Map();
    const n = between(1, maxLines);
    while (chosen.size < n) {
      const p = pick(products.filter((x) => x.onHand >= 10));
      chosen.set(p.id, [p, between(1, maxQty)]);
    }
    return [...chosen.values()];
  };
  const pickAndPack = async (o, complete = true, pack = true) => {
    const pl = await call('warehouse', 'POST', `/api/fulfillment/orders/${o.id}/pick-list`);
    if (!complete) {
      await call('warehouse', 'POST', `/api/fulfillment/pick-lists/${pl.id}/lines/1`, { picked: true });
      return;
    }
    for (const l of pl.lines) await call('warehouse', 'POST', `/api/fulfillment/pick-lists/${pl.id}/lines/${l.lineNo}`, { picked: true });
    await call('warehouse', 'POST', `/api/fulfillment/pick-lists/${pl.id}/complete`);
    if (pack) {
      await call('warehouse', 'POST', `/api/fulfillment/pick-lists/${pl.id}/pack`, { parcels: between(1, 4), weightKg: between(3, 180) + 0.5 });
    }
  };

  const counts = { shipped: 0, packed: 0, picking: 0, reserved: 0, exception: 0, draft: 0, cancelled: 0 };
  for (let i = 0; i < 18; i++) {
    const o = await order(true, someLines());
    if (o.status === 'RESERVED') { await pickAndPack(o); counts.shipped++; }
  }
  for (let i = 0; i < 3; i++) {
    const o = await order(true, someLines());
    if (o.status === 'RESERVED') { await pickAndPack(o, true, false); counts.packed++; }
  }
  for (let i = 0; i < 3; i++) {
    const o = await order(true, someLines(4, 4));
    if (o.status === 'RESERVED') { await pickAndPack(o, false); counts.picking++; }
  }
  for (let i = 0; i < 7; i++) {
    const o = await order(true, someLines(), pick(CUSTOMERS), i === 0 ? 'Deliver to loading bay B before 10:00' : null);
    if (o.status === 'RESERVED') counts.reserved++;
  }
  // Stock exceptions: asks for more than is on the shelf.
  const scarce = products.filter((p) => p.onHand > 0 && p.onHand <= 18);
  for (const p of scarce.slice(0, 4)) {
    const o = await order(true, [[p, p.onHand + between(2, 12)]]);
    if (o.status === 'STOCK_EXCEPTION') counts.exception++;
  }
  for (let i = 0; i < 3; i++) {
    await order(false, someLines(), pick(CUSTOMERS), 'Quote, awaiting customer PO');
    counts.draft++;
  }
  for (let i = 0; i < 3; i++) {
    const o = await order(true, someLines());
    await call('sales', 'POST', `/api/orders/${o.id}/cancel`, { reason: pick(['Customer changed supplier', 'Duplicate PO', 'Budget freeze']) });
    counts.cancelled++;
  }
  console.log('  orders:', counts);
  console.log('Packed orders are booked with the carrier by the background job runner (carrier-sim must be up).');
}

main().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
