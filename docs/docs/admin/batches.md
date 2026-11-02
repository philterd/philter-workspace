# Batches

Batches live at `/batches`. The page shows every batch you have access to,
sorted by date created (newest first by default). The **Name**, **Created**,
and **Documents** columns are click-to-sort headers — click again to flip
direction. Additional columns include **Queued** (count of non-terminal
documents) and a **Review** link/badge that opens the first reviewable
document in the batch.

## Create a batch (admin only)

Click the **Create Batch** button at the top of the page to open the batch
creation dialog. Fill in:

| Field                | Default | Notes                                                                                      |
| -------------------- | ------- | ------------------------------------------------------------------------------------------ |
| Name                 | —       | Required                                                                                   |
| Group                | —       | Required; only existing groups appear in the dropdown                                      |
| Policy               | Philter's default | A policy in Philter                                                              |
| Choose each document's policy from its case type | unchecked | When checked, each document is redacted with the policy mapped to its case type under **Admin → General → Case types**, and a document with no case type or an unmapped one is marked `FAILED` instead. See [Case metadata](../reference/case-metadata.md#choosing-the-policy-by-case-type). |
| Batch Finalization Policy | —  | Required; governs document retention when the batch is finalized                           |
| Compliance Profile   | —       | Required; **cannot be changed after the batch is created**                                 |
| Exemption Code       | checked | When checked (the default), accepting a redaction in this batch prompts the reviewer to pick an exemption code from the chosen compliance profile. Uncheck to suppress the prompt even when the profile defines codes — useful for batches where exemption-code tracking isn't required. |
| Blind Double Review  | unchecked | When checked, a random sample of incoming documents is selected for an independent second review by a different reviewer. Default sample is **10%**; the percentage field accepts an integer 1–100. **Both the on/off flag and the percentage are write-once — they cannot be changed after the batch is created.** See [Blind Double Review](#blind-double-review) below. |
| Domain               | —       | Optional grouping tag used in the Reports page aggregates                                  |
| PII Threshold        | `0.80`  | Per-span confidence floor for auto-accepting detections                                    |
| Document Threshold   | `0.25`  | Risk score at or below which a document is marked Low risk in the queue. It approves nothing. |
| Audit Sampling Rate  | `0.10`  | Fraction of would-be auto-approved documents pulled into review. At `1.0`, every document goes to a person. |

The three defaults are the deployment's: the form is filled with the values in
[Batch defaults](../reference/configuration.md#batch-defaults), which are
`0.80`, `0.25`, and `0.10` unless configured. Changing them affects new batches
only.

Click **Create** in the dialog footer to save, or **Cancel** (or press
**Escape**) to dismiss without saving. The dialog is admin-only — non-admins
do not see the **Create Batch** button, and the underlying endpoint refuses
non-admin POSTs as a defense in depth. When no user groups exist yet, the
button is rendered disabled with a message reminding the operator to create
a group first.

## Finalization policies

A batch's finalization policy, chosen when it is created and managed under
**Policies → Finalization**, decides what happens to a document's original
once the document is finalized:

| Option | When the original is deleted |
| --- | --- |
| Delete original documents immediately after finalizing | At finalization. |
| Delete original documents after 48 hours | 48 hours after finalization. |
| Delete original documents after X days | The policy's number of days after finalization. |
| Keep original documents (never delete them) | Never. |

Deleting the original clears the document's original text and stored file,
its spans (which hold the detected values), its full-text index entry, and any
copy kept for retrying redaction. The document record, its redacted text (so
**Download** keeps working), and its certificate of redaction are kept. Each
deletion is recorded in the audit log as `FINALIZATION_POLICY_APPLIED` with
`SOURCE_CLEARED`.

The two delayed options are carried out by a job that runs every 15 minutes
(`philter-workspace.finalization.retention-poll-millis`, default `900000`), so
a document is deleted within 15 minutes of its time. The job uses each batch's
policy when it runs, so a document whose batch has since moved to **Keep
original documents** is kept, and one whose batch moved to a delete option is
deleted by that option's time. It counts from when each document was finalized,
so a document whose time passed while Philter Workspace was not running is
deleted on the next run.

## Per-row actions

Each batch row has:

- An inline **Group** selector with a **Save** button (changes the assigned
  group; non-admins are limited to groups they belong to).
- A **Settings** button that opens a modal to edit the PII Threshold,
  Document Threshold, **and Audit Sampling Rate**. All three are validated to
  `[0.0, 1.0]`. When the deployment sets a minimum audit sampling rate, a
  lower rate is refused here and in the create dialog, and the attempt is
  recorded in the audit log as `BATCH_AUDIT_SAMPLING_RATE_REFUSED`.
- **Change…** in the Policy column, to choose the batch's policy or have it
  [choose each document's policy from its case type](../reference/case-metadata.md#choosing-the-policy-by-case-type);
  the column then shows **By case type**.
- A **Weights** link that opens the per-batch PII weight editor (see below).
- A **Destination** button (admin only) that chooses where documents
  finalized in the batch are delivered: a configured
  [destination](destinations.md#delivering-finalized-documents), or **None**.
  **Info** shows the batch's current destination.
- A **Close** button (admin only) that marks the batch closed.
- A **Closed** badge next to the name when the batch is in the closed state.

Only admins can create or close a batch. Approval rules for each batch are
managed separately under **Admin → Approval Rules**; see
[Approval rule sets](rules.md) for the AND-within / OR-across model and
worked examples. All other settings (group, thresholds, weights) remain
editable for users with access to the batch.

## Closing a batch

Closing is one-way. A closed batch:

- Refuses new uploads from the web UI (it disappears from the upload page's
  batch dropdown, and direct POSTs to `/redact` are rejected).
- Returns **HTTP 409** from the API ingestion endpoint with a body like
  `{"error": "Batch \"<name>\" is closed and cannot accept new documents.",
  "batchId": "...", "closed": true}`.
- Stays fully visible in the queue and review UI — existing documents in the
  batch remain reviewable, exportable, and modifiable.
- Records a `BATCH_CLOSE` audit entry.

There is no built-in reopen action.

## PII weights (`/batches/{id}/weights`)

Each batch has its own per-PII-type sensitivity weight. The defaults are:

| Type              | Default weight |
| ----------------- | -------------- |
| `ssn`             | 10             |
| `credit-card`     | 10             |
| `phone-number`    | 5              |
| `email-address`   | 5              |
| `person`          | 3              |
| `first-name`      | 3              |
| `surname`         | 3              |
| `physician-name`  | 3              |
| `street-address`  | 3              |
| `zip-code`        | 2              |
| Everything else   | 1              |

The weights page lists every supported PII type alphabetically. The displayed
**Weight** column starts at the effective value (default unless overridden
for this batch). Set any value to `0` to ignore that type entirely. **Save
weights** persists overrides; only values that differ from the default are
stored, so re-tuning a single default later picks up automatically for any
batch that didn't override it. **Reset to defaults** clears all overrides.

Higher weights cause spans of that type to contribute more to a document's
risk score. The full formula is on
[the Risk score reference page](../reference/risk-score.md).

## Blind Double Review

Blind Double Review is an opt-in quality-control feature on a batch. When
enabled, a random sample of the documents that flow into the batch is flagged
for an **independent second review** by a different reviewer. The first
reviewer's decision is preserved; a second reviewer separately reviews the
same document and their decisions are recorded alongside. Together the two
reviews drive the
[Inter-Annotator Agreement (IAA) report](reports.md#inter-annotator-agreement-iaa).

### What gets flagged

The selection happens **at ingest time**, once per document, using the
percentage configured on the batch. The decision is persisted on the document
(`doubleReview = true`) so the same document keeps the same selection
regardless of how many times it's read or re-redacted. Documents not selected
follow the normal review flow.

The default sample rate is **10%**. The percentage field accepts an integer
in the range **1–100** and only takes effect when the **Blind Double Review**
checkbox is also ticked.

### Write-once

Blind Double Review is a property of the batch and is **set at creation time
only**. After the batch is saved:

- The on/off flag cannot be toggled.
- The percentage cannot be edited.

This is intentional: changing the sample rate mid-batch would invalidate any
inter-annotator agreement statistics computed across the batch's documents.
If you need a different setting, create a new batch.

### Reviewer experience

A document selected for blind double review is reviewed twice, independently,
and the two reviews are then reconciled. It cannot be finalized until they
are.

**The first review** is the usual one: a reviewer works on the document's
spans and approves or rejects it. When the document is redacted, Philter
Workspace also keeps Philter's detections as they arrived, for the second
review to start from.

**The blind second review.** Once the first review is recorded, the document
appears in **My Queue** for reviewers who have not been shown its spans.
Anyone shown the document's spans before the second review was recorded,
on its review page, in its audit history or audit trail, or through
`GET /api/v1/documents/{id}/spans` or `/audit`, counts as shown, as do the
first reviewer and anyone who approved it. None of these is available to the
reviewer doing the blind review.

- Opening the document shows its text without any spans, and a **Start blind
  review** button. The first review's spans, its decision, and who made it
  are not shown.
- **Start blind review** gives the reviewer their own copy of Philter's
  detections, every one unreviewed. They review it as they would any document:
  accept or refuse each span, choose exemption codes, add spans for PII that
  was missed, move boundaries, and use Redact All Like This. Changes go to
  their copy only; the document's spans and approvals are untouched, and the
  first reviewer never sees the copy.
- Only one person does the blind review at a time. The document leaves the
  other reviewers' queues once it is started.
- **Submit blind review** records it once every span in the copy is accepted
  or refused.

A blind review cannot adjudicate spans, ask for a second opinion, or use Ask
AI or Explain. While it is in progress the blind reviewer cannot approve,
reject, unapprove, or unreject the document.

An approver of the batch's group, or an administrator, can **Release blind
review** to discard a blind review in progress, for example when its reviewer
is unavailable, so someone else can start it. They can also **Show the first
review**, after which they can no longer do the blind review themselves.

**Reconciling.** Once both reviews are recorded, the review page shows
approvers and administrators where the document's spans and the blind review
differ: text redacted in one and not the other, or redacted as different
types. To reconcile:

1. Change the document's spans where the blind review is right: **Unapprove**
   or **Unreject** the document, edit its spans, and approve or reject it
   again.
2. Click **Mark reconciled**.

The reviews are reconciled by an approver of the group, or an administrator,
who did neither review. The document then drops out of the queue and can be
finalized.

The first and second reviews are compared, as recorded, in the
[IAA report](reports.md#inter-annotator-agreement-iaa). Reconciling changes
the document, not the report.

A document selected before Philter Workspace kept the detections starts its
blind review from the document's detected spans: those added by hand are left
out, and moved spans are at the boundaries they were detected at. A span whose
type the first reviewer changed keeps the new type, since the detected type
was not kept.

## Visibility scope (admin checkbox)

Admins see a **Limit to my groups** checkbox at the top of the page. With
it checked (the default), the list shows only batches in groups the admin
belongs to. Unchecking it shows every batch system-wide. The selection is
preserved across sort clicks and reflected in the URL via `?myGroupsOnly=…`.
