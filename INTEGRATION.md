# LegacySupply Integration Notes

Everything below marked **TODO** requires an actual API key and live calls
against `https://legacysupply.onrender.com/api/v1` — it can't be filled in
without that, per the lab's own instructions ("measure it, the manual does
not say"). Run the discovery steps at the bottom of this file once you have
your key, then replace the TODOs.

## Product mapping

| Our product ID | Our name             | LegacySupply SupplierSku | PackSize |
|-----------------|-----------------------|----------------------------|----------|
| P100            | Wireless Mouse         | TODO                        | TODO     |
| P200            | Mechanical Keyboard    | TODO                        | TODO     |
| P300            | USB-C Hub              | TODO                        | TODO     |

Fill this from `GET /catalog` for your Client ID — every partner's catalog
is their own, so these will differ from the example in the manual
(`ABC-1234`). Once known, update the same values in
`backend/.../supplier/ProductCatalogMapping.java`.

## Sessions

- `POST /auth/token` with `ClientId` + `ApiKey` returns a `SessionToken` +
  `IssuedAt`. Every other call sends it back as the `X-LS-Session` header.
- **Measured session lifetime: TODO.** The manual only says sessions are
  "short-lived" and partners should re-authenticate "when theirs is no
  longer accepted" — it doesn't state a duration. To measure it: get a
  token, then call an authenticated endpoint (e.g. `GET /catalog`) at
  increasing intervals until you get `E-AUTH-07`. Record the interval that
  first failed here.

## Errors actually received

| Code | HTTP | What triggered it (TODO — fill in as you hit each one) |
|------|------|-----------------------------------------------------------|
| E-AUTH-01 | 401 | |
| E-AUTH-02 | 401 | |
| E-AUTH-03 | 401 | |
| E-AUTH-07 | 401 | |
| E-FMT-01  | 415 | |
| E-FMT-02  | 400 | |
| E-REF-05  | 400 | |
| E-SKU-02  | 422 | |
| E-QTY-11  | 422 | |
| E-IDEM-04 | 409 | |
| E-PO-04   | 404 | |
| E-QRY-06  | 400 | |
| E-RATE-03 | 429 | |
| E-SYS-50  | 503 | |
| E-SYS-99  | 503 | |

## Qty and Uom, in my own words

**TODO** — write this after placing at least one real order. From the
manual: `Qty` on a `PurchaseOrder` request is a whole number (1–99) in
LegacySupply's own unit of measure for that item (`Uom`, e.g. `CS` =
cases), not in our own per-unit inventory count. `PackSize` from the
catalog tells you how many of our units are in one of theirs. Worked
example (TODO — fill in with a real SupplierSku/PackSize once known):

> If `PackSize` for SupplierSku `TODO` is `12` and Inventory needs 20
> units restocked, the ACL requests `Qty: 2` (`ceil(20 / 12)`), which
> LegacySupply fulfills as 24 units (2 cases × 12) — the extra 4 units are
> the cost of rounding up rather than under-ordering.

## Unexpected StatusCode handling

If `DeliveryTrackingJob` receives a `StatusCode` it doesn't recognize (not
`10/20/30/40`), it maps the order to our own `FAILED` status rather than
leaving it silently stuck in its previous state, and logs a warning
(`SupplierGatewayImpl.mapStatus`) so it's visible rather than swallowed. We
chose `FAILED` over "leave it as-is" because an order polling loop that
never terminates on an unrecognized code would poll forever and never
surface the problem to a human.

## Discovery steps to run once LS_API_KEY is provided

```bash
# 1. Reachability, no session needed
curl https://legacysupply.onrender.com/api/v1/ping

# 2. Authenticate
curl -X POST https://legacysupply.onrender.com/api/v1/auth/token \
  -H "Content-Type: application/xml" \
  -d "<AuthRequest><ClientId>YOUR_STUDENT_ID</ClientId><ApiKey>YOUR_KEY</ApiKey></AuthRequest>"

# 3. Catalog (use the SessionToken from step 2)
curl https://legacysupply.onrender.com/api/v1/catalog \
  -H "X-LS-Session: YOUR_TOKEN" -H "Accept: application/xml"

# 4. Place a test order
curl -X POST https://legacysupply.onrender.com/api/v1/purchase-orders \
  -H "Content-Type: application/xml" -H "X-LS-Session: YOUR_TOKEN" \
  -H "X-Request-Id: test-001" \
  -d "<PurchaseOrder><SupplierSku>REAL_SKU</SupplierSku><Qty>1</Qty><BuyerRef>RO-test-1</BuyerRef></PurchaseOrder>"

# 5. Track it
curl https://legacysupply.onrender.com/api/v1/purchase-orders/PO-NUMBER-FROM-STEP-4 \
  -H "X-LS-Session: YOUR_TOKEN"
```
