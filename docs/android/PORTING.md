# Meiro Android port reference

Purpose: make the Kotlin + Jetpack Compose + Media3 port of the Meiro desktop app mechanical. Everything below was read from code on 2026-10-30. Where a claim could not be checked in code it is marked **unverified** and collected in section 8.

Sources: desktop `meiro/` (Go), server `meiro-server/backend/` (Go), UI target `mobile-mock/index.html` (SCREENS array, 28 screens), decisions in `PLAN.md` section A3 (Turkish).

Conventions:

- Cites are `path:line`, relative to `meiro/` for desktop files and to `meiro-server/backend/` when prefixed `server:`. Another agent is editing sync code on both sides while this was written, so lines can drift by a few; the symbol name is authoritative.
- Providers are the strings `ytm` (YouTube Music) and `soundcloud`. A YouTube Music track is keyed by its video ID, a SoundCloud track by its page URL. Everywhere ms means milliseconds.
- "Being added" = the other agent's in-flight work (`note` on library items, `thumbnail` on play events, `DELETE /api/v1/events` with `cleared_at`). Treat it as not final; section 1.6.
- The server's error messages are Turkish and unaccented (`gecersiz kimlik`). Never branch on message text; branch on status code.

---

## 1. Meiro server API for the phone

### 1.1 Basics

| Item | Value |
|---|---|
| Default base URL | `https://meiro.muratkirazkaya.com` (`internal/sync/sync.go:20`); user-editable; trailing slash trimmed (`sync.go` `Client.url`) |
| Auth | `Authorization: Bearer <token>` on every route except health and login (`server:middleware.go:36` `requireAuth`) |
| Token | 32 random bytes, base64url without padding (43 chars); server stores only its sha256 hex (`server:auth.go:25`) |
| Token lifetime | 30 days from login, fixed. `last_used_at` is touched on use but `expires_at` is never extended (`server:handlers_auth.go:11`, `server:store.go:96`). No refresh route, no logout route, no registration route (users are created by the server CLI, `server:cli.go`) |
| Success | Always HTTP 200 with a JSON body. The Go client treats any non-200 as an error (`internal/sync/sync.go:164`, `:193`) |
| Error body | `{"error":"<turkish text>"}` |
| Timeout used by desktop | 30 s per request (`sync.go` `Client.http`, `syncpage.go:34`) |
| Time format | Requests: RFC 3339 (fractional seconds allowed, any offset). Responses: UTC `RFC3339Nano`, trailing zeros trimmed, e.g. `2026-10-30T11:03:11.123456Z` |
| IDs | Library IDs and event IDs are UUID strings in 8-4-4-4-12 form. The server validates with `uuid.Parse` |
| Unknown JSON fields | Ignored (plain `json.Decoder`) |
| CORS, rate limits | None in the server code |

Error status table:

| Status | Meaning |
|---|---|
| 400 | Bad JSON, bad field (`kind gecersiz`, `updated_at gecersiz`, `track alanlari eksik`, `client_event_id gecersiz`, `since gecersiz`, `limit gecersiz`, `en fazla 1000 olay gonderilebilir`) |
| 401 | `gecersiz kimlik`: missing or malformed Bearer, unknown or expired token, or wrong email or password at login. Not retryable; the user must sign in again |
| 404 | `kutuphane bulunamadi`: only `library_items` push and `GET /libraries/{id}/items`; also an unknown route |
| 405 | `/auth/login` and `/auth/me` only (they are registered without a method prefix, `server:main.go:21`) |
| 500 | `sunucu hatasi` |
| 503 | `veritabani yapilandirilmamis`: the server runs without `DATABASE_URL` |

Retry rule used by the desktop (`internal/sync/sync.go:140` `Retryable`): network failure or status >= 500 retries; any 4xx does not. See 1.4.7 for the poison-pill problem this creates.

### 1.2 Endpoints the phone needs

`R` = required for A3c, `O` = optional or A3d.

| | Method and path | Auth | Purpose | Server handler |
|---|---|---|---|---|
| R | `GET /healthz`, `GET /api/v1/health` | no | liveness, `{"status":"ok"}` | `server:main.go:11` |
| R | `POST /api/v1/auth/login` | no | email + password -> token | `server:handlers_auth.go:28` |
| O | `GET /api/v1/auth/me` | yes | `{"id","email"}`; cheap token check | `server:handlers_auth.go:80` |
| R | `POST /api/v1/sync/events` | yes | push plays | `server:handlers_sync.go:70` |
| R | `POST /api/v1/sync/libraries` | yes | push lists | `server:handlers_sync.go:124` |
| R | `POST /api/v1/sync/library_items` | yes | push the tracks of one list | `server:handlers_sync.go:178` |
| R | `POST /api/v1/sync/downloads` | yes | push downloads of this device | `server:handlers_sync.go:228` |
| R | `POST /api/v1/sync/links` | yes | push MusicBrainz and other-provider links | `server:links.go:95` |
| R | `GET /api/v1/sync/changes?since=N&limit=M` | yes | pull lists and tracks, tombstones included | `server:handlers_feed.go:42` |
| R | `GET /api/v1/events?since=N&limit=M` | yes | pull plays of every device | `server:handlers_feed.go:104` |
| R | `GET /api/v1/links` | yes | pull all links | `server:links.go:197` |
| O | `GET /api/v1/stats/summary?period=month\|year&anchor=` | yes | server-side stats for the Ozet screen | `server:handlers_read.go:101` |
| O | `GET /api/v1/downloads` | yes | downloads of all devices | `server:handlers_read.go:75` |
| not needed | `GET /api/v1/libraries`, `GET /api/v1/libraries/{id}/items`, `GET /api/v1/events/recent` | yes | dashboard reads; the changes feed supersedes them (they carry no covers) | `server:handlers_read.go:22`, `:46`, `:167` |
| being added | `DELETE /api/v1/events` | yes | clear history on all devices | see 1.6 |

### 1.3 Payloads

`TrackRef` is shared by every push and by the changes feed (`server:models.go:88`). Desktop type: `internal/sync/sync.go:48`.

```json
{
  "provider": "ytm",                       // required, non-empty
  "provider_track_id": "dQw4w9WgXcQ",      // required; soundcloud: the page URL
  "title": "Song", "artist": "Artist",     // both required, non-empty
  "album": "Album",                        // optional, null/absent ok
  "duration_ms": 213000,                   // optional; desktop omits 0
  "isrc": null,                            // optional; no desktop code fills it
  "thumbnail": "https://...",              // optional
  "album_thumbnail": "https://..."         // optional
}
```

**Login**

```
POST /api/v1/auth/login   {"email":"a@b.c","password":"..."}   -> 200 {"token":"..."}
```
Email is matched exactly (case-sensitive, `server:store.go` `UserByEmail`); the desktop trims whitespace first (`syncpage.go` `signInSync`). Unknown email and wrong password both give 401. The desktop never stores the password; a token that expires after 30 days therefore means a visible re-login. Its 401 message ("The server did not accept the email and password.", `syncpage.go:367`) is also shown for an expired token; do better.

**Push events** (`internal/sync/sync.go:64` `Event`)

```json
POST /api/v1/sync/events
{"events":[{
  "client_event_id":"<uuid v4>",
  "track":{TrackRef},
  "played_ms":187000,
  "duration_ms":213000,          // optional
  "completed":true,
  "device":"Pixel 8",            // optional
  "played_at":"2026-10-30T11:03:11.123+03:00"
}]}
-> {"accepted":["<client_event_id>",...],"duplicates":["<client_event_id>",...]}
```
Max 1000 per request, else 400. Validation order per event: UUID, TrackRef complete, `played_ms >= 0`, `played_at` parses. One bad event rejects the whole request. Insert is `ON CONFLICT (user_id, client_event_id) DO NOTHING`, all in one transaction under the per-user lock (`server:store_data.go` `InsertPlayEvents`). Remove both `accepted` and `duplicates` from the outbox; if both are empty the desktop treats it as an error and stops (`internal/sync/outbox.go` `Push`, last loop).

**Push libraries** (`internal/sync/sync.go:75`)

```json
POST /api/v1/sync/libraries
{"libraries":[{
  "id":"<uuid>",                 // optional in the schema; ALWAYS send it for custom lists (see below)
  "kind":"custom",               // "service" | "custom"
  "provider":null, "provider_id":null,
  "name":"Gym", "cover":"https://...",   // name required non-empty
  "updated_at":"2026-10-30T11:03:11.123Z",
  "deleted":false
}]}
-> {"accepted":["<library id>",...]}     // same order as the request
```
All-or-nothing: one transaction, a failure writes nothing (`server:store_data.go` `UpsertLibraries`). Behaviour of `upsertLibraryTx` (`server:store_data.go:154`):

- with `id`: upsert by id, applied only when `updated_at >= stored updated_at`; otherwise the stored row is left and its id is returned anyway;
- no `id` but `provider` set: upsert by `(user, provider, provider_id)` among live rows;
- no `id`, no `provider`: **always inserts a new library**, so a retry duplicates it. Phones must generate and send a UUID for every custom list.
- `cover` is `COALESCE(new, old)`: a push cannot clear a cover.
- `deleted:true` stores `deleted_at = now()` (server clock) the first time and keeps the first value after; `deleted:false` clears `deleted_at` (a later live push resurrects, if its `updated_at` wins).
- The conflict target is the primary key `id` only; the update does not check `user_id`, so another user's list id would be overwritten. Not a phone concern, a server note.

**Push library items** (`internal/sync/sync.go:87`, `:101`)

```json
POST /api/v1/sync/library_items
{"library_id":"<uuid>","items":[{
  "track":{TrackRef}, "position":0,
  "updated_at":"...", "deleted":false
}]}
-> {"accepted":["<item row id>",...]}
```
`library_id` must be a UUID (400) and name a live list owned by the caller (404 `kutuphane bulunamadi`, nothing written). Items upsert on `(library_id, track_id)` with the unique index covering tombstones (`server:migrations/0004_sync_feed.sql`), `updated_at >= stored`. A track the server has not seen is created from the TrackRef; tracks are global (shared by all users) and keyed by `(provider, provider_track_id)` in `track_sources` (`server:store_data.go:90` `resolveTrackTx`). Title, artist, album and duration are fixed at first creation; later pushes only update thumbnails, and only with non-empty values.

**Push downloads** (`internal/sync/sync.go:101`)

```json
POST /api/v1/sync/downloads
{"downloads":[{"track":{TrackRef},"device":"Pixel 8","size_bytes":4123456,
  "last_listened_at":"...","updated_at":"...","deleted":false}]}
-> {"accepted":["<download id>",...]}
```
`device` required. Rows are keyed `(user, track, device)` among live rows, `updated_at >=` wins. Not transactional: a 400 on item N leaves items before N written (`server:handlers_sync.go:228`). The desktop sends a full snapshot of live downloads every push and never sends `deleted:true` (`syncpage.go:218` `syncSnapshot`), so deleted downloads stay on the server. The phone should send `deleted:true` when the user removes a download (recommendation; **unverified** whether the dashboard cares).

**Push links** (`internal/sync/links.go:59`; handler `server:links.go:95`)

