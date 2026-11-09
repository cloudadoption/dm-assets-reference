# DM Asset References: MVP

This MVP shows, close to real time, which Sites pages reference a Dynamic Media (remote DAM) asset. It works when the Sites instance is on-prem or on AMS behind the customer's firewall, because the Sites instance only makes **outbound** HTTPS calls. It doesn't depend on CDN logs or Databricks.

```
 Sites AUTHOR (6.5 on-prem / AMS / Cloud)                       Assets AUTHOR (Cloud Service)
 ┌───────────────────────────────────────────┐                ┌─────────────────────────────────────┐
 │ Replication event ─► delayed Sling job    │                │ POST /bin/dmrefs/ingest             │
 │ (Activate/Deactivate/Delete)  (~10 s)     │                │   authN: IMS OAuth token            │
 │   │ read page's CURRENT status, scan page │                │   authZ: dm-reference-writers group │
 │   ▼ + template structure (cached)         │ HTTPS          │   validate ─► vanity→uuid ─► /var   │
 │ push batch ───────────────────────────────┼─(outbound)────►│                                     │
 │ Manual JMX backfill ─► same queue         │                │ GET  /bin/dmrefs/references         │
 └───────────────────────────────────────────┘                │   ?assetId=urn:aaid:aem:… | ?path=… │
                                                               └─────────────────────────────────────┘
```

For a non-technical overview, see [docs/wiki](docs/wiki/DM-Asset-References-Wiki.md). For why it's built this way, see [docs/design-decisions.md](docs/design-decisions.md). For the code map, conventions and backlog, see [.github/copilot-instructions.md](.github/copilot-instructions.md).

No product code is changed. Each side ships as one content package (bundle plus OSGi configs), which you can install on an RDE or with Package Manager.

## Layout

```
dm-asset-references/
  docs/wiki/                    plain-language wiki page + architecture diagram (PNG/SVG)
  sites-reference-tracker/      Sites author service (builds for 6.5 by default, -Pcloud for Cloud Service)
    core/                       bundle: com.adobe.dmrefs.sites.core
    all/                        package: bundle + repoinit + service user mapping + configs
  assets-reference-receiver/    Assets author receiver (Cloud Service)
    core/                       bundle: com.adobe.dmrefs.assets.core
    all/                        package: bundle + repoinit + service user mapping + configs
  sample-ingest.json            example payload for curl testing
```

## Sites side: how it works

Sites is **stateless**. It stores nothing. It tracks **publish, unpublish and delete** reliably (queued and retried), and **page edits** best effort. The Sites service user has `jcr:read` on `/content` and `/conf` only.

| Trigger | What happens |
|---|---|
| Replication: Activate, Deactivate or Delete | `ReplicationEventListener` queues one Sling job per chunk of paths, scheduled `job.delay.seconds` (default 10) in the future |
| The publish job runs (queue `dmrefs-publish`) | For each path it **ignores the event's action** and reads the page's current `cq:lastReplicationAction`. `Activate` means live: scan and send the **published** set. Anything else means not live: send an empty published set. A missing page is sent as deleted (both sets cleared) |
| Page edit (create, modify, restore, move, delete) | `DraftTracker` notes the page in memory. Once the page has had no edits for `draft.quiet.seconds` (default 60), or `draft.max.wait.seconds` (default 300) have passed, its sweep (every 15 s) queues it. The draft job (queue `dmrefs-draft`) scans it and sends the **draft** set, unless its references are unchanged since the last delivery (fingerprint): then there is no HTTP call |
| Manual backfill (JMX) | Queues every page under a root whose last action is `Activate`, in chunks with a pause between them. It loads published sets only; a page's draft set arrives with its next edit |

- **Scanning** reads every String and String[] property under `jcr:content` and matches it against the configured regexes:
  - `uuid` patterns: `urn:aaid:aem:<uuid>` and the legacy `dm-aid--<uuid>`. Percent-encoded (`urn%3Aaaid%3Aaem%3A…`) and JSON-escaped (`\/`) URLs are decoded before matching.
  - `vanity` patterns.
  - It follows `fragmentVariationPath` and `fragmentPath` into Experience Fragments and Content Fragments, up to depth 3, with cycle protection and a cap of 20,000 nodes per page. A page that hits the cap is sent as `partial`, so Assets only adds references and never removes any.
