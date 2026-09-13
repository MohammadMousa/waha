# Resource Management

---

## Overview

Resources are binary or text assets (images, HTML pages, fonts, etc.) owned by a store and organized into directories. They are served directly from the database over a clean URL scheme, eliminating dependency on external file storage.

Landing pages are not a separate concept — they are resources with `mime_type: text/html`, served and cached identically to images.

---

## URL Scheme

### Public (no auth)

```
GET /resource/{org}/{directory}/{name}           ← org-level resource
GET /resource/{org}/{branch}/{directory}/{name}  ← branch-level resource
```
Notes:
- {org} >> organization.slug
- {branch} >> store.name
- {directory} >> resource_directory.name
- {name} >> resource.fileName [including ext]
- public url is used only with pages [html] and inner images, not products/categories/stores avatar resources.

| Segment | Example | Description |
|---------|---------|-------------|
| `org` | `waha` | Organization slug (`organizations.slug`) |
| `branch` | `north-branch` | Branch store slug (`stores.name`), present only for branch-level resources |
| `directory` | `pages` | Directory name (alphanumeric + hyphens/underscores) |
| `name` | `KIOSK_LANDING.html` | Asset filename including extension |

**Examples:**
```
/resource/waha/pages/KIOSK_LANDING.html          ← global kiosk page (all branches)
/resource/waha/images/logo.png                   ← org-level image
/resource/waha/north-branch/pages/KIOSK_LANDING.html  ← branch-specific override
/resource/waha/north-branch/images/banner.jpg    ← branch-specific image
```

The two shapes are told apart purely by segment count — two distinct route templates, matched before any lookup runs. A branch's name is never compared against its org's slug to decide which shape applies, so a branch is free to be named anything, including something that happens to match the org's slug.

---

## URL Resolution

### 3-segment path (org-level): `/resource/{org}/{dir}/{name}`

1. `SELECT id FROM organizations WHERE slug = :org` → `orgId`
2. `SELECT id FROM resource_directories WHERE organization_id = :orgId AND store_id IS NULL AND name = :dir` → `dirId`
3. `SELECT resource_id FROM resource_assets WHERE directory_id = :dirId AND name = :name` → `resourceId`
4. Serve bytes with ETag + long-cache headers.

Org-level directories are owned directly by the organization — `store_id IS NULL` — not by a store that happens to share the org's name. That's the only signal for "this is global"; it's never inferred by comparing a name or slug against anything.

### 4-segment path (branch-level): `/resource/{org}/{branch}/{dir}/{name}`

1. `SELECT id FROM organizations WHERE slug = :org` → `orgId`
2. `SELECT id FROM stores WHERE organization_id = :orgId AND name = :branch` → `storeId`
3. `SELECT id FROM resource_directories WHERE store_id = :storeId AND name = :dir` → `dirId`
4. `SELECT resource_id FROM resource_assets WHERE directory_id = :dirId AND name = :name` → `resourceId`
5. Serve bytes with ETag + long-cache headers.

Always validate the full chain. No partial resolution.

---

## Database Model

### `resources`
| Column | Type | Notes |
|--------|------|-------|
| `id` | bigint PK | |
| `filename` | varchar(255) | original upload filename |
| `mime_type` | varchar(100) | e.g. `image/jpeg`, `text/html` |
| `size_bytes` | bigint | |
| `sha256` | varchar(64) | content hash — deduplication key and ETag value |

Content-addressed: same bytes → same `sha256` → same row. Multiple assets can point to the same resource.

### `resource_data`
| Column | Type | Notes |
|--------|------|-------|
| `resource_id` | bigint PK, FK → resources | 1-to-1 |
| `data` | mediumblob | raw bytes |

Split table so metadata queries never load binary content.

### `resource_directories`
| Column | Type | Notes |
|--------|------|-------|
| `id` | bigint PK | |
| `organization_id` | bigint FK → organizations | owning org |
| `store_id` | bigint NULL, FK → stores | owning branch — **NULL means org-level (global)** |
| `name` | varchar(100) | alphanumeric + hyphens/underscores |
| `store_id_key` | bigint, generated (`COALESCE(store_id, 0)`) | uniqueness helper, see below |