```json
POST /api/v1/sync/links     (max 1000 per list, else 400)
{"songs":[{"provider":"ytm","provider_track_id":"...","state":"matched","mbid":"...",
           "artist":"...","artist_mbid":"...","artist_state":"matched",
           "checked_at":"...","updated_at":"..."}],
 "artists":[{"artist_key":"yorushika","name":"Yorushika","state":"matched","mbid":"...","updated_at":"..."}],
 "alternates":[{"provider":"ytm","provider_track_id":"...","other_provider":"soundcloud",
                "other_track_id":"https://soundcloud.com/...","other_url":"https://...",
                "checked_at":"...","updated_at":"..."}]}
-> {"accepted": <int count>}
```
`state` and `artist_state` in `matched | unknown | other`. `artist_key` is `match.Normalize(name)` (section 4.4). An alternate with empty `other_*` means "searched, not found". Conflict rule is strict `updated_at >` (not `>=`), unlike lists and items (`server:links.go:239`, `:252`, `:265`). All records are validated first, then written one by one, not in a transaction.

`GET /api/v1/links` returns the same three arrays, empty arrays when none, `omitempty` fields omitted.

**Pull changes** (`server:handlers_feed.go:42`, `server:models.go:352`; client `internal/sync/changes.go`)

```json
GET /api/v1/sync/changes?since=0&limit=1000
-> {"libraries":[{"id","kind","provider","provider_id","name","cover","updated_at","deleted","seq"}],
    "items":[{"library_id","track":{TrackRef},"position","updated_at","deleted","seq"}],
    "next":1234,"more":false}
```
`limit` default 500, clamped to 1..1000; `since` default 0, negative -> 400. The track fields are `null` when absent. Libraries of `kind` `service` appear too; the desktop ignores everything but `custom` (`syncpull.go:250`).

**Pull events** (`server:handlers_feed.go:104`, `server:models.go:367`; client `internal/sync/events.go`)

```json
GET /api/v1/events?since=0&limit=1000
-> {"events":[{"client_event_id","track":{"provider","provider_track_id","title","artist"},
               "played_ms","duration_ms","completed","device","played_at","seq"}],
    "next":9876,"more":false}
```
Includes this device's own plays; dedupe on `client_event_id`. The track here carries no album, duration or thumbnail (the `thumbnail` field is being added, 1.6). Rows with empty `provider_track_id` or `client_event_id` are skipped by the desktop (`syncpull.go:133`).

**Stats summary** (`server:handlers_read.go:101`)

```json
GET /api/v1/stats/summary?period=month&anchor=2026-10     // period default "month"; year anchor "2026"
-> {"totals":{"played_ms","plays","unique_tracks","unique_artists"},
    "top_tracks":[{"title","artist","plays","played_ms"}],   // top 10
    "top_artists":[{"artist","plays","played_ms"}],          // top 10
    "providers":[{"provider","played_ms"}],
    "series":[{"bucket":"2026-10-03","played_ms":...}]}      // day buckets for month, "2026-10" for year
```
Counts only plays passing the counting rule (section 5.1). The anchor is parsed as UTC, so the month or year boundary is UTC, not the phone's zone; the desktop computes its own stats locally in local time (`internal/history/history.go:242` `Summarize`). For an Ozet screen that matches the desktop, compute locally from the pulled events. Bucket time zone of `date_trunc` is the DB session zone (**unverified**, probably UTC).

### 1.4 Sync semantics

#### 1.4.1 Last write wins, with sub-second times

- Every list, list item, download and link carries a client-chosen `updated_at`. The server keeps the row whose `updated_at` is larger. Lists, items and downloads use `>=` (a tie lets the later push win); links use `>`.
- Times must keep sub-second precision end to end. A bug was found and fixed where the server rounded to seconds and the wrong side won inside one second (PLAN.md A2.5 status; server now always emits `RFC3339Nano`; test `TestSyncChangesKeepSubSecondTimes`). The phone should send milliseconds at least (`Instant.toString()` is fine). Postgres stores microseconds, so a value with nanoseconds comes back truncated or rounded; compare client-side with a microsecond tolerance or truncate before storing.
- Clock skew is not corrected anywhere. The `deleted_at` column uses the server clock, but conflict resolution only uses `updated_at`, which is the client's.
- What bumps a record's `updated_at` on the desktop (`internal/library/library.go`): adding a track (`AddItem`, `:374`), moving a track (every track between the two positions, `MoveItem`, `:534`), replacing a track list (`ReplaceItems`, `:274`), switching a track's provider, renaming a list or changing its cover (list `UpdatedAt`). Adding or removing tracks does not touch the list's own `updated_at`: tracks are records of their own, so an old rename cannot overwrite a newer one.

#### 1.4.2 Tombstones

