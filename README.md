# DM Asset References: MVP

This MVP shows, close to real time, which Sites pages reference a Dynamic Media (remote DAM) asset. It works when the Sites instance is on-prem or on AMS behind the customer's firewall, because the Sites instance only makes **outbound** HTTPS calls. It doesn't depend on CDN logs or Databricks.

```
 Sites AUTHOR (6.5 on-prem / AMS / Cloud)                       Assets AUTHOR (Cloud Service)
 ┌───────────────────────────────────────────┐                ┌─────────────────────────────────────┐
 │ PageEvent ─┐                              │                │ POST /bin/dmrefs/ingest             │
 │ Replication├─► Sling job ─► scan page ───►│ /var snapshot  │   authN: IMS bearer                 │
 │ Reconcile ─┘   (per page)   (+XF/CF)      │ + pending queue│   authZ: dm-reference-writers group │
 │                                           │       │        │   validate ─► vanity→uuid ─► /var   │
 │ Push task (every 30s, batch ≤100) ────────┼───────┘ HTTPS ►│                                     │
 └───────────────────────────────────────────┘   (outbound)   │ GET  /bin/dmrefs/references         │
                                                               │   ?assetId=urn:aaid:aem:… | ?path=… │
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

| Trigger | Job action | Effect on the snapshot |
|---|---|---|
| Page created, modified or restored | `AUTHOR_CHANGED` | Rescans the page and updates `authorRefs` |
| Page moved | `DELETED` (source) + `AUTHOR_CHANGED` (destination) | |
| Page deleted | `DELETED` | Clears both sets and sets `deleted=true` |
| Replication: Activate | `PUBLISHED` | Rescans the page. `publishedRefs` = `authorRefs` = current content, `publishedAt=now` |
| Replication: Deactivate or Delete | `UNPUBLISHED` | Clears `publishedRefs` |
| Reconcile (daily at 02:30) | `RECONCILE` | Rescans pages changed or replicated since the last run. Uses `cq:lastReplicationAction` to correct `publishedRefs`. Snapshots whose page no longer exists get `DELETED` |

- **Scanning** reads every String and String[] property under `jcr:content` and matches it against the configured regexes:
  - `uuid` patterns: `urn:aaid:aem:<uuid>` and the legacy `dm-aid--<uuid>`.
  - `vanity` patterns.
  - It follows `fragmentVariationPath` and `fragmentPath` into Experience Fragments and Content Fragments, up to depth 3, with cycle protection and a cap of 5,000 nodes per page.
- **Snapshots** are stored at `/var/dm-references/pages/<h0h1>/<sha256(pagePath)>`. A snapshot is only written when the page references at least one DM asset, or used to. A change bumps `seq` and adds a marker under `/var/dm-references/pending/`.
- **The push task** runs on a single cluster node every 30 seconds, sending up to 10 batches of 100 pages per run. A page stays pending until Assets acknowledges exactly the `seq` that was sent, so failed pushes retry automatically. Once a deleted page's push is acknowledged, its snapshot is removed.
- **Full rebuild:** delete the `lastReconcile` property on `/var/dm-references/state`. The next reconcile run then processes every page.
- **Author only:** nothing runs on publish.

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

- **Asset nodes are never modified.** There's one node per (asset, instance, page), so many pages referencing one popular asset don't cause write conflicts.
- **Each push replaces the page's previous entries.** The page index records which entries belong to the page, so stale entries are deleted and the store converges no matter how often a page is re-sent. A `seq` lower than or equal to the stored one is ignored as stale, which makes retries and out-of-order deliveries safe.
- **Vanity resolution** happens at ingest. The receiver runs a JCR-SQL2 query with a bound parameter on `jcr:content/metadata/<vanity.metadata.property>`:
  - If exactly one asset matches, the entry is stored under that asset's UUID, with `referencedAs: ["vanity:summer-hero"]`.
  - If no asset matches, or more than one does, the entry is stored under `unresolved/`. The read endpoint still finds these by looking up the asset's current vanity ID, so they appear once the mapping exists.
- **Reads** resolve the asset with the **caller's** session, so a user can only see references for assets they're allowed to read. The reference rows themselves are read with the service user.

## API contract

### `POST /bin/dmrefs/ingest`

- **Headers:** `Authorization: Bearer <IMS token>` and `Content-Type: application/json`.
- **Who can call it:** the caller must be in the group `dm-reference-writers`.
- **Body:** see `sample-ingest.json`. Each page carries its **full** author and published sets (replace semantics, not diffs). That makes every push idempotent and safe to apply in any order, and each page's sets are small.

| Field | Rule |
|---|---|
| `schemaVersion` | `1` |
| `instanceId` | `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` (also used as a node name) |
| `authorUrl` | Optional. Public https base URL of the sending author instance, e.g. `https://author.acme.com`. No credentials, query or fragment, at most 200 characters. An invalid value fails validation with `400`. |
| `pages` | 1–200 per request, no duplicate `pagePath` |
| `pagePath` | Under `/content`, at most 1024 characters, no `..`, no `[]\|*` or control characters |
| `seq` | Positive long, increasing per page |
| `deleted` | If `true`, both sets must be empty |
| `author[]` / `published[]` | Up to 500 each. `{"type":"uuid","ref":"urn:aaid:aem:<uuid>"}` or `{"type":"vanity","ref":"[A-Za-z0-9._~-]{1,200}"}` |

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
2. Get a token:
   - For a quick test, use the Developer Console **local development token** (it expires after 24 hours).
   - For longer runs, create a **service credential** (technical account) and exchange it for an access token.
3. Make one call with the token so its user is created in AEM. Then add that user to **`dm-reference-writers`**.

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
- secret `DMREFS_BEARER_TOKEN`

