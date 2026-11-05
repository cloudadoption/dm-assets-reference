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

## Open questions / assumptions to verify

- The DM asset ID `urn:aaid:aem:<uuid>` is the asset node's `jcr:uuid`.
- The format of the customer's vanity delivery URLs (sets the Sites `vanity.patterns` regex).
- Where vanity IDs are stored on the asset (sets the Assets `vanity.metadata.property`).
- Whether the Granite CSRF filter or the Sling Referrer Filter blocks the server-to-server POST on Cloud Service.
