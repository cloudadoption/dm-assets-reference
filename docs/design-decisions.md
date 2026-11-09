# Design Decisions

This is a record of what we chose and why. Read it before changing the architecture.

## Problem

References to Dynamic Media (remote DAM) assets used on AEM Sites pages are broken. The current approach builds references from CDN logs, which a batch job pulls from Databricks. That data is slow, stored in cold storage, needs provisioning for each customer, and often can't see Sites instances that are on-prem or on AMS behind customer firewalls.

## Decisions

| # | Decision | Why | Rejected alternatives |
|---|---|---|---|
| D1 | **Sites pushes references to Assets, outbound only** | Customer firewalls block incoming connections, but outgoing HTTPS already works because Sites has to fetch DM assets | Assets pulling from Sites (blocked by the firewall) |
| D2 | **Capture references on the server (page and replication events), not in the Asset Selector UI** | The UI misses removals, cancelled dialogs, page copy, move and delete, MSM, packages and scripted edits | Modifying the Asset Selector or Content Advisor so it saves the page path into the asset's metadata |
| D3 | **Store references in `/var/dm-references` on the Assets author tier, never on the asset node** | One node per (asset, instance, page) avoids write conflicts on popular assets, and doesn't churn asset versions or events | A multi-value property on the asset; Databricks (cold storage, slow, per-customer provisioning) |
| D4 | **Author tier only; nothing runs on publish** | Every change and every publish starts on author | Running a listener on publish |
| D5 | **Two reference sets per page: `author` and `published`** | An author can edit a page after publishing it. The published set is frozen when the page is activated | A single list of references |
| D6 | **Each push sends a page's full reference sets (replace), plus a `seq` number** | Pushes are idempotent and safe in any order; the store converges and retries are safe | Sending diffs (adds and removes), which breaks on reordered or lost messages |
| D7 | **Key references by asset UUID, but record the vanity ID the page actually used (`referencedAs`)** | Survives vanity renames and lets us flag broken vanity IDs. Vanity → UUID is resolved on Assets, which owns the mapping | Keying by vanity ID alone (a rename or reassignment points references at the wrong asset) |
| D8 | **Vanity IDs that can't be resolved are kept under `unresolved/`** | They show up at read time once the mapping exists | Dropping them |
| D9 | **Events plus a batched push every 30 seconds, plus a nightly reconcile** | Near real time, with a safety net for missed events. The reconcile corrects drift between "publish triggered" and "publish delivered" | A daily batch job only |
| D10 | **The MVP is a custom package, not a product code change** | Product code is hard to deploy to an RDE | Changing product code |
| D11 | **For the MVP, the Sites side authenticates with a bearer token in OSGi config** | It's the simplest option; swap to an IMS technical account later | |
| D12 | **The CDN/Databricks data is a secondary signal, merged asynchronously in the UI later** | It catches consumers outside AEM, but it's slow | Making CDN data the main source |

### v2 decisions (see `docs/spec-v2-stateless-tracker.md`)

These replace D9 and D11 for the Sites side. D5 (two sets per page) still holds, and D22 makes the two sets independent.

| # | Decision | Why | Rejected or deferred |
|---|---|---|---|
| D13 | **Sites is stateless; Assets holds all reference state** | Full-replace pushes mean Sites doesn't need a baseline. No `/var` writes on the author | The v1 `/var` snapshot store |
| D14 | **Publish and unpublish are tracked reliably (replication listener); page edits are tracked best effort (see D22)** | Live usage is what customers need first, and nothing heavy runs on author edits | A page-edit listener that scans on every save |
| D15 | **The job reads the page's current replication status and content, not the event's action** | Correct even when jobs run out of order or retries run late, and simpler code | Trusting the event payload |
| D16 | **Template structure assets are added to each page's references** (cached per template) | The logo and header are the first thing customers check | Link edges to templates (deferred) |
| D17 | **A partial scan only adds references; it never removes any** | A false "unused" is the most costly error | Replacing with a truncated set |
| D18 | **No scheduled reconcile or background repair; a manual JMX backfill instead** | Avoids bulk background work; drift risk is low on a single AMS author node | Nightly reconcile (v1), and rechecks in replies (deferred) |
| D19 | **OAuth client-credentials tokens, refreshed automatically** (static token for development only) | The 24-hour manual token would cause outages | Static bearer token in production |
| D20 | **`oak:Unstructured` for reference nodes on Assets** | Popular assets have very large child lists, which are slow on orderable node types | `nt:unstructured` |
| D21 | **Considered and deferred:** replication agent, link edges for XFs and templates, recheck poll, scanning frozen versions, Cloud Service Sites, a draft backfill | Over-engineering for this stage; each has a trigger to reconsider in spec §8 | |
| D22 | **Draft references come from a debounced, in-memory page-edit coalescer, and published and draft sets are independent on Assets** (an absent set is left unchanged; each has its own `seq`) | Pages can stay unpublished for days. About one scan per editing session, and nothing when nothing is edited. A draft update must not wipe live references, or the reverse | Scanning on every save; one shared `seq`; persisting the pending edits (losing them on a restart is acceptable) |

## Verified in the spike (2026-11-09)

- The DM asset ID `urn:aaid:aem:<uuid>` is the asset node's `jcr:uuid` (confirmed on the RDE with `/content/dam/snow/image1.jpg` and `image9.jpg`).
- `cq:lastReplicationAction` and `cq:lastReplicated` are set synchronously by the replication call: within 20-100 ms on the local SDK with a publisher. Measured on the Cloud SDK, not 6.5. Re-check on AMS before release.
- Moving a published page makes AEM replicate a DELETE for the old path. The new path has no replication status and is not live until it is published.
- IMS OAuth server-to-server (`client_credentials`, `/ims/token/v3`, 24 h token) is accepted by the Cloud Service author. The technical account's AEM user ID is `<guid>@techacct.adobe.com`, which differs from the `email` in the credential JSON. Add that ID to `dm-reference-writers`; quote it in repoinit (`add "<id>" to group dm-reference-writers`).

## Open questions / assumptions to verify

- The format of the customer's vanity delivery URLs (sets the Sites `vanity.patterns` regex).
- Where vanity IDs are stored on the asset (sets the Assets `vanity.metadata.property`).
- Whether the Granite CSRF filter or the Sling Referrer Filter blocks the server-to-server POST on Cloud Service.