- Nothing is ever hard-deleted. A removed list or track is pushed with `deleted:true` and the removal time as `updated_at`; the row stays and appears in the changes feed with `deleted:true`.
- Locally the desktop keeps a tombstone per removed track (`Library.Removed`, `internal/library/library.go:91`) and per deleted list (`DeletedList`), for 90 days (`tombstoneLife`, `:114`), and sends them with every snapshot (`syncpage.go:218`). A removed track is pushed with position 0 and `updated_at = RemovedAt`.
- A later live push can bring a tombstone back (`deleted_at` cleared when the incoming row wins), and an older live push cannot (it loses on `updated_at`). That is the whole resurrection rule.
- When a list is deleted its items are not individually tombstoned on the server; the feed still returns them (`server:store_data.go:641` joins `libraries` only for ownership). The client drops a deleted list together with its tracks.
- Receiving a tombstone must not create a tombstone back (the desktop's `ApplyRemoteItems` stamps nothing and leaves nothing, `internal/library/library.go:622`), or deletions echo forever.

#### 1.4.3 Seq cursor paging

- `seq` comes from one Postgres sequence (`sync_seq`) shared by libraries, library items and play events (`server:migrations/0004_sync_feed.sql`). INSERT takes `nextval`; UPDATE takes a new `nextval` only when the row actually changed (trigger from `0005_sync_seq_on_change.sql`), so re-sending an unchanged snapshot does not make other devices re-download everything.
- Read loop (`internal/sync/changes.go:57` `FetchAllChanges`, `internal/sync/events.go:68` `FetchAllEvents`):
  1. request `since=cursor&limit=1000`;
  2. collect; if `more` is false: cursor = max(cursor, `next`), stop;
  3. if `more` is true and `next <= cursor`: fail ("more but did not move on"), never loop;
  4. otherwise cursor = `next`, repeat.
- Server side: it reads `limit+1` rows per table ordered by `seq`; `more` is true when an extra row exists; `next` is the `seq` of the last returned row, or `since` when nothing was returned. For the changes feed the two lists are merged by `seq` and cut at `limit` rows in total (`server:handlers_feed.go:42`). `seq` values have gaps (other users, unchanged updates), never rely on contiguity.
- Reads and writes of one user are serialized by `pg_advisory_xact_lock` so a reader never skips a row that commits late (`server:store_data.go:46`, `withUserTx`). Save the new cursor only after the page was merged.
- Cursors belong to one (server URL, account email) pair. The desktop resets to 0 when either changes and clears them at sign-out (`syncpull.go:33`, `syncpage.go` `signOutSync`). Keep separate cursors for the changes feed and the events feed (`SyncCursor`, `EventCursor`, `internal/settings/settings.go:128`).
- Cadence: on sign-in and app start, after every successful push, and every 5 minutes (`main.go:108-110`, `syncpage.go:41`). Pulls are single-flight; one asked for during another reruns afterwards (`syncpull.go` `pullAgain`).

#### 1.4.4 Pushes are one transaction; the 404-drop rule

- `libraries`, `library_items` and `events` pushes are single transactions: a server error writes nothing, so the whole batch can be retried verbatim. Downloads and links are not.
- Outbox batch size is 200 per request (`internal/sync/outbox.go:19` `MaxBatch`); server limit is 1000 events or links per request.
- Push order in one run (`outbox.go:234` `Push`): libraries, then each library's items, then downloads, then links, then events. Lists first so the items have a list to land in.
- **404 rule** (`outbox.go:250`): if `library_items` push answers 404, the server does not know that list or has it deleted (another device deleted it). The desktop logs it, drops every waiting item for that list from the outbox and moves on; the next changes read delivers the deletion. Other errors stop the whole push and leave the batch queued.

#### 1.4.5 Outbox, retry, backoff

- Everything waits in an on-disk outbox until a 200 arrives (`internal/sync/outbox.go`). Same-key records collapse: libraries by id, items by `(provider, provider_track_id)`, downloads replaced wholesale, links by key; the record with the later `updated_at` is kept (`AddLibraries :125`, `AddLibraryItems :143`).
- Plays: kept by `client_event_id`; capped at 5000, oldest dropped first (`MaxEvents`, `outbox.go:23`).
- A delivery tries up to 4 times with `Backoff(attempt)` = 2 s doubling, capped at 5 minutes (`outbox.go:415`, `syncpage.go:38`, `:120` `syncNow`), only for retryable errors.
- On Android use WorkManager with a network constraint instead of the desktop's timer, but keep the semantics: record leaves the queue only after its batch returned 200.

#### 1.4.6 Applying pulled changes (`syncpull.go:244` `mergeChanges`, `internal/library/library.go:574`, `:622`)

Lists, `kind == custom` only. For each remote list:

| Local | Remote | Action |
|---|---|---|
| absent | live | add with the server's id as `ServerID`, unless the device deleted that list and `DeletedAt` is after the remote `updated_at` |
| absent | deleted | nothing |
| present | deleted | drop locally unless remote `updated_at` is before the local `updated_at` |
| present | live, `remote.updated_at > local.updated_at` | take name and cover and the remote time |

Tracks of a known list, in feed order; a list this device does not hold is skipped. A remote track whose provider is neither `ytm` nor `soundcloud` is ignored (`syncpull.go:182` `remoteItem`). Identity of a track is `(source, id)` with id = video ID for `ytm`, page URL for `soundcloud` (`internal/library/library.go:709` `ActiveRef`).

| Remote | Local | Action |
|---|---|---|
| deleted | has it | delete unless local `updated_at` is newer than remote |
| live | has it | if remote is newer: take its time and position, fill empty thumbnails |
| live | lacks it | add, unless a local tombstone for it is newer than the remote time |

Then reorder: sort by the server's `position`; ties broken by `(source, id)` string compare so every device orders equal positions the same way. Items the server did not mention keep their current index as position.

#### 1.4.7 Desktop behaviours not to copy

- **Poison pill**: a 400 on a batch stops the push at that batch on every retry forever (`outbox.go` `Push` returns on any non-404 error). On the phone, quarantine a batch that gets 400 (log, drop or park it) instead of blocking later batches.
- The 401 message for expired tokens is misleading (above).
- Downloads are never deleted server-side (above).

#### 1.4.8 First sync rules (`syncpull.go:47`, `syncpage.go:173`)

State: `SyncTwoWay` (bool), `SyncLastAt` (time of last successful push).

- `firstRead = !SyncTwoWay`; `migrate = firstRead && SyncLastAt != zero`.
- **A device that never pushed (fresh phone) imports as usual**: `migrate` is false, so it takes every list and track the server holds.
- A device that pushed before the feed existed (old desktop install) treats itself as the truth: for every server list or track it lacks it queues a deletion (stamped now) and changes nothing locally. This stops removed items from coming back from stale server rows.
- `SyncTwoWay` becomes true after the first completed read, or after the first successful push when `SyncLastAt` was zero (so a new install never migrates).
- A phone signing in for the first time against an account that already has desktop data: use the `migrate = false` path. Pull lists and events from `since=0`, then push.
- The first sign-in sequence on the desktop is `pullLinks`, `pullChanges`, `pullEvents`, `syncNow` (`syncpage.go:96` `loadSyncToken`). After a push succeeds it pulls changes and events again.

#### 1.4.9 Identity and the shared Daily mix UUIDs

Library server id (`internal/sync/uuid.go:34`):

- A list that came from the server keeps `ServerID` and uses it.
- A list made on the desktop derives its server id from its local id: `LibraryID(localID) = DeriveID("6ba7b811-9dad-11d1-80b4-00c04fd430c8", localID)` (`uuid.go:16`, `:52`; selection in `syncpage.go:201` `libraryServerID`).
- The Daily mix has one id per account on every device: `DailyMixID() = DeriveID("6ba7b812-9dad-11d1-80b4-00c04fd430c8", "daily-mix")` (`uuid.go:77`, `:81`).
- Phone-made lists can simply use a random UUID as both local and server id; `LibraryID` only matters for desktop-made local ids. `DailyMixID` must be derived exactly.

`DeriveID` is **not** RFC 4122 UUIDv5 and is not `java.util.UUID.nameUUIDFromBytes` (that is MD5 v3). It hashes the namespace as its **36-character ASCII text**, not as 16 raw bytes:

```
sha1( namespaceText.utf8  ||  0x00  ||  name.utf8 )   -> take the first 16 bytes
b[6] = (b[6] & 0x0f) | 0x50          // version 5
b[8] = (b[8] & 0x3f) | 0x80          // RFC 4122 variant
format lowercase hex as 8-4-4-4-12
```

```kotlin
fun deriveId(namespace: String, name: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-1")
    md.update(namespace.toByteArray(Charsets.UTF_8))
    md.update(0)
    md.update(name.toByteArray(Charsets.UTF_8))
    val b = md.digest().copyOf(16)
    b[6] = ((b[6].toInt() and 0x0f) or 0x50).toByte()
    b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
    val h = b.joinToString("") { "%02x".format(it) }
    return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
}
const val LIBRARY_NS = "6ba7b811-9dad-11d1-80b4-00c04fd430c8"
const val DAILY_MIX_NS = "6ba7b812-9dad-11d1-80b4-00c04fd430c8"
```

Golden values, computed with a Go program that copies `DeriveID` and cross-checked with an independent Python implementation (both agree):

| Call | Result |
|---|---|
| `DailyMixID()` = `deriveId(DAILY_MIX_NS, "daily-mix")` | `bab208cb-a929-55cd-a091-c414d0e03864` |
| `LibraryID("abc123")` | `6e55280b-fa0f-50d8-952c-6b0c7384b653` |
| `LibraryID("0123456789abcdef")` | `12f21daa-677d-585d-9347-eb0518710a7d` |

Random ids (`NewID`, `uuid.go:21`): UUID v4, generated once per record and kept so a retry is the same record. Use `UUID.randomUUID()`.

#### 1.4.10 The shared Daily mix on the wire

- It is an ordinary `custom` list: id `bab208cb-...`, name `Daily mix`, empty cover, items in rank order with `position` 0..n-1 (`recommendations.go:423` `writeDailyMix`).
- Each day the first device to build it replaces the items; removed ones become tombstones, kept ones are re-sent with new positions and `updated_at = now` (`ReplaceItems`).
- `adoptDailyMix` (`syncpull.go:212`): if a local list has the Daily mix server id, remember it as the Daily mix list; if any of its tracks has an `updated_at` whose local calendar date is today, mark today's mix as made and do not build a second one. Use the same check on the phone, and do it after every changes merge.
- The "why this was recommended" line is not synced today (known limit in PLAN.md); the `note` field being added is meant to carry it (1.6).

### 1.5 Server caveats found while reading

- `tracks.status` (`matched|unknown|other`) is never written by any code path I could find (grep of `server:store_data.go`, `server:links.go`), so `status` in the read endpoints is always `unknown`. MusicBrainz state travels through `/sync/links`, not through tracks. (**unverified** beyond grep.)
- `GET /api/v1/downloads` returns the track without provider ids (`server:models.go` `TrackView`), so another device's downloads cannot be mapped to playable tracks from that endpoint alone.
- The working tree of `meiro-server` has uncommitted changes (feed, links, covers, one-transaction pushes, migrations 0004 and 0005 are all there); PLAN.md says prod was an older build when last checked (`/api/v1/links` answered 404). I made no calls to prod, so whether prod runs this code is **unverified**.

### 1.6 Being added (do not code against details yet)

The other agent is working on these in both repos; everything here is from the interim working tree and may change.

| Feature | What is visible so far |
|---|---|
| `note` on library items | optional string on push items and on changes-feed items; later record wins with the item; intended for the Daily mix reason |
| `thumbnail` on play events | optional cover address on pushed plays and probably on the events feed track |
| `DELETE /api/v1/events` | clears the account's plays and stamps `users.history_cleared_at`; answers `{"cleared_at":"<RFC3339Nano>"}`; the events feed gains a top-level `cleared_at` (null when never cleared); a pushed play whose `played_at` is not after `cleared_at` is accepted but not kept; devices drop local plays at or before `cleared_at` |

Port these after they land and are tested; until then ignore unknown fields and send nothing new.

---

## 2. YouTube Music InnerTube

All requests are made by `youtube/client.go`. There is no official documentation; this is a private web API and the main fragility of the port.

### 2.1 Envelope (`youtube/client.go:213` `executeAs`)

```
POST https://music.youtube.com/youtubei/v1/<endpoint>
Content-Type: application/json
Accept: application/json
User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36
Origin: https://music.youtube.com
Referer: https://music.youtube.com/
X-YouTube-Client-Name: <id>
X-YouTube-Client-Version: <version>
(anonymous)  X-Goog-Visitor-Id: <visitorData>          // only once known
(signed in)  Cookie, Authorization, X-Goog-Authuser, [X-Goog-PageId]   // see 2.2
```

No `?key=` query parameter is sent (the API key option exists but the app never sets it). Body = the endpoint's payload plus:

```json
{
  "context": {
    "client": {"hl":"en","gl":"US","clientName":"WEB_REMIX","clientVersion":"1.20250219.01.00",
               "visitorData":"<only when anonymous and known>"},
    "user": {"onBehalfOfUser":"<brand channel id, omitted when empty>"}   // present only when signed in
  },
  "isAudioOnly": true        // set by execute(); NOT set on continuation or WEB/ANDROID_MUSIC calls
}
```

The three client identities:

| Client | `clientName` | `X-YouTube-Client-Name` | Version | Used for |
|---|---|---|---|---|
| YTMUSIC web | `WEB_REMIX` | `67` | `1.20250219.01.00` (`client.go:25`) | almost everything |
| WEB | `WEB` | `1` | `2.20260623.01.00` (`client.go:26`) | `account/accounts_list`, `player` for loudness |
| ANDROID_MUSIC | `ANDROID_MUSIC` | `21` | `7.21.50`, UA `com.google.android.apps.youtube.music/7.21.50 (Linux; U; Android 14) gzip` (`client.go:200`) | only the timed-lyrics `browse` |

Details that matter:

- `isAudioOnly:true` asks YTM for songs instead of music videos. `ContinueBrowse` and the WEB and ANDROID calls use `executeAs(..., audioOnly=false)` (`browse.go:182`); first-page calls use `execute` (true).
- `visitorData` is learned from the first response's `responseContext.visitorData` and remembered only if none is stored yet (`client.go:167`). When signed in it is not sent at all.
- Response header timeout 30 s; body bounded by the call's context; response size limit 64 MiB (`client.go:33`). A non-2xx response becomes an error; 401 and 403 become `ErrUnauthorized` (`client.go:336`). Error text is scrubbed so cookies and URLs are never echoed.
- The default `hl` is `en` and `gl` is `US` and nothing in the app changes them. Text-based logic in the app (chip labels, " • " separators) assumes English.

### 2.2 Cookie auth and SAPISIDHASH (`youtube/cookie.go`)

- The credential is the full `Cookie:` header string of the browser session for youtube.com and its subdomains (name=value pairs joined with `; `, expired ones dropped, later duplicates win; `internal/cookies/devtools.go:83`, `internal/cookies/browsers.go:406`). It must contain `SAPISID`; the value is the cookie value with surrounding quotes trimmed (`cookie.go:44`, `:59`).
- Header per request, computed fresh with the current Unix time `ts`:

```
Authorization: SAPISIDHASH <ts>_<sha1hex(ts + " " + SAPISID + " " + origin)>
origin = "https://music.youtube.com"   // scheme + host of the request URL
Cookie: <the full header>
X-Goog-Authuser: <accountIndex, default 0>
X-Goog-PageId: <brand channel id>        // only when a brand channel is selected
```

Only `SAPISIDHASH` is sent (no SAPISID1PHASH or SAPISID3PHASH). The client refuses to attach the cookie to any non-HTTPS or non-`*.youtube.com` URL (`client.go:317`); keep that guard.

- A session is "good" only if `account/account_menu` returns an account with a name or channel id. YouTube answers a retired session with signed-out content and 200, not with an error, so check the account name after sign-in and on failures (`account.go:189` `errSessionGone`, `openSession`).
- **Cookie rotation**: YouTube replaces cookies of a session while a browser keeps it open and the old ones stop working. The desktop therefore signs in in a throwaway window, reads the cookies once, and closes and deletes that profile at once (`internal/cookies/window.go:102` comment). Mirror this on Android: after the sign-in WebView shows a `SAPISID` cookie, wait about 3 s for the rest to be set, read the cookie string, then clear the WebView's cookies and storage and never reuse that WebView (`window.go:104-116`: poll 1 s, settle 3 s, URL `https://music.youtube.com`, signed in = `SAPISID` present on a youtube.com domain, `window.go:487`). Whether `CookieManager.getCookie` returns HttpOnly cookies and whether Google accepts the sign-in page inside an embedded WebView are **unverified**.
- Anonymous use needs no cookie: the app's public client is `NewClient(Options{})` (`account.go:69`). The code routes search, browse, related, lyrics and up next through this anonymous client (PLAN.md says search and playback work without sign-in); library, likes, playlist writes and subscribe need the cookie. That the anonymous Home feed returns content from a phone was not run (section 8).

### 2.3 Request catalogue

Payload shown is what `c.execute` receives; the envelope fields of 2.1 are added.

| Phone feature | Go function | Endpoint | Payload | Parsed by |
|---|---|---|---|---|
| Home shelves | `GetHomeFeed` `browse.go:40` | `browse` | `{"browseId":"FEmusic_home"}` | `browseResult` (2.4) |
| Explore (optional) | `GetExplore` `:44` | `browse` | `{"browseId":"FEmusic_explore"}` | same |
| Library landing (signed in) | `GetLibrary` `:48`, `StreamLibrary` `:58` | `browse` + continuations | `{"browseId":"FEmusic_library_landing"}` then `{"continuation":"<token>"}` | same |
| Playlist page | `GetPlaylist` `:203` | `browse` | `{"browseId":"VL<id>"}` (prefix added if missing) | same |
| Album page | `GetAlbum` `:196` | `browse` | `{"browseId":"MPR..."}` or `FEmusic_library_privately_owned_release...` | same |
| Artist page | `GetArtist` `:189` | `browse` | `{"browseId":"UC..."}` or `FEmusic_library_privately_owned_artist...` | same |
| Podcast show (optional) | `GetPodcast` `:216` | `browse` | `{"browseId":"MPSPP..."}` | `episodePage`, header `musicResponsiveHeaderRenderer` |
| Continuation of any list | `ContinueBrowse` `:178` | `browse` (audioOnly false) | `{"continuation":"<token>"}` | `browseResult` |
| Search | `Search` `search.go:13` | `search` | `{"query":"...","params":"<filter params>"}` (`params` only for a filtered search) | `browseParts(root, true)` + `searchFilters` |
| Search next page | `ContinueSearch` `:31` | `search` | `{"continuation":"<token>"}` | same |
| Search suggestions | `GetSearchSuggestions` `:45` | `music/get_search_suggestions` | `{"input":"..."}` | `searchSuggestionRenderer.suggestion` texts + `browseParts` items |
| Up next / radio | `GetUpNext` `upnext.go:23` | `next` | `{"videoId":"...","playlistId":"<id or RDAMVM+videoId>","playlistIndex":N?,"continuation":"..."?}` | `upNextResult` |
| Related ("Benzerler") | `GetRelated` `tracktabs.go:111` | `next` then `browse` | 1) `{"videoId":"..."}`; find the node with `pageType == "MUSIC_PAGE_TYPE_TRACK_RELATED"`, take the nearest ancestor `browseId`; 2) `{"browseId":"<that>"}` | `browseResult` |
| Lyrics, plain | `GetLyrics` `tracktabs.go:34` | `next` then `browse` | same two steps with `MUSIC_PAGE_TYPE_TRACK_LYRICS` | `musicDescriptionShelfRenderer.description` and `.footer` |
| Lyrics, timed | same | `browse` as `ANDROID_MUSIC` | `{"browseId":"<lyrics browseId>"}` | first `timedLyricsData` array: `[{lyricLine, cueRange.startTimeMilliseconds}]` (number or string ms) |
| Like / unlike a song | `RateTrack` `rating.go:27` | `like/like` or `like/removelike` | `{"target":{"videoId":"..."}}` | check body for `error` |
| Save / remove album or playlist | `AddToLibrary` `library.go:13` | `like/like` or `like/removelike` | `{"target":{"playlistId":"<id without VL>"}}` | check `error` |
| Create playlist | `CreatePlaylist` `playlists.go:24` | `playlist/create` | `{"title","description":"","privacyStatus":"PRIVATE","videoIds":[...]?}` | response `playlistId`; absent = refused |
| Add to playlist | `AddToPlaylist` `:71` | `browse/edit_playlist` | `{"playlistId":"<no VL>","actions":[{"action":"ACTION_ADD_VIDEO","addedVideoId":"..."}]}` | `status` must contain `SUCCEEDED` |
| Remove from playlist | `RemoveFromPlaylist` `:163` | `browse/edit_playlist` | `{"playlistId":"...","actions":[{"action":"ACTION_REMOVE_VIDEO","setVideoId":"<playlistSetVideoId>"}]}` | same |
| Delete playlist | `DeletePlaylist` `:136` | `playlist/delete` | `{"playlistId":"<no VL>"}` | refused when the response has any `actions` |
| Follow artist | `Subscribe` `subscription.go:11` | `subscription/subscribe` or `/unsubscribe` | `{"channelIds":["<channelId>"]}` | check `error` |
| Account name and photo | `GetAccountDetails` `accounts.go:35` | `account/account_menu` | `{}` | `activeAccountHeaderRenderer` (`accountName`, `email`, `accountPhoto`) |
| Brand channels | `GetAccounts` `:67` | `account/accounts_list` as `WEB` | `{"requestType":"ACCOUNTS_LIST_REQUEST_TYPE_CHANNEL_SWITCHER","callCircumstance":"SWITCHING_USERS_FULL"}` | `extractAccountChannels` |
| Report a play to YTM history (signed in) | `ReportPlayback` `playback.go:74` | `player` then GET | `{"videoId","playbackContext":{"contentPlaybackContext":{}}}`; take `videostatsPlaybackUrl.baseUrl`; GET it with `ver=2&c=WEB_REMIX&cpn=<16 chars from [A-Za-z0-9_-]>` using the same identity headers | status 2xx |
| Loudness (optional) | `Loudness` `playback.go:52` | `player` as `WEB` | same payload | `playerConfig.audioConfig.loudnessDb`, else `perceptualLoudnessDb`; needs a signed-in session; `UNPLAYABLE` = error |