- **Template structure:** the page's `cq:template` structure (`<template>/structure/jcr:content`) is scanned too, so a logo or header in the template counts as a reference from every page that uses it. Results are cached per template and re-checked against the last-modified time of the structure and of each fragment it embeds, with a TTL (`template.cache.ttl.seconds`, default 600). Policies are not scanned.
- **Repeat publishes are merged:** a page published several times inside the delay window is scanned and pushed once. A page's "already delivered" mark is only set after Assets answers `2xx`, so a failed push is never skipped on retry.
- **Failures:** `5xx`, transport errors and auth failures fail the job, and Sling retries it (queue `dmrefs-publish`: ordered, 288 retries, 5 minutes apart, about 24 hours). A `400` or `413` makes the job resend each page on its own, so one bad page is logged at ERROR and dropped without blocking the queue.
- **Authentication:** `auth.mode=oauth` (default) exchanges the technical account's client credentials at IMS (`ims.token.url`, restricted to known Adobe IMS hosts) and keeps the token in memory until 5 minutes before it expires. On a `401` or `403` it refreshes once and retries. `auth.mode=static` uses `bearer.token` and is for development only.
- **Backfill:** `com.adobe.dmrefs:type=Backfill` in `/system/console/jmx` has `start(root)`, `status()` and `cancel()`. It runs in its own queue (`dmrefs-backfill`), isn't resumable (run it again after a restart; Assets applies by `seq`), and doesn't wait for the job delay. There's no servlet, so no new HTTP surface on Sites.
- **Draft and published are independent.** A publish update carries only `published[]` and a draft update only `author[]`; Assets leaves the other set alone, so unpublishing keeps a page's draft rows and editing a draft never touches what is live.
- **Draft cost:** an edit costs one in-memory update (no JCR access). Forty saves over ten minutes make a few scans, not forty. An edit that changes no DM reference costs one scan and no HTTP call. With nothing edited, the sweeper does no JCR read and no HTTP call. `draft.ignored.users` (default `replication-service`) lists users whose edits are skipped; set `draft.enabled=false` to switch the whole path off.
- **Author only:** nothing runs on publish servers, and there's no scheduled bulk job.
- **Limits:** a draft edit still waiting in memory is lost on a restart (the next edit of that page restores it), and an Experience Fragment or template edit is not seen by pages until they are edited, republished or backfilled (spec §8).

## Assets side: how it works

`/var/dm-references` on the Assets **author** tier:

```
assets/<u0u1>/<u2u3>/<uuid>/<instanceId>/<sha256(pagePath)>
    instanceId, pagePath, assetId, author, published, referencedAs[], authorUrl (optional), updatedAt
unresolved/<v0v1>/<sha256(vanity)>/<instanceId>/<sha256(pagePath)>
    instanceId, pagePath, vanity, author, published, updatedAt
pages/<instanceId>/<p0p1>/<sha256(pagePath)>
    instanceId, pagePath, seq, deleted, keys[], updatedAt
```

- **Asset nodes are never modified.** There's one node per (asset, instance, page), so many pages referencing one popular asset don't cause write conflicts. Reference nodes are `oak:Unstructured`, because popular assets collect very large child lists that are slow on orderable node types.
- **A `partial` page only adds.** If the sender's scan was cut short, nothing is deleted and flags are never cleared; the page index keeps the old and new keys.
- **An unseen page with no references writes nothing.** Unpublishing a page that was never tracked leaves no node behind.
- **Each push replaces the page's previous entries.** The page index records which entries belong to the page, so stale entries are deleted and the store converges no matter how often a page is re-sent. A `seq` lower than or equal to the stored one is ignored as stale, which makes retries and out-of-order deliveries safe.
- **Vanity resolution** happens at ingest. The receiver runs a JCR-SQL2 query with a bound parameter on `jcr:content/metadata/<vanity.metadata.property>`:
  - If exactly one asset matches, the entry is stored under that asset's UUID, with `referencedAs: ["vanity:summer-hero"]`.
  - If no asset matches, or more than one does, the entry is stored under `unresolved/`. The read endpoint still finds these by looking up the asset's current vanity ID, so they appear once the mapping exists.
- **Reads** resolve the asset with the **caller's** session, so a user can only see references for assets they're allowed to read. The reference rows themselves are read with the service user.

## API contract

### `POST /bin/dmrefs/ingest`

