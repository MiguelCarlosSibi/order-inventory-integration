# Order & Inventory Integration — Lab 2: Extending the Modular Monolith

Builds on Lab 1's Order/Inventory modular monolith (Spring Boot, one deployable,
shared Supabase/Postgres database). Lab 2 adds: multi-item orders with
all-or-nothing rollback, order cancellation with restock, read endpoints for
a live dashboard, and a third module — Notification — that reacts to Order
and Inventory activity purely through in-process domain events, never a
direct method call.

```
order-inventory-integration/
├── backend/    Spring Boot app (Java 17, Maven)
│   └── src/main/java/edu/cit/sibi/
│       ├── shop/          Order module (edu.cit.sibi.shop)
│       ├── inventory/     Inventory module (edu.cit.sibi.inventory)
│       └── notification/  Notification module (edu.cit.sibi.notification) — new in Lab 2
├── frontend/   React + Vite app
├── sql/        schema.sql — full rebuild: inventory, orders, order_items, notifications
└── docs/       Network tab evidence screenshots
```

## 1. Supabase setup

1. Create a free project at [supabase.com](https://supabase.com) (skip if you
   already have the Lab 1 project — this schema rebuilds on top of it).
2. Open **SQL Editor → New query**, paste the contents of
   [`sql/schema.sql`](sql/schema.sql), and run it. **This drops and recreates
   `inventory`, `orders`, and adds `order_items` and `notifications` from
   scratch** — the `orders` table's shape changed (no more single
   `product_id`/`quantity` columns; those moved into `order_items` to support
   multiple line items per order), so any orders from Lab 1 testing are gone
   after this runs. Inventory is reseeded to the original three products.
3. Grab your JDBC connection string from **Project Settings → Database →
   Connection string**. If your network doesn't support IPv6, use the
   **Session pooler** string instead of the direct connection — see the note
   at the bottom of this section.

## 2. Backend — environment variables

Same three as Lab 1, unchanged:

```
SUPABASE_DB_URL=jdbc:postgresql://<host>:5432/postgres
SUPABASE_DB_USERNAME=postgres   (or postgres.<project-ref> if using the pooler)
SUPABASE_DB_PASSWORD=<your-database-password>
```

Run it the same way as before (IntelliJ run configuration, or
`./mvnw spring-boot:run` from `backend/` with the vars exported).

## 3. Frontend

```bash
cd frontend
npm install
npm run dev
```

Opens on `http://localhost:5173`.

## 4. New API surface

| Endpoint | Purpose |
|---|---|
| `POST /api/orders` | Place a multi-item order: `{ items: [{ productId, quantity }, ...] }` → `{ orderId, status, reason, items: [{ productId, quantity, outcome }], inventory }` |
| `GET /api/orders` | Order history: every order with its status, reason, and line items |
| `POST /api/orders/{orderId}/cancel` | Cancel a CONFIRMED order and restock every line item; 404 if the order doesn't exist, 409 if already cancelled |
| `GET /api/inventory` | Live stock for every product (unchanged from Lab 1) |
| `GET /api/notifications` | Activity feed: order confirmations, rejections, and low-stock alerts, newest first |

### Multi-item, all-or-nothing

`OrderService.placeOrder` validates every line item against current stock
**before** reserving anything (`InventoryService.checkAvailability`, a
read-only sibling of `reserve`). If any single item would fail, the whole
order is saved as `REJECTED` and nothing is decremented — each item's
`outcome` in the response shows either `"OK"` (it would have succeeded) or
its specific failure reason, so you can see exactly which item sank the
order. Only when every item passes does the app loop back through and
actually call `reserve()` for each one, inside the same `@Transactional`
method that saves the order — so a database-level failure partway through
the reserve loop rolls back both the stock changes and the order row
together.

### Low-stock alerts

After any successful `reserve()`, if that product's remaining stock drops
below 5 (`OrderService.LOW_STOCK_THRESHOLD`), a `LowStockEvent` is published
alongside the order's own event. The frontend's inventory table highlights
any row below that threshold in red.

### Notification module boundary

