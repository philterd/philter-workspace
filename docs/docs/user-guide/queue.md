# Document Queue

The **Document Queue** (linked from the sidebar) is your starting point as a
reviewer. It lists every document you have access to, ordered by risk score
(highest risk first) by default, so you can work top-down.

## Columns

| Column     | Notes                                                                       |
| ---------- | --------------------------------------------------------------------------- |
| Filename   | Original filename of the uploaded document                                  |
| Priority   | `Low` / `Normal` / `High` — see [Priority column](#priority-column)          |
| Batch      | The batch the document belongs to                                           |
| Status     | Document status (see below)                                                 |
| Risk Score | A number between 0.00 and 1.00 — see [Risk score](../reference/risk-score.md) |
| Actions    | A **Review** link that opens the side-by-side review pane, plus an **Audit Log** button (see below) |

The Filename, Priority, Batch, Status, and Risk Score headers are sortable —
click a header to sort by that column, and click again to flip the direction.
Risk Score and Priority sort highest-first by default; the others sort
ascending.

The status pill colors are:

- Green — `APPROVED`
- Emerald — `AUTO_APPROVED`
- Amber — `AUDIT_REQUIRED`
- Yellow — `REVIEW_REQUIRED`
- Blue — `PENDING`
- Red — `REJECTED`, `FAILED`

The Status column always shows the document's stored status. A document whose
risk score is at or below its batch's Document Threshold has a **Low risk**
badge beside its risk score. The badge does not approve the document: a
`REVIEW_REQUIRED` document marked Low risk still has redactions to decide.

## Priority column

The Priority column shows the document's priority as a label and a small
chevron icon:

- **Red double-up chevron · High** — priority `3`.
- **(no icon) · Normal** — priority `2`, the default.
- **Blue double-down chevron · Low** — priority `1`.

Priority is set at ingest (the upload form's **Priority** dropdown, or the
`priority` field on the [REST `POST /api/v1/ingest`](../reference/api.md#post-apiv1ingest)
request). It is a hint for reviewers — the queue's default sort is still
**Risk Score, highest first**, so priority does not change the order until
you click the **Priority** header to sort by it (defaults to highest-first
on the first click).

## Filters

- **Batch**: narrow the list to a single batch.
- **Status**: narrow the list to a single status value.
- **Clear**: wipes both filters.
- **Refresh**: re-fetches without changing filters or pagination.

Each filter change resets pagination to page 1.

## Limit to my groups (admins only)

Admins see a checkbox labeled **Limit to my groups**. With it checked (the
default), an admin sees only the batches and documents in groups they belong
to — exactly what a reviewer in those groups would see. Unchecking it expands
the view to every batch and document in the system. Toggling reloads the
batch dropdown to match the new scope.

The checkbox state isn't persisted across pages — it resets to "checked" each
time you load the queue.

## Blind Double Review

Documents in batches with
[Blind Double Review](../admin/batches.md#blind-double-review) follow a
different visibility rule. A document with status `APPROVED` or `REJECTED`
normally drops out of the review queue. A document selected for blind double
review stays in it for:

- reviewers who have not been shown its spans, until one of them starts the
  blind second review;
- the reviewer doing the blind second review, until they submit it;
- approvers of its group, and administrators, who did neither review, until
  the two reviews are reconciled.

The **Previous** and **Next** buttons on the review page skip double-review
documents whose first review was performed by you, so you are never paged into
a document you have already reviewed.

## Audit Log popup

Each row has an **Audit Log** button that opens a modal with the full
history for that document — document-level events plus events on every span
it contains, sorted newest first. See [Audit log](../admin/audit-log.md#per-document-audit-log)
for the full description.

The popup also has a **Download** button that exports the history as a CSV
file. The download is restricted to administrators; reviewers see the
button rendered disabled. The exported CSV omits PII text and includes span
location instead, so it can be safely shared as a chain-of-custody record.

## Pagination

The queue is paginated 10 documents per page. Page navigation uses the
**Previous** and **Next** buttons; the position indicator shows
`Page N of M (X documents)`.
