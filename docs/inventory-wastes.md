# Inventory wastes

Stock taken out because of expiry, damage, spoilage, missing items and so on. It is an inventory activity, not a sale or an
expense: no price, no cost, no VAT, no invoice, no payment. Saving a waste lowers the branch stock (`store_inventory`) in the
same transaction, with the same rule as transfers and sales: **stock may go negative**, nothing is blocked.

A saved waste is never edited or deleted, and the daily cleanup never removes it. There is no correction in this version.
Quantities are whole numbers. Products only (no units, ingredients or options).

## Permissions

| Permission | Gives |
|------------|-------|
| `VIEW_WASTES` | list, open, export |
| `CREATE_WASTES` | record a waste (the dashboard import also uses this) |

The organization owner has both by default. Anyone else gets them from a role the owner edits. They are checked per branch:
a person only sees and records wastes for branches where their role gives the permission (the super admin sees all).
Dashboard sessions only.

## Reasons

`GET /api/admin/inventory/wastes/reasons` returns `[{ code, nameEn, nameAr }]`: `EXPIRED`, `DAMAGED`, `SPOILED`, `MISSING`, `OTHER`.

## Calls

| Call | Result |
|------|--------|
| `GET /api/admin/inventory/wastes?branchId=&reason=&employeeId=&productId=&search=&from=&to=&page=0&size=20` | `{ items, totalCount, page, size, summary }`, newest first, size up to 200, `from` and `to` are dates like `2026-11-17` (inclusive). `search` matches the waste number (id) or notes. `summary` = `{ totalQuantity, byReason: [{ reason, wastes, quantity }] }` for the same filters |
| `GET /api/admin/inventory/wastes/{id}` | The waste with its lines |
| `POST /api/admin/inventory/wastes` | Records a waste (below). 201 with the waste |
| `GET /api/admin/inventory/wastes/export?format=csv|xlsx` + the same filters | A file, one row per waste line: Waste no., Date, Branch, Reason, Employee, Created by, Notes, Barcode, Product (EN), Product (AR), Quantity |

List row: `{ id, occurredAt, branchId, branchName, reason, employeeId, employeeName, createdBy, createdByName, notes, itemCount, quantity }`.
The detail adds `createdAt` and `items: [{ productId, barcode, nameEn, nameAr, quantity }]`.

### Record a waste

```json
{
  "branchId": 1,
  "reason": "EXPIRED",
  "items": [ { "productId": 5, "quantity": 3 }, { "productId": 7, "quantity": 1 } ],
  "date": "2026-11-17T09:30:00Z",
  "employeeId": 12,
  "notes": "optional",
  "clientRef": "a-uuid-made-by-the-caller"
}
```

- `branchId`, `reason` and `items` are required; `date` defaults to now (not in the future), `employeeId` and `notes` are optional.
- `quantity` is a whole number above 0; the same product on two lines is added up; at most 500 lines.
- The branch must be an active branch of the organization, the products must belong to it, the employee too.
- `clientRef` (up to 64 letters, digits, `-` or `_`) makes a retry safe: saving the same `clientRef` again returns the first waste
  with `"alreadySaved": true` and changes nothing. The dashboard should send one per waste when it imports a file.
- Everything is saved or nothing is. Send an organization (`X-Organization-Id`) when you are a super admin.
- 400 `{ message }` for a bad value, 403 without the permission for that branch.

## Import

The server has no import call. The dashboard reads the file (CSV or XLSX) in the browser, shows it in a grid where rows are
checked and fixed, then calls the create call once per waste (one waste = one branch, reason and date, many lines).
Use a `clientRef` per waste so a repeated send cannot save it twice; a failed waste can be fixed and sent again.

## Audit

Each waste is recorded in the audit history as `WASTE_CREATED` (entity type `WASTE`).
