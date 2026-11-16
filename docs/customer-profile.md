# Customer profile

Personal details are kept in their own table, `user_profiles`, one row per user, apart from the login account in `users`.
The row is created the first time the person saves a profile.

| Field | Rules |
|-------|-------|
| `firstName`, `lastName` | up to 100 characters |
| `phone` | saved in one standard form (`+966501234567`). A number typed without a country code gets the server default (`WAHA_DEFAULT_COUNTRY_CODE`, 966). Unique per organization |
| `phoneVerified` | read only; becomes true only after a phone check (not built yet). Changing the phone clears it |
| `email` | lower-cased, unique per organization |
| `gender` | `MALE` or `FEMALE` |
| `birthDate` | `yyyy-MM-dd`, not in the future |
| `language` | `ar`, `en`, ... |
| `avatarResourceId` | read only; the picture's id in resources, shown from `GET /api/resources/{id}` (public by id, like product pictures). `null` when there is none |

## Calls (signed-in user, own profile only)

| Call | Result |
|------|--------|
| `GET /api/profile` | `{ userId, username, firstName, lastName, phone, phoneVerified, email, gender, birthDate, language }`, fields empty when never saved |
| `PUT /api/profile` | Same object after saving. A field that is missing stays as it is; a field sent empty or `null` is cleared. 400 with a message for a bad value, 409 when the phone or email belongs to another account |

## Admin: another account's profile

Needs `MANAGE_USERS` (the accounts screen permission); the account must be in the admin's organization (a super admin without one may use any). Each change is recorded in the audit history as `PROFILE_UPDATED` (field names only), `AVATAR_UPDATED` or `AVATAR_REMOVED`, entity type `USER`.

| Call | Result |
|------|--------|
| `GET /api/admin/users/{id}/profile` | The same object as `GET /api/profile` for that account. 404 for an unknown account or one in another organization |
| `PUT /api/admin/users/{id}/profile` | Same body and rules as `PUT /api/profile` |
| `PUT /api/admin/users/{id}/avatar` | Same as `PUT /api/profile/avatar` (multipart `file`) |
| `DELETE /api/admin/users/{id}/avatar` | Same as `DELETE /api/profile/avatar` |

`GET /api/admin/users` (the accounts list) now also returns `firstName`, `lastName`, `phone`, `email` and `avatarResourceId` for each account (null when no profile).

## Avatar

| Call | Result |
|------|--------|
| `PUT /api/profile/avatar` multipart field `file` | A JPEG or PNG up to 5 MB (and no larger than 8000 pixels a side). The server crops the middle square, shrinks it to 256 x 256, and saves it as a new JPEG, which drops GPS and camera details. Returns the profile with `avatarResourceId`. 400 `{message}` when it is not a valid picture, 413 above 5 MB |
| `DELETE /api/profile/avatar` | Removes the picture from the profile and returns the profile. The stored file stays (identical files are shared) |

Phone cameras that save HEIC should convert to JPEG in the app first. The picture is read from `GET /api/resources/{avatarResourceId}`; the id changes when the picture changes, so the app can cache by id.

The profile is deleted together with the account. The username is not changed by the profile.
