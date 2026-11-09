# Spec v2: Lean, publish-only Sites tracker

**Status:** implemented and tested end to end on a local Cloud SDK author plus an RDE (see README, Verification status). The step-0 spike is done; results are in `design-decisions.md`.
**Target:** Sites author on AEM 6.5 / AMS (a single author node). Cloud Service Sites is out of scope (see §8).
**Supersedes:** the earlier v2 draft (draft coalescer, replication agent, rechecks), which was cut as over-engineered for this stage.
**Read first:** `README.md`, `docs/design-decisions.md`, `.github/copilot-instructions.md`

---

## 1. What changes, in one paragraph

Sites stores nothing. It listens to **publish and unpublish** (reliable, queued, retried) and to **page edits** (best effort, debounced: about one scan per editing session, see §4.9). For every page it sends Assets the page's full list of DM assets: the assets in the page itself, plus the ones in its **template structure** (logo, header, footer). The **published** and **draft** sets are independent. Assets keeps the state, as it does today. There's no daily job. Existing content is loaded once with a **manual backfill**. A short list of small hardening fixes comes with it (§5).

## 2. Goals and non-goals

### Goals

| # | Goal |
|---|---|
| G1 | **Sites is stateless.** The Sites service user only has `jcr:read` on content. The only thing Sites writes is Sling's own job storage |
| G2 | **Live references** appear on Assets within seconds of a publish, and survive an Assets outage of up to about 24 hours |
| G3 | **Minimal load on authors.** One lightweight event handler; nothing runs on page edits; no scheduled jobs |
| G4 | **No silent false negatives** from the usual causes: template-structure assets, encoded URLs, and very large pages |
| G5 | **Authentication that doesn't expire** (no 24-hour manual token) |

### Non-goals (deferred; see §8 for when to reconsider each)

- Exact draft references at every moment. Draft tracking is best effort (§4.9).
- Rescanning pages when an Experience Fragment or template changes.
- Background repair or periodic reconcile.
- Cloud Service Sites support.
- Paging or counts in the References panel.

## 3. Architecture

```
 Sites AUTHOR (6.5 / AMS)                                   Assets AUTHOR (Cloud Service)
 ┌───────────────────────────────────────────────┐        ┌──────────────────────────────────┐
 │ ReplicationEventListener                      │        │ POST /bin/dmrefs/ingest (v1 + ε) │
 │   (Activate / Deactivate / Delete)            │        │   + "partial" flag per page      │
 │      │ enqueue (delayed ~10 s)                │        │   oak:Unstructured nodes         │
 │      ▼                                        │        │   skip if new + empty            │
 │ Sling job queue "dmrefs-publish"              │        │                                  │
 │   PushJobConsumer                             │ HTTPS  │ GET /bin/dmrefs/references       │
 │     reads CURRENT replication status          ├───────►│   (unchanged)                    │
 │     scans page + template structure (cached)  │  out   │                                  │
 │     AssetsClient (OAuth token, auto-refresh)  │  only  │                                  │
 │                                               │        │                                  │
 │ BackfillMBean (JMX, manual) → same queue      │        │                                  │
 └───────────────────────────────────────────────┘        └──────────────────────────────────┘
```

## 4. Sites side

### 4.1 Remove

- `SnapshotStore`, `model.Snapshot`, `ReferencePushTask`, `ReconcileTask` and `PageEventListener`, plus their OSGi configs.
- From the Sites repoinit: `/var/dm-references` and the write ACL. The service user keeps `jcr:read` on `/content` **and `/conf`** (needed for template structures).

### 4.2 Keep, with small changes

| Class | Change |
|---|---|
| `TrackerSettings` | Add the auth settings (§4.5) and `job.delay.seconds` (default `10`). Remove the push and reconcile settings |
| `ReplicationEventListener` | Keep it as it is: local events only, filtered to `content.roots`. Map ACTIVATE, DEACTIVATE and DELETE to **one job per chunk of paths** (`push.batch.size`), scheduled `job.delay.seconds` in the future. The job carries **only the paths**, not the action (§4.3) |
| `PageReferenceScanner` | Add template-structure scanning (§4.4) and partial detection (§4.6) |
| `ReferenceExtractor` | Add normalisation for encoded URLs (§4.7) |
| `AssetsClient` | Get the token from `TokenProvider` (§4.5). On a 401, refresh once and retry |
| `PushPayload` | Add `"partial": true` when it applies. Always send `author: []`, since drafts are out of scope |

