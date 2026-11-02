# Reviewing a document

The review page (`/review/{id}`) is where you confirm or correct the redactor's
findings. It has three panes:

1. **Original** — the source text with each detected span highlighted yellow.
2. **Redacted** — the same text with each *accepted* span replaced by a
   `<<TYPE>>` marker. Refusing a span removes the marker from this pane.
3. **PII Navigator** — a scrollable list, one entry per span, sorted by
   position in the document.

The Original and Redacted panes scroll together. Clicking a highlighted span
in either pane focuses the matching entry in the navigator and scrolls it
into view; clicking a navigator entry does the reverse.

## Finding what is left to review

An entry is **unreviewed** until someone accepts, refuses, or asks for a second
opinion on it. Entries the batch's PII threshold accepted arrive accepted, so
on many documents only a few need a decision. The navigator's header shows how
many entries there are and how many are unreviewed.

- **All**, **Unreviewed**, and **2nd opinion** above the list choose which
  entries it shows. With **Unreviewed**, an entry leaves the list as soon as
  it is decided, and the list keeps its place. When none are left, the list
  says the document is ready to approve, how many entries still await a
  second opinion, or, if you have already approved it, that the next approval
  must come from another reviewer. The choice is kept while you move between
  documents in the same browser tab.
- **Next unreviewed** goes to the next unreviewed entry after the one you last
  selected, in document order, and scrolls both panes to it. After the last
  one it starts again from the top. It is disabled when every entry has a
  decision.

Both only change what you see. They work on a document that is approved,
rejected, or open for someone else, where the entries cannot be changed.

## Per-span actions

Each navigator entry shows:

- A **type pill** that's actually a dropdown — click it to change the
  detected PII type (e.g., flip a span from `ssn` to `phone-number`). The
  change is saved immediately and the highlighted text updates.
- The matched **text**.
- The redactor's **confidence** as a percentage.
- **Accept**, **Refuse**, **2nd Opinion**, and **Reset** buttons. Refusing a
  span strikes it through in the navigator and removes its redaction marker
  from the Redacted pane. **Reset** returns the entry to unreviewed, and puts
  an adjusted span back where it was detected.
- A full-width **Redact All Like This** button. Clicking it scans the
  document for every other occurrence of the exact text and creates or
  updates spans so that all of them are accepted with the same PII type.
  The page reloads to reflect the new spans. (Match is case-sensitive and
  literal — substrings of longer words can match, so use it on
  unambiguous text like a project name or an email address.)
  So that one click cannot flood a document with spans, the span's text must
  be at least 3 characters, not counting spaces at either end (the button is
  disabled on a shorter span), and one click accepts or creates at most 500
  spans, then stops and says so. Clicking again applies any that remain,
  since matches a reviewer already accepted with the same type are not
  counted again. Adding a span by selecting
  text has no minimum length: a selection makes one span, and a single
  character, such as an initial, can be what needs redacting.
- **Don't flag this again** on a refused span, and **Always flag this** on a
  span added by hand, make a [detection rule](detection-rules.md) that changes
  what Philter flags in documents redacted from then on, in every batch in every group.

## Adjusting a span's boundaries

When a detection covers too little (for example, the last digits of an account
number) or too much, move its start or end instead of refusing it and adding a
new span. Either way:

- **Adjust boundaries** on the span's entry shows **Start** and **End** arrows
  that move each end one character at a time, previewed in both panes.
  **Save and accept** saves it; **Cancel** leaves the span as it was.
- **Select the new extent in the Original pane** over the span. Philter
  Workspace asks whether to adjust that span to the selection.

Saving new boundaries also accepts the span, with an exemption code when the
batch requires one. The span keeps its identity and its link to the detection:
it records where it was detected, is marked **Adjusted** in the navigator, and
**Reset** returns it to those boundaries, unless a span added since covers part
of them; then Reset is refused until that span is removed. A span cannot be moved onto another
span or onto only whitespace, and a span an approver adjudicated can be moved
only by an approver. Each move is audited as `SPAN_ADJUST` with the start and
end before and after, never the text.

Boundaries can be adjusted in text documents. Spans in PDFs cannot be adjusted
yet, since a moved span also needs a new position on the page.

## Read-only access for auditors