Notes:

- Playlist IDs: reads want the `VL` prefix, writes want it stripped (`library.go:28`, `playlists.go`). Liked songs show up in the library as a playlist whose id without `VL` is `LM` (`playlist.go:296`).
- Up next: when no playlist is known the app seeds with `RDAMVM<videoId>`. If the first response has no playlist panel id, it looks for `automixPreviewVideoRenderer.content.automixPlaylistVideoRenderer.navigationEndpoint.watchPlaylistEndpoint`, copies its members as the payload, adds `videoId`, and calls `next` again (`upnext.go:23-61`, `:148`). The continuation token is read from `playlistPanelRenderer` or `playlistPanelContinuation`: `continuation`, else `continuations[].nextRadioContinuationData|nextContinuationData.continuation` (`upnext.go:102`). Panel playlist id from the same two nodes (`:73`). A token equal to the one just used ends the loop.
- Library drain (`browse.go:96` `drainBrowse`): collect the page token and every section token, request them 4 at a time (`drainConcurrency`), fold results back in the order requested, dedupe tokens, fail beyond 1000 pages. Continuation results go to the section that owned the token.
- Search fan-out and merge with SoundCloud (`search.go`): both providers are asked in parallel with a 7 s timeout each (`searchTimeout`, `:27`); results are shown as each arrives; if both fail the result list is built from local downloads and cache metadata ("From this device", `:296` `localSearch`). SoundCloud is skipped when a filter other than label `songs` or `videos` is active (`:154`). `mergeProviders` (`:194`): a SoundCloud track that `match.Match`es an unmatched YTM track becomes an extra source ref on it; unmatched SoundCloud tracks are interleaved one after every 3 YTM rows (`soundCloudEvery`, `:222`). Suggestions: 180 ms debounce, 5 s timeout, 6 queries and 5 items shown (`search.go:19-31`).
- Queue rules for generated tracks (`queue.go`): at most 100 generated tracks (`autoplayQueueLimit`, `:16`); skip items of kind video; skip ids already queued; skip another upload of a song already queued (same letters-and-digits-only lowercase title and artist and lengths within 3 s, `sameSongTolerance :242`, unknown length matches). Note this name folding (`queue.go:253`) is different from `match.Normalize`. Generated pages are requested at once, then the next page when 1 or fewer tracks remain (`upnext.go:39` `ensureUpNext`, 45 s timeout). Recommended songs are put ahead of YTM's generated ones, 5 per fill (section 5.4).

### 2.4 Parsing the responses (`youtube/renderers.go`, `youtube/orderedjson.go`)

The Go parser is a generic walk, not a set of fixed paths. A port that tries fixed paths will drift; port the walk.

1. **Order-preserving JSON with duplicate keys.** `decodeResponse` (`orderedjson.go:80`) builds objects as ordered member lists and sets `AllowDuplicateNames(true)`. `get(key)` returns the **first** member with that key. Of several candidates for a field the first in document order wins. Kotlin: do not parse into `Map`/`JsonObject` (kotlinx.serialization and org.json lose order or duplicates). Write a small streaming reader (`android.util.JsonReader` or `kotlinx.serialization.json.JsonElement` is not enough) that yields `List<Pair<String, Any?>>` per object. Numbers decode as `Double`.
2. **`findRenderer(root, name)`** (`renderers.go:9`): depth-first, document order, first object that has a member `name`.
3. **`browseParts(root, search)`** (`renderers.go:40`): one walk that returns `(items, sections, topResult)`.
   - For a non-search page, any member whose key is a *section renderer* becomes a `Section` and its subtree is parsed on its own with a private dedupe set: `musicShelfRenderer`, `musicShelfContinuation`, `musicPlaylistShelfRenderer`, `musicPlaylistShelfContinuation`, `playlistVideoListRenderer`, `playlistVideoListContinuation`, `gridRenderer`, `gridContinuation`, `musicCarouselShelfRenderer` (`:122`). Section title = `rendererText(title)`, else the first header-renderer child's `title` (`:459`); section token = `continuationToken(renderer)`.
   - Every item renderer found anywhere is also added to the page-level `items` (page-level dedupe).
   - Item renderer keys (`rendererKind :238`): `musicResponsiveListItemRenderer` -> track; `musicTwoRowItemRenderer` -> music item (album, artist, playlist or song, decided later); `musicVideoRenderer`, `videoRenderer`, `playlistVideoRenderer`, `playlistPanelVideoRenderer` -> video (but a `playlistPanelVideoRenderer` whose `musicVideoType` is `MUSIC_VIDEO_TYPE_ATV` or missing is a **track**, `:109`); `gridPlaylistRenderer`, `gridAlbumRenderer`, `gridArtistRenderer`; `musicMultiRowListItemRenderer` -> episode. The search top result is `musicCardShelfRenderer` (`parseMusicCardShelf :159`: title, subtitle, `cardTarget`, card thumbnail), kept apart from the list.
   - Dedupe identity per item: `(videoId, browseId, playlistId, title, playlistSetVideoId)`; an item with all empty is dropped (`:134`, `:151`). A playlist that holds a song twice keeps both because their `playlistSetVideoId` differ.
4. **Field extraction in `parseMusicItem`** (`renderers.go:271`):

