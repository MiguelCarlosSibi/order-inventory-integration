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