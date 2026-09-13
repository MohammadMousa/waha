# Landing Pages

Each browsing mode has its own HTML landing page, resolved per organization with an optional per-branch override — see `resource-management.md` for the underlying URL scheme and data model. The app shows the page immediately from local cache, then updates it in the background once auth resolves.

---

## Page keys

| Page key | Browsing mode | Shown to |
|---|---|---|
| `KIOSK_LANDING` | Kiosk | All kiosk sessions |
| `SHOPPING_LANDING` | Shopping | All shopping sessions |
| `CLIENT_LANDING` | Normal | Registered users, guests |
| `ADMIN_LANDING` | Normal | Users with `MANAGE_STORES` (Admin, Super-admin) |

---

## File location

Global pages live directly under the organization — no store row involved (`store_id IS NULL` in `resource_directories`), in a `pages` directory owned by the org itself:

```
ORGANIZATION (global — store_id IS NULL)
└── pages/
    ├── KIOSK_LANDING.html
    ├── SHOPPING_LANDING.html
    ├── CLIENT_LANDING.html
    └── ADMIN_LANDING.html
```

Upload via the Resource Manager, scoped to the organization rather than any branch. A branch-specific override can be placed in that branch's own `pages/` directory — the backend checks the session's branch first, then falls back to the organization's global page. There is no "root store" — that was a fixed store id (1) standing in for global scope, and it's gone; global is a property of the organization, not of any particular store.

---

## Backend endpoint

```
GET /api/landing/{pageKey}
Authorization: Bearer <token>   (optional — omit for anonymous access)
```

Response:
```json
{
  "page_key": "KIOSK_LANDING",
  "scope": "local",
  "store": "waha",
  "resource_url": "/resource/waha_corp/waha/pages/KIOSK_LANDING.html",
  "content_hash": "sha256:..."
}
```

`scope` is `"local"` when the session's branch has its own override, `"global"` when falling back to the organization's page (`resource_url` then has no branch segment, e.g. `/resource/waha_corp/pages/KIOSK_LANDING.html`). The organization is always the authenticated caller's own org — never inferred from `storeId` — so a caller with no branch in scope at all can still resolve straight to global. Returns `404` when no page is configured for that key anywhere in the chain.

---

## App-side caching

```
Cold start
  └── Read  <appSupportDir>/landing/{pageKey}.html
        ├── Hit  → show WebView immediately (no network needed)
        └── Miss → show coded fallback screen

After auth + store resolve (background, once per session)
  └── GET /api/landing/{pageKey}
        ├── 404 → no page configured; keep coded fallback
        └── 200 → compare contentHash with LocalPrefs
                    ├── Same hash + same key → nothing to do
                    └── Changed → fetch HTML bytes, overwrite cache, reload WebView
```

The `contentHash` in `LocalPrefs` is the sole freshness signal — the HTML file is only re-downloaded when the hash differs.

### Image URL portability

Landing page HTML is saved with **root-relative** image paths (e.g. `src="/resource/waha_corp/waha/res/banner.jpg"`), never absolute URLs. `resolveAbsolutePaths()` rewrites them to the correct server origin at display time — see `waha_platform/docs/landing-page-rendering.md` for exactly how, and why that alone isn't the whole story on every platform. This means the cached file works even if the server IP changes between when an admin saved the page and when the kiosk loads it.

Root-relative is not the same guarantee across an *organization or branch rename*, though: the org/branch identifiers themselves are baked into that path. See the "Decisions" table in `resource-management.md` — reopening and re-saving a page through the admin editor heals it, but nothing does that automatically in the background.

### Cache expiry — when does a re-check happen?

The background update fires **once per session**, but "session" resets on any meaningful identity change:

| Event | Re-check triggered? |
|---|---|
| Cold start (first auth + store ready) | Yes |
| Store switched by admin | Yes |
| Token expired → kiosk re-authenticates | Yes |
| App stays running, same token, same store | No — cached version served |

The local file is never deleted or invalidated — it is always shown immediately on startup regardless of age. The re-check simply gives the server a chance to replace it with a newer version if one exists. If the server is unreachable the local version keeps showing with no error.

A published update will be picked up within one token lifetime at the latest (typically ~24 h for kiosk sessions), because token expiry → re-login → re-check. It may arrive sooner if the device restarts or the store switches before then.

### Normal-mode key selection

At startup the app doesn't know the user's role yet. It reads `LocalPrefs.landingNormalKey` (saved from the previous session) to decide whether to load `ADMIN_LANDING` or `CLIENT_LANDING`. On first install this defaults to `CLIENT_LANDING`. Once auth resolves, the correct key is determined from permissions, saved back to `LocalPrefs`, and the page is updated if needed.

---

## Authoring the HTML

The page is loaded via `WebViewController.loadHtmlString(html, baseUrl: <serverOrigin>)`, so:

- Relative URLs (e.g. `<img src="/resource/waha_corp/waha/res/banner.jpg">`) resolve against the server origin.
- Absolute `https://` URLs work normally.
- JavaScript is enabled (`JavaScriptMode.unrestricted`).

### i18n

The app injects the current locale after page load:

```javascript
document.documentElement.setAttribute('lang', 'ar');   // or 'en'
document.documentElement.setAttribute('dir',  'rtl');  // or 'ltr'
// re-applies all [data-i18n] elements using a global `translations` object
```

Declare translations in the page:

```html
<script>
var translations = {
  en: { welcome: "Welcome", lang_btn: "عربي" },
  ar: { welcome: "أهلاً",  lang_btn: "English" }
};
</script>
<h1 data-i18n="welcome">Welcome</h1>
```

### Navigation links

To navigate to a Flutter screen from a link in the page, use:

```html
<a href="/screen?name=browse_screen&tag=Desserts">Browse Desserts</a>
```

Supported `name` values:

| `name` | Flutter route |
|---|---|
| `browse_screen` | `/browse` (optional `tag` query param filters by category tag) |

The WebView intercepts these URLs and delegates to Flutter's navigator instead of loading them in the browser.