### 4.3 `PushJobConsumer`, publish jobs (replaces `PageReferenceJobConsumer`; the same class also runs draft jobs, §4.9)

The job runs on queue `dmrefs-publish`.

For each path, **ignore what the event said and read the current state.** That's simpler, and it can't send an outdated state if jobs run out of order or a retry runs late.

1. Load the resource. If it isn't a `cq:Page` under `content.roots`, or it doesn't exist, the result is **not live**.
2. Otherwise read `jcr:content/cq:lastReplicationAction`:
   - `Activate`: **live**. Scan the page (§4.4) and use the result as `published`.
   - anything else: **not live**.
3. If the page isn't live, send `published: []`. Assets deletes the page's rows, and does nothing if it never knew the page (§6).
4. `seq` = `System.currentTimeMillis()` at processing time. That's safe because the AMS author is one node and the queue is ordered.

**Configurable delay (`job.delay.seconds`):**

- **Default:** `10`. The customer chooses how fresh the data needs to be; for example `1800` gives a 30-minute delay.
- **Range:** clamped to `[min, 86400]`. The minimum is set by spike item §9.2: the time `cq:lastReplicationAction` takes to update, expected to be about 5 s.
- **Scheduling:** jobs are created with `JobBuilder.schedule().at(now + delay)`. Sling persists scheduled jobs, so they survive a restart.
- **Backfill jobs are never delayed.**

**Skip jobs already covered by a scan:**

- Each job carries `enqueuedAt`.
- The consumer keeps an in-memory LRU cache (`scan.cache.size`, default `10000`) of the last time each path was **delivered** (scanned and acknowledged by Assets with a `2xx`).
- Set the cache entry only **after the send succeeds**, and also for "not live" results. If you set it at scan time, a failed send makes the retry skip every path and the data is never delivered.
- If `lastDeliveredAt(path) > enqueuedAt`, that delivery already read the current state, so skip the path. `lastDeliveredAt` is the time the state was **read** (not the time Assets answered), so a publish that arrives while a push is in flight is never skipped.
- Losing the cache on a restart only costs one extra scan.
- With a long delay this merges repeat publishes: a page published 5 times within the window is scanned once.

**Tell the customer:** a long delay works against safety for publishes. A newly published asset shows as *unused* for up to the delay, so someone could delete it in that window. Unpublishes err on the safe side, because the asset shows as used for longer. If customers object, split the setting into `job.delay.activate.seconds` and `job.delay.deactivate.seconds` (the job would then need to carry the event type, for routing only).

Then send **all pages in the job as one batch:**

| Response | Action |
|---|---|
| `2xx` | Done |
| `401` / `403` | Refresh the token and retry once. If it still fails, return `FAILED`, so Sling retries later |
| `400` / `413` | Resend each page **on its own**, once. Log any page that still fails at ERROR level (path only) and drop it. **The queue must never be blocked** |
| `5xx` or a transport error | Return `FAILED`, so Sling retries later |

**Queue config:** ship `org.apache.sling.event.jobs.QueueConfiguration~dmrefs-publish.cfg.json` with:

- `queue.type=ORDERED`, `queue.maxparallel=1`
- `queue.retries=288`, `queue.retrydelay=300000` (5 minutes, so about 24 hours in total, and at most 5 minutes of lag once Assets is back)
- `queue.priority=MIN`

After the last retry, Sling marks the job as failed and our consumer logs ERROR.

Ship a **second queue, `dmrefs-backfill`,** for topic `com/adobe/dmrefs/backfill`: `ORDERED`, `maxparallel=1`, `priority=MIN`. Backfill must never run in the default queue (it would compete with authoring) or in `dmrefs-publish` (it would block live publishes).

### 4.4 Template-structure scanning (with a cache)

1. Read the page's `jcr:content/cq:template`, e.g. `/conf/acme/settings/wcm/templates/page-content`.
2. The structure lives at `<template>/structure/jcr:content`. Scan it **with the same rules as the page**, including following XF paths (`fragmentVariationPath`). That covers a header or footer XF placed in the structure.
3. Add the structure's refs to the page's refs. Assets sees them as ordinary references from that page, so the data model doesn't change.
4. **Cache:** an in-memory LRU cache (`template.cache.size`, default `200`) keyed by template path. Each entry stores the structure's `cq:lastModified`, the `cq:lastModified` of **every XF the structure embeds**, and the scan result. On a hit, re-read those values (one property read each) and rescan if any has changed. Also expire entries after `template.cache.ttl.seconds` (default `600`). Memory only. A static template with no `structure` node adds no refs.
5. **Out of scope:** policies (`/conf/.../policies`). Add them later if customers report images set by policies (§8).