| Field | Source, in order |
|---|---|
| `title` | `title` text, else `headline`, else `flexColumns[0]` text |
| `subtitle` | `subtitle`, else `flexColumns[1]`, else `longBylineText`, else `shortBylineText` |
| `duration` text | `lengthText`, else `fixedColumns[0]`, else the last run (without `navigationEndpoint`) of the last flex column that looks like `M:SS` or `H:MM:SS` (parts of at most 2 digits, `:511`, `:551`); that run is then trimmed from the subtitle as `" • "+duration` |
| `videoId` | member `videoId`, else the first non-empty `videoId` found inside `playlistItemData`, `overlay`, `flexColumns`, `thumbnailOverlay`, `navigationEndpoint`, `title`, `onTap` (in that order, **not** the menu, whose entries hold queue ids, `:444`) |
| `browseId` | `navigationID(navigationEndpoint)`, else `navigationID(title)`. `navigationID` (`:569`) skips any subtree containing `watchEndpoint`; takes the first `browseId`, or a `playlistId` only if it starts with `VL` or `PL` |
| episode | `MPED<11-char video id>` browse id, or `musicVideoType == MUSIC_VIDEO_TYPE_PODCAST_EPISODE` in `navigationEndpoint`, `onTap`, `overlay` |
| credits | `rendererCredits` (`credits.go:58`): first of `subtitle`, `longBylineText`, `shortBylineText`, flex column 1 whose **linked** runs include an artist; run with `browseId` starting `UC` or containing `privately_owned_artist` = artist, `MPR`/`privately_owned_release` = album (`media/item.go:288`, `:307`). `artist` = first, `artists` = joined ", ". Only linked runs count |
| artist page for a song | if still no `browseId` on a track or video: first `browseEndpoint.browseId` of artist shape in `subtitle`, bylines, flex column 1 (`credits.go` `rendererArtistBrowseID`) |
| `playlistId` | member `playlistId` |
| `playlistSetVideoId` | `playlistItemData.playlistSetVideoId` (needed to remove from a playlist) |
| thumbnail | the **last** (largest) entry of the first `thumbnails` array found under `thumbnail`, else under the renderer itself (two-row items use `thumbnailRenderer`) (`:606`) |
| like state | `menu.menuRenderer.topLevelButtons[].likeButtonRenderer.likeStatus` in `LIKE|INDIFFERENT|DISLIKE` (`rating.go:83`) |
| save state of an album/playlist | in the header or menu, `toggleButtonRenderer` or `toggleMenuServiceItemRenderer` whose `defaultServiceEndpoint`/`toggledServiceEndpoint.likeEndpoint.target.playlistId` is set; saved = `isToggled`, or for menu items `status == "INDIFFERENT"` (`library.go:102`) |
| subscribe state | first `subscribeButtonRenderer` with a `channelId` in the page `header`: `{channelId, subscribed}` (`subscription.go:38`) |
| episode extras | description text, `playbackProgress.musicPlaybackProgressRenderer.playbackProgressPercentage` clamped 0..100, length "1 hr 30 min" appended to subtitle |

   Text helper `rendererText` (`:396`): a string, or `simpleText`, or the concatenation of `runs[].text`. Page heading: first member of `header` that has a `title` (`browse.go:19`).
5. **What an item opens** (`media/item.go:121` `Target`): video id present and the browse/playlist id is not an album or browsable playlist -> play; album (`MPR...`/`privately_owned_release`) -> album page; `VL`/`PL` playlist -> playlist page; `MPSPP` -> podcast; artist (`UC...`) -> artist page; otherwise video id -> play; SoundCloud items with only a URL -> play the URL. A video ID always wins over the artist link that rows put beside the track.
6. **Tokens.** `continuationToken(root)` (`browse.go:334`) returns the first of `continuationCommand.token`, `nextContinuationData.continuation`, or a bare `continuation` string in document order. The page-level token (`pageContinuationToken :306`) is the first bare `continuation` string found **outside** section renderers; shelf tokens stay on their section.

### 2.5 Lyrics flow (`lyrics.go:74` `findLyrics`, `internal/lrclib/lrclib.go`)

1. Item has a video id: YTM plain lyrics (`musicDescriptionShelfRenderer`) and, separately via ANDROID_MUSIC, timed lines. A failure here is not fatal.
2. If there are no timed lines: ask LRCLIB. `GET https://lrclib.net/api/get?track_name=&artist_name=[&album_name=][&duration=<rounded seconds>]`, UA `Meiro (https://github.com/elianiva/meiro)`. If that finds lyrics or `instrumental`, use it. Else `GET /api/search?track_name=&artist_name=` for the title and for `ShortTitle(title)` (cut at the first `" - "`, strip trailing `(feat ...)`, `(ft. ...)`, `(with ...)` groups), prefer the first hit with synced lyrics, else the first with plain lyrics. Each request is tried 3 times with delay `attempt * 700 ms` on network errors, 5xx and 429; 404 means none; 15 s client timeout (`lrclib.go:157-215`).
3. Footer text: `Source: YouTube Music` / `Source: LRCLIB`. Plain text comes from YTM if present, else LRCLIB (only when there are no timed lines), else the timed lines joined by newline.
4. SoundCloud tracks go straight to LRCLIB with the title, artist and duration of the row.
5. `ParseLRC` (`lrclib.go:231`): lines with one or more `[mm:ss.xx]` stamps; each stamp makes a line; tags like `[ar: x]` and unstamped lines skipped. A first stamp that jumps back more than 10 s from the previous line starts a new copy of the song; keep the copy with the most non-ASCII letters, ties to the first; then stable-sort by time. `IndexAt(lines, pos)` = last line with `at <= pos`, -1 before the first (`:304`).

### 2.6 Fragile points

| # | Risk | Where |
|---|---|---|
| 1 | Behaviour depends on JSON member order and duplicate names; standard Kotlin JSON maps break it | `orderedjson.go` |
| 2 | Hard-coded client versions (`WEB_REMIX 1.20250219.01.00`, `WEB 2.20260623.01.00`, `ANDROID_MUSIC 7.21.50`). YouTube can start refusing an old version; keep them in one remote-updatable constant | `client.go:25-26`, `:200` |
| 3 | English only (`hl=en`, `gl=US`). Chip labels `songs`/`videos`, " • " subtitle splitting, `M:SS` duration text, "1 hr 30 min" all assume it | `search.go:154`, `renderers.go:229`, `playback.go:718` |
| 4 | Writes can answer 200 with a refusal: `like/*` and `subscription/*` carry an `error` object; `edit_playlist` needs `status` containing `SUCCEEDED`; `playlist/create` needs a `playlistId`; `playlist/delete` fails when `actions` is non-empty. "Already in playlist" is recognised by an `actions[].dedupeOption == DEDUPE_OPTION_SKIP` for the same video id, not by text | `rating.go:56`, `playlists.go:118`, `:236` |
| 5 | Session cookies rotate; a retired session returns signed-out content with 200 | 2.2 |
| 6 | Timed lyrics only exist for the ANDROID_MUSIC client and its user agent | `tracktabs.go:34` |
| 7 | Play reporting depends on `videostatsPlaybackUrl` in the `player` response and on parameter names `ver`, `c`, `cpn`; failures are silent for the user | `playback.go:97` |
| 8 | Renderer names for save and subscribe buttons differ per page and version, so the header is searched, not followed | `library.go:39`, `subscription.go:38` |
| 9 | Any unrecognised renderer is silently dropped: pages can lose rows without errors | `renderers.go:94` |
| 10 | No client-side rate limiting beyond the 4-way continuation drain and the spaced recommendation requests (5.4) | `browse.go:86` |

There are no recorded response fixtures in the repo; `youtube/*_test.go` use inline minimal JSON. The Kotlin port should record a few real responses (home, search, playlist, next, lyrics) as golden files, signed out, before writing parsers.

---

## 3. Stream resolution

### 3.1 YouTube Music: exact yt-dlp invocations (`internal/ytdlp/ytdlp.go:32` `Resolve`, `:48` `resolve`)

Target URL: `https://music.youtube.com/watch?v=<URL-encoded video id>` (`youtube/item.go:54`). Resolution is **anonymous**: no cookies are passed to yt-dlp. Account cookies stay with the InnerTube client.

Attempt 1, no JavaScript runtime ("visionos first"):

```
yt-dlp -f bestaudio -g --no-playlist \
       --extractor-args "youtube:skip=hls,dash;player_client=visionos" \
       "https://music.youtube.com/watch?v=<id>"
```

Attempt 2, only if attempt 1 failed and the context is still alive:

```
yt-dlp -f bestaudio -g --no-playlist \
       --extractor-args "youtube:skip=hls,dash" \
       [--js-runtimes <name>:<absolute path>] \
       "https://music.youtube.com/watch?v=<id>"
```

- `--js-runtimes` picks the first that exists: `node`, then `bun` (looked up on PATH), then the bundled `qjs` as `quickjs:<path>`; if none exists the flag is omitted (`ytdlp.go:147-179`). The comments say the web client path makes yt-dlp run its challenge solver, about 7 s of CPU under QuickJS on a desktop, which is why `visionos` is tried alone first.
- Output handling: stdout, first line, trimmed = the direct media URL; empty = error "could not resolve the audio". Duration: query parameter `dur` of that URL in seconds (float) (`ytdlp.go:182`), 0 if absent. The caller falls back to the row's own duration text when this is 0 (`stream.go:433`).
- Errors: on non-zero exit take the **last** stderr line starting with `ERROR:` as the reason (`ytdlp.go:131` `LastError`). Timeout per resolve: 90 s (`internal/audio/urls.go:16`).
- `skip=hls,dash` is there because "the player API already lists the audio formats; the HLS and DASH manifests only cost two more requests".
- What `bestaudio` yields (itag, container) for `visionos` is **unverified**. Media3 plays WebM/Opus and MP4/AAC.

### 3.2 SoundCloud (`internal/ytdlp/ytdlp.go:219` `ResolveSoundCloud`; `soundcloud/client.go`)

Resolve a track page URL:

```
yt-dlp -f bestaudio --dump-single-json --no-warnings "<page url>"
```
Read JSON fields `url` (direct audio URL, required) and `duration` (seconds, float). The URL is an **HLS manifest (m3u8) for a full track** and an ordinary media file for a 30 s preview; the desktop feeds both to ffmpeg. Media3 needs `media3-exoplayer-hls` for the manifest. A 30 s duration is SoundCloud's preview of a track that is not fully available (`soundcloud/parse.go:77`). No JS runtime is needed.

Reads (all flat, through `read`, `soundcloud/client.go:114`):

```
yt-dlp --flat-playlist --dump-single-json --no-warnings <target>
```

| Use | `<target>` |
|---|---|
| search | `scsearch50:<query>` (`searchLimit = 50`, `:28`) |
| set or user page | the page URL |
| related to a track | track page URL + `/recommended` (not doubled if already present, `:128`) |
| station (optional) | `<scheme>://<host>/stations/track<path>` (`:138`) |

JSON: `{title, entries:[{id,title,uploader,url,webpage_url,duration}]}`. Map: `ID=id`, `URL = webpage_url` if present else `url`, `Title=title`, `Artist=Subtitle=uploader`, `Duration="M:SS"` or `"H:MM:SS"` from seconds (empty if 0), kind track, source `soundcloud`; entries with neither id nor page URL are skipped (`soundcloud/parse.go:46-76`). Search fills every field; set, user, recommended and station listings give only `id` and `url`, so titles are missing there and must be fetched when shown. The Meiro identity of a SoundCloud track is the page URL (also its `provider_track_id`).

SoundCloud has no library or likes: everything else in `soundcloud/unsupported.go` returns `ErrUnsupported`.

### 3.3 URL expiry and IP binding