An auditor sees the review page with every action that would change the
document disabled or hidden, and "Read-only access" beside **Focus**. See
[What an AUDITOR can do](../reference/roles.md#what-an-auditor-can-do).
Opening a document as an auditor does not lock it, so reviewers can keep
working on it.

## Downloading a finalized document

**Download** on a finalized document saves its redacted text as a UTF-8 text
file named after the upload with `_redacted.txt` in place of its extension:
`letter.txt` downloads as `letter_redacted.txt`, and `filing.pdf` as
`filing_redacted.txt`. The audit log records the download with a keyed hash of
the file name, not the name.

## Delivery to a destination

When a document's batch has a [destination](../admin/destinations.md#delivering-finalized-documents),
finalizing the document writes its redacted text there in the background. The
review page then shows whether the delivery is pending, delivered, or failed,
with the reason. A failed write is retried three times; after that, an
administrator or the batch's team lead can click **Retry delivery**. The
finalized document is kept whatever happens to the delivery.

## Case metadata

Below the status line, the review page shows the document's
[case metadata](../reference/case-metadata.md) and the policy and version
Philter reported redacting it with. **Edit case** changes the metadata, unless
the document is finalized or still waiting to be redacted; the change is audited
and does not redact the document again.

## Adjudicating a span

An [approver](../reference/roles.md#approvers) of the document's group, or an
administrator, sees **Final decision: Accept / Refuse** on each detected span.
Use it to settle a disputed decision, or to resolve a span awaiting a second
opinion. After you confirm, the span is accepted or refused and shows
**Adjudicated by** your email. The audit log records it as `SPAN_ADJUDICATE`.
Overturning another reviewer's approval still asks for a reason.

Once a span is adjudicated, only an approver of the group can change it:
reviewers see its Accept, Refuse, 2nd Opinion, Reset, type, and Redact All Like
This controls disabled, and the server refuses those changes. An approver's
change to an adjudicated span is a new adjudication by that approver, and
**Reset** withdraws the adjudication. **Redact All Like This** never changes an
adjudicated span.

## Releasing a document

While another reviewer has a document open, it is read-only to everyone else
until they finish or the lock expires. An approver of the document's group, or
an administrator, can click **Break Lock** to release it so someone else in the
group can take it. The audit log records `DOCUMENT_LOCK_BROKEN` with the
previous holder.

Opening an approved, rejected, or finalized document takes no lock, since its
spans cannot change until it is reopened with Unapprove or Unreject.

While someone holds the lock, no one else can change the document's spans or
approve, reject, unapprove, unreject, or finalize it, from the review page or
through the API. Changing a span after the document has an approval clears
that approval (see [Changes after an approval](../admin/rules.md#changes-after-an-approval)).

## Document-level actions

The header has **Previous** / **Next** buttons that step through the
documents in this batch. By default the order is **highest risk score
first** (with a stable tie-break on document id), so working straight
through Next pulls the riskiest document of the batch up first and the
lowest-risk last. You can change the sort to **Document Priority** or
**Document Filename** under your [personal review-page
preferences](settings.md); whether the buttons skip documents that have
already been accepted or rejected (`APPROVED`, `AUTO_APPROVED`, `REJECTED`)
is controlled by the same preferences page.

Next to the buttons is a **Document X of Y** counter. `Y` is the number of
*pending* documents in the batch (everything except `APPROVED`,
`AUTO_APPROVED`, and `REJECTED`); `X` is the position of the document you
are currently viewing within that list, ordered by your chosen review
sort. The counter only appears while the current document is itself
pending — once you accept or reject it, it leaves the pending list and the
counter is hidden.

In batches with
[Blind Double Review](../admin/batches.md#blind-double-review) enabled,
**Previous** and **Next** also skip any document flagged for double review
whose first review was performed by you, so you are never paged into a
document you have already reviewed.

Opening a document that awaits its blind second review shows its text without
any spans. Click **Start blind review** to review your own copy of Philter's
detections, every one unreviewed, then **Submit blind review** once each is
accepted or refused. Your copy never changes the document's spans or its
decision. See [Reviewer experience](../admin/batches.md#reviewer-experience).

It also has Approve / Reject / Unapprove buttons. These set the *document's*
status:

- **Approve** — records your approval and returns you to the queue (or
  jumps to the next document if your "advance to next on approve or
  reject" preference is on). Whether the document moves to `APPROVED`
  immediately depends on the batch's [approval rule sets](../admin/rules.md):
  if dual approval is required and you're the first approver, the document
  stays in `REVIEW_REQUIRED` until a second different reviewer approves. A rule
  set can reserve that second approval for an
  [approver](../reference/roles.md#approvers) of the group.
- **Reject** — moves the document to `REJECTED`. Like Approve, it returns
  to the queue by default and jumps to the next document if your
  advance-to-next preference is on.
- **Unapprove** / **Unreject** — only shown for already-decided documents;
  flips the document back to `REVIEW_REQUIRED` so it can be re-reviewed.

## Focus mode

The header's **Focus** button toggles a distraction-free view of the review
page. When focus mode is on:

- The left navigation sidebar, page footer, and Back-to-queue link are hidden.
- The dual-approval progress banner, lock-conflict banner, and the status /
  risk-score / batch metadata strip below the filename are hidden.
- The terminal-status banners (`APPROVED`, `REJECTED`, `FINALIZED`) and the
  Unapprove / Unreject controls are hidden.
- The filename, the Original / Redacted / PII Navigator panes, the Previous /
  Next buttons with the Document X of Y counter, and the Approve / Reject
  buttons remain visible.

Click **Exit focus** to return to the full layout. The toggle is per-tab and
not persisted.

The same reviewer can never approve a document twice — a duplicate click
shows an inline error. The Document Queue's **Approvals** column tracks
progress (e.g. `1 of 2`, `0 of 1`).

Approve / Reject are hidden once the document is in a terminal status
(`APPROVED`, `REJECTED`, or `FAILED`). The decision is captured in the audit
log along with who made it and when.

## What happens to the underlying spans

- Span status changes (Accept / Refuse) are persisted on every click.
- Type changes are persisted on every selection change.
- Document Approve / Reject does not modify span statuses — it only marks the
  document. Approved documents typically have all their spans accepted by the
  time you click Approve.