`OrderService` publishes `OrderPlacedEvent` / `OrderRejectedEvent` /
`LowStockEvent` via Spring's `ApplicationEventPublisher`. It has no import
from `edu.cit.sibi.notification` anywhere in its code. `NotificationEventListener`
listens with plain `@EventListener` (not `@Async` — see the reflection below
for why) and is the only thing in the Notification module that imports
anything from Order or Inventory, and even then only the three event
record types in `edu.cit.sibi.shop.event`. Order and Inventory never import
anything from `notification`.

## 5. Network tab evidence

_Screenshots go here — replace this section with your own captures, saved
into `docs/`._

- **Multi-item order, all CONFIRMED:** add 2+ products to the cart, all
  within stock, submit. Screenshot the `POST /api/orders` request/response
  showing `"status": "CONFIRMED"` and every item's `outcome: "OK"`.
  → `docs/network-multi-confirmed.png`
- **Multi-item order, one item over stock → whole order REJECTED:** add one
  product within stock and one over stock (e.g. USB-C Hub, seeded at 0).
  Screenshot the response showing `"status": "REJECTED"`, the failing item's
  specific reason, and the other item still showing `"OK"` (proving it was
  validated but never reserved).
  → `docs/network-multi-rejected.png`

## 6. Reflection (300–500 words)

**1. In-process vs. network integration.** Calling `InventoryService.reserve()`
in-process gets several things for free that disappear the moment Order and
Inventory are split across a network. The biggest is transactional
atomicity: `OrderService.placeOrder` runs inside one `@Transactional`
method, sharing a single JDBC connection and database transaction with
every `reserve()` call and the order-row insert. If anything fails partway
through, Spring rolls back the whole thing automatically. Split into two
services, each `reserve()` call becomes its own remote transaction against
its own database — there's no shared transaction to roll back. I'd need a
saga: track which items succeeded, and if a later item fails, issue
compensating "restock" calls to undo the earlier ones, accepting a brief
window where a customer might see a confirmed item before compensation
finishes. I'd also lose the guarantee that a call actually happens — a
network call can time out or fail silently in ways a local method call
can't, so I'd need retries, idempotency keys (so a retried compensation
doesn't double-restock), and a decision about what "temporarily
unavailable" inventory means to the caller. Latency also changes: what's a
negligible method call becomes a network round-trip repeated per line item.

**2. Why `InventoryServiceImpl` is package-private.** Marking the
implementation class package-private (not `public`) means nothing outside
`edu.cit.sibi.inventory` can import it, hold a reference to its concrete
type, or call any method that isn't declared on the `InventoryService`
interface. Spring can still find and wire it via component scanning, since
that uses reflection and ignores visibility — but `OrderService` is
compiler-enforced to depend only on the interface. If `InventoryServiceImpl`
were public, nothing would stop `shop` from importing it directly,
downcasting an `InventoryService` reference to the impl, or calling
implementation details never meant to be exposed (e.g., internal helper
methods). That would quietly turn the module boundary into a suggestion
rather than a rule — the compiler wouldn't catch a violation, only code
review would, and by then it's already coupled. Package-private visibility
makes the module's public contract exactly the interface, nothing more,
and lets the implementation change freely as long as the interface holds.

**3. Extracting Inventory.** Inventory is the harder module to extract,
unlike Notification. It sits in the critical path of every order, has
referential integrity via foreign keys from `order_items`, and — per
question 1 — extracting it immediately forces the saga/compensating-
transaction complexity onto the whole checkout flow. I'd only extract it
once the atomicity problem has a real answer: an idempotent
`reserve`/`restock` API with request IDs, a saga coordinator (or
orchestration in `OrderService` itself) to manage multi-step reservations,
and a decision about eventual consistency — briefly showing stale stock is
now possible. Code-wise: `InventoryService` becomes an HTTP/gRPC client
instead of an interface backed by a local `@Service`, `reserve()`/
`restock()` need timeout and retry handling, and the `inventory` table
moves to its own database, so `order_items`' foreign key to
`inventory.product_id` has to be dropped and validated at the application
layer instead.