- Desktop trusts a resolved YouTube URL for **30 minutes** and remembers the last **8** (`internal/audio/urls.go:12-16`), noting real URLs "stay valid for hours". Concurrent requests for one track share a single resolve, a failed resolve is not remembered, `Forget(id)` drops an entry to force a fresh resolve.
- PLAN.md states the audio URL is bound to the requesting IP, so a Wi-Fi to mobile switch invalidates it. The Go code has no IP logic; it recovers by re-resolving. **Unverified**: the real lifetime and the exact binding (not measured; no network calls were made).
- Mid-stream recovery (`stream.go:382` `recoverStream`): up to 3 consecutive recoveries (`maxRecoveries`), waiting `attempt * 500 ms` before each, `Forget` the cached URL, resolve again (cache or download first), and resume at the position reached, keeping pause state. A stream that breaks off within 3 s of the end counts as the end of the track (`endOfTrackLead`). Recovery counts as healthy after 10 s of playback (`stream.go:348-364`).
- Media server requests: the desktop sends the Chrome UA above and `-reconnect 1 -reconnect_streamed 1 -reconnect_delay_max 5 -rw_timeout 15s` (`player/player.go:586-588`) and, for long files, 10 MiB range requests (`rangeSize`, `player.go:47`). Equivalent for Media3: `DefaultHttpDataSource.Factory().setUserAgent(<Chrome UA>).setConnectTimeoutMs(15000).setReadTimeoutMs(15000)` and a `LoadErrorHandlingPolicy` that retries then raises to your re-resolve path.

### 3.4 What the desktop caches, and for how long

| Thing | Where | Lifetime and size |
|---|---|---|
| Resolved audio URL | memory, `audio.URLs` (`urls.go`) | 30 min, 8 entries, per provider (SoundCloud has its own resolver instance) |
| Audio files ("cache") | disk, one folder per provider, key `<source>:<id>` (`stream.go:70` `audioKey`) | LRU by bytes; default 512 MB per provider (`settings.go:227`); size 0 turns the cache off; only complete files count; downloaded in 10 MiB ranges with the Chrome UA, extension from `Content-Type` (`internal/audio/cache.go:552-687`) |
| Downloads ("keep") | disk JSON index + one `.mkv` per track (`download.go:30`, `internal/downloads/downloads.go`) | never evicted, only removed by the user; written with `ffmpeg -i <url> -vn -c copy -f matroska` (`download.go:212`); 30 min timeout |
| Related lists for recommendations | `relatedCache`, JSON | 24 h, entries older than 7 days dropped (`recommendations.go:47`, `:134`) |
| MusicBrainz and ListenBrainz answers | JSON files | TTLs in 4.2 |
| Covers | `internal/artwork` disk cache (**not read in detail**) | see PLAN.md S5 |

Playback order for a track (`stream.go:433` `findAudio`): downloaded file, then audio cache, then resolve over the network. If the resolve fails and `networkOnline()` (a 3 s TCP dial to `music.youtube.com:443`, `download.go:60`) also fails, the error is "offline: this track is not downloaded".

Pre-resolving: when a track starts, the next queue entry's URL is resolved in the background and, if the cache can hold more than one track (`Warmable`), it is downloaded too (`stream.go:492` `warmNext`). 20 s before the end the next track is opened in the player (`preloadLead`, `stream.go:242`, `syncPreload`). After a successful start the played track is added to the cache from the URL already in hand, unless it came from cache or a download (`audioStarted`, `stream.go:218`).

### 3.5 What youtubedl-android must reproduce

1. Both command lines of 3.1 with exactly those flags, in that order of attempts. Parse the first stdout line; do not use `--dump-json` for YTM (the desktop uses `-g`). Keep `ERROR:` last-line extraction for user-facing reasons.
2. The `--js-runtimes` fallback needs a runtime. PLAN.md says QuickJS under youtubedl-android is untested. A3b exists to measure: visionos-only time, fallback time with QuickJS, memory. How youtubedl-android exposes `--extractor-args`, `-g` and `--js-runtimes` and whether it bundles QuickJS and ffmpeg is **unverified** (no access to the library in this repo).
3. The SoundCloud commands of 3.2 (JSON in stdout).
4. A 90 s timeout and cancellation; no cookies; one resolve per track shared by concurrent callers; failures not cached.
5. A cache of resolved URLs with a TTL no longer than 30 min and a way to drop one entry when playback fails (including after a network change).
6. In-app yt-dlp updates (PLAN.md decision): playback can stop until yt-dlp is updated when YouTube changes.
7. Downloads and cache files cannot use ffmpeg `-c copy` unless ffmpeg is bundled. For YouTube, plain ranged HTTP download of the resolved URL (as the cache already does) yields a playable `.webm`/`.m4a`. For SoundCloud HLS manifests a segment downloader is needed (Media3 `HlsDownloader` or ffmpeg from youtubedl-android). Which to use is **unverified** and a design choice.

---

## 4. MusicBrainz and ListenBrainz

Client: `musicbrainz/musicbrainz.go`. All requests send:

```
User-Agent: Playlist Manager SideProject/1.0.0 (muratkirazkaya0@gmail.com)     (musicbrainz.go:31)
Accept: application/json
Content-Type: application/json      // only when there is a body
```
HTTP timeout 20 s; response body read limit 4 MiB. Whether the phone should use a distinct app name in the UA is the owner's call (**unverified** policy).

### 4.1 Endpoints

| Call | Request | Cache key and TTL |
|---|---|---|
| Artist search (`SearchArtists :200`) | `GET https://musicbrainz.org/ws/2/artist?query=<q>&fmt=json&limit=25` | `mb:artist-search:<q>`, 30 days |
| Recording search (`SearchRecordings :222`) | `GET /ws/2/recording?query=recording:"<title>" AND artist:"<artist>"&fmt=json&limit=10`; terms escaped by `\` -> `\\` and `"` -> `\"` and wrapped in quotes (`quoteTerm :277`); artist clause omitted when empty | `mb:recording-search:<query>`, 24 h |
| URL lookup (`LookupURL :381`) | `GET /ws/2/url?resource=<url>&inc=recording-rels&fmt=json` | `mb:url:<resource>`, 90 days; 404 -> cached empty |
| ISRC lookup (`LookupISRC :413`) | `GET /ws/2/isrc/<ISRC upper>?fmt=json` | `mb:isrc:<ISRC>`, 90 days; 404 -> cached empty |
| Recording links (`RecordingLinks :441`) | `GET /ws/2/recording/<mbid>?inc=url-rels&fmt=json`; keep relations whose `type` is `free streaming`, `streaming`, `download for free`, `purchase for download`, `purchase for mail-order`, `streaming (other)` (`playableRelation :470`) | not cached |
| Artist recordings fallback (`mbArtistRecordings`) | `GET /ws/2/recording?artist=<mbid>&fmt=json&limit=25` | not cached |
| Popularity (`ArtistPopularity :329`) | `POST https://api.listenbrainz.org/1/popularity/artist` body `{"artist_mbids":[sorted, deduped]}`; answer `[{artist_mbid,total_listen_count,total_user_count}]` | `lb:artist-popularity:<ids joined by ,>`, 7 days |
| Top recordings (`TopRecordings :362`) | `GET https://api.listenbrainz.org/1/popularity/top-recordings-for-artist/<mbid>` (`recording_mbid, recording_name, artist_name, length, total_user_count, total_listen_count`), falls back to the MusicBrainz recordings above when it errors or is empty | not cached |
| Similar artists (`SimilarArtists :299`) | `GET https://labs.api.listenbrainz.org/similar-artists/json?algorithm=session_based_days_9000_session_300_contribution_5_threshold_15_limit_50_skip_30&artist_mbids=<mbid>`; each `score` divided by the best score so values are 0..1 | `lb:similar-artists:<mbid>`, 7 days; 404 -> cached empty |

Recording search result mapping: `id, title, length (ms), score, artist-credit[]` -> artist = concatenation of each credit's `name` (else `artist.name`) plus `joinphrase`, trimmed; `ArtistID` = first credit's artist id.

Caches are only written on success; errors are never cached (`musicbrainz.go:531` `fetchCached`). Cache file name = first 16 bytes of sha256(key) in hex, content `{storedAt, key, body}` (`musicbrainz/cache.go`).

### 4.2 Rate limiting and cooldown (`musicbrainz/ratelimit.go`)

- Only MusicBrainz (`/ws/2`) requests are paced; ListenBrainz and Labs are not.
- One request in flight at a time (mutex held across the call), and at least 1 s must pass after the **end** of the previous MusicBrainz request before the next one starts (`requestGap`, `musicbrainz.go:36`; `last` is set after the call returns).
- Status 429 or 503 -> `ErrRateLimited` (with `Retry-After` seconds if given). The limiter then sets a cooldown: `strikes++`; pause = `Retry-After` if > 0 else `min(10 s << (strikes-1), 5 min)`; no request is made before `last + min(pause, 5 min)`. A success resets `strikes` to 0. The failed request is not retried by the limiter; the caller gets the error.
- Status 404 -> "not found" (callers treat as empty); any other non-2xx -> error.
- Callers that run batches stop on `ErrRateLimited` (auto-link, 4.5).

### 4.3 Artist pages (`artists.go`)

Search shows MusicBrainz artists beside provider results: search artists, then popularity for their ids (a popularity failure keeps MusicBrainz order), stable sort by listeners descending (`artists.go:62-118`). The artist page lists `TopRecordings`, then resolves playable links for the first 12 recordings only (`mbLinkLimit = 12`, `:45`), one request a second; a recording is playable when a link is a YouTube/YouTube Music watch URL (any `youtube.com` host or `youtu.be`) or a SoundCloud URL, preferring type `free streaming` (`playableRecording :441`). Others are shown greyed out.

### 4.4 Matching rules (`internal/match/match.go`)

`match.Match(a, b)` returns `matched` or `unknown` (never `other`; `other` is only a user mark):

1. If both ISRCs are non-empty and equal -> matched. **No desktop code fills ISRC**; only the rule and the sync field exist (grep: `ISRC` appears only in `match`, `sync` and `musicbrainz`).
2. Else matched iff `Normalize(title)` equal **and** `Normalize(artist)` equal **and** durations within **4 s** (`DurationTolerance`, `match.go:45`), where a duration <= 0 is unknown and never narrows.
3. Else unknown.

`Normalize(name)` (`match.go:98`), in this exact order:

1. Fold per rune: `ı İ I` -> `i`, `ş Ş` -> `s`, `ğ Ğ` -> `g`, `ü Ü` -> `u`, `ö Ö` -> `o`, `ç Ç` -> `c`.
2. Lowercase.
3. Drop everything inside `()` or `[]`, brackets included; one shared depth counter, so `(a]` closes; an unmatched closing bracket at depth 0 is dropped, and an unmatched opening bracket drops the rest of the string (`dropBrackets :133`). Only ASCII brackets.
4. Remove, in this order and as plain **substrings** (no word boundary), each of: `official music video`, `official video`, `official audio`, `official lyric video`, `lyric video`, `sped up`, `slowed down`, `slowed`, `remastered`, `remaster`, `explicit`, `lyrics`, `audio`, `video`, replacing with a space (`noise`, `:77`). Because it is substring replacement, `Audioslave` becomes `slave`; keep this behaviour for parity.
5. Every rune that is not a Unicode letter or digit becomes a space.
6. Split on whitespace and rejoin with single spaces.

