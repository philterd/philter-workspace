# Reports

The Reports page lives at `/reporting` and is open to **administrators and
auditors**. It is a read-only summary of activity across every batch the
caller can see, scoped to a date range you choose.

The page is broken into the sections below, in render order.

## Filters

### Date range

A row at the top of the page sets the time window. Both **Start date** and
**End date** are required. The default range is the last 30 days; the
**Last 30 days** button resets to that default.

The date range filters documents by their **created-at** timestamp. The
window is inclusive of both endpoints: documents created any time on the
end date are included. Documents with no timestamp (legacy data created
before the field existed) are kept in totals so they aren't silently
dropped.

A status line on the right of the filter bar echoes the active range
("Showing documents created from … to …").

### Domain

The page also accepts an optional **`domain`** query parameter — repeat it
to pick more than one domain (e.g. `?domain=Healthcare&domain=Legal`).
There is no UI control for it; set it on the URL when you want to compare
a single domain or a small subset against the rest of the deployment. With
no parameter the page covers every domain.

### Visibility scope

Administrators see every batch, document, span, and audit entry across the
whole deployment. Auditors see the same global scope read-only —
[Roles and permissions](../reference/roles.md#what-an-auditor-can-do)
covers the boundary.

Reviewers (the `USER` role) cannot reach the Reports page; the link does
not appear in their sidebar and the URL is rejected at the security
filter.

## Top KPI cards

A row of four high-level counters across the top of the page:

| Tile             | What it counts                                                                      |
|------------------|-------------------------------------------------------------------------------------|
| **Batches**      | Total visible batches in the date range. Sub-line splits open vs. closed.           |
| **Documents**    | Total documents across those batches.                                               |
| **Needs review** | Documents in `REVIEW_REQUIRED` or `AUDIT_REQUIRED` status (yellow highlight).       |
| **Auto-approved**| Documents in `AUTO_APPROVED` status: no redactions were left to decide at ingest and audit sampling did not select them (emerald highlight). |

## Span KPI cards

A second row of four counters focused on spans (the individual PII
detections inside documents):

| Tile               | What it counts                                                                      |
|--------------------|-------------------------------------------------------------------------------------|
| **Spans accepted** | PII detections approved by a reviewer.                                              |
| **Spans rejected** | False positives or incorrect detections rejected by a reviewer.                     |
| **Manually created**| Spans the redactor missed and a reviewer added by hand.                            |
| **Boundaries adjusted**| Detections whose start or end a reviewer moved. Moving a span accepts it, so these are also counted as accepted. Spans added by hand are not counted here, even if moved. |
| **Edit rate**      | `manual ÷ (accepted + rejected + manual)`. The fraction of decisions that required a manual addition. Higher numbers mean the redactor is missing PII the reviewers have to backfill. |

## Documents by status

Two cards side by side:

- **Documents by status** — a count for every known status
  (`PENDING`, `REVIEW_REQUIRED`, `AUDIT_REQUIRED`, `AUTO_APPROVED`,
  `APPROVED`, `REJECTED`, `FAILED`, `FINALIZED`) along with a pie chart.
  Status pills use the same colors as the rest of the UI: green for
  approved, red for rejected/failed, yellow/amber for documents that need
  attention, blue for `PENDING`.
- **Average risk score** — the mean risk score across every document in
  the current scope. Range is `0.000` (no risk) to `1.000` (clamped
  maximum). See [Risk score](../reference/risk-score.md) for the formula.

## Per domain

Aggregated by the **Domain** field on each batch (one row per domain
value, plus a `(none)` row for batches with no domain set):

| Column     | Meaning                                                                       |
|------------|-------------------------------------------------------------------------------|
| Domain     | The domain string.                                                            |
| Batches    | Number of batches in this domain in scope.                                    |
| Docs       | Total documents in those batches.                                             |
| Accepted   | Spans accepted by reviewers across those batches.                             |
| Rejected   | Spans rejected.                                                               |
| Manual     | Spans manually added by reviewers.                                            |
| Adjusted   | Detections whose boundaries a reviewer moved.                                 |
| Edit rate  | `manual ÷ (accepted + rejected + manual)` for the domain. Edit rate is colored amber at 10% and red at 20% to flag domains where the redactor leans on reviewers more than expected. |

Sorted by edit rate descending so problem domains float to the top.

## Reviews per user

Approvals and rejections each user recorded **in the selected date
range**, derived from the audit log:

| Column         | Meaning                                                                      |
|----------------|------------------------------------------------------------------------------|
| User           | The user's email (or `(unknown)` for legacy entries with no actor).          |
| Approvals      | `DOCUMENT_APPROVAL` events the user produced.                                |
| Rejections     | `DOCUMENT_STATUS_CHANGE` events the user produced where the new status was `REJECTED`. |
| Total reviews  | Approvals + Rejections.                                                       |

Sorted by total decisions, highest first. Users who took no action in the
range are not listed.

## Per Policy

Aggregated across all batches sharing a policy. The same shape as the
per-domain table:

| Column     | Meaning                                                                       |
|------------|-------------------------------------------------------------------------------|
| Policy     | Philter policy name, or `(default policy)`.                                  |
| Batches    | Batches in scope using this policy.                                          |
| Docs       | Documents in those batches.                                                  |
| Accepted   | Spans accepted by reviewers.                                                 |
| Rejected   | Spans rejected.                                                              |
| Manual     | Spans manually added.                                                        |
| Adjusted   | Detections whose boundaries a reviewer moved.                                |
| Edit rate  | Same as per-domain. Same color thresholds.                                   |

Sorted by edit rate descending. High edit rate flags policies that are
either letting too much through or false-positiving so reviewers are doing
extra manual work — a candidate to retune the policy or the per-PII-type
weights on the batch.

## Documents by batch and priority

Document counts grouped by **batch** and **priority** (High / Normal /
Low), broken out by status. Sorted by batch name, then by priority
highest-first.

| Column           | Meaning                                                                          |
|------------------|----------------------------------------------------------------------------------|
| Batch            | Batch name.                                                                      |
| Priority         | `High` (red), `Normal` (gray), or `Low` (blue) — see [Priority](../user-guide/queue.md#priority-column). |
| Total            | Total documents in this (batch, priority) cell.                                  |
| One column per status | Count for each of `PENDING`, `REVIEW_REQUIRED`, `AUDIT_REQUIRED`, `AUTO_APPROVED`, `APPROVED`, `REJECTED`, `FAILED`, `FINALIZED`. Zero counts are dimmed. |
| Not yet approved | `PENDING` + `REVIEW_REQUIRED` + `AUDIT_REQUIRED` — documents that still need a human decision. Highlighted amber when non-zero. |

Use the **Not yet approved** column to see, for example, how many
High-priority documents in a batch still need a reviewer.

## Per-batch breakdown

The bottom-of-page comprehensive table — one row per batch in scope:

| Column           | Meaning                                                                          |
|------------------|----------------------------------------------------------------------------------|
| Batch            | Batch name. A `Closed` pill appears when the batch has been closed.              |
| Policy           | The Philter policy this batch uses.                                              |
| Docs             | Document count.                                                                  |
| Accepted         | Spans accepted by reviewers.                                                     |
| Rejected         | Spans rejected.                                                                  |
| Manual           | Spans manually added.                                                            |
| Adjusted         | Detections whose boundaries a reviewer moved.                                    |
| Edit rate        | `manual ÷ (accepted + rejected + manual)`. Color thresholds as above.            |
| Auto-approved    | Documents in `AUTO_APPROVED` status.                                             |
| Avg risk         | Mean risk score across this batch's documents.                                   |

Sorted by document count descending, with a name tie-break.

## Inter-Annotator Agreement (IAA)

The bottom card on the page, rendered for every batch that has
[Blind Double Review](batches.md#blind-double-review) enabled. Batches
that do not have the feature turned on are not listed.

For each such batch the report shows:

| Column        | Meaning                                                                              |
|---------------|--------------------------------------------------------------------------------------|
| Batch         | Batch name.                                                                          |
| Documents     | Number of documents in the batch whose first and blind second reviews are both recorded. |
| Tokens        | Total tokens compared across those documents.                                        |
| Cohen's Kappa | Pooled token-level Cohen's Kappa between the two reviewers (see method below).      |

If a batch has no doubly-reviewed documents yet, the **Cohen's Kappa**
column reads *— not enough data —*. As soon as at least one document has
been reviewed by both reviewers, a numeric kappa is computed.

### How the score is computed

The score is **token-level Cohen's Kappa**:

1. For every double-reviewed document with snapshots from both reviewers,
   the original document text is split on whitespace into tokens
   (one token = one whitespace-delimited word).
2. Each token is given a binary label per reviewer:
    - **PII** — the token's character range overlaps any APPROVED span the
      reviewer left at the moment they completed their review: for the first
      reviewer, when the document was approved or rejected; for the second,
      when they submitted their blind review of their own copy. The two
      reviews are independent, and reconciling them afterwards does not
      change the score.
    - **O** — otherwise.

    Partial overlap counts as PII. This is conservative: a token that touches
    a PII span at all is treated as PII for the agreement calculation.
3. The per-token decisions are pooled across **every** double-reviewed
   document in the batch into a single 2x2 confusion matrix
   (both PII / first-only / second-only / both O).
4. Cohen's Kappa is computed once per batch from that pooled matrix.

Pooling at the batch level (rather than averaging per-document kappas)
gives a single defensible score even when individual documents are short
or lopsided in their PII vs. O distribution.

### How to read the score

The score is colored:

| Score range  | Color  | Reading                                                                                              |
|--------------|--------|------------------------------------------------------------------------------------------------------|
| **≥ 0.80**   | Green  | Substantial-to-near-perfect agreement. Reviewers are calibrated; the redaction policy is being applied consistently. |
| **0.60–0.79**| Amber  | Moderate agreement. Worth spot-checking the disagreements; usually points at edge cases in the policy. |
| **< 0.60**   | Red    | Low agreement. Indicates a real training gap or an ambiguous rule in the redaction policy that needs to be tightened. |

A kappa of **0.9** means the Blind Double Review process is acting as a
sanity check on a healthy pipeline; the admin can sleep soundly. A kappa of
**0.5** is a signal that the process has surfaced a meaningful issue —
either reviewers need additional guidance, or the redaction policy itself
has ambiguous cases the team needs to resolve.

### Edge cases

- **Both reviewers labeled every token as O.** Cohen's Kappa is degenerate
  in this case (the standard formula evaluates to 0/0). Philter Workspace reports
  **1.000** by convention, reflecting unanimous agreement.
- **Only one reviewer reviewed.** The document is excluded from the
  pooled matrix until the second reviewer completes their review.
- **Document text is empty.** The document is excluded.
- **A reviewer left no APPROVED spans.** Their labels are all O for that
  document, which is valid input to the kappa calculation.
