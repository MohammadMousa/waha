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
| `org` | `waha` | Organization slug (`organizations.name`) |
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

**Constraint:** store names must not collide with directory names (`pages`, `images`, `products`, etc.). Validated on store creation.

---

## URL Resolution

### 3-segment path (org-level): `/resource/{org}/{dir}/{name}`

1. `SELECT id FROM organizations WHERE name = :org` → `orgId`
2. `SELECT id FROM stores WHERE organization_id = :orgId AND name = :org` → `storeId` (global store has same name as org)
3. `SELECT id FROM resource_directories WHERE store_id = :storeId AND name = :dir` → `dirId`
4. `SELECT resource_id FROM resource_assets WHERE directory_id = :dirId AND name = :name` → `resourceId`
5. Serve bytes with ETag + long-cache headers.

### 4-segment path (branch-level): `/resource/{org}/{branch}/{dir}/{name}`

1. `SELECT id FROM organizations WHERE name = :org` → `orgId`
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
| `store_id` | bigint FK → stores | owning store |
| `name` | varchar(100) | alphanumeric + hyphens/underscores |

**Unique constraint:** `(store_id, name)`

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
| `POST` | `/api/resources/{store}/directories/{dir}` | Upload asset (multipart `file`, optional `name`) |
| `PATCH` | `/api/resources/{store}/directories/{dir}/{name}/move` | Move asset to another directory |
| `PATCH` | `/api/resources/{store}/directories/{dir}/{name}/rename` | Rename asset |
| `DELETE` | `/api/resources/{store}/directories/{dir}/{name}` | Delete asset |

The `{store}` segment in admin paths is the store slug (`stores.name`). For org-level (global) resources use the org's global store slug (same as the org name). The response from upload includes `url` — the correct public URL for the asset.

---

## Decisions

| # | Decision |
|---|----------|
| 1 | **Org name = global store name.** The global store for an org has the same slug as the org (e.g., org `waha` → global store `waha`). This makes 3-segment URLs natural: `/resource/waha/pages/X` reads as "org waha, pages directory, file X". |
| 2 | **Branch names must not collide with directory names.** The 3-segment vs 4-segment distinction relies on the second segment being unambiguously a branch name or a directory name. Store creation must reject names that match common directory names (`pages`, `images`, `products`, etc.). |
| 3 | **No draft/publish workflow.** Last-write-wins. Use a `drafts` directory for staging. |
| 4 | **No versioning.** Re-upload to update. |
| 5 | **Content-addressed deduplication.** Same bytes → same resource row. Multiple asset entries can point to the same resource id. |

---

*Last updated: 2026-09-07*
