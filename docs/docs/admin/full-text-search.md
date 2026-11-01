# Full text search

Full text search is the optional capability that lets reviewers find documents
by their content. When it is on, every document Philter Workspace redacts is also
indexed, and the **Search** page in the sidebar, the
[`POST /api/v1/search`](../reference/api.md#post-apiv1search) endpoint, and **Find similar
documents** on the review page are served from that index.

The feature is **off by default**, because the index is a copy of every
document's original, unredacted text. An administrator turns it on deliberately
under **Admin → General → Full text search**; the change is recorded in the
audit log as `GENERAL_SETTINGS_CHANGE`, with the previous and new values.

The index is kept by one of two **backends**, chosen when Philter Workspace
starts (see [Choosing a backend](#choosing-a-backend)):

- **Embedded** (the default): an index inside Philter Workspace, on its own
  disk, encrypted with Philter Workspace's key. No other service is needed.
- **OpenSearch**: an index in an OpenSearch cluster you run.

## What full text search is used for

- The **Search** page in the sidebar runs a full-text match over every
  document the caller is allowed to see. Results in batches the caller cannot
  see are left out, and the total counts only the rest.
- The `POST /api/v1/search` REST endpoint serves the same query for
  programmatic clients.
- Neither puts the query in a URL, where access logs and browser history would
  keep it. The Search page posts the query, keeps it in the person's session
  encrypted with `PHILTER_WORKSPACE_CRYPTO_SECRET`, and shows the results under
  a random token (`/search?r=…`), so back, refresh, and paging still work. A
  session keeps its ten most recent searches, and they end with it. A query in
  the URL (`/search?q=…`, from before) is not run.
- The **Find similar documents** button on the document review page runs
  OpenSearch's `more_like_this` query to surface documents in the same batch
  whose text resembles the one currently open. The button is hidden on the
  review page when full text search is disabled.

What full text search is **not** used for: it does not drive the queue,
status filtering, dual-approval evaluation, audit log, or any of the
review-decision pipeline. Disabling full text search affects discovery only;
ingest, redaction, review, and finalize all continue to function normally.

## Choosing a backend

Set `PHILTER_WORKSPACE_SEARCH_BACKEND` (`philter-workspace.search.backend`) to
`embedded` or `opensearch`. Any other value stops Philter Workspace at startup.
The Full text search card on **Admin → General** names the backend in use, and
Philter Workspace logs it at startup (`Full-text search backend: ...`).

| | Embedded | OpenSearch |
|---|---|---|
| Needs | Nothing beyond Philter Workspace | An OpenSearch cluster, its credentials, and its own backups and patching |
| Index stored | A directory on Philter Workspace's disk (`PHILTER_WORKSPACE_SEARCH_INDEX_DIR`) | The cluster |
| Encryption at rest | The index files, by Philter Workspace, with a key derived from `PHILTER_WORKSPACE_CRYPTO_SECRET` | Whatever the cluster's disks provide; Philter Workspace sends plaintext |
| Philter Workspace instances | One per index directory | Any number sharing one index |
| Memory | Read from disk, not memory-mapped, so it competes only for the operating system's file cache | The cluster's own heap, typically 2 to 4 GB |
| Configured in | The environment (the index directory) | **Admin → General** (endpoint, index name, credentials) |

Use the embedded backend for a single Philter Workspace, such as Philter One
or another single-server or air-gapped install. Use OpenSearch when several
Philter Workspace instances serve one deployment, or when you already run a
cluster and want the index there. Both serve the same Search page, API, and
Find similar documents, with the same group restrictions and highlighting.

## The embedded backend

The embedded index is a directory, `PHILTER_WORKSPACE_SEARCH_INDEX_DIR`
(`philter-workspace.search.index-dir`, default `data/search-index` under
Philter Workspace's working directory; `/app/data/search-index`, in the data
volume, in the shipped Compose files). Every file in it is encrypted with
AES-256 under a key derived from `PHILTER_WORKSPACE_CRYPTO_SECRET`, so a copy of
the directory without that secret reveals no document text. Changing the secret
makes the index unreadable; rebuild it afterwards.

The index is a disposable copy: the database stays the record, and losing the
directory costs a [rebuild](#rebuilding-the-index), not data. Exclude it from
backups if you like, or place it on a volume of your choice by setting the
directory. Only one Philter Workspace may use a directory at a time; a second
instance cannot write to it.

Nothing is created on disk until full text search is turned on. Turning it on
opens the index, and **Admin → General** reports an error if the directory
cannot be written.

## Rebuilding the index

Documents are indexed as they are redacted, so documents redacted while full
text search was off, or before a move to another backend, are not in the index.
**Rebuild index** on the Full text search card empties the index and indexes
every document that still has its original text, in the background; the card
shows its progress and how the last rebuild ended. Running it again gives the
same index. It is recorded in the audit log as `SEARCH_INDEX_REBUILD_START` and
`SEARCH_INDEX_REBUILD_FINISH`.

## Moving from OpenSearch to the embedded backend

An existing deployment on OpenSearch keeps working by setting
`PHILTER_WORKSPACE_SEARCH_BACKEND=opensearch`, with no reindex. To move to the
embedded backend:

1. Set `PHILTER_WORKSPACE_SEARCH_BACKEND=embedded`, and
   `PHILTER_WORKSPACE_SEARCH_INDEX_DIR` to a directory on a persistent volume.
2. Restart Philter Workspace.
3. Under **Admin → General → Full text search**, click **Rebuild index**.
   Search results fill in as the rebuild runs.
4. Once you no longer need it, delete the old index in OpenSearch, which holds
   the documents' unredacted text, and stop the cluster.

The two backends do not run side by side; moving back is the same steps in the
other direction.

## Enabling and configuring OpenSearch

These settings apply with `PHILTER_WORKSPACE_SEARCH_BACKEND=opensearch`.
The connection details to the OpenSearch cluster live in the database; there is
no environment variable for them.

The Full text search card carries five fields:

| Field        | Required | Notes                                                                                   |
|--------------|----------|-----------------------------------------------------------------------------------------|
| Enabled      | yes      | Master on/off switch. **Off by default.** Turning it on indexes original, unredacted text. |
| Endpoint     | yes      | Base URL of the OpenSearch cluster, including scheme and port (e.g. `http://opensearch:9200`). |
| Index name   | yes      | Lower-case letters, digits, `-`, `_`, `+`. Defaults to `philter-workspace-documents`.              |
| Username     | no       | Optional HTTP basic-auth username for clusters that require it.                         |
| Password     | no       | Optional basic-auth password. Encrypted at rest with AES-GCM (the same scheme as data-source credentials). Leave blank to keep the previously stored value; tick **Clear stored password** to wipe it back to none. |

Click **Save**. Philter Workspace immediately tries to reach the cluster and one of
four things happens:

1. **The index does not exist** — Philter Workspace creates it with the canonical
   mapping (one `text` field on `originalText` for full-text matching, plus
   keyword fields on `id`, `batchId`, `filename`, and `status` for filters)
   and the settings are saved. A success banner confirms the new index.
2. **The index exists with the canonical mapping** — settings are saved
   without modifying the index. A success banner confirms the reuse.
3. **The index exists but its mapping differs from canonical** — settings
   are **not** saved yet. A yellow callout appears below the form with a
   side-by-side diff of the existing and expected mappings, and two buttons:
    - **Continue with existing index** — saves the settings as submitted and
      uses the existing index as-is. Search may still work but documents
      that relied on the canonical mapping (e.g. `originalText` indexed for
      full-text matching) may not surface as expected. Recommended only when
      you know exactly why the mapping differs and want to keep it.
    - **Cancel** — discards the submission. The form is reset and nothing
      is saved.
4. **OpenSearch is unreachable, or returns a server error** — settings are
   not saved and an error banner explains why. Fix the connection (or auth)
   and try again. The probe is intentionally a hard gate when the feature
   is being **turned on** — saving an endpoint that isn't reachable would
   only produce a steady stream of failed indexing calls.

The save event is recorded in the [audit log](audit-log.md) as a
`GENERAL_SETTINGS_CHANGE` row carrying the previous and new values
(passwords are never logged, only a `passwordChanged` boolean).

### Choosing an index name

A single OpenSearch cluster can host multiple deployments by giving each
its own index. Pick a name that is descriptive enough to identify the
deployment when looking at the cluster directly — e.g. `philter-workspace-staging`,
`philter-workspace-prod-redactions`. The index is created on first save; renaming it
later writes a brand-new empty index, so the search page will be empty
until enough new documents are ingested.

### Mapping comparison rules

Philter Workspace compares only the canonical fields that the search code reads back
(`id`, `batchId`, `filename`, `status`, `originalText`). Extra fields on the
existing index — added by hand, by another tenant, by a previous Philter Workspace
version — are **tolerated**. A mapping is reported as a mismatch only when:

- A canonical field is missing entirely from the existing index.
- A canonical field exists but with a different `type` (for example, an
  `originalText` field that's been declared as `keyword` rather than
  `text` cannot serve full-text match queries).

This loose comparison keeps the mismatch dialog rare in practice while still
catching the differences that actually break search.

## Disabling full text search

Untick **Enable** on the same form and click **Save**. Disabling does not
require the cluster to be reachable — Philter Workspace writes the setting and skips
the OpenSearch probe entirely. This means you can turn the feature off even
if the OpenSearch cluster is currently down.

When disabled:

- New ingests **do not** push the document text to OpenSearch. The text
  still lives in MongoDB exactly as it did before, and review continues to
  work normally.
- The **Search** page returns empty results (an informational note explains
  why). The `POST /api/v1/search` endpoint behaves the same way.
- The **Find similar documents** button on the review page is **not
  rendered** — reviewers won't see it at all rather than getting a button
  that produces empty results.
- The OpenSearch cluster, if you still have one running, is left untouched.
  No documents are deleted; previously-indexed documents stay in the index
  so a future re-enable can use them as-is (subject to the mapping
  comparison above). To remove their text, delete the index in OpenSearch.

### Implications of disabling

- **Search-by-text becomes unavailable.** Reviewers can still find documents
  by batch, by status, by priority, by filename (the queue page filters all
  do this without OpenSearch), and by document ID. They cannot run
  free-text matches over the body of every document.
- **Find similar documents goes away.** This is the only feature that
  surfaces textually-related documents in the same batch.
- **No new documents index.** If you re-enable later, the gap between
  disable and re-enable will not be back-filled — only documents ingested
  after re-enable show up in search results, until you
  [rebuild the index](#rebuilding-the-index).
- **Reports, audit log, and the review queue are unchanged.** None of
  those depend on OpenSearch.
- **Health stops reporting the backend.** With the feature enabled, an
  index that cannot be opened or a cluster that does not answer makes
  `/api/health` and `/actuator/health` report `DOWN` (component
  `fullTextSearch`), because search and indexing are degraded. With it
  disabled, health does not check the backend. If you do not
  run a cluster, turn the feature off rather than leaving health down. See
  [REST API → Health and metrics](../reference/api.md#health-and-metrics).

Disabling is the right choice when:

- You don't have an OpenSearch cluster available and don't want indexing
  attempts to flap against `localhost:9200`.
- Your deployment policy disallows indexing PII-bearing document text in a
  second store. Note that with full text search **enabled**, the
  unredacted source text is sent to OpenSearch in plaintext (see
  [Security considerations](#security-considerations) below). If you
  cannot satisfy the precautions listed there, leave the feature off.
- You're temporarily isolating the deployment from the OpenSearch cluster
  for maintenance and want clean signals (no warning logs about indexing
  failures).

## Removing documents from the index

While full text search is on, a document is removed from the index when:

- Finalization clears its original text (a batch finalization policy that
  deletes originals). The `FINALIZATION_POLICY_APPLIED` audit entry records
  whether the index removal succeeded (`indexCleared`).
- An administrator removes it from **Admin → Ingest Queue**, as a queued
  document or a failure.

Removal is best-effort: if the backend does not answer, Philter Workspace logs a
warning naming the document's id, and the indexed copy remains until it is
removed there or the index is rebuilt. Removal is skipped while full text
search is off.

## Security considerations

With the embedded backend, the index files are encrypted by Philter Workspace
(see [The embedded backend](#the-embedded-backend)); protect the
`PHILTER_WORKSPACE_CRYPTO_SECRET` as you would for the database. The rest of
this section applies to the OpenSearch backend.

!!! danger "PII is indexed in OpenSearch when this feature is enabled"
    Enabling full text search causes the **complete, unredacted source text**
    of every ingested document to be written to the configured OpenSearch
    cluster — including any PII that document contains (names, email
    addresses, SSNs, phone numbers, account numbers, medical details, …).
    This is the design: OpenSearch needs the raw text to power the
    full-text match query.

    The OpenSearch index sits **outside** the at-rest encryption boundary
    that covers MongoDB. The
    [`SymmetricCipher` field-encryption callbacks](../security.md#pii-at-rest)
    encrypt PII inside MongoDB before it is written to disk; **no equivalent
    transformation runs on the OpenSearch indexing path.** Documents go
    over the wire to OpenSearch as plaintext and are stored as plaintext.

    Treat the OpenSearch cluster, the network path to it, the basic-auth
    credentials configured below, the disks and snapshots backing it, and
    any backups taken from it as **part of the same security boundary as
    your MongoDB instance**. Anything less and the search index becomes the
    weakest point in your PII handling.

Concretely, the precautions you need to apply to the OpenSearch cluster
when full text search is enabled:

- **Encrypt the OpenSearch data directory at rest.** Use disk-level
  encryption (LUKS, dm-crypt, EBS encryption, Azure Disk Encryption, GCP
  CMEK) on every node in the cluster. The same volume and the same key
  management posture you give your MongoDB data directory.
- **Encrypt OpenSearch backups and snapshots.** Snapshots written to S3
  or another object store carry the full document text. Encrypt the
  destination bucket and rotate keys on the same schedule as your other
  PII backups.
- **Encrypt the network path.** Use **HTTPS** for the endpoint
  (`https://` in the field above) so the document text isn't exposed on
  the wire between Philter Workspace and OpenSearch. A `http://` endpoint is fine
  only if Philter Workspace and OpenSearch are co-located on a trusted network
  segment that isn't shared with anything else — a single Docker bridge
  network on the same host, for example.
- **Require authentication.** Configure OpenSearch's security plugin (or
  your reverse proxy) to require basic auth, and enter the username and
  password into this form. Philter Workspace encrypts the password at rest with the
  same AES-GCM scheme used for data-source credentials. **Anonymous
  access to the cluster equals anonymous access to every PII document
  Philter Workspace has ever ingested.**
- **Scope the credential to least privilege.** The user Philter Workspace logs in
  as needs only:
    - `indices:data/write/index` and `indices:admin/create` /
      `indices:admin/get` on the configured index name (so Philter Workspace can
      create the index on first save and write documents on every ingest).
    - `indices:data/read/search` on the same index (so the Search page
      can run match and `more_like_this` queries).
    Avoid cluster-wide admin or all-indices grants.
- **Restrict network reachability.** Bind OpenSearch to a private network
  interface; don't expose `9200` on a public IP. Lock the firewall down
  to the subnets that Philter Workspace runs on.
- **Audit OpenSearch access independently.** Philter Workspace writes
  `DOCUMENT_PII_SENT_TO_LLM` rows when text leaves for an LLM, but it
  does **not** write a per-document audit row for every OpenSearch index
  operation (those happen on every ingest and would flood the audit log).
  Rely on OpenSearch's own audit logging or your network capture tooling
  to attribute reads of the index.
- **Apply the same retention policy as MongoDB.** When a batch is
  finalized and a finalization policy purges the source text from
  MongoDB, the OpenSearch document is **not** automatically deleted.
  Either disable full text search for batches under a delete-immediately
  policy, or run a periodic reindex/cleanup job against OpenSearch that
  honors the same retention rules.

If your deployment policy does not allow PII to live in a second store
even with all of the above applied, **disable full text search** (see
[Disabling full text search](#disabling-full-text-search) above). With
the feature off, the document text never leaves MongoDB.

See [Security · PII at rest](../security.md#pii-at-rest)
for the broader at-rest story; the section there explicitly calls out
OpenSearch as needing its own encryption.

## Troubleshooting

- **"Could not reach OpenSearch at … : Connection refused"** — the cluster
  is down, or the endpoint URL is wrong. Double-check the scheme and port,
  and try again.
- **"OpenSearch returned HTTP 401"** — the cluster requires authentication
  but the credentials supplied are missing or wrong. Re-enter the username
  and password and re-save.
- **Mapping mismatch dialog every time** — something is creating the index
  with a non-canonical mapping (a templated index, an external indexer, or
  manual `curl` setup). Either delete the index out-of-band so Philter Workspace can
  recreate it, or click **Continue with existing index** and accept the
  divergence.
- **Health reports `DOWN` and every other dependency looks fine** — full
  text search is enabled but the cluster is not answering. Fix the endpoint
  or the credentials, or untick **Enable** if this deployment has no
  cluster.
- **Search page is empty even though documents exist** — the feature was
  disabled at some point during ingestion. Re-enable, then either re-index
  against the cluster yourself or accept that only post-re-enable documents
  will appear.