### 2b. Sites on 6.5 (on-prem or AMS)

1. Install the zip with Package Manager.
2. The `$[env:…]` and `$[secret:…]` placeholders only work on Cloud Service. On 6.5, open `/system/console/configMgr` → **DM References - Tracker settings** and set:
   - `instance.id`, e.g. `acme-prod-author`
   - `endpoint.url`
   - `author.url` (optional), the public https URL of this author instance
   - `bearer.token`. Encrypt it first at `/system/console/crypto`; values in the `{…}` form are decrypted automatically.
3. Confirm the customer allows outbound HTTPS to `*.adobeaemcloud.com`, or route it through their proxy. The client uses JVM proxy system properties (`https.proxyHost` and so on).

### 3. End-to-end check

1. Edit a Sites page so it uses a DM asset, then publish it.
2. Within about 30 seconds, `GET /bin/dmrefs/references?...` on Assets should show the page with `author: true, published: true`.
3. Unpublish the page and wait for the next push: `published` should become `false`.
4. Delete the page and wait for the next push: the row should disappear.

On Sites, set `com.adobe.dmrefs` to DEBUG to see job and push activity. At DEBUG the tracker logs, in order: the page event, the enqueued job, the scan result (reference count), "queued for push", the POST status and "pushed N page(s)". The first place the sequence stops is where the problem is. The log never contains the token or payloads.

### Deployment notes

- **Repoinit ACLs:** use `set principal ACL for <service user>`. A plain `set ACL for <service user>` is not effective on Cloud Service and on the SDK: the service user could not see `/var/dm-references` or `/content`.
- **Reinstalling the Sites package resets `TrackerSettings`** to the package defaults. Re-apply the instance ID, endpoint, author URL and token afterwards. Updating a config through the Felix console replaces the whole config, so send every property.
- **A re-push with an unchanged `seq` is ignored as stale.** To re-send a page, change it so its `seq` increases.
- **Author URL must be https.** The receiver rejects `http` and any URL with credentials, query or fragment.

## Placeholders you must check

| Item | Where | Why |
|---|---|---|
| **Vanity URL regex** | Sites `vanity.patterns` | The default assumes the vanity ID replaces the asset ID in `/adobe/assets/<id>/as/<seo-name>`. Match it to the customer's real delivery URLs. |
| **Vanity metadata property** | Assets `vanity.metadata.property` | `dam:vanityId` is a placeholder. |
| **Asset ID equals `jcr:uuid`** | Assets read endpoint | Confirmed for a local DAM asset on the RDE: `urn:aaid:aem:<uuid>` matches the asset's `jcr:uuid`. Not yet confirmed for a remote Dynamic Media asset. |
| **SDK and uber-jar versions** | Both poms | Set them to the target environments. |

## Known MVP gaps and next steps

- **Experience Fragment edits** don't re-trigger the pages that embed them, so those pages update at the next reconcile run. Next step: on an XF change, query for pages that reference that XF and enqueue them.
- **Published refs during reconcile** are rebuilt from current author content. That's exact when the page wasn't edited after its last activation, and approximate otherwise. The live event path is exact.
- **"Published" means activation was triggered, not delivered.** If the publish queue is blocked, the page still shows as published; reconcile corrects it later.
- **Vanity performance:** add an Oak property index for the vanity metadata property on Assets.
- **Unresolved vanity IDs** are only re-resolved when they're read or when the page is pushed again. Next step: listen for vanity changes on Assets and re-key entries.
- **References UI:** this MVP only provides the endpoint. Wiring it into the Assets References panel, and merging in CDN or Databricks results asynchronously, comes next.
- **Rate limiting:** use AEM Cloud Service CDN traffic filter rules on `/bin/dmrefs/ingest`.
- **Scale:** `/var` with sharding is fine for an MVP. At millions of references, move the store behind a dedicated service; the endpoint contract stays the same.
- **Possible 403 on POST despite a valid token:** not seen on the RDE test, where the bearer-token POST worked. If you do see it, check the Granite CSRF filter and the Sling Referrer Filter, and exclude `/bin/dmrefs/ingest` for this server-to-server client.

### Hardening before real customer use

- **Permanent push failures block the queue.** A `400` or `413` response is retried forever, and the oldest pending pages are always sent first. Fix: bisect the batch on a permanent 4xx and quarantine the single bad page.
- **A full reconcile enqueues one job per page.** On a large site, run it in capped, resumable slices.
- **Read endpoint limits.** `truncated` is true when exactly `max` rows come back, and the limit applies before merging by page.
- **Housekeeping.** Prune empty acknowledged snapshots on Sites, tombstones (`deleted=true` page indexes) on Assets, and rows whose asset no longer exists.
- **Size.** At about 1 to 2 pages per asset, the wrapper nodes (asset, instance and shard folders) outnumber the reference nodes. A flatter layout and `oak:Unstructured` for large folders would reduce them.

## Verification status

- **Compiled and tested:** all three builds succeed (Sites on 6.5 with Java 8, Sites with `-Pcloud`, and the Assets receiver). 18 unit tests pass (10 Sites, 8 Assets). They cover the extractor, path and author URL validation, the payload builder and the ingest validator. The classes that use OSGi, Sling and JCR have no unit tests.
- **Verified end to end** between a local AEM SDK author (Sites side) and an RDE (Assets side), using UUID references:
  - creating a reference on a page makes the row appear on the RDE
  - replacing the image on a page removes the old row
  - re-sending an unchanged `seq` is ignored as stale
  - the author URL and `pageUrl` come back from the read endpoint
- **Not tested yet:** publish and unpublish, page delete and move, vanity references, and the nightly reconcile.