Golden cases (`internal/match/match_test.go`): `Just a Moment (feat. Someone)` -> `just a moment`; `Ame [Remastered]` -> `ame`; `Şımarık` -> `simarik`; `İstanbul'da` -> `istanbul da`; `Gülüşün` -> `gulusun`; `Song - Sped Up` -> `song`; `Some Song!!` -> `some song`. In Kotlin iterate code points, use `Character.isLetter/isDigit` on code points, and lowercase with `Locale.ROOT` after the manual fold.

The same `Normalize` is the key for artists (`mblinks.ArtistKey`, `internal/mblinks/mblinks.go:138`) and, with the track title, for taste and recommendation keys (5.2). A list refuses a duplicate track when `(source, key)` equal, or when both titles normalise non-empty and `Match` says matched with lengths (`internal/library/library.go:766` `sameItem`).

### 4.5 Auto-link and certainty (`mblink.go`)

- A song is auto-linked at most once per 30 days unless it is `matched` or `other`: `needsAutoLink` (`mblink.go:371`), `Fresh(checkedAt)` = checked within `RecheckAfter` = 30 days (`internal/mblinks/mblinks.go:35`). Songs are processed one after another, in the background.
- For each: `SearchRecordings(title, firstArtist)`; `certainRecording` (`:448`) picks a result only if **all** hold:
  - the song's duration is known (> 0) and its normalised artist is non-empty;
  - candidate has `LengthMS > 0` and `match.Match` (names equal after `Normalize`, durations within 4 s) says matched;
  - if two or more matching candidates exist and their **first-artist MBIDs differ**, choose none (ambiguous);
  - among the matching ones take the highest MusicBrainz `Score`.
- There is **no numeric score threshold**. The "percent fit" shown in the manual dialog is the raw MusicBrainz `score`; manual candidates are sorted by `score/10` bucket descending, then, for songs, by smallest length distance, for artists by ListenBrainz listeners (`mblink.go:281`, `:320`). The dialog lists 6 (`linkResultsShown`, `:43`). A song search with the artist that finds nothing is retried without the artist.
- Outcome: matched -> state `matched`, `mbid` set, and if the recording names an artist the artist is linked `matched` too; not sure -> keep the previous state or `unknown`, and set `checked_at = now` so it is not retried for 30 days (`applyAutoLink :427`, `recordSong :137`). On `ErrRateLimited` the whole batch stops and the rest stay as they are.
- Every change is queued as a song link (one per provider id the song is known by, `songRefs :92`) and an artist link, then pushed with `/sync/links`. On pull, `Merge` keeps the record with the later `updated_at` (`mblinks.go:226`); the desktop sends all its links after a pull (`syncpage.go:572` `pullLinks`, `queueAllLinks`).
- The "other provider" search (`crosssource.go`): for a song held by one provider, search the other provider with `"<title> <artist>"` (30 s timeout), accept the first track for which `match.Match` is matched, and store an `alternate`; "not found" is stored too and re-checked after 30 days.

---

## 5. Recommendation and history rules to keep identical across devices

### 5.1 Counting rule (`internal/history/history.go:53` `Counts`; server `statsCountRule`, `server:store_data.go:463`)

A play counts iff `playedMs >= 30000` **or** (`durationMs > 0` and `playedMs * 2 >= durationMs`). The server applies the same rule in SQL (`duration_ms IS NOT NULL AND duration_ms > 0`). Plays recorded before timing existed (no event id) always count (`Counted :62`); the phone has none.

How a play is measured on the desktop:

- A history entry (event id = fresh UUID v4, source, video id/page URL, title, artist, `durationMs` parsed from the row's duration text, device, `playedAt = start time`) is created **when audio starts** and `playedMs` is updated as it plays (`stats.go:20` `recordHistory`, `:64` `observeRecord`).
- The event is queued for the server only when the play **ends** (`finishPlayRecord`, `stats.go:84`): `completed=true` if it ended by reaching the end (`trackEnded`, `playback.go:486`), `completed=false` for a skip, a new track, or quitting (`main.go:337`).
- `playedMs` comes from `Watch.Observe(at, position)` (`history.go:342`), samples every 250 ms (`pollInterval`, `playback.go:405`): the movement since the previous sample is credited only if `0 < wait <= 2 s` and `0 <= moved <= wait + 500 ms`. So pauses, stalls, seeks forward and a machine asleep add nothing. `Break()` is called while the stream is being found, while seeking or scrubbing, and while buffering (`scrobble.go:227` `observeListen`); the next sample only re-anchors. Use `SystemClock.elapsedRealtime()` for `at` and `player.currentPosition` for position.
- Local history is capped at the latest **5000** plays (`MaxEntries`, `history.go:20`); `Merge` dedupes by event id, sorts by `playedAt` and keeps the newest 5000. Keep the same cap on the phone, because the taste profile is built from this window and identical recommendations need identical input.
- `device` is the machine name; downloads use it as part of their key. Use a stable name per install (for example `Build.MODEL`); changing it creates new download rows.

### 5.2 Taste profile (`internal/taste/taste.go`)

Keys: `TrackKey(title, artist) = Normalize(title) + "\u0000" + Normalize(artist)` (empty title -> no key, play ignored); `ArtistKey = Normalize(artist)`.

| Constant | Value | Cite |
|---|---|---|
| Vote heard through | +1.0 | `taste.go:63` |
| Vote partly heard | +0.3 | |
| Vote skipped | -0.7 | |
| Vote like / saved / downloaded | +3.0 / +2.0 / +2.0 | `:67-69` |
| Repeat factor | 0.8 per earlier counted hearing of the same track | `:72` |
| Half-life (plays) | 30 days | `:77` |
| Explicit votes half-life | 30 days x 3 = 90 days | `explicitSlowdown :79` |

Vote of a play (`voteOf :174`):

- `completed` -> heard.
- duration known: `playedMs/durationMs >= 0.8` heard; `>= 0.3` or `playedMs >= 30000` partly; else skipped.
- duration unknown: `>= 90000` ms heard; `>= 30000` partly; else skipped.

Build (`taste.go:111`): plays sorted by time ascending (stable); plays after `now` ignored; for each play with a key: if the vote is positive multiply by `0.8^heard[key]` and then increment `heard[key]` (negative votes do not count as hearings and are not discounted); weight = vote x `0.5^(age/halfLife)` (age <= 0 -> 1); add to the track score and the artist score; per artist count `plays++` and `left++` when the vote is negative; remember the latest play time per track key; keep the latest display name per artist. Signals add `vote x 0.5^(age/(90 days))` to both the track and artist scores; an artist first seen through a signal gets its display name from it.

Signals the desktop feeds (`recommendations.go:169` `tasteSignals`): every track of every list **except the Daily mix** (matched by the settings' Daily mix id or by server id == `DailyMixID()`), as `Saved` at the track's `AddedAt`; every download as `Downloaded` at `DownloadedAt`. YouTube likes are **not** fed (the `Like` kind exists but is unused). Input plays: every history entry mapped by `tastePlays` (`:151`), provider default `ytm`.

Queries: `TopArtists(n)`: artists with score > 0, sorted by score descending then key ascending (`:243`); `SkipRate(artist) = left/plays`; `Known(artist)` = artist key present in the score map (any play or signal, even negative); `Track`, `Artist`: scores, 0 when unknown.

### 5.3 Ranking (`internal/recommend/recommend.go`)

Defaults (`:87`): `N = 30`, `Explore = 0.3` (the Daily mix uses `settings.Explore / 100`, default 30, clamp 0..100, `settings.go:167`, `:353`), `PerArtist = 2`, `Recent = 7 days`, `Salt = local date "yyyy-MM-dd"`.

Relation weights (`:36`): YouTubeRelated 1.0, SoundCloudRelated 0.8, SimilarArtist 0.7.

Algorithm (`Rank :92`):

1. For each candidate with a key and `seed.score > 0`: `contribution = seed.score x weight[relation] x (strength > 0 ? strength : 1)`. Pool by `TrackKey`: `total += contribution`; the candidate with the largest single contribution (strictly greater, first wins ties) supplies the pick's seed name and relation.
2. `maxArtist` = score of the top artist (`TopArtists(1)`).
3. For each pooled pick not skipped: `score = ln(1 + total)`; if `maxArtist > 0`, `score += 0.3 x max(0, artistScore(pick.artist)) / maxArtist`; if the track was last played less than `Recent` ago, `score x= 0.1`; `Fresh = !Known(artist)`; `Why = Fresh ? "New to you, like <seed>" : "Because you listen to <seed>"`.
4. Skipped entirely (`skip :161`): `trackScore < 0`, or `artistScore < -0.5`, or (`skipRate > 0.6` and the artist is known and `artistScore <= 0`), or last played less than `Recent / 2` (3.5 days) ago.
5. Sort by score descending; ties by `fnv32a(salt + "\u0000" + TrackKey)` ascending (unsigned) (`tie :182`). FNV-1a 32: offset `0x811c9dc5`, prime `0x01000193`, over UTF-8 bytes.
6. `choose :192` is greedy: for position `k` (0-based), `wantFresh = fresh < Explore x (k + 1)`; take the first unused pick with `Fresh == wantFresh` that is allowed (fewer than `PerArtist` picks by the same artist key among the **last 9** already chosen); if none, take the first allowed of the other kind; stop when no pick is allowed or `N` reached.

Floating point: use `Double`, `ln` = `Math.log1p`, and `0.5^x` with `Math.pow`. Go and the JVM may differ in the last ulp, so test with order and set overlap, not bit equality (**unverified** how often ties flip).

### 5.4 Gathering candidates and the Daily mix (`recommendations.go`)

| Constant | Value | Line |
|---|---|---|
| Seed artists | top 8 (`recSeeds`) | `:39` |
| Request budget per run | 10 YouTube, 3 SoundCloud, 4 similar-artist searches | `:43-45` |
| Pause before each uncached request | YouTube 1.5 s, SoundCloud 3 s (the similar-artist search also uses the YouTube pause) | `:60-61` |
| Related cache | 24 h; entries older than 7 days dropped | `:47`, `:134` |
| Daily mix size | 30 | `:52` |
| Minimum picks for a mix | 5 | `:62` |
| Songs added to the queue per fill | 5 | `:50` |
| Run timeout | 10 min | `:54` |
| Check cadence | every hour, first check 45 s after launch (`main.go:207`) | |

Flow (`refreshRecommendations :373`): runs only if the Suggestions setting is on, no run is in flight, the profile is not empty and there is at least one seed.

1. Seeds: for each of the 8 top artists, the **latest played** track by that artist (history order by `playedAt`), with that artist's MusicBrainz id when the link store has the artist as `matched` (`recSeedTracks :205`).
2. For each seed, in order, using the cache key `"<provider>|<id>"`: on a miss, spend one budget unit, pause, then `GetRelated(videoId)` for YTM or SoundCloud `GetRelated(pageUrl)`; keep only playable tracks from `items` and from every section's items (`relatedTracks :232`); cache them. Add them as candidates (strength 1) with relation YouTubeRelated or SoundCloudRelated and `Seed{name, score}` of the artist.
3. If the seed has an MBID and similar-artist budget remains: `SimilarArtists(mbid)`; for the first 3 results (best first) use cache key `"similar|<mbid>"`; on a miss spend one budget unit, pause, search YouTube Music for the artist's name and keep the first **2** tracks whose artist key equals the similar artist's key; add them with relation SimilarArtist and strength = the artist's similarity.
4. Rank with the options above (`N = 30`). Ranking uses the profile as it is when the gathering finishes, not when it started (`:401`).
5. `writeDailyMix :423`: needs >= 5 picks; skip if today's mix was already made (`DailyMixDate == today()` where `today()` is the local calendar date `yyyy-MM-dd`, `:414`) unless the user asked for a new one; first try `adoptDailyMix` (1.4.10); otherwise find or create the list with id `DailyMixID()` named `Daily mix`, and **replace** its items with the picks (an older mix under another id is deleted); record id and date.
6. In the queue (`withSuggestions :501`): put up to 5 picks not yet used, not already queued and not the current track ahead of YouTube's generated tracks for each fill, marking them used for this ranking; the rest of YTM's generated tracks follow, minus those already chosen.

---

## 6. Port checklist (PLAN.md A3b, A3c, A3d)

Order inside each stage is the order to build in. Every line points at the Go source to port or test against.

### A3b Trial APK: resolve and play

- [ ] Gradle project, Compose, Media3 (`exoplayer`, `session`, `exoplayer-hls`), youtubedl-android. Decision record in PLAN.md A3.
- [ ] Resolver module with the two YTM command lines and error extraction: `internal/ytdlp/ytdlp.go:32-88`, `:131`, `:182`; SoundCloud resolve `:219`.
- [ ] Media3 `DataSource` with the Chrome UA, 15 s timeouts, HLS support: `player/player.go:43`, `:586`; `stream.go:382` (re-resolve then resume).
- [ ] Measure and record: visionos-only resolve time, JS fallback time (QuickJS), first-audio latency, memory, and what happens on Wi-Fi to mobile data. Answers the open items in section 8.
- [ ] URL cache with 30 min TTL, 8 entries, single-flight, forget-on-failure: `internal/audio/urls.go`.

### A3c First release: Meiro login, lists, playback, plays, search

Server and sync

- [ ] Login screen and token storage (EncryptedSharedPreferences); handle 401 and the 30 day expiry: `internal/sync/sync.go:238`, `syncpage.go:278`.
- [ ] `deriveId` + golden test of section 1.4.9; `NewID` = random UUID: `internal/sync/uuid.go`.
- [ ] Local store for lists, items, tombstones (90 days), deleted lists, and the metadata in 1.4.6: `internal/library/library.go`.
- [ ] Outbox (Room): collapse rules, batch 200, event cap 5000, push order, 404-drop, backoff; quarantine 400s: `internal/sync/outbox.go`, `syncpage.go:120`.
- [ ] Pull changes with the cursor loop and merge rules, first-sync rules, shared Daily mix adoption: `internal/sync/changes.go`, `syncpull.go:24-334`.
- [ ] Pull events into history with dedupe and the 5000 cap: `internal/sync/events.go`, `syncpull.go:83-146`, `internal/history/history.go:86`.
- [ ] Links: pull and merge, push on change: `internal/sync/links.go`, `internal/mblinks/mblinks.go:226`, `syncpage.go:572`.
- [ ] Be ready for `note`, play-event `thumbnail` and history clear (1.6) once merged.

Playback

- [ ] MediaSession service: background play, lock screen, one media notification, no per-track notification (PLAN.md A3).
- [ ] Order downloaded, cache, network; offline error; pre-resolve the next track; preload 20 s before the end: `stream.go:433`, `:492`, `:242`.
- [ ] Play recording and counting: `Watch` algorithm, event on finish, completed only at natural end: `internal/history/history.go:320-345`, `stats.go:20-124`, `playback.go:486`, `scrobble.go:227`.
- [ ] Queue with up-next generation, 100 generated tracks, same-song filter: `queue.go:16`, `:242-345`, `upnext.go`.
- [ ] Single tap plays (PLAN.md decision), item target rules: `media/item.go:121`.

Search and YouTube Music anonymous reads

- [ ] Ordered JSON reader and the `browseParts` walk with unit tests from recorded responses: `youtube/orderedjson.go`, `youtube/renderers.go`.
- [ ] Search, suggestions, continuation, filters: `youtube/search.go`; SoundCloud search `soundcloud/client.go:70`, parse `soundcloud/parse.go`.
- [ ] Merge of the two providers, local fallback: `search.go:171-300`.
- [ ] Home shelves, album, artist, playlist pages, related, up next, lyrics (YTM + LRCLIB): `youtube/browse.go`, `youtube/tracktabs.go`, `youtube/upnext.go`, `lyrics.go`, `internal/lrclib/lrclib.go`.

### A3d Later: account, linking, offline, recommendations, stats

- [ ] YouTube Music sign-in with an in-app web page, cookie read, immediate WebView wipe, session check, SAPISIDHASH: `internal/cookies/window.go`, `youtube/cookie.go`, `account.go:189-260`.
- [ ] Library landing with the 4-way continuation drain, likes, save album/playlist, playlist create/add/remove/delete, follow artist, brand channels: `youtube/browse.go:58-152`, `rating.go`, `library.go`, `playlists.go`, `subscription.go`, `accounts.go`.
- [ ] Optional play reporting to YTM history and loudness: `youtube/playback.go`.
- [ ] MusicBrainz client with the 1 req/s limiter, cooldown and TTL cache: `musicbrainz/musicbrainz.go`, `musicbrainz/ratelimit.go`, `musicbrainz/cache.go`.
- [ ] `Normalize` and `Match` with golden tests: `internal/match/match.go`, `match_test.go`.
- [ ] Auto-link and the manual link dialog; cross-provider alternates: `mblink.go:371-474`, `crosssource.go`.
- [ ] Artist page (MB search, popularity, top recordings, 12 links): `artists.go:45-130`, `:260-470`.
- [ ] Downloads (keep) and the cache with the sizes of 3.4; push downloads incl. `deleted`; storage screen: `download.go`, `internal/downloads/downloads.go`, `internal/audio/cache.go`.
- [ ] Taste profile, ranking, candidate gathering, Daily mix, queue suggestions: `internal/taste/taste.go`, `internal/recommend/recommend.go`, `recommendations.go`. Port the Go tests (`taste_test.go`, `recommend_test.go`) as the golden suite.
- [ ] Listening history screen and Ozet screen computed locally from history: `historypage.go`, `internal/history/history.go:242` `Summarize`, `stats.go`.
- [ ] In-app yt-dlp update flow (PLAN.md decision).

---

## 7. Constants cheat sheet

| Constant | Value | Cite |
|---|---|---|
| Default server | `https://meiro.muratkirazkaya.com` | `internal/sync/sync.go:20` |
| Session TTL | 30 days, not sliding | `server:handlers_auth.go:11` |
| Feed limit default / max | 500 / 1000 | `server:handlers_feed.go:15` |
| Push events max | 1000 per request | `server:handlers_sync.go:12` |
| Links max | 1000 per list | `server:links.go` `maxSyncLinks` |
| Outbox batch / event cap | 200 / 5000 | `outbox.go:19`, `:23` |
| Sync attempts / backoff | 4 / 2 s doubling, cap 5 min | `syncpage.go:38`, `outbox.go:415` |
| Sync timer | 5 min | `syncpage.go:41` |
| Tombstone life | 90 days | `internal/library/library.go:114` |
| History cap | 5000 | `internal/history/history.go:20` |
| Counting rule | >= 30 s or >= half | `history.go:53` |
| Watch gap / slack / poll | 2 s / 500 ms / 250 ms | `history.go:320`, `playback.go:405` |
| Match duration tolerance | 4 s | `internal/match/match.go:45` |
| Same-song tolerance in queue | 3 s | `queue.go:242` |
| Auto-link recheck | 30 days | `internal/mblinks/mblinks.go:35` |
| MB gap / cooldown | 1 s / 10 s doubling to 5 min | `musicbrainz.go:36`, `ratelimit.go:86` |
| MB TTLs | artist 30 d, recording 24 h, lookups 90 d, popularity and similar 7 d | `musicbrainz.go:45-53` |
| URL cache | 30 min, 8 entries | `internal/audio/urls.go:12` |
| Resolve timeout | 90 s | `urls.go:16` |
| Audio cache default | 512 MB per provider | `internal/settings/settings.go:227` |
| Preload lead | 20 s | `stream.go:242` |
| Stream recoveries | 3, backoff 500 ms x attempt | `stream.go:348` |
| Search timeout | 7 s per provider | `search.go:27` |
| Autoplay queue limit | 100 | `queue.go:16` |
| Taste half-life / votes | 30 d / +1, +0.3, -0.7, like +3, saved +2, downloaded +2, repeat 0.8 | `taste.go:63-79` |
| Daily mix | 30 picks, min 5, explore 0.3, per artist 2, recent 7 d, salt local date | `recommend.go:87`, `recommendations.go:52`, `:62` |
| Seeds / budgets / pauses | 8 / 10, 3, 4 / 1.5 s, 3 s | `recommendations.go:39-61` |
| Related cache | 24 h | `recommendations.go:47` |
| Daily mix id | `bab208cb-a929-55cd-a091-c414d0e03864` | derived, `uuid.go:81` |
| Provider strings | `ytm`, `soundcloud` | `media/source.go` |
| Seed color | `#6750a4` | PLAN.md A3 |

---

## 8. Claims marked unverified

1. Whether prod (`meiro.muratkirazkaya.com`) runs the current server code; no network call was made. PLAN.md recorded an older build on 2026-10-29.
2. How youtubedl-android exposes `--extractor-args`, `-g`, `--js-runtimes`, and whether it bundles QuickJS or ffmpeg; whether QuickJS runs on a phone (PLAN.md also lists this as untested).
3. Which format `-f bestaudio` selects for the `visionos` client.
4. The real lifetime and exact IP binding of YouTube audio URLs; the code only says "hours" and caches for 30 minutes.
5. That Google accepts the music.youtube.com sign-in inside an Android WebView, and that `CookieManager.getCookie` includes HttpOnly cookies such as `SAPISID`.
6. That YouTube actually sends duplicate JSON member names in InnerTube responses (the Go parser allows them; the port should too).
7. That ANDROID_MUSIC timed lyrics still work today (desktop code path only, not run).
8. The time zone used by the server's stats bucketing (`date_trunc` uses the DB session zone).
9. Whether the dashboard reads stale download rows; recommendation to send `deleted:true` is a design suggestion.
10. That `tracks.status` is never written (based on grep).
11. Whether the phone should use a distinct MusicBrainz User-Agent.
12. Go versus JVM floating-point ulp differences affecting rank ties.
13. How to download SoundCloud HLS on Android (Media3 downloader versus ffmpeg).
14. The three in-flight server features (1.6): described from an interim working tree and may change.
15. `internal/artwork` (cover cache) was not read; only referenced from PLAN.md.
16. That the anonymous client gets non-empty Home, related and lyrics responses from a phone network; the desktop code path was read, not run.