### 4.5 Authentication: `TokenProvider`

Configured with `auth.mode`:

| Mode | Behaviour |
|---|---|
| `static` | The current bearer token from config. **For development only.** Log WARN when it's activated |
| `oauth` (default) | Client-credentials token exchange against IMS. Settings: `ims.token.url` (default `https://ims-na1.adobelogin.com/ims/token/v3`), `client.id`, `client.secret` and `scopes` |

In `oauth` mode:

- **Cache the token in memory** until `expires_in` minus 5 minutes. Use one synchronized refresh, so concurrent callers don't each fetch a token.
- **`client.secret` is a password attribute.** On 6.5 it can be CryptoSupport-protected (`{…}`), and is decrypted the same way as the current token.
- **Never log the token or the secret.** On failure, log the HTTP status only.
- **Allowlist the IMS host:** it must be HTTPS and one of a fixed list in code (`ims-na1.adobelogin.com` and the other Adobe IMS regional hosts), not derived from config. No redirects.
- **Customer prerequisite:** outbound HTTPS to the IMS host. Document it in the README next to the Assets host.

On the Assets side, add the technical-account user to `dm-reference-writers`, as in v1.

### 4.6 Partial-scan protection

- If the scan stops at `max.nodes.per.page` (default `20000`), the result is **partial**.
- Send `"partial": true` for that page and log WARN with the path.
- Assets then **adds only** (§6). A cut-short scan must never delete real references.

### 4.7 Encoded-URL normalisation (`ReferenceExtractor`)

Before matching, also build a normalised copy of the value and match both:

- Replace `\/` with `/` (JSON-escaped URLs).
- If the value contains `%`, URL-decode it as UTF-8. If decoding fails (`IllegalArgumentException`), keep only the original.
- `srcset` values need nothing special, because the regexes already find several matches in one string.

### 4.8 Manual backfill (`BackfillMBean`, JMX)

- **Name and operations:** `com.adobe.dmrefs:type=Backfill`, with `start(String root)`, `status()` and `cancel()`. It's reachable through `/system/console/jmx` on 6.5. There's no servlet, so there's no new HTTP attack surface.
- **`start`:**
  1. Validates `root` with the existing content-path regex.
  2. Enqueues one Sling job on topic `com/adobe/dmrefs/backfill`.
  3. That job walks the resource tree under `root` and collects `cq:Page` paths whose `cq:lastReplicationAction` is `Activate`.
  4. Every `push.batch.size` paths, it enqueues a normal publish job and sleeps `backfill.pause.ms` (default `2000`).
  5. It checks the cancel flag between chunks.
- **Not resumable:** after a restart, run it again. It's idempotent, because Assets applies by `seq`.
- **`status()`** returns the run state, the number of pages queued, the current path and the start time.

### 4.9 Draft tracking (page edits)

Page edits are tracked best effort, so a page that stays unpublished for days still shows the assets it uses. The cost goal is about **one scan per editing session**, not one per save, and **nothing at all** when nothing was edited.

**Components (Sites)**

| Piece | Behaviour |
|---|---|
| `DraftTracker` (`EventHandler` on `PageEvent`, and a scheduled sweep in the same component) | **Event side:** ignores non-local events and edits by `draft.ignored.users` (exact match, or a prefix ending in `*`; default `replication-service`). For CREATED, MODIFIED and RESTORED it records the page path in memory. For MOVED it records the destination and the source. For DELETED it records the path. Paths must be valid and under `content.roots`. No JCR access, no logging above DEBUG. **Sweep side** (every 15 s, not `runOn=SINGLE`, because each node owns its own map): drains the due paths and queues one job per chunk of `push.batch.size` on topic `com/adobe/dmrefs/draft`. If nothing is due it does nothing: no JCR read and no HTTP call |
| `Coalescer` (pure) | `ConcurrentHashMap` of path to (first edit, last edit). A path is **due** when it has been quiet for `draft.quiet.seconds` (default 60), or `draft.max.wait.seconds` (default 300) have passed since its first edit. When the map exceeds `draft.max.entries` (default 10000) the oldest 10% are marked due. Draining removes an entry only if it is unchanged (`remove(key, value)`), so an edit that arrives during a sweep is kept for the next one |
| `PushJobConsumer`, draft jobs (queue `dmrefs-draft`: ordered, 5 retries, 5 minutes apart) | For each path: a missing page is sent as deleted. Otherwise scan the page and template (same scanner and caches as the publish path), compute a **fingerprint** of the reference set, and skip the path if it equals the last one delivered (an edit that changed no DM reference costs one scan and **zero HTTP calls**). Otherwise push the page's **draft set** (`author[]`). The fingerprint is recorded only after a `2xx`. A scan cut short is sent as `partial` |
| `FingerprintCache` (in `Caches`) | In-memory LRU (`fingerprint.cache.size`, default 10000), keyed by path |

`draft.enabled` (default `true`) switches the whole path off.

**Wire change: independent states.** In a page entry, `author[]` (draft) and `published[]` are each **optional**. A field that is **absent is left unchanged**; a field that is present, even empty, **replaces** that set. The publish path sends only `published[]`, the draft path only `author[]`. A `deleted` page clears both. A page entry must carry `author`, `published` or `deleted`. Payloads that carry both sets, as in v1, still work.

**Assets**

- `seq` is tracked **per state** (`authorSeq`, `publishedSeq`; a legacy index with only `seq` counts for both). An update is stale for a state if its `seq` is not greater than that state's stored one. A draft update that arrives after a newer publish update is therefore not dropped.
- A reference node is removed only when **both** flags are false. A `partial` update only adds, as before.
- The References response is unchanged: each row has `author` (draft) and `published`.

**Cost** (acceptance scenarios D1 to D6 in §7)

- An edit costs one map update, under 50 µs, with no JCR access.
- 40 edits to one page over 10 minutes make at most about 3 scans.
- Losing the map on a restart loses only edits not yet flushed; the next edit of that page restores it.

## 5. Small hardening fixes (summary)

| Fix | Where | Why |
|---|---|---|
| OAuth token refresh | Sites, §4.5 | The 24-hour token expiry is certain otherwise |
| Template-structure scanning | Sites, §4.4 | Logos and headers are the first thing customers check |
| `oak:Unstructured` nodes | Assets, §6 | Popular assets get very large child lists, which are slow on orderable node types |
| Partial-scan guard | Sites and Assets | Never delete references because of a cut-short scan |
| Read current state in the job | Sites, §4.3 | Correct even when jobs run out of order or a retry runs late |
| Finite, long retries, and send pages individually on a 4xx | Sites, §4.3 | Rides out an outage without blocking the queue |
| Encoded-URL normalisation | Sites, §4.7 | Prevents misses on `%3A` and `\/` |

## 6. Assets side (small changes only)

The payload stays `schemaVersion: 1`. The changes are additive, and the References panel doesn't change.

1. **`partial` flag** (an optional boolean on each page). If it's true, merge: the new key set is the **union** of the old and new keys, and no entries are deleted. Flags are only set, never cleared.
2. **No-op for pages it has never seen:** if no index exists for the page and the incoming sets are empty, write nothing and count it as `applied`. Otherwise every unpublish of an untracked page would leave an index node behind.
3. **Node type:** `ReferenceStore.ensure()` creates **`oak:Unstructured`** instead of `nt:unstructured`. Existing nodes keep their type; that's fine.
4. **Validator:** accepts `partial` and ignores unknown fields, as it does today.

## 7. Acceptance scenarios

| # | Action | Expected |
|---|---|---|
| A1 | Publish page P, which uses asset A | A lists P as published within about 15 s (the 10 s job delay plus the push) |
| A2 | Unpublish P | P disappears from A |
| A3 | Delete published page P | P disappears from A (AEM deactivates the page before deleting it) |
| A4 | Move published page P to P2 (confirm what events fire, see §9.5) | P no longer appears on A; P2 appears only once it is published |
| A5 | Template T's structure has logo L. Publish a page that uses T | L lists that page |
| A6 | Template T's structure contains XF X, which contains asset B. Publish a page that uses T | B lists that page |
| A7 | Assets is down for 2 hours, during which 50 pages are published | All 50 appear after Assets recovers, with no manual action |
| A8 | Run for longer than 24 hours in `oauth` mode | No 401 failures; the token refreshes automatically |
| A9 | Publish a page that exceeds `max.nodes.per.page` | A WARN is logged; existing references are kept; new ones are added |
| A10 | A page contains `urn%3Aaaid%3Aaem%3A<uuid>` or a JSON-escaped delivery URL | The asset is detected |
| A11 | One page in a batch is rejected with 400 | The other pages are applied, the bad page is logged, and later jobs still run |
| A12 | Run a backfill on `/content/acme` (published pages only) | All published pages appear; queue load stays smooth |
| A13 | Publish a page with no DM assets | No rows are written on Assets |
| A14 | Edit a published page without republishing | Nothing changes on Assets; no load on Sites |
| A15 | `job.delay.seconds=1800`. Publish P five times within 10 minutes | P appears on Assets about 30 minutes after the first publish. The page is **scanned once**; the other jobs are skipped as already covered |
| A16 | `job.delay.seconds=1800`. Restart Sites before the job runs | The scheduled job still runs after the restart |
| D1 | Add a DM image to an unpublished page and wait for the quiet window | The page is listed for the asset with `author: true, published: false` |
| D2 | Edit only text on that page | One scan, **no HTTP call** (the fingerprint matches) |
| D3 | Publish the page | `published` becomes true; `author` stays true |
| D4 | Remove the image from the draft of a published page, without republishing | `author` becomes false; `published` stays true |
| D5 | Unpublish the page | `published` becomes false; the draft row stays |
| D6 | Delete the page | The page disappears from the list |
| D7 | Idle author, nothing edited | No JCR reads and no HTTP calls from the sweeper |

## 8. Known limitations and when to reconsider

| Limitation | Effect | Reconsider when… | Likely addition |
|---|---|---|---|
| Draft tracking is best effort | Edits still waiting in memory are lost on a restart; they are picked up by the next edit of that page. Pages edited only by a user on the ignore list, or by a bulk tool that bypasses page events, are missed | Drift is seen in practice | A draft backfill, or a recheck returned in ingest replies |
| XF or template edited and republished, but embedding pages not republished | Those pages show the old fragment or template assets until they're republished or a backfill runs | Support tickets about stale XF or template usage | Link edges from page to XF/template, followed at read time |
| No background repair | Drift (lost events, restarts mid-event) persists until the page is republished or a backfill runs | Drift appears in practice | Low-rate rechecks returned in ingest replies |
| Policies not scanned | Images set in component policies are missed | Customers use policies for images | Scan the policies referenced by each component |
| "Published" means activation was requested | A stuck publish queue still shows the page as published | Reported in practice | Check replication status in a recheck |
| Cloud Service Sites | Not supported | Cloud Service Sites comes into scope | Filter out the `preview` agent, and handle clock drift across pods |

## 9. Step 0: spike before implementing (half a day)

1. **Asset ID:** confirm that `urn:aaid:aem:<uuid>` equals the asset's `jcr:uuid` on Assets. The whole data model depends on this.
2. **Replication status timing:** when the ACTIVATE or DEACTIVATE event fires on 6.5, is `cq:lastReplicationAction` already updated? If not, how long does it take? This sets the `job.delay.seconds` default.
3. **Credential type:** which credential type does the AEM Cloud Service author accept for `/bin/dmrefs/ingest` (OAuth server-to-server vs. the older service credentials / JWT)? If it's JWT, `TokenProvider` gets a JWT mode instead.
4. **DM property names:** list the DM image property names in the customer's components, to sanity-check `scan.mode=all` and the regexes.
5. **Move:** when a published page is moved, which replication events fire for the old and new paths, and is the new path live? This decides A4.
6. **Scheduled jobs:** confirm `JobBuilder.schedule().at(...)` persists across a restart and what it writes to the repository.

## 10. Implementation order

1. Run the spike (§9) and record the answers in `design-decisions.md`.
2. **Assets:** the `partial` merge, the no-op for unseen pages, and `oak:Unstructured`, with unit tests.
3. **Sites:** the pure parts first, with unit tests: extractor normalisation, the partial flag, the template cache (with an injectable clock and a fake `lastModified`), and the payload `partial` field.
4. **Sites:** `PushJobConsumer` (publish jobs), the listener changes and the queue config.
5. **Sites:** template-structure scanning.
6. **Sites:** `TokenProvider` (`oauth` and `static` modes) and the `AssetsClient` integration.
7. **Sites:** `BackfillMBean`.
8. **Sites:** remove the v1 `/var` code and configs; make the repoinit read-only, including `/conf`.
9. Update the README, `copilot-instructions.md` and `design-decisions.md`.
10. Run acceptance scenarios A1–A14.
