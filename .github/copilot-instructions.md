# Copilot instructions: dm-asset-references

This MVP tracks which AEM Sites pages reference Dynamic Media (remote DAM) assets and pushes those references to AEM Assets in near real time.

Read these before changing anything:

- `README.md`: architecture, API contract, build and deploy
- `docs/design-decisions.md`: why it's built this way (don't reverse a decision without updating that file)
- `docs/wiki/`: non-technical overview
- **`docs/spec-v2-stateless-tracker.md`: the design that is built.** The Sites side is stateless: it listens to replication events (reliable, delayed job that reads each page's current state) and to page edits (best effort, debounced, §4.9), and pushes to Assets. Don't add anything listed in spec §8 unless asked.

## Modules

- `sites-reference-tracker/`: runs on **Sites author** (AEM 6.5 by default, Cloud Service with `-Pcloud`)
- `assets-reference-receiver/`: runs on **Assets author** (AEM as a Cloud Service)
- Each has `core/` (the OSGi bundle) and `all/` (a container package with the bundle, repoinit and OSGi configs)

## Code map

### Sites (`com.adobe.dmrefs.sites.core`)

| Class | Role |
|---|---|
| `impl.TrackerSettings` | Central OSGi config: endpoint, auth, regexes, limits, job delay, caches |
| `impl.ReplicationEventListener` | Turns ACTIVATE / DEACTIVATE / DELETE events into one delayed Sling job per chunk of paths |
| `impl.PushJobConsumer` | One consumer for both job topics. Publish jobs (queue `dmrefs-publish`): read each page's current replication status, scan it, push the published set. Draft jobs (queue `dmrefs-draft`): scan an edited page and push its draft set unless the fingerprint is unchanged |
| `impl.DraftTracker` | Notes page edits in memory (`PageEvent`), and every 15 s queues the pages whose editing has settled (event handler and scheduled sweep in one component) |
| `impl.PageReferenceScanner` | Walks `jcr:content`, follows XF/CF paths, adds template-structure refs (cached) |
| `impl.TokenProvider` | IMS client-credentials token, cached in memory (`static` mode for development) |
| `impl.AssetsClient` | HTTP client: no redirects, timeouts, one retry after a 401/403; `push()` sends a batch through `util.BatchPusher` |
| `impl.PushPayload` + `util.JsonWriter` | Builds the JSON payload (no JSON library) |
| `impl.Backfill` (+ `BackfillMBean`) | Manual JMX backfill of published pages: the MBean operations and the job that walks the tree (queue `dmrefs-backfill`) |
| `extract.ReferenceExtractor` | Regex extraction of refs, including percent-encoded and JSON-escaped URLs (pure Java) |
| `util.BatchPusher` | Sends a batch; on 400/413 resends each page alone and drops the bad one (pure Java) |
| `util.Caches` | The in-memory caches: `Lru`, `DeliveryCache` (already-delivered pages), `FingerprintCache` (unchanged draft sets), `TemplateCache`, `TokenCache` (pure Java) |
| `util.Coalescer` | Draft edit debouncing, and the ignored-users match (pure Java) |
| `util.Ims` | IMS token URL allow-list and token response parsing (pure Java) |

### Assets (`com.adobe.dmrefs.assets.core`)

| Class | Role |
|---|---|
| `impl.IngestServlet` | `POST /bin/dmrefs/ingest`: checks group, size and content type, validates, applies with retry |
| `validate.PayloadValidator` | All-or-nothing validation (pure Java) |
| `impl.ReferenceStore` | `/var` layout (`oak:Unstructured` nodes), replace semantics via the page index, independent `author`/`published` sets with a `seq` each (an absent set is left unchanged), `partial` pages only add, unseen empty pages write nothing |
| `impl.VanityResolver` | Vanity ID ↔ UUID through a metadata property (bound JCR-SQL2 parameter) |
| `impl.ReferencesServlet` | `GET /bin/dmrefs/references`: resolves the asset with the caller's session, reads rows with the service user |
| `impl.ReceiverSettings` | OSGi config |

## Conventions (must follow)

- **Sites code must compile for Java 8** against the AEM 6.5 uber-jar **and** for Java 11 against `aem-sdk-api`. That means no `var`, `List.of`, `String.isBlank`, records or text blocks.
- **Sites side has no JSON library.** Use `util.JsonWriter`, because Gson/Jackson exports differ between 6.5 and Cloud. Assets side may use Gson.
- **Keep pure logic (extraction, validation, payload) free of AEM APIs** so it can be unit tested. Add JUnit 5 tests in `core/src/test`.
- **Repository access:** use the service users only (`tracker` subservice on Sites, `writer` on Assets). Never use admin sessions. The Sites service user is read-only (`/content` and `/conf`); Sites writes nothing to the repository itself.
- **Node names:** derive from SHA-256 hashes or values validated by regex. Never use raw input as a path segment.
- **Queries:** use bound variables. Any value concatenated into a query must first pass a strict allow-list regex.
- **Logging:** never log tokens or payloads. Log counts and IDs only.
- **Repoinit:** use `set principal ACL for <service user>`. A plain `set ACL for` does not take effect on Cloud Service or the SDK.
- **SDK version:** build against the SDK or uber-jar of the runtime you deploy to. A newer SDK can add package imports the target lacks, and the bundle then won't resolve.
- **Asset nodes in `/content/dam`:** never write to them.
- **Listeners stay lightweight:** they only enqueue Sling jobs (or, for page edits, note a path in memory), and the job consumer does the work. The page-edit handler runs on every edit: no JCR access and no logging above DEBUG.
- **Secrets:** never put a client secret or token in the repo. Keep test credentials outside it (for example `~/.dmrefs/`).

## Build and test

```bash
cd sites-reference-tracker && mvn clean install                  # AEM 6.5
cd sites-reference-tracker && mvn clean install -Pcloud -Daem.sdk.api.version=<v>
cd assets-reference-receiver && mvn clean install -Daem.sdk.api.version=<v>
aio aem rde install <module>/all/target/*-all-*.zip
```

## Status

- **Built and tested:** both Sites builds (AEM 6.5 / Java 8 and Cloud / Java 11) and the Assets build succeed. 35 Sites and 17 Assets unit tests pass (pure-Java classes only).
- **Verified end to end** (local Cloud SDK author for Sites, RDE for Assets), scenarios from spec §7: A1 publish, A2 unpublish, A3 delete, A4 move, A5 template-structure asset and its cache refresh, A7 Assets outage (recovered by the queue retry), A9 partial scan, A10 percent-encoded and JSON-escaped URLs, A11 bad page dropped without blocking, A12 JMX backfill, A15 five quick publishes scanned once, and draft tracking D1 to D7 (plus the ignored-users filter).
- **Not tested yet:** A6 (XF inside a template structure), A8 (24 h token refresh; the 401 path is unit-tested only), A16 across a full restart, vanity references, a draft backfill, and everything on a real AEM 6.5 / AMS author (including whether `PageModification.getUserId()` is set for editor saves there).

## Backlog (in priority order)

1. Run the remaining scenarios on a real AEM 6.5 / AMS author (replication status timing, move events, `job.delay.seconds`).
2. Test vanity references end to end, and confirm the vanity URL format and metadata property.
3. Confirm CSRF and referrer behaviour on Cloud Service (open question in `docs/design-decisions.md`).
4. Add an Oak index for the vanity metadata property on Assets.
5. Re-resolve unresolved vanity IDs when the vanity mapping changes.
6. Add the results to the Assets References panel, and merge in the CDN/Databricks signal asynchronously.
7. Add a CDN traffic filter rate limit on `/bin/dmrefs/ingest`.
8. Housekeeping on Assets: tombstones (`deleted=true` page indexes) and rows for deleted assets.
9. Reconsider the deferred items in spec §8 when their triggers occur (draft references, XF/template link edges, background repair, policies).
