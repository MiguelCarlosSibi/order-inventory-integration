# Reflection

## Question 1

> PO-100017 (BuyerRef "RO-5") ended with StatusCode 90, which is not in the documentation. How did you work out what it means, and what does your system now do with the stock that will never arrive?

The manual lists only 10, 20, 30 and 40, so when I called `GET /purchase-orders/PO-100017` and it returned StatusCode 90 (SupplierSku SGU-8430, Qty 1 CS, BuyerRef RO-5), there was nothing in the documentation to look up. I worked out that 90 means cancelled by combining evidence: the self-check page's "Noticed a cancelled order" check counted exactly one cancelled order, and PO-100017 was the only one of my orders with an undocumented code, while my other orders moved normally through 10 to 40. In my code, `SupplierGatewayImpl.mapStatus` sends any unrecognised code to our own `FAILED` status and logs a warning, so RO-5 shows as `FAILED` in `supplier_orders`. `DeliveryTrackingJob` only polls ACCEPTED, PICKING and SHIPPED orders, so it stopped polling RO-5, and because no delivered event was published, Inventory was never restocked for it and the stock that will never arrive is never counted. The system does not automatically replace the cancelled order: the `FAILED` row stays as a record for a person to review, and the next order that pushes that product below the threshold triggers a fresh reorder.

## Question 2

> LegacySupply never tells you how long a session lasts. Measure your session lifetime from your own logs, state the number, and explain how your adapter decides when to sign in again.

I measured it with a PowerShell loop that called `GET /catalog` every 20 seconds using one token issued at 11:37:06Z: it was accepted at 20, 41, 61, 82 and 102 seconds and rejected at 123 seconds with E-AUTH-07, so the lifetime is about 2 minutes (most likely 120 seconds). My adapter does not watch a clock; `LegacySupplyClient` keeps the token in memory and only signs in when it has none. When any call gets a 401 (E-AUTH-02, E-AUTH-03 or E-AUTH-07), `withSession` clears the token, calls `ensureSession()` to sign in again, and repeats the same call once. If the call still fails after that, it is treated as "unavailable", so the normal retry and PENDING logic takes over. The self-check page confirms this works: it recorded 11 sign-ins and 6 requests that arrived with an expired session, all of which recovered. The trade-off is that one request is wasted each time a session expires, and refreshing a little before 100 seconds would avoid that.

## Question 3

> The catalog reports PackSize and orders report Uom "CS". Using one of your own orders, show the arithmetic from "units your Inventory needed" to the Qty you sent, and to the units your Inventory received on delivery.

My P100 (Wireless Mouse) order left stock at 4, which is below the threshold of 5, and the listener's rule is target stock = 2 x threshold = 10, so Inventory needed 10 - 4 = 6 units (stored as `units = 6` in `supplier_orders`). The catalog gives PackSize 12 for SGU-4425 and orders are counted in Uom "CS" (cases), so `ProductCatalogMapping.unitsToCases` calculates Qty = ceil(6 / 12) = 1, and I sent Qty 1 (stored as `cases = 1`). LegacySupply ships whole cases, so on delivery 1 x 12 = 12 units arrived, and `DeliveryTrackingJob` publishes that number (via `casesToUnits`) in `SupplierOrderDeliveredEvent`. Inventory went from 4 to 16, a gain of 12 and not 6, and the extra 6 units are the cost of rounding up. The same rule gave P200 (SGU-8430, PackSize 24) 4 to 28 (+24), and P300 (SGU-7316, PackSize 20) 4 to 24 (+20).




# Lab 4 – Marketplace reflection

## Question 1

> Tiangge order TG-3QG2TD (2 x P200) was accepted at 17:09:09. At that moment your last published stock for P200 was 1, and the stock Tiangge worked out from your own decisions, cancellations and deliveries was 1. Where did your application's stock figure come from, and why did it disagree?

The figure my application used came from the `stock` column of the `inventory` table in Postgres, which only my Inventory module changes: `OrderService.placeOrder` read it through `InventoryService.checkAvailability`, and `reserve` lowers it with the atomic `InventoryRepository.tryDecrement` (`update ... where stock >= :q`). Tiangge's figure is a second copy that exists only because `StockSyncListener` sends the number after each `InventoryChangedEvent`, so it is always a message behind my database. At 17:09:09 my Inventory held <<FILL: units of P200 at that moment>> because <<FILL: what raised the stock a moment earlier, e.g. "PO-xxxxxx was delivered" or "TG-xxxxxx was cancelled">>, but the update carrying that increase had not reached Tiangge yet, so Tiangge still held 1 and judged my acceptance of 2 as an oversell. The updates were slow because Tiangge's `PUT /stock` calls often fail with 503 or read timeouts and my sync runs on one background thread, and my earlier versions also published stock straight after an order committed, before its decision was delivered, and retried a failed update with its old body. I fixed this by rebuilding the figure from the database on every attempt, retrying every 500 ms, sending `stock + units reserved for decisions Tiangge has not received yet` (`ChannelStore.publishableStock`), and making `BackorderResolver` wait for the stock sync to catch up before it resolves a backorder.

