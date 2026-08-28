# Resource Management

> **Status:** design phase — implementation not started.

---

## Overview

Resources are binary or text assets (images, HTML pages, fonts, etc.) owned by a store and organized into directories. They are served directly from the database over a clean URL scheme, eliminating dependency on external file storage (Google Drive, cPanel, S3, etc.).

Landing pages are not a separate concept — they are resources with `mime_type: text/html`, served and cached identically to images.

---

## URL Scheme

```
GET /resource/{store}/{directory}/{name}
```

| Segment | Example | Description |
|---------|---------|-------------|
| `store` | `waha` | URL-safe unique store identifier |
| `directory` | `landing` | Admin-defined directory name (free-form, validated on creation) |
| `name` | `banner.jpg` | Resource filename including extension |

**Examples:**
```
/resource/waha/landing/home.html
/resource/waha/shared/logo.png
/resource/hrco/products/pizza.jpeg
/resource/waha/shared/logo.png    ← used inside an hrco landing page (cross-store)
```

Cross-store references are valid — a resource is always served from its owning store's namespace regardless of which page embeds it.

---

## Database Model

### `resources`
| Column | Type | Notes |
|--------|------|-------|
| `id` | bigint PK | |
| `store_id` | bigint FK → stores | owning store |
| `directory_id` | bigint FK → resource_directories | |
| `name` | varchar(255) | filename, validated on creation |
| `mime_type` | varchar(100) | e.g. `image/jpeg`, `text/html` |
| `content_hash` | varchar(64) | SHA-256 of content — used as ETag |
| `size_bytes` | int | enforced against system max (see System Properties) |
| `created_at` | datetime | |
| `updated_at` | datetime | |

**Unique constraint:** `(store_id, directory_id, name)` — same name allowed in different directories or stores.

### `resource_data`
| Column | Type | Notes |
|--------|------|-------|
| `resource_id` | bigint PK, FK → resources | 1-to-1 |
| `data` | mediumblob | raw bytes — max ~16MB at MySQL level, capped by system property |

Split table so metadata queries never load binary content.

### `resource_directories`
| Column | Type | Notes |
|--------|------|-------|
| `id` | bigint PK | |
| `store_id` | bigint FK → stores | owning store |
| `name` | varchar(100) | free-form, validated on creation (alphanumeric + hyphens) |
| `created_at` | datetime | |

**Unique constraint:** `(store_id, name)`

---

## URL Resolution

For `GET /resource/waha/landing/banner.jpg`:

1. `SELECT id FROM stores WHERE name = 'waha'` → `store_id`
2. `SELECT id FROM resource_directories WHERE store_id = ? AND name = 'landing'` → `directory_id`
3. `SELECT id, mime_type, content_hash FROM resources WHERE store_id = ? AND directory_id = ? AND name = 'banner.jpg'` → resource
4. `SELECT data FROM resource_data WHERE resource_id = ?` → bytes
5. Respond: `200 OK`, `Content-Type: {mime_type}`, `ETag: "{content_hash}"`, `Cache-Control: public, max-age=31536000`

Always validate the full store → directory → resource chain. Never resolve by `directory_id + name` alone.

---

## Caching

- `ETag` is set to `content_hash` (SHA-256).
- `Cache-Control: public, max-age=31536000` (1 year) — content is immutable under a given name+hash.
- On update, `content_hash` changes → browser/client fetches fresh copy on next request.
- HTML and images cache independently — updating a landing page does not force image re-downloads.

---

## System Properties

| Key | Default | Description |
|-----|---------|-------------|
| `resource.max_size_bytes` | `2097152` (2 MB) | Upload size limit, enforced server-side before write |

Stored in `system_properties` table. Adjustable by `SUPER_ADMIN` without redeployment.

---

## Permissions

### Phase 1 — Simple
| Permission | Description |
|-----------|-------------|
| `EDIT_RESOURCES` | Upload, update, delete resources and manage directories |

Granted to: `SUPER_ADMIN`, `ADMIN`, and any `REGISTERED` user explicitly granted it.

### Phase 2 — Granular (future)
| Permission | Description |
|-----------|-------------|
| `VIEW_RESOURCES` | Browse resource explorer (read-only) |
| `EDIT_RESOURCES` | Upload and update resource content |
| `DELETE_RESOURCES` | Delete resources |
| `MANAGE_DIRECTORIES` | Create, rename, delete directories |
| `EDIT_PAGES` | Edit landing page HTML resources |
| `PUBLISH_PAGES` | Make a landing page live (if draft/publish workflow added) |

---

## Resource Explorer (UI)

A dedicated admin screen inside `waha_platform` for managing resources — browsing directories, uploading files, previewing HTML pages and images.

### Entry point (phase 1)

A button in the **Settings screen → Admin sector** opens the Resource Explorer. No navigation menu needed until the full admin menu is built.

### Layout

Two-panel file-explorer layout:

```
┌─────────────────┬──────────────────────────────────────┐
│  Directories    │  Files                               │
│                 │                                      │
│  landing        │  [thumbnail] home.html               │
│  products    ←  │  [thumbnail] banner.jpg              │
│  shared         │  [thumbnail] logo.png                │
│  drafts         │                                      │
│                 │                                      │
│  [+ New dir]    │                          [+ Upload]  │
└─────────────────┴──────────────────────────────────────┘
```

- Selecting a directory loads its resources in the main panel
- Clicking a resource opens a preview (image inline, HTML in iframe) with options: copy URL, delete
- Upload button adds a file to the current directory

### Access points from other admin screens

When an admin edits a product, category, or landing page and needs to attach an image, they get three options:

| Option | Description |
|--------|-------------|
| **Resource Manager** | Pick an existing resource from the Explorer |
| **Camera** | Capture a new photo (mobile / tablet) |
| **Gallery** | Upload from device gallery / file system |

Camera and Gallery uploads go directly into a resource in the current store's selected directory before being linked to the product/category.

---

## API Endpoints

### Public (no auth)
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/resource/{store}/{directory}/{name}` | Serve resource bytes |

### Admin (requires `EDIT_RESOURCES` or specific phase-2 permission)
| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/resources/{store}/directories` | List directories for a store |
| `POST` | `/api/resources/{store}/directories` | Create directory |
| `GET` | `/api/resources/{store}/{directory}` | List resources in directory |
| `POST` | `/api/resources/{store}/{directory}` | Upload resource (multipart) |
| `PUT` | `/api/resources/{store}/{directory}/{name}` | Replace resource content |
| `DELETE` | `/api/resources/{store}/{directory}/{name}` | Delete resource |

---

## Decisions

| # | Decision |
|---|----------|
| 1 | **No draft/publish workflow.** Last-write-wins. Admins manage work-in-progress by saving to a `drafts` directory (or any name they choose); when ready, they copy/move to the live directory. No `status` column needed. |
| 2 | **Store-level scope only.** Directories and resources belong to a store. Cross-store sharing works by referencing the owning store's URL path — no platform-wide shared scope needed. |
| 3 | **No versioning.** Last-write-wins. Re-upload if something needs to be reverted. Revisit only if an audit trail becomes a hard requirement. |
| 4 | **Resource Explorer lives in `waha_platform`.** It is an admin screen like any other — same app, same auth, same permission checks. The 3-option image picker (Resource Manager / Camera / Gallery) is also a widget inside `waha_platform` used wherever an image needs to be attached. |

---

*Last updated: 2026-08-28*