- **Headers:** `Authorization: Bearer <IMS token>` and `Content-Type: application/json`.
- **Who can call it:** the caller must be in the group `dm-reference-writers`.
- **Body:** see `sample-ingest.json`. Each page entry carries the full set of **one or both** states (replace semantics, not diffs). `author[]` is the draft set and `published[]` the live set; **a set that is absent is left unchanged**, and a present one, even empty, replaces the stored set. A `deleted` page clears both. The Sites tracker sends only one set per entry. That makes every push idempotent and safe to apply in any order, and each page's sets are small.

| Field | Rule |
|---|---|
| `schemaVersion` | `1` |
| `instanceId` | `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` (also used as a node name) |
| `authorUrl` | Optional. Public https base URL of the sending author instance, e.g. `https://author.acme.com`. No credentials, query or fragment, at most 200 characters. An invalid value fails validation with `400`. |
| `pages` | 1–200 per request, no duplicate `pagePath` |
| `pagePath` | Under `/content`, at most 1024 characters, no `..`, no `[]\|*` or control characters |
| `seq` | Positive long. Tracked per state (`author` and `published`), so a draft update is never dropped because of a newer publish update |
| `deleted` | If `true`, both sets must be empty or absent, and `partial` must not be set |
| `partial` | Optional boolean. `true` means the sender's scan was cut short, so Assets only adds references (nothing is removed) |
| `author[]` / `published[]` | Optional, but an entry needs at least one unless `deleted`. Up to 500 each. `{"type":"uuid","ref":"urn:aaid:aem:<uuid>"}` or `{"type":"vanity","ref":"[A-Za-z0-9._~-]{1,200}"}` |

The body is capped at 1 MB. The whole request is validated before anything is written.

