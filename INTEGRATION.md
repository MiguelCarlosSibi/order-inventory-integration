# LegacySupply Integration Notes

## Product mapping

| Our product ID | Our name            | LegacySupply SupplierSku | PackSize |
|----------------|---------------------|--------------------------|----------|
| P100           | Wireless Mouse      | SGU-4425                 | 12       |
| P200           | Mechanical Keyboard | SGU-8430                 | 24       |
| P300           | USB-C Hub           | SGU-7316                 | 20       |

Values come from `GET /catalog` for our Client ID. They live only inside the supplier module (`ProductCatalogMapping`), so Order and Inventory never see a SKU or pack size.

## Sessions

- `POST /auth/token` with our ClientId and ApiKey returns a `SessionToken`. Every other request sends it in the `X-LS-Session` header.
- **Measured lifetime: about 2 minutes.** I called `GET /catalog` every 20 seconds with one token. It was accepted at 102 s and rejected at 123 s with `E-AUTH-07`, so the session most likely expires at 120 s.
- The adapter (`LegacySupplyClient`) keeps the token in memory. When LegacySupply answers 401, it clears the token, signs in again, and repeats the call once. No token is ever pasted by hand. The self-check page recorded 11 sign-ins and 6 requests that arrived with an expired session, and all of them recovered.

## Errors actually received

| Code      | HTTP | What actually caused it |
|-----------|------|-------------------------|
| E-AUTH-01 | 401  | Signing in with the wrong ClientId/ApiKey. My PowerShell variables held placeholder text instead of the real values. |
| E-AUTH-02 | 401  | Calling `GET /catalog` without the `X-LS-Session` header. |
| E-AUTH-07 | 401  | Using a session token older than about 2 minutes (seen at 123 s in my measurement, and by the app after idle periods). |
| E-SKU-02  | 422  | Ordering a SupplierSku that is not in our catalog (I sent `NOPE-0000` in a manual probe). |
| E-QTY-11  | 422  | Ordering with `Qty` 0. Only whole numbers 1 to 99 are valid. |
| E-PO-04   | 404  | Asking for a PO number that does not exist (`PO-000000`). |
| E-QRY-06  | 400  | Calling `GET /purchase-orders` without the required `buyerRef` query parameter. |

## Qty and Uom, in my own words

`Qty` is how many **cases** we order, a whole number from 1 to 99. It is not our own unit count. `Uom` is the unit LegacySupply counts in, and for our items it is `CS` (case). `PackSize` from the catalog says how many of our single units are in one case, and it is different for every product.

Worked example: Wireless Mouse (P100, SKU SGU-4425, PackSize 12). Stock dropped to 4, so we reorder up to double the threshold (10), which needs 6 units. We send `Qty = ceil(6 / 12) = 1`, and LegacySupply ships 1 case = 12 mice. We round **up** so we never order too few. When the order was delivered, Inventory went from 4 to 16 (+12). The same happened for P200 (4 to 28, +24) and P300 (4 to 24, +20). Inventory is restocked with cases x PackSize, which is the amount that actually arrived, and not the 6 we asked for.

## Unexpected status codes

The manual lists only 10 (Accepted), 20 (Picking), 30 (Shipped), 40 (Delivered). During testing LegacySupply reported **StatusCode 90** for `PO-100017` (BuyerRef RO-5), and the self-check page counted it as a cancelled order.

Decision: any code we do not recognize maps to our own `FAILED` status and logs a warning (`SupplierGatewayImpl.mapStatus`). A `FAILED` order is no longer polled, no delivered event is published (so nothing is restocked), and the row stays visible in `supplier_orders` for a person to check. I chose this over leaving the order in its old status because that would poll it forever and never show the problem. RO-5 is the example: it shows as `FAILED` and Inventory was not changed for it.

## Reliability and quota

- Timeouts: 3 seconds connect and read.
- Retries: at most 3 attempts per call, backoff 400 ms then 800 ms. A 429 (quota exceeded) is not retried immediately, and the scheduled jobs try again later.
- Duplicates: each reorder gets its `X-Request-Id` when its `supplier_orders` row is created, and reuses it across retries and restarts. `BuyerRef` is `RO-` plus the row id, so it is unique per reorder. The self-check page shows 0 duplicates.
- No lost reorders: the row is saved as PENDING (committed) before any call is made, and a scheduled job resends PENDING rows every 60 seconds.
- The low-stock listener runs after the order transaction commits and on a background thread, so a slow or down supplier never blocks or rolls back a customer order.
- Quota: delivery tracking runs every 60 seconds, with one status call per open order. The self-check page shows 37 status checks and 0 rate-limited.