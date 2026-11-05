# Copilot instructions: dm-asset-references

This MVP tracks which AEM Sites pages reference Dynamic Media (remote DAM) assets and pushes those references to AEM Assets in near real time.

Read these before changing anything:

- `README.md`: architecture, API contract, build and deploy
- `docs/design-decisions.md`: why it's built this way (don't reverse a decision without updating that file)
- `docs/wiki/`: non-technical overview

## Modules

- `sites-reference-tracker/`: runs on **Sites author** (AEM 6.5 by default, Cloud Service with `-Pcloud`)
- `assets-reference-receiver/`: runs on **Assets author** (AEM as a Cloud Service)
- Each has `core/` (the OSGi bundle) and `all/` (a container package with the bundle, repoinit and OSGi configs)

## Code map

### Sites (`com.adobe.dmrefs.sites.core`)

| Class | Role |
|---|---|
| `impl.TrackerSettings` | Central OSGi config: endpoint, token, regexes, limits |
| `impl.PageEventListener` | Turns `PageEvent`s into jobs (`AUTHOR_CHANGED` / `DELETED`) |
| `impl.ReplicationEventListener` | Turns replication events into jobs (`PUBLISHED` / `UNPUBLISHED`) |
| `impl.PageReferenceJobConsumer` | Recomputes a page's sets, writes the snapshot, marks it pending |
| `impl.PageReferenceScanner` | Walks `jcr:content` and follows XF/CF paths |
| `extract.ReferenceExtractor` | Regex extraction of refs (pure Java) |
| `impl.SnapshotStore` | `/var/dm-references` pages, pending markers and state |
| `impl.ReferencePushTask` | Scheduler (every 30 s, runs on one cluster node) that batches pending pages and POSTs them |
| `impl.AssetsClient` | HTTP client: no redirects, timeouts, CryptoSupport token decryption |
| `impl.PushPayload` + `util.JsonWriter` | Builds the JSON payload (no JSON library) |
| `impl.ReconcileTask` | Nightly incremental or full reconcile, and orphan cleanup |

### Assets (`com.adobe.dmrefs.assets.core`)

| Class | Role |
|---|---|
| `impl.IngestServlet` | `POST /bin/dmrefs/ingest`: checks group, size and content type, validates, applies with retry |
| `validate.PayloadValidator` | All-or-nothing validation (pure Java) |
| `impl.ReferenceStore` | `/var` layout, replace semantics via the page index, `seq` staleness check |
| `impl.VanityResolver` | Vanity ID ↔ UUID through a metadata property (bound JCR-SQL2 parameter) |
| `impl.ReferencesServlet` | `GET /bin/dmrefs/references`: resolves the asset with the caller's session, reads rows with the service user |
| `impl.ReceiverSettings` | OSGi config |

## Conventions (must follow)

- **Sites code must compile for Java 8** against the AEM 6.5 uber-jar **and** for Java 11 against `aem-sdk-api`. That means no `var`, `List.of`, `String.isBlank`, records or text blocks.
- **Sites side has no JSON library.** Use `util.JsonWriter`, because Gson/Jackson exports differ between 6.5 and Cloud. Assets side may use Gson.
- **Keep pure logic (extraction, validation, payload) free of AEM APIs** so it can be unit tested. Add JUnit 5 tests in `core/src/test`.
- **Repository access:** use the service users only (`tracker` subservice on Sites, `writer` on Assets). Never use admin sessions.
- **Node names:** derive from SHA-256 hashes or values validated by regex. Never use raw input as a path segment.
- **Queries:** use bound variables. Any value concatenated into a query must first pass a strict allow-list regex.
- **Logging:** never log tokens or payloads. Log counts and IDs only.
- **Asset nodes in `/content/dam`:** never write to them.
- **Listeners stay lightweight:** they only enqueue Sling jobs, and the job consumer does the work.

## Build and test

```bash
cd sites-reference-tracker && mvn clean install                  # AEM 6.5
cd sites-reference-tracker && mvn clean install -Pcloud -Daem.sdk.api.version=<v>
cd assets-reference-receiver && mvn clean install -Daem.sdk.api.version=<v>
aio aem rde install <module>/all/target/*-all-*.zip
```

## Status

- **Not yet built:** the classes that use AEM APIs haven't been compiled against real dependencies. On the first build, expect small fixes to signatures or imports.
- **Tested:** the pure-Java classes plus the two test classes compile, and their tests pass.

## Backlog (in priority order)

1. Build both projects against the uber-jar and the SDK, and fix any compile issues.
2. Deploy to an RDE and run the end-to-end check from the README.
3. Confirm the open questions in `docs/design-decisions.md` (asset ID vs `jcr:uuid`, the vanity URL format, the vanity metadata property, CSRF and referrer behaviour).
4. When an XF changes, re-enqueue the pages that embed it.
5. Add an Oak index for the vanity metadata property on Assets.
6. Re-resolve unresolved vanity IDs when the vanity mapping changes.
7. Replace the bearer token with an IMS technical account (token exchange in `AssetsClient`).
8. Add the results to the Assets References panel, and merge in the CDN/Databricks signal asynchronously.
9. Add a CDN traffic filter rate limit on `/bin/dmrefs/ingest`.