| Response | Meaning |
|---|---|
| `200 {"applied":n,"stale":n,"unresolvedVanityRefs":n,"failed":0}` | All pages applied or skipped as stale |
| `400` | Malformed JSON or validation failed (`details` lists field positions; submitted values aren't echoed) |
| `403` | Caller isn't in the writer group, or the instance ID isn't allowed |
| `413` | Body too large |
| `415` | Content type isn't JSON |
| `503` | One or more pages failed to persist; the sender retries the batch |

### `GET /bin/dmrefs/references?assetId=urn:aaid:aem:<uuid>` (or `?path=/content/dam/...`)

```json
{
  "assetId": "urn:aaid:aem:3f2a…",
  "assetPath": "/content/dam/acme/hero.jpg",
  "vanity": "summer-hero",
  "total": 1,
  "truncated": false,
  "references": [
    { "instanceId": "acme-prod-author", "pagePath": "/content/acme/us/en/home",
      "author": true, "published": true,
      "referencedAs": ["urn:aaid:aem:3f2a…", "vanity:summer-hero"],
      "authorUrl": "https://author.acme.com",
      "pageUrl": "https://author.acme.com/content/acme/us/en/home.html",
      "updatedAt": "2026-11-04T10:15:00.000Z" }
  ]
}
```

`authorUrl` and `pageUrl` appear only when the sending instance supplied a valid author URL. `pageUrl` is the author URL plus the URL-encoded page path plus `.html`.

## Build

Both projects build and their tests pass (see Verification status). Build against the SDK or uber-jar that matches the runtime you deploy to: a bundle built against a newer SDK can import package versions the target doesn't provide (for example `slf4j` 2.x), and then stays in the `Installed` state instead of starting.

Set `aem.sdk.api.version` to the SDK version your Cloud project uses. The poms ship with a placeholder so you don't build against a mismatched version by accident.

```bash
# Sites: AEM 6.5 (Java 8, uber-jar; adjust aem65.uberjar.version to the customer's service pack)
cd sites-reference-tracker && mvn clean install

# Sites: Cloud Service RDE (Java 11)
cd sites-reference-tracker && mvn clean install -Pcloud -Daem.sdk.api.version=<sdk-version>

# Assets receiver (Cloud Service)
cd assets-reference-receiver && mvn clean install -Daem.sdk.api.version=<sdk-version>
```

The installable packages are `*/all/target/*-all-0.1.0-SNAPSHOT.zip`.

## Deploy and test

### 1. Assets RDE

```bash
aio aem rde install assets-reference-receiver/all/target/dmrefs-assets-all-0.1.0-SNAPSHOT.zip
```

1. Set `vanity.metadata.property` in `ReceiverSettings` to wherever the customer stores vanity IDs.
2. Create an **OAuth server-to-server credential** (technical account) for the Assets environment. The Sites tracker exchanges its client ID and secret for a token at IMS and refreshes it automatically. For a quick manual `curl`, the Developer Console **local development token** also works (it expires after 24 hours).
3. Make one call with the token so its user is created in AEM, then find the user's AEM ID with `GET /libs/granite/security/currentuser.json`. It is `<guid>@techacct.adobe.com` and differs from the `email` in the credential JSON. Add that user to **`dm-reference-writers`**. If you do it with repoinit, quote the ID: `add "<guid>@techacct.adobe.com" to group dm-reference-writers`.

Smoke-test the receiver directly:

```bash
curl -sS -X POST "https://author-pXXXX-eYYYY.adobeaemcloud.com/bin/dmrefs/ingest" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  --data @sample-ingest.json

curl -sS -H "Authorization: Bearer $TOKEN" \
  "https://author-pXXXX-eYYYY.adobeaemcloud.com/bin/dmrefs/references?path=/content/dam/acme/hero.jpg"
```

For a positive read test, change the UUID in `sample-ingest.json` to a real asset's `jcr:uuid`.

### 2a. Sites on Cloud Service (RDE)

```bash
aio aem rde install sites-reference-tracker/all/target/dmrefs-sites-all-0.1.0-SNAPSHOT.zip
```

Set these environment variables and secrets on the environment through Cloud Manager:

- `DMREFS_INSTANCE_ID`
- `DMREFS_ENDPOINT_URL`, set to `https://<assets-author>/bin/dmrefs/ingest`
- `DMREFS_AUTHOR_URL` (optional), the public https URL of this author instance
- `DMREFS_IMS_CLIENT_ID`, and secret `DMREFS_IMS_CLIENT_SECRET` (the technical account)

### 2b. Sites on 6.5 (on-prem or AMS)

1. Install the zip with Package Manager.
2. The `$[env:…]` and `$[secret:…]` placeholders only work on Cloud Service. On 6.5, open `/system/console/configMgr` → **DM References - Tracker settings** and set:
   - `instance.id`, e.g. `acme-prod-author`
   - `endpoint.url`
   - `author.url` (optional), the public https URL of this author instance
   - `auth.mode` = `oauth`, with `ims.client.id` and `ims.client.secret`. Encrypt the secret first at `/system/console/crypto`; values in the `{…}` form are decrypted automatically.
   - `job.delay.seconds` (default 10): how long to wait after a publish before reading the page. A longer delay merges repeat publishes but means a newly published asset shows as unused for that long.
3. Confirm the customer allows outbound HTTPS to `*.adobeaemcloud.com`, or route it through their proxy. The client uses JVM proxy system properties (`https.proxyHost` and so on).

### 3. End-to-end check

1. Edit a Sites page so it uses a DM asset, then publish it.
2. After the job delay (10 s by default) plus a second or two, `GET /bin/dmrefs/references?...` on Assets should show the page with `published: true`. Before publishing, an edit shows up as `author: true, published: false` once the page has been quiet for `draft.quiet.seconds` (default 60) plus up to 15 s for the sweeper.
3. Remove the image from the draft: `author` becomes false while `published` stays true until you republish.
4. Unpublish the page: `published` becomes false and the draft row stays.
5. Delete the page: the row should disappear.
6. To load pages that were already published before the tracker was installed, call `start("/content/<site>")` on the `com.adobe.dmrefs:type=Backfill` MBean in `/system/console/jmx`, then watch `status()`.

On Sites, set `com.adobe.dmrefs` to DEBUG to see activity. At DEBUG the tracker logs, in order: the replication event and queued paths, the scan result (reference count), the IMS token fetch, the POST status and "pushed N page(s)". The first place the sequence stops is where the problem is. The log never contains the token, the secret or payloads.

### Deployment notes

- **Repoinit ACLs:** use `set principal ACL for <service user>`. A plain `set ACL for <service user>` is not effective on Cloud Service and on the SDK: the service user could not see `/content` or `/conf`.
- **Reinstalling the Sites package resets `TrackerSettings`** to the package defaults. Re-apply the instance ID, endpoint, author URL and credentials afterwards. Updating a config through the Felix console replaces the whole config, so send every property.
- **A re-push with an unchanged `seq` is ignored as stale.** To re-send a page, change it so its `seq` increases.
- **Author URL must be https.** The receiver rejects `http` and any URL with credentials, query or fragment.

## Placeholders you must check

| Item | Where | Why |
|---|---|---|
| **Vanity URL regex** | Sites `vanity.patterns` | The default assumes the vanity ID replaces the asset ID in `/adobe/assets/<id>/as/<seo-name>`. Match it to the customer's real delivery URLs. |
| **Vanity metadata property** | Assets `vanity.metadata.property` | `dam:vanityId` is a placeholder. |
| **Asset ID equals `jcr:uuid`** | Assets read endpoint | Confirmed on the RDE for local DAM assets (`/content/dam/snow/image1.jpg` and `image9.jpg`): `urn:aaid:aem:<uuid>` is the asset's `jcr:uuid`. |
| **SDK and uber-jar versions** | Both poms | Set them to the target environments. |

## Known MVP gaps and next steps

The deferred items and the point at which to reconsider each are in `docs/spec-v2-stateless-tracker.md` §8.

- **Draft tracking is best effort.** Edits still waiting in memory are lost on a restart (the next edit of that page restores it). Edits by users on `draft.ignored.users`, and changes that bypass page events, are missed. Existing drafts are not loaded by the backfill; a page's draft set arrives with its next edit.
- **Experience Fragment and template edits** are not seen by pages until those pages are edited, republished or backfilled.
- **No background repair.** Drift (a lost event, a restart mid-job) persists until the page is edited, republished or backfilled. A backfill adds and updates, but does not remove pages that were deleted while Sites was not tracking.
- **"Published" means activation was requested, not delivered.** If the publish queue is blocked, the page still shows as published.
- **A long `job.delay.seconds`** makes a newly published asset look unused for that long.
- **Backfill is JMX-only,** so it is not available on Cloud Service production. Cloud Service Sites is out of scope for this version.
- **Vanity performance:** add an Oak property index for the vanity metadata property on Assets.
- **Unresolved vanity IDs** are only re-resolved when they're read or when the page is pushed again. Next step: listen for vanity changes on Assets and re-key entries.
- **References UI:** this MVP only provides the endpoint. Wiring it into the Assets References panel, and merging in CDN or Databricks results asynchronously, comes next.
- **Rate limiting:** use AEM Cloud Service CDN traffic filter rules on `/bin/dmrefs/ingest`.
- **Scale:** `/var` with sharding is fine for an MVP. At millions of references, move the store behind a dedicated service; the endpoint contract stays the same.
- **Possible 403 on POST despite a valid token:** not seen on the RDE test, where the OAuth token POST worked. If you do see it, check the Granite CSRF filter and the Sling Referrer Filter, and exclude `/bin/dmrefs/ingest` for this server-to-server client.

### Hardening before real customer use

- **Read endpoint limits.** `truncated` is true when exactly `max` rows come back, and the limit applies before merging by page. A template logo on every page produces one row per page.
- **Housekeeping.** Tombstones (`deleted=true` page indexes) on Assets, and rows whose asset no longer exists.
- **Size.** At about 1 to 2 pages per asset, the wrapper nodes (asset, instance and shard folders) outnumber the reference nodes. A flatter layout would reduce them.
- **Real 6.5 / AMS author.** The scenarios were run on the Cloud SDK. Re-check replication status timing, move events and the job delay on AMS.

## Verification status

- **Compiled and tested:** both Sites builds (AEM 6.5 with Java 8, and `-Pcloud` with Java 11) and the Assets receiver build succeed. 52 unit tests pass (35 Sites, 17 Assets). They cover the extractor (including encoded URLs), path and author URL validation, the payload builder, the caches, the coalescer (including a concurrent test), the batch pusher, the IMS helpers, the independent draft and published state logic and the ingest validator. The classes that use OSGi, Sling and JCR have no unit tests.
- **Verified end to end** between a local AEM Cloud SDK author with a publisher (Sites side) and an RDE (Assets side), using the IMS technical account: publish, unpublish, delete, move, a template-structure asset and its cache refresh, an Assets outage recovered by the queue retry, a partial scan, percent-encoded and JSON-escaped URLs, a rejected page dropped without blocking, the JMX backfill, five quick publishes scanned once, and draft tracking (a draft edit appears, a text-only edit makes no push, publish and unpublish leave the other set alone, removing an image from a draft, delete, the ignored-users filter, and an idle sweeper that logs nothing).
- **Not tested yet:** an Experience Fragment inside a template structure, the 24-hour token refresh (the 401 path is unit-tested only), a full AEM restart with a scheduled job pending, vanity references, and everything on a real AEM 6.5 / AMS author.
