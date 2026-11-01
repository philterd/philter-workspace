# Workflow

This page walks through how Philter Workspace is used end-to-end: how an administrator
sets it up, how documents flow through it, and how reviewers approve or reject
the redactions Philter Workspace proposes. If you have not yet read
[Concepts](concepts.md), start there for the vocabulary used below.

## The big picture

Philter Workspace is a **human-in-the-loop deidentification reviewer**. It does not
detect PII on its own — that is Philter's job. Philter Workspace coordinates the work:

1. **An admin connects Philter Workspace to its Philter**, in Philter Workspace's environment.
2. **An admin authors policies in Philter.** A policy tells Philter what counts
   as PII and how to redact each type.
3. **An admin creates a batch** for a group of related documents and chooses
   its policy.
4. **A reviewer uploads documents** to the batch. Each document is sent to
   Philter with the batch's policy. Detected PII is stored as *spans*.
5. **A reviewer works the queue.** Documents that need attention surface at
   the top, sorted by risk score. Each document is opened in the side-by-side
   review UI.
6. **The reviewer accepts or rejects each span**, then approves or rejects
   the document as a whole. Decisions are captured in the audit log.

Everything in Philter Workspace is built around that flow.

## 1. Configure Philter

Philter Workspace works with one [Philter](https://philterd.ai). It signs
people in through it, lists its policies,
and sends every batch's documents to it for redaction. Philter Workspace does not detect
PII itself.

### Supported Philter versions

This release of Philter Workspace supports Philter 4.x: 4.0.0 and later 4.x releases. Philter Workspace reads Philter's
version from `GET /api/health` on the sign-in page and at each sign-in. For any other major version,
the sign-in page names the Philter's version and the supported one, and does not show the form.
Philter 3.x has no `/api/health` and is reported as older than 4.0. When the version cannot be read,
sign-in goes ahead and reports any failure itself. **Admin → Philter → Test** also reports an
unsupported version.

### Connecting Philter Workspace to Philter

Set two environment variables on Philter Workspace:

| Variable | Purpose |
|---|---|
| `PHILTER_WORKSPACE_PHILTER_URL` | Philter's `http` or `https` URL, such as `http://philter:8080`. |
| `PHILTER_WORKSPACE_PHILTER_API_KEY` | The API key Philter Workspace uses for background work such as ingest, sent as a bearer token. It must not belong to a Philter administrator; see [Philter API key for Philter Workspace](#philter-api-key-for-philter-workspace). |

Both are required: Philter Workspace does not start without them. For HTTPS with a
private certificate authority, or mutual TLS, set the `philter-workspace.philter.tls.*`
settings in
[Philter integration](reference/configuration.md#philter-integration). The URL
and key are not admin settings, so they cannot be changed from the UI.

**Admin → Philter** shows the URL and whether the key belongs to a Philter
administrator. **Test** calls Philter's `/api/health` endpoint and reports its
Philter version, then makes an authenticated call to confirm Philter accepts the
key and to check its role. Philter Workspace also checks the role when it starts, and logs
a warning for an administrator's key. The key keeps working, but the page shows
a warning until it is replaced. Each Test is recorded in the audit log as
`PHILTER_TEST`.

### Philter API key for Philter Workspace

Philter keeps policies, contexts and their consistent-pseudonymization
mappings, and the default format-preserving encryption key per user: a key
reaches only its own user's. Every batch therefore uses the policies and
contexts of the user who owns `PHILTER_WORKSPACE_PHILTER_API_KEY`.

That key must not belong to a Philter administrator. Philter Workspace asks Philter for
the key's role (`GET /api/limits`) when it starts and on **Test**, which fails
for an administrator's key. Such a key keeps working, but **Admin → Philter**
shows a warning until the key's user is no longer an administrator.

`docker-compose.prod.yaml` does not publish Philter's port, so the commands below
run `curl` in a container on the compose network. `docker network ls` shows its
name, `<project>_philter-workspace`; it is `philter-workspace_philter-workspace` when the compose project is
named `philter-workspace`. Set `PHILTER_ADMIN_KEY` to an administrator's key, such as
`PHILTER_BOOTSTRAP_API_KEY`.

**For a new deployment**, create a Philter user for Philter Workspace and a key with only
the `redact`, `policies:read`, `lists:read`, `lists:write`, and `ledger:read` scopes. The two
`lists` scopes are needed only for [detection rules](user-guide/detection-rules.md), and
`ledger:read` only to show Philter's ledger in a document's
[audit trail](admin/audit-log.md#audit-trail-with-philters-ledger):

```sh
docker run --rm --network philter-workspace_philter-workspace curlimages/curl:8.10.1 -s -X POST "http://philter:8080/api/users" \
  -H "Authorization: Bearer $PHILTER_ADMIN_KEY" -H "Content-Type: application/json" \
  --data '{"username":"philter-workspace","role":"user"}'
docker run --rm --network philter-workspace_philter-workspace curlimages/curl:8.10.1 -s -X POST "http://philter:8080/api/users/philter-workspace/api-keys" \
  -H "Authorization: Bearer $PHILTER_ADMIN_KEY" -H "Content-Type: application/json" \
  --data '{"scopes":["redact","policies:read","lists:read","lists:write","ledger:read"]}'
```

The second response holds the key in `apiKey`, shown only once. Set it as
`PHILTER_WORKSPACE_PHILTER_API_KEY`. Create Philter Workspace's
policies with a key of this user, or as an administrator with `owner=philter-workspace`
(which needs `ADMIN_CROSS_USER_ACCESS_ENABLED=true` in Philter); a key with only
`policies:read` cannot create them.

**If Philter Workspace already uses an administrator's key**, do not switch to another
user's key: that user would not have the policies and contexts your batches use,
pseudonyms and encrypted values would change, and context mappings cannot be
moved to another user. Instead, keep the user and remove its administrator role:

1. Create another Philter administrator for people, if there is none. With
   [sign-in through Philter](security.md#sign-in-through-philter), do this on the
   Users page. Otherwise, with the current key, create the administrator and a key
   for them holding every scope (Philter only lets a key change a key whose scopes
   it also holds):

    ```sh
    docker run --rm --network philter-workspace_philter-workspace curlimages/curl:8.10.1 -s -X POST "http://philter:8080/api/users" \
      -H "Authorization: Bearer $PHILTER_ADMIN_KEY" -H "Content-Type: application/json" \
      --data '{"username":"ops","role":"admin"}'
    docker run --rm --network philter-workspace_philter-workspace curlimages/curl:8.10.1 -s "http://philter:8080/api/api-keys/scopes" \
      -H "Authorization: Bearer $PHILTER_ADMIN_KEY"
    docker run --rm --network philter-workspace_philter-workspace curlimages/curl:8.10.1 -s -X POST "http://philter:8080/api/users/ops/api-keys" \
      -H "Authorization: Bearer $PHILTER_ADMIN_KEY" -H "Content-Type: application/json" \
      --data '{"scopes":[ every scope name from the previous response ]}'
    ```

2. With the new administrator's key (`OPS_KEY`), or their session on the Users
   page, make the user that owns Philter Workspace's key a `user`. For the bootstrap key that
   user is `admin`:

    ```sh
    docker run --rm --network philter-workspace_philter-workspace curlimages/curl:8.10.1 -s -X PUT "http://philter:8080/api/users/admin/role" \
      -H "Authorization: Bearer $OPS_KEY" -H "Content-Type: application/json" --data '{"role":"user"}'
    ```

3. Narrow Philter Workspace's key to `redact` and `policies:read`, finding its `id` in the
   user's key list:

    ```sh
    docker run --rm --network philter-workspace_philter-workspace curlimages/curl:8.10.1 -s "http://philter:8080/api/users/admin/api-keys" \
      -H "Authorization: Bearer $OPS_KEY"
    docker run --rm --network philter-workspace_philter-workspace curlimages/curl:8.10.1 -s -X PUT "http://philter:8080/api/api-keys/<id>/scopes" \
      -H "Authorization: Bearer $OPS_KEY" -H "Content-Type: application/json" \
      --data '{"scopes":["redact","policies:read"]}'
    ```

4. Click **Test** under **Admin → Philter**. The warning clears, and the key,
   policies, contexts, and output are unchanged.

## 2. Create PII redaction policies

A **policy** is a JSON document Philter uses to identify and redact PII. It
declares which PII types to look for and what to do with each
(`REDACT`, `MASK`, `REPLACE`, etc.). For the policy schema and examples, see
the [Philter documentation](https://philterd.github.io/philter/).

Policies live in Philter, and each belongs to one Philter user. The **Policies** page
(administrators, and auditors read-only) manages them through Philter's Policies API as
you, with your session, so Philter's per-user ownership applies and its audit log names
you.

- **Policies** lists your policies, with their description and when each was created and
  updated. Search by name, and sort by name, created, or updated.
- **New policy** starts from Philter's policy template. Enter a name, an optional
  description (up to 200 characters) and notes (up to 1,000), and the policy's JSON. The
  JSON is checked before it is sent, and when Philter refuses a policy, its reason is
  shown. **Build a policy in the Policy Editor** opens the Policy Editor that the compose
  files run beside Philter Workspace.
- **Edit** changes a policy's JSON, description, and notes. The save is made only if the
  policy is unchanged since you opened it; if someone else changed it, the save is
  refused and your edit is kept, so reload it and make your change again. Philter adds a
  revision only when the JSON changes.
- **Duplicate** copies a policy to a new name. **Delete** asks you to confirm. Philter's
  default policy cannot be deleted.
- **History** lists a policy's revisions, with who saved each. **View** shows one,
  **Compare** shows what changed between two (each change's path, and the value before
  and after), and **Roll back** makes an older revision's content a new revision. A roll
  back is refused if the policy changed since you opened its history.
- **Managed** lists Philter's ready-made policies. They cannot be changed or deleted;
  **Create a policy from it** copies one to a policy of yours.
- **Deleted** lists deleted policies, whose history Philter keeps. **Restore** saves one
  again under its name, from its latest revision or from any revision in its history.
- **Owner** (administrators only) shows another user's policies, by username, or
  **Every user's** (on **Deleted**, one user at a time). Every action on the page then acts
  on that user's policies. Philter
  allows this only with `ADMIN_CROSS_USER_ACCESS_ENABLED=true`; otherwise the page says
  so.

### Policies for batches

Batches are redacted with `PHILTER_WORKSPACE_PHILTER_API_KEY`, so a batch can use only
the policies of the Philter user that owns that key (`philter-workspace` in the steps
above) and Philter's default policy. **Used by batches** on the Policies page lists
them, and the batch dialogs offer them. To create or change them, an administrator
enters that username in the **Owner** filter (which needs
`ADMIN_CROSS_USER_ACCESS_ENABLED=true` in Philter), or uses Philter's API with a key
of that user. A policy you create as yourself is not available to batches.

A batch with no policy selected uses Philter's default policy.

### Custom lists

A **custom list** is a named group of terms kept in Philter. A policy refers to one as
`list:name`, in a custom dictionary's terms to redact them, or in its ignored terms to
leave them alone. See Philter's
[Managing Custom Lists](https://philterd.github.io/philter/latest/redaction/custom_lists.html).

The **Custom Lists** tab of the Policies page manages your lists through Philter's API as
you, like policies:

- The list shows each list's name, description, and number of items. Search by name.
- **New list** takes a name, an optional description (up to 200 characters), and the
  items, one per line (up to 100 items of up to 50 characters each). Blank lines are left
  out and each item is trimmed. A name cannot contain `/`, `\`, `;`, or `%`, and a list
  cannot be renamed.
- **Edit** replaces a list's items and description. Auditors see **View** instead.
- **Delete** asks you to confirm. Redaction with a policy that still refers to the list
  fails until the policy no longer does, and a deleted list cannot be restored.
- **Owner** (administrators only) shows another user's lists, by username, or **Every
  user's**, as on the Policies list.

### Always and never redact lists

Terms on your **always redact** list are redacted, and terms on your **never redact** list
are left alone, in every redaction you make, whatever the policy. See Philter's
[Always and Never Redact Lists](https://philterd.github.io/philter/latest/redaction/redact_lists.html).

The **Always and Never Redact** tab of the Policies page shows both lists, one term per
line (up to 1,000 terms of up to 100 characters each). Saving one replaces it in full,
and saving an empty list clears it. The save is made only if the list is unchanged since
you opened the page; if someone else changed it, nothing is saved, the list shows
Philter's current terms, and your edits are shown below it to apply again. **Owner**
(administrators only) shows one other user's lists.

Batches use the lists of the user who owns `PHILTER_WORKSPACE_PHILTER_API_KEY`.
[Detection rules](user-guide/detection-rules.md) add terms to those lists.

### Contexts

A **context** holds the replacements consistent pseudonymization reuses across the
documents redacted in it, and what entity type disambiguation learns. See Philter's
[Redaction Contexts](https://philterd.github.io/philter/latest/redaction/contexts.html).

The **Contexts** tab of the Policies page manages your contexts through Philter's API as
you:

- The list shows each context's number of entries, its settings, and when it was created.
  Search by name, and sort by name or created.
- **View** shows a context's entries counted by filter type.
- **New context** takes a name and the settings: **Entity type disambiguation**, what it
  **learns from** (each document, or the whole context, kept between documents), and the
  **Redaction ledger**. A context cannot be renamed, and a Philter user can have at most 10
  contexts, counting `default`.
- **Edit** changes the settings; only those you change are sent.
- **Clear** deletes a context's entries and what disambiguation learned for it, keeping the
  context and its settings, so later redactions in it no longer reuse earlier
  replacements. **Delete** removes the context with its entries. Both ask you to confirm
  and cannot be undone. Philter refuses to delete a context while redaction jobs that use
  it are pending or running, and once it is deleted, refuses redactions that name it.
- **Owner** (administrators only) shows another user's contexts, by username, or **Every
  user's** (without entry counts or settings; **View** shows them), as on the Policies list.
- Auditors can view contexts but not change them.

A batch's context is one of the contexts of the user that owns
`PHILTER_WORKSPACE_PHILTER_API_KEY`, as with [policies](#policies-for-batches). Philter
refuses to redact in a context that user does not have, so create it first, with the
**Owner** filter (which needs `ADMIN_CROSS_USER_ACCESS_ENABLED=true` in Philter).

### Models

The **Models** tab of the Policies page lists the PhEye models in Philter's models directory
(`PHEYE_MODELS_DIRECTORY`), the same for every Philter user, with each one's size, the most words
of a text it reads (it does not examine later words), the most words one entity it finds can span,
and its encoder. A policy uses a model by giving its name as a ph-eye filter's
`phEyeConfiguration.modelPath`. To see what a policy finds, use [Try a policy](user-guide/try-a-policy.md).

Batches use the custom lists and the always and never redact lists of the user that owns
`PHILTER_WORKSPACE_PHILTER_API_KEY`, as with [policies](#policies-for-batches). An
administrator reaches them by entering that username in the **Owner** filter, which needs
`ADMIN_CROSS_USER_ACCESS_ENABLED=true` in Philter.

Philter Workspace talks to Philter through the
[Philter SDK for Java](https://github.com/philterd/philter-sdk-java). Philter Workspace
reviews plain-text documents. PDF review through Philter is not yet available,
so a PDF upload fails to ingest.

## 3. Create a batch

A **batch** is the unit of organisation in Philter Workspace. Every document belongs
to exactly one batch. A batch carries:

| Field                | Purpose                                                              |
| -------------------- | -------------------------------------------------------------------- |
| Name                 | Human-readable label, unique within the batch's group. Two groups may each have a batch of the same name, so a name another group uses is never refused. An administrator cannot move a batch into a group that already has one of the same name. |
| Group                | Which user group can see and act on this batch (visibility scope).   |
| Policy               | Which Philter policy to apply, or Philter's default policy.          |
| PII Threshold        | Per-span confidence floor for auto-accepting detections.             |
| Document Threshold   | Risk score at or below which a document is marked Low risk. It approves nothing. |
| PII type weights     | Per-type sensitivity used in the risk score.                         |
| Closed flag          | A closed batch refuses new documents.                                |

Only administrators can create, modify, or close a batch. To create one:

1. Sign in as an administrator and open **Batches**.
2. Click **Create Batch** to open the create dialog and fill it out:
    - **Name** and **Group** are required.
    - **Policy** lists the policies in Philter. **Default policy** uses
      Philter's default policy.
    - **PII Threshold** (default `0.80`): spans whose confidence is at or
      above this value are auto-accepted. Lower values give the redactor
      more autonomy; higher values send more spans to the reviewer.
    - **Document Threshold** (default `0.25`): documents whose risk score
      is at or below this value are marked **Low risk** in the queue. It
      does not approve them or change their status.
    - **Audit Sampling Rate** (default `0.10`): the fraction of documents
      with nothing left to decide that still go to a person. At `1.0`, a
      person approves every document.

    The defaults, and a minimum audit sampling rate, can be set for the
    deployment; see [Batch defaults](reference/configuration.md#batch-defaults).
3. Click **Create** in the dialog footer.

After creation, anyone with access to the batch can adjust the group,
thresholds, and weights. The policy can be changed with the **Change…** link
in the **Policy** column. Only admins can close a batch.

For more on batch settings see the
[Batches admin guide](admin/batches.md).

## 4. Upload documents

Documents enter Philter Workspace through one of two paths.

### Through the web UI

1. Click **Upload** in the left-side menu.
2. Choose a **Batch** from the dropdown — only open batches in groups you
   belong to are listed. The "Create a new batch." link below the dropdown
   takes you to the Batches page if you need a new one.
3. Choose a file with **Document**. Plain text (`.txt`) is supported. PDF
   review through Philter is not yet available, so a `.pdf` fails to ingest.
4. Click **Redact Document**.

Philter Workspace sends the file to Philter using the batch's
policy, persists the document, creates a span for each detection, and
computes the document's risk score. You then land on a preview page where
you can download the redacted text or PDF.

### Through the REST API

Programmatic ingestion uses `POST /api/v1/ingest` with a JSON body
containing the batch ID, a filename, and the text. Authentication is by a
Philter API key holding `users:read`. See the
[REST API reference](reference/api.md).

The API path is admin-or-group-member-only; it returns HTTP 409 when the
batch is closed.

### What happens after upload

Ingestion is **asynchronous**. The upload (or `POST /api/v1/ingest`) persists
the document in `PENDING` and returns immediately; a background worker drains
the redaction queue oldest-first. Multiple Philter Workspace replicas can run side by
side: each document is claimed atomically (`PENDING → PROCESSING`) so it is
never processed twice. Admins watch the queue at **Admin → Ingest Queue**.

When the worker picks up a document, for each one:

1. Spans are extracted by Philter, scored by confidence.
2. Each span's initial status is set from the batch's PII Threshold:
    - `confidence ≥ threshold` → `APPROVED` (auto-accepted).
    - `confidence < threshold` → `PENDING` (needs review).
3. The document's risk score is computed using the batch's PII weights and
   the count of unresolved spans.
4. The document's status is set:
    - Any `PENDING` span → `REVIEW_REQUIRED`.
    - Otherwise the worker rolls the batch's **Audit Sampling Rate**:
        - sampled in → `AUDIT_REQUIRED` (sent to review for spot-check).
        - not sampled → `AUTO_APPROVED`.
5. The document is indexed in OpenSearch (full original text + metadata) and
   appears in the queue.

A document whose risk score is at or below the batch's Document Threshold is
marked **Low risk** in the queue, beside its stored status. The Document
Threshold does not approve documents; only step 4 sets `AUTO_APPROVED`. Re-tuning the threshold changes the badges on existing rows
on the next page load.

If Philter fails for any reason the document moves to `FAILED` and the
exception is captured both in the Philter Workspace logs and in a `failureMessage`
field on the document — admins can view the failure detail from the
**View log** link in the Recent failures table on Ingest Queue, and **Retry**
it once the cause is fixed. A retried document that is redacted no longer
carries the failure message.

For more detail see [Adding documents](user-guide/uploading.md).

## 5. Work the queue

The **Queue** at `/queue` is the reviewer's home page. It lists every
document the user has access to, ordered by risk score (highest first), so
the riskiest material lands at the top.

A reviewer typically:

1. Optionally filters by **Batch** or **Status** — `REVIEW_REQUIRED` first if
   you want to focus on documents needing attention.
2. Clicks **Review** on a row to open the side-by-side review UI.
3. Works through pending spans (see the next section).
4. Approves or rejects the document.
5. Returns to the queue and repeats.

Admins see an extra **Limit to my groups** checkbox that toggles between
their group-scoped view and a global view. Pagination is 10 documents per
page. For a column-by-column reference see [Queue](user-guide/queue.md).

## 6. Review a document

The review page (`/review/{id}`) has three panes:

1. **Original** — the source text with each detected span highlighted.
2. **Redacted** — the same text with each accepted span replaced by a
   `<<TYPE>>` marker.
3. **PII Navigator** — one entry per span, sorted by position.

The Original and Redacted panes scroll together. Clicking a span anywhere
focuses the matching navigator entry and vice versa.

### Per-span actions

Each navigator entry exposes:

- A **type pill** that doubles as a dropdown to change the detected type
  (e.g. flip a span from `ssn` to `phone-number`).
- The matched **text** and the redactor's **confidence**.
- A status pill — **Accepted** or **Refused** — that toggles on click. A
  refused span is removed from the Redacted pane and struck through in the
  navigator.
- **Redact All Like This** — finds every other case-sensitive occurrence
  of the exact text in the document and updates them to match. Useful for
  unambiguous strings (project names, email addresses); use with care on
  short or substring-prone text.

### Document-level actions

The header has **Approve**, **Reject**, and (for already-approved
documents) **Unapprove**:

- **Approve** moves the document to `APPROVED` and returns to the queue.
- **Reject** moves the document to `REJECTED` and returns to the queue.
- **Unapprove** flips an approved document back to `REVIEW_REQUIRED`.

Approve / Reject are hidden once the document is in a terminal state
(`APPROVED`, `REJECTED`, `FAILED`). Every action is captured in the audit
log with the actor, timestamp, and the change made.

For more detail see [Reviewing a document](user-guide/reviewing.md).

## A typical end-to-end run

Putting all the pieces together:

1. **Admin** stands up Philter Workspace, its database, and Philter, with
   `PHILTER_WORKSPACE_PHILTER_URL` and `PHILTER_WORKSPACE_PHILTER_API_KEY` set.
2. **Admin** opens **Admin → Philter** and clicks **Test** to confirm Philter Workspace
   reaches Philter with its key.
3. **Admin** creates a policy tuned to their data in Philter, and checks it is
   listed under **Policies**.
4. **Admin** creates a user group under **Admin → Groups** and adds the
   relevant reviewers to it.
5. **Admin** opens **Batches**, creates `Q3-onboarding-forms`, picks the
   group, picks a policy, and accepts the
   default thresholds.
6. **Reviewer** signs in, lands on the **Dashboard**, and sees the new batch
   under "Manage batches" and the documents-needing-review widget.
7. **Reviewer** uses **Upload** to send a document into the batch — it lands
   in the queue with status `REVIEW_REQUIRED` and a non-zero risk score.
8. **Reviewer** opens the queue, clicks **Review**, accepts the spans that
   are correct, refuses or retypes the ones that are wrong, and clicks
   **Approve**.
9. The document moves to `APPROVED`. Risk score, span decisions, and the
   approval are now in the audit log.
10. When `Q3-onboarding-forms` is finished, **Admin** clicks **Close** on
    the batch to lock it against new uploads. Existing documents in the
    batch stay reviewable forever.

## Where to go next

- [Concepts](concepts.md) — the underlying vocabulary.
- [Adding documents](user-guide/uploading.md), [Queue](user-guide/queue.md),
  [Reviewing a document](user-guide/reviewing.md) — page-by-page reference
  for reviewers.
- [Batches](admin/batches.md), [Users and groups](admin/users-and-groups.md),
  [Audit log](admin/audit-log.md) — admin reference.
- [Risk score](reference/risk-score.md), [PII types](reference/pii-types.md),
  [REST API](reference/api.md) — reference material.
