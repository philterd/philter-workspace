# Concepts

## Users and roles

A **user** signs in with an **email address** and a password. Each user has
exactly one role:

- **`USER`** — the default. Can view and review the batches and documents in
  groups they belong to.
- **`ADMIN`** — has full visibility (with an opt-in "Limit to my groups"
  filter) and exclusive access to administrative actions like creating batches,
  closing batches, and managing users / groups / settings.
- **`AUDITOR`** — read-only counterpart to `ADMIN`. Sees the same cross-group
  data an admin sees (queue, search, audit log, reports, batches), but cannot
  mutate any state. Useful for compliance, legal, or analyst roles that need
  to inspect activity without being able to change it. See
  [Roles and permissions](reference/roles.md) for the full feature matrix.

People sign in through Philter, and call Philter Workspace's API with a **Philter API key**, which carries
the permissions of the Philter user that owns it. See [Security](security.md#authentication).

## Groups

A **group** is a named collection of users. Every batch must be assigned to
exactly one group, and that assignment is what scopes visibility:

- A `USER` only sees batches whose group they belong to (and only the documents
  inside those batches).
- An `ADMIN` sees everything by default. The "Limit to my groups" checkbox on
  the queue and batches pages flips an admin to the same scoped view a regular
  user would see.
- An `AUDITOR` sees everything an admin sees and is not bound to a group; the
  role is intentionally cross-cutting because the whole point is a global read.

A group must have at least one member. Admins manage groups under
**Admin → Groups**.

Within a group, an admin can additionally designate one or more members as
**team leads**. A team lead is a regular `USER` who, *for that one group*,
gains the operational authority to create batches, close them, and change
their settings (policy, domain, weights, thresholds). Team
leadership is per-group: a user can lead group A and remain a regular member
of group B. This lets day-to-day batch operations be delegated without
granting site-wide admin authority. See
[Team leads](reference/roles.md#team-leads) for the full description.

## Batches

A **batch** is a container for documents. It has:

| Field                 | Meaning                                                                                |
| --------------------- | -------------------------------------------------------------------------------------- |
| Name                  | Human-readable label                                                                   |
| Group                 | Which user group can see and act on it                                                 |
| PII Threshold         | Per-span confidence floor for *auto-accepting* PII detections (default `0.8`)          |
| Document Threshold    | Risk score at or below which a document is marked *Low risk* in the queue (default `0.25`). It approves nothing. |
| Audit Sampling Rate   | Fraction of would-be auto-approved documents pulled into review for audit (default `0.10`, configurable; at `1.0` every document goes to a person) |
| PII type weights      | Per-type sensitivity for the risk score (see [PII types](reference/pii-types.md))      |
| Policy                | Name of the Philter policy used during redaction (else Philter's default policy)       |
| Domain                | Optional grouping tag used for reporting                                               |
| Closed                | A closed batch refuses new documents (existing ones remain reviewable)                 |

Admins can create or close any batch, and team leads can create or close
batches in groups they lead. Most batch settings (policy, domain,
thresholds, weights) can be changed by an admin or by a team lead of the
batch's group; reassigning a batch to a different group is admin-only.

## Documents

A **document** belongs to a single batch. It carries the original text, a
filename, a status (see below), a numeric **risk score** between 0 and 1
that reflects how much PII is in the document weighted by sensitivity, and a
**priority** flag (`1` Low, `2` Normal, `3` High; defaults to Normal) that the
queue surfaces as a small chevron icon next to the filename.

Document statuses:

| Status            | Meaning                                                            |
| ----------------- | ------------------------------------------------------------------ |
| `PENDING`         | Awaiting redaction in the [ingest queue](#ingest-queue)            |
| `PROCESSING`      | Currently claimed by a redaction worker (transient)                |
| `REVIEW_REQUIRED` | Has at least one span the reviewer must accept or reject           |
| `AUDIT_REQUIRED`  | Eligible for auto-approval but pulled into review by audit sampling |
| `AUTO_APPROVED`   | All spans auto-accepted, no human review needed                    |
| `APPROVED`        | A reviewer explicitly approved the document                        |
| `REJECTED`        | A reviewer explicitly rejected the document                        |
| `FINALIZED`       | The document was finalized after approval — the redacted text has been rendered and the Certificate of Redaction issued. Terminal: a finalized document cannot be reopened or re-finalized. |
| `FAILED`          | Redaction failed and the document was stored without spans         |
| `SKIPPED`         | Placeholder row written when an OpenSearch / Elasticsearch import detected a duplicate `(sourceIndex, sourceDocId)` and chose not to re-enqueue. Carries source attribution but no content. |

The queue always shows a document's stored status. A document whose risk score
is at or below the batch's Document Threshold also gets a **Low risk** badge
beside its risk score. The badge is a risk indicator, not an approval: a
`REVIEW_REQUIRED` document with a low risk score still has redactions to
decide. Retuning the threshold changes the badges on existing rows.

## Ingest queue

Newly received documents (whether uploaded through the UI or submitted via the
[REST API](reference/api.md)) are persisted as `PENDING` and placed on a
shared redaction queue kept in Philter Workspace's database. A background worker drains the queue
oldest-first, claiming each document atomically (`PENDING → PROCESSING`) so
multiple replicas can run safely. Once Philter completes, the worker writes
the spans, computes the risk score, and transitions the document to its final
post-ingest status (`REVIEW_REQUIRED`, `AUDIT_REQUIRED`, or `AUTO_APPROVED`).

Admins monitor the queue at **Admin → Ingest Queue**, where they can also
remove a still-pending document. The page also surfaces six summary widgets:

- **Pending** — documents currently awaiting redaction.
- **Processing** — documents claimed by a worker.
- **Failed** — documents whose redaction failed (clearable via Clear failures).
- **Skipped** — placeholder rows from re-runs of OpenSearch / Elasticsearch
  imports that detected duplicates by `(sourceIndex, sourceDocId)`.
- **Last 24 hours** — documents created in the last 24 hours, regardless
  of current status.
- **Throughput** — documents/hour averaged over the last 24 hours,
  computed from `statusChangedAt` for documents that have left
  `PENDING` / `PROCESSING`.

## Background jobs

Long-running ingests (today: pulling from OpenSearch or Elasticsearch
[data sources](admin/data-sources.md)) run as **background jobs** rather
than blocking the request thread. Each job is a row in the `background_jobs`
collection with status `PENDING` → `RUNNING` → `COMPLETED` / `FAILED`. A
dispatcher polls every couple of seconds and atomically promotes one
`PENDING` job per batch to `RUNNING`, so admins can queue many imports
against a batch without worrying about race conditions:

- **Per-batch serialisation.** A partial unique index on `background_jobs`
  guarantees at most one `RUNNING` data-import job per batch, even across
  replicas. Subsequent jobs sit at `PENDING` until the running one finishes.
- **Global ceiling.** **Admin → General → Max concurrent data imports**
  caps how many jobs run system-wide (`1`–`10`, default `1`).

Reviewers watch progress on the [Background Jobs](user-guide/background-jobs.md)
page; the user who started a job receives an inbox notification when it
ends.

## Spans

A **span** is a single PII detection inside a document. Each span has:

- A **type** (e.g., `ssn`, `phone-number`, `email-address`) — see
  [PII types](reference/pii-types.md).
- A **confidence** between 0 and 1 from the redactor.
- A **status**: `PENDING` (needs review), `APPROVED` (will be redacted),
  or `REJECTED` (will be left as-is).
- A character `start`/`end` and PDF coordinates if applicable.

When a document is created, each span's initial status is set automatically
based on the batch's PII Threshold:

- `confidence ≥ PII Threshold` → `APPROVED`
- `confidence <  PII Threshold` → `PENDING`

Reviewers can later flip a span's status, change its type, or use **Redact All
Like This** to apply the same decision to every other occurrence of the exact
text within the document.

## Risk score

Each document's risk score is computed from its spans, the batch's per-PII-type
weights, the number of unresolved spans (those still `PENDING`), and the
document's word count. The exact formula and an example are on the
[Risk score reference page](reference/risk-score.md).

## Approval rules and dual approval

A batch may optionally require **two approvals from two different reviewers**
before a document moves to `APPROVED`. Admins control this through
**rule sets** under **Admin → Approval Rules**.

### Rule sets, batches, and the AND/OR model

A batch can have **zero or more rule sets** attached to it. Each rule set is a
collection of conditions, and each condition is one of the rules listed below.

- **Within a single rule set, conditions are AND-ed.** A rule set "fires" only
  when *every* condition in it is satisfied at approve time.
- **Across rule sets on the same batch, results are OR-ed.** A document
  requires dual approval if *any* of the batch's rule sets fires.
- A batch with **no** rule sets behaves as before: single approval.

This lets you express "trigger dual approval when (A AND B) OR (C AND D)"
without complex per-rule expressions: each AND group is its own rule set, and
adding more rule sets simply adds more independent triggers.

### Available rules

| Rule | What it checks | Configurable cutoff |
| ---- | -------------- | ------------------- |
| Document contains an SSN, credit card, or passport number | Span types `SSN`, `CREDIT_CARD`, `PASSPORT_NUMBER` | — |
| Document risk score above a threshold | The document-level risk score | yes (0–1, default `0.9`) |
| A rejected span has confidence above a threshold | Any rejected span's `confidence` | yes (0–1, default `0.95`) |
| Approving reviewer has performed fewer reviews than a threshold | Reviewer's prior approve count | yes (≥0, default `100`) |
| Reviewer manually added more than X redactions | Count of `manuallyCreated` spans on the document | yes (integer ≥0, default `5`) |
| Document contains classified keywords | Case-insensitive substring match against an admin list (e.g. *Classified*, *Proprietary*, *Secret*) | yes (comma-separated list) |
| Dual-approval sampling rate | Per-document random roll persisted at ingest, compared to a configured rate | yes (0.0–1.0, default `0.02` = 2%) |

The reviewer-experience rule short-circuits at queue display time so it can
flag documents up front; document-side rules are evaluated against the live
document state.

### Behavior when a rule set fires

When dual approval is required, the document tracks an ordered list of
approver emails (`approvedBy`) and stays in `REVIEW_REQUIRED` until enough
approvals have been collected. The same reviewer can never approve a document
twice. The Document Queue's **Approvals** column shows progress (e.g. `1 of 2`).

For worked examples and recommended configurations, see the admin guide:
[Approval rule sets](admin/rules.md).

## Inbox

Each user has a per-user **Inbox** for system-delivered messages
(`inbox_messages` collection: id, userId, message, createdAt, read). The
sidebar **Inbox** link displays an unread-count badge that's available on every
page via a global controller advice. Users mark messages read individually;
admins can deliver messages programmatically via the inbox service.

## Search

Full text search is the optional capability that indexes every ingested
document in **OpenSearch** so reviewers can search by content. When enabled,
every document goes into the configured index at ingest time with its
filename, batch, status, and full original text. The **Search** page in the
sidebar and the `POST /api/v1/search` endpoint run a full-text match query
against that index. Results are limited to the batches the caller can see, and
the total counts only those. Both take the query in the request body, so it
stays out of URLs and access logs; the Search page keeps it in the person's
session, encrypted, and shows its results under a random token.

The feature is **off by default**, since the index is a copy of the original,
unredacted text. An administrator turns it on and sets its connection details
(endpoint, optional basic-auth credentials, index name) at runtime under
**Admin → Settings → Full text search**. The full configuration, mapping
bootstrap, and what changes when the feature is turned off are documented
on the [Full text search](admin/full-text-search.md) admin page.

## Audit log

Every state-changing action — login, logout, batch changes, span and document
updates, settings changes — is written to the `audit_log` collection in
Philter Workspace's database with the actor's email, the resource touched, a timestamp, and
context-specific details. Admins can browse and export the log under
**Admin → Audit log**.