**Unique constraint:** `(organization_id, store_id_key, name)`. `store_id` is nullable so a directory can be owned by the org itself with no branch at all; `store_id_key` exists only because MySQL's own `UNIQUE` treats two `NULL`s as distinct, which would let one org create the same "global" directory name twice — the generated column collapses `NULL` to `0` so uniqueness actually holds for org-level rows too.

### `resource_assets`
| Column | Type | Notes |
|--------|------|-------|
| `id` | bigint PK | |
| `directory_id` | bigint FK → resource_directories | |
| `name` | varchar(255) | asset filename |
| `resource_id` | bigint FK → resources | points to the binary |

**Unique constraint:** `(directory_id, name)` — same filename allowed in different directories or stores.

---

## Caching

- `ETag` is set to `"{sha256}"`.
- `Cache-Control: public, max-age=31536000, immutable` (1 year).
- On update, `sha256` changes → cache miss → fresh fetch.

---

## System Properties

| Key | Default | Description |
|-----|---------|-------------|
| `resource.max_size_bytes` | `2097152` (2 MB) | Upload size limit |

---

## Permissions

| Permission | Who | Description |
|-----------|-----|-------------|
| `EDIT_RESOURCES` | OPERATOR and above | Upload, update, delete resources and manage directories |

---

## API Endpoints

### Public (no auth)
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/resource/{org}/{directory}/{name}` | Serve org-level resource |
| `GET` | `/resource/{org}/{branch}/{directory}/{name}` | Serve branch-level resource |

### Admin (requires `EDIT_RESOURCES`)
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/resources/{store}/directories` | List directories for a store |
| `POST` | `/api/resources/{store}/directories` | Create directory (`{"name": "pages"}`) |
| `GET` | `/api/resources/{store}/directories/{dir}` | List assets in directory |
| `POST` | `/api/resources/{store}/directories/{dir}` | Upload asset (multipart `file`, optional `name`) — creates `{dir}` first if it doesn't exist yet |
| `PATCH` | `/api/resources/{store}/directories/{dir}/{name}/move` | Move asset to another directory |
| `PATCH` | `/api/resources/{store}/directories/{dir}/{name}/rename` | Rename asset |
| `DELETE` | `/api/resources/{store}/directories/{dir}/{name}` | Delete asset |

`{store}` is resolved as a real branch's store name first; if nothing matches, it's tried as an organization's slug, which scopes every call in that request to that org's global (`store_id IS NULL`) resources instead of a branch. The response from upload includes `url` — the correct public URL for the asset, in whichever of the two shapes matches the resolved scope.

### Landing pages (`/api/landing`, requires session auth)
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/landing/{pageKey}?storeId=` | Resolve a landing page's resource URL + content hash |

`storeId` is optional and only ever means "also check this branch for a local override" — omitting it (or the branch having none) resolves straight to the organization's global page. The organization itself always comes from the authenticated caller's own session, never from `storeId`, so a caller with no store in scope at all can still reach its org's global page.

---

## Decisions

| # | Decision |
|---|----------|
| 1 | **Global scope is `store_id IS NULL`, full stop.** Never inferred from a name or slug matching anything — an org's slug and a branch's name are independent strings that can legitimately coincide, and once did in production, which is exactly what broke resolution before this was fixed. If you need to check "is this global", check for `NULL`, not equality. |
| 2 | **A store name changing, or an org's slug changing, does not retroactively update anything.** Absolute `/resource/...` links already baked into saved resource bytes (HTML pages especially) keep whatever identifier was current when they were saved. The admin landing/ad editors re-derive and heal those links against the *current* scope every time a page is reopened and re-saved, but a page that's never reopened stays on the old identifier indefinitely — this isn't a background migration, and there isn't one. |
| 3 | **No draft/publish workflow.** Last-write-wins. Use a `drafts` directory for staging. |
| 4 | **No versioning.** Re-upload to update. |
| 5 | **Content-addressed deduplication.** Same bytes → same resource row. Multiple asset entries can point to the same resource id. |

---

## Known limitations

- `ResourceAdminController`'s branch-name lookup (resolving `{store}` to a store id) is not scoped by organization — it matches by name across *all* orgs. Harmless while a single organization exists; a real collision risk the moment a second org is added. Not fixed yet.
- `waha_admin`'s products, categories, and employee-avatar screens still key their shared image bucket off a hardcoded `'waha'` store name rather than the org-relative scoping described above. Same underlying pattern this doc now warns against, just not yet migrated for those resource types.

---

*Last updated: 2026-09-12*