## Question 2

> Event evt_6a7b04a38b09b0c8 (order TG-F48NSV) reached your application twice, as seq 533 and seq 534, and you processed it once. Show the code and the stored data that made the second delivery harmless, and explain what would happen if your application restarted between the two.

```java
// FeedPoller.handle()
if (store.eventSeen(ev.eventId())) {
    log.info("Duplicate event {} (order {}) ignored", ev.eventId(), ev.orderId());
    return;
}
// ... handlePlaced / handleCancelled ...
store.markEventSeen(ev);

// FeedPoller.handlePlaced()
if (store.find(tid).isPresent()) { /* "already handled", no second order */ return; }

// ChannelStore
select count(*) from channel_events where event_id = ?
insert into channel_events (event_id, seq, type, tiangge_order_id) values (?,?,?,?) on conflict (event_id) do nothing
insert into channel_orders (tiangge_order_id, shop_order_id, placed_at, decision) values (?,?,?,?)
```

Every feed event carries a unique `eventId`, and `FeedPoller.handle()` first calls `ChannelStore.eventSeen`, which counts rows in the `channel_events` table; for the second delivery of evt_6a7b04a38b09b0c8 it found one and logged `Duplicate event evt_6a7b04a38b09b0c8 (order TG-F48NSV) ignored`, so nothing more ran. An event is only written to `channel_events` (`event_id` is the primary key, inserted with `on conflict do nothing`) after it has been handled completely, so the table holds one row for seq 533 and none for seq 534. Behind that sits a second guard: `handlePlaced` looks the Tiangge order up in `channel_orders` (primary key `tiangge_order_id`, unique `shop_order_id`), and `placeAtomically` inserts the shop order and that row in one transaction, so a repeat can never create a second shop order. Both guards live in Postgres, not in memory, and so does the feed cursor (`channel_cursor.last_seq`). If the application restarted between the two deliveries, it would continue from the saved cursor and either read seq 534 or replay seq 533, and `eventSeen` or the `channel_orders` lookup would skip it because the first delivery's rows were already committed. If the restart came in the middle of the first delivery before its transaction committed, nothing would be stored and the order would be processed once on the replay, and if it came after the commit but before the event was marked seen, the `channel_orders` check would catch it, so in every case there is exactly one shop order.

## Question 3

> Order TG-F48NSV was backordered at 17:01:33 and accepted at 17:07:12, after PO-101264 was delivered at 17:06:58. Trace how the delivery reached your Inventory and what then resumed the backordered order.

At 17:01:33 `FeedPoller.handlePlaced` saw that P200 was short and called `SupplierGateway.ensureRestock`, LegacySupply accepted PO-101264 (`supplier_orders` row <<FILL: buyer_ref, e.g. RO-123>>), so `OrderService.placeOrder` saved the order as BACKORDERED and `channel_orders` recorded decision BACKORDERED for TG-F48NSV. `DeliveryTrackingJob` polls every open purchase order, and when `GET /purchase-orders/PO-101264` returned StatusCode 40 around 17:06:58 it mapped that to DELIVERED, saved it, and published a `SupplierOrderDeliveredEvent` with the product id and `casesToUnits(cases)` units, without ever importing Inventory. Inventory's `SupplierDeliveryRestockListener` receives that event and calls `InventoryService.restock`, which adds the units to `inventory.stock` and publishes an `InventoryChangedEvent`, which `StockSyncListener` turns into a stock update to Tiangge. Nothing in the supplier module calls the backorder directly; instead `BackorderResolver`, scheduled every 5 seconds, loads the unresolved backorders from `channel_orders` oldest first and calls `OrderService.fulfilBackorder(shopOrderId)`. That method checks every line, reserves all of them (all-or-nothing) and changes the order from BACKORDERED to CONFIRMED, after which the resolver sends ACCEPTED to Tiangge, marks the row resolved and publishes the reduced stock. That is why the order was accepted at 17:07:12, 14 seconds after the delivery: one polling interval of the tracker plus one tick of the resolver.
