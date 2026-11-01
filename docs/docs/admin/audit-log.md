# Audit log

Philter Workspace's audit log supplements Philter's. It records what only Philter Workspace knows
about: batches, documents, spans, reviews, finalization, data sources and imports, LLM calls, the
inbox, groups, and Philter Workspace's own settings. Sign-ins, users, passwords, MFA, API keys,
webhooks, policies, lists, contexts, legal holds, ledgers, and Philter's settings are recorded in
[Philter's audit log](#philters-audit-log) instead, which is readable from **Audit Log → Philter**.
The [two logs together](#philter-workspaces-and-philters) cover what people do in Philter Workspace.

Philter Workspace's entries are kept in the **`audit_log`** collection. There are two ways to view
and export them:

- **Admin → Audit log** (`/admin/audit`) — filtered slices across the whole
  system, exported as JSON or CSV. Admins only.
- **Audit Log popup** on the Document Queue — the full history for a single
  document (document-level events plus all of its span events), shown inline
  in a modal. **Admin only** — the underlying
  `GET /api/v1/documents/{id}/history` endpoint requires `ROLE_ADMIN`.
  Admins can also download the history as CSV from the popup.

## What gets logged

| Action                              | Resource    | Notes                                              |
| ----------------------------------- | ----------- | -------------------------------------------------- |
| `LOGIN` (failure only)            | User        | A sign-in Philter never recorded, with a `reason`: `UnsupportedPhilter` (Philter is not 4.x), `Unavailable` (Philter could not be reached or answered with an error), `SignInDisabled` (Philter's password sign-in is off), or `NoSessionKey` (Philter accepted the sign-in but issued no session key). Other sign-ins are in Philter's log. See [Sign-in through Philter](../security.md#sign-in-through-philter). |
| `BATCH_CREATE`                      | Batch       | Includes name, group, thresholds, and audit sampling rate |
| `BATCH_AUDIT_SAMPLING_RATE_REFUSED` | Batch       | A batch created or saved with an audit sampling rate below the deployment's minimum, refused; with the batch name, the requested rate, and the minimum. Has no resource id when the batch was being created. |
| `BATCH_GROUP_CHANGE`                | Batch       | Old → new group                                     |
| `BATCH_THRESHOLDS_CHANGE`           | Batch       | PII threshold, Document Threshold, and audit sampling rate deltas |
| `BATCH_WEIGHTS_CHANGE`              | Batch       | Override map                                        |
| `BATCH_WEIGHTS_RESET`               | Batch       | All overrides cleared                               |
| `BATCH_CLOSE`                       | Batch       | Records who closed it and when                      |
| `BATCH_POLICY_CHANGE`               | Batch       | Old → new Philter policy name, and whether the batch chooses each document's policy by case type (`policyByCaseType`) |
| `DOCUMENT_UPLOAD`                   | Document    | Web upload                                          |
| `DOCUMENT_INGEST`                   | Document    | API ingest (`POST /api/v1/ingest`), with the case type when one was given |
| `DOCUMENT_CASE_METADATA_UPDATE`     | Document    | A document's [case metadata](../reference/case-metadata.md) changed: previous and new case type, fee status, and IFP flag, and keyed hashes of a changed case number or filer, never their values |
| `DOCUMENT_IMPORT`                   | Document    | One row per document pulled in by a data-import background job. Outcome is `SUCCESS` for newly-imported documents and `SKIPPED` for placeholders written when the source row already had a Document. Details include the import `jobId` and the source attribution (`sourceSystem`, `sourceUrl`, `sourceIndex`, `sourceDocId`) so the document can be traced back to the OpenSearch / Elasticsearch hit, S3 object, or local file it came from. |
| `DATA_IMPORT_STARTED`               | BackgroundJob | Fired when a data-import job is promoted from PENDING to RUNNING by the dispatcher. Details include the job's `type`, `sourceId`, `batchId`, and friendly names. |
| `DATA_IMPORT_COMPLETED`             | BackgroundJob | Terminal SUCCESS row for a data-import job. Details include `processed`, `failed`, `skipped` counters. |
| `DATA_IMPORT_FAILED`                | BackgroundJob | Terminal FAILURE row for a data-import job (validation failure at queue time, or runtime error during the run). Carries the same counters plus an `error` string. |
| `DOCUMENT_STATUS_CHANGE`            | Document    | Generic status transition; also fires on Approve / Unapprove / Unreject |
| `DOCUMENT_APPROVAL`                 | Document    | Reviewer approved a document; payload includes `approvedBy`, `acquired`, and `required` approval counts, and `approverRequired` when a rule set reserves the second approval for an approver |
| `DOCUMENT_APPROVALS_CLEARED`        | Document    | A change to the document's spans cleared the approvals recorded so far ([changes after an approval](rules.md#changes-after-an-approval)): the change (`SPAN_UPDATE`, `SPAN_ADJUST`, `SPAN_CREATE`, `SPAN_DELETE`, `SPAN_RESET`, or `SPAN_REDACT_LIKE`), the span, the approvers cleared, and the actor |
| `DOCUMENT_REJECT`                   | Document    | Reviewer rejected a document; payload includes `previous` status and `rejectedBy` |
| `DOCUMENT_UNAPPROVE`                | Document    | Approval rescinded and document returned to review  |
| `DOCUMENT_UNREJECT`                 | Document    | Rejection rescinded and document returned to review |
| `DOCUMENT_FINALIZE`                 | Document    | Document finalized; includes `certificateId`, `documentHash`, the actor, and `via`: `review` from the review page or `api` from `POST /api/v1/documents/{id}/finalize` |
| `DOCUMENT_DOWNLOAD`                 | Document    | Reviewer downloaded a finalized redacted document   |
| `DOCUMENT_DELIVER`                  | Document    | One attempt to write a finalized document's redacted text to its batch's [destination](destinations.md#delivering-finalized-documents), as the person who finalized it (or retried the delivery). Outcome `SUCCESS` with the destination's type, id, and name, the attempt number, the file name as a keyed hash, and the SHA-256 and size of what was written; or `FAILURE` with the destination, the attempt number, and `finalAttempt` when no more attempts follow |
| `DOCUMENT_DELIVERY_RETRY`           | Document    | An administrator or the batch's team lead queued a failed delivery again, with the destination |
| `DOCUMENT_AUDIT_EXPORT`             | Document    | Admin downloaded the per-document audit log as CSV  |
| `AUDIT_LOG_EXPORT`                  | AuditLog    | An [export](#export) of the audit log, written whether or not it returned any rows: the format, the time range, the resource type, the user and resource ID filters as keyed hashes, the number of entries exported, and whether the export reached its cap (`truncated`). The preview is not recorded. |
| `AUDIT_TRAIL_EXPORT`                | Document / AuditLog | An [audit trail with Philter's ledger](#audit-trail-with-philters-ledger) exported: for one document, with the format; for a time range, with the range, the number of events and chains, how many chains verified, and whether it was truncated |
| `PHILTER_LEDGER_READ`               | Document    | The Review page read a document's chains from Philter's ledger, with the number of chains and entries |
| `DOCUMENT_PII_SENT_TO_LLM`          | Document    | Fired **before** each outbound Ollama LLM call (Explain or Second Opinion). Records the Ollama instance, model, and span count. Written before the HTTP request so the entry exists even if Ollama is unreachable or returns an error. See [LLM-as-a-Judge](#llm-as-a-judge-events). |
| `DOCUMENT_LOCK_BROKEN`              | Document    | An administrator, or an approver of the document's group (`asApprover`), released a document another user held |
| `FINALIZATION_POLICY_APPLIED`       | Document    | A finalization policy ran: at finalization, or later for a delete-after-48-hours or delete-after-X-days policy (by the retention job, with an empty `actor`). Payload includes `policyId`, `policyName`, `option`, and `action` (`RETAIN` or `SOURCE_CLEARED`, with `spansDeleted` and `indexCleared`) |
| `SPAN_UPDATE`                       | Span        | Status and/or type change                           |
| `SPAN_ADJUDICATE`                   | Span        | An approver's final decision on a span, or their change to a span an approver adjudicated; includes `previousAdjudicatedBy` when it replaces another adjudication |
| `DETECTION_RULE_CREATE`, `DETECTION_RULE_PROPOSE`, `DETECTION_RULE_CONFIRM`, `DETECTION_RULE_DISMISS`, `DETECTION_RULE_REMOVE` | DetectionRule | A [detection rule](../user-guide/detection-rules.md) made by a reviewer, proposed after repeated refusals, confirmed (and whether its value was added to Philter's list), dismissed, or removed. The rule's kind, status, PII type, document, and batch, and a keyed hash and the length of its value, never the value. |
| `SPAN_REDACT_LIKE`                  | Span        | Counts of new-and-approved spans                    |
| `USER_UPDATE`                       | User        | A person's Philter Workspace role (User or Auditor) or groups changed under Admin → Users |
| `USER_DELETE`                       | User        | A Philter Workspace user record deleted under Admin → Users → Records |
| `GROUP_CREATE` / `_UPDATE` / `_DELETE` | Group    | Admin group management, with member, team lead, and approver counts |
| `NOTIFICATION_SETTINGS_CHANGE`      | Settings    | SMTP settings save (excluding the password value)   |
| `PHILTER_TEST`                      | Philter     | Admin → Philter's Test: Philter's URL, whether it passed, and the HTTP status and reason. Never the API key. |
| `PHILTER_WEBHOOK_REGISTER`, `PHILTER_WEBHOOK_TEST` | Philter | Admin → Philter's Register with Philter (the webhook URL, whether it succeeded, and Philter's status) and Send a test event (whether it was delivered, and the status the webhook answered). Never the secret. Philter records both too, naming only the API key. |
| `REDACTED_PDF_REFUSED`              | Redaction   | A redacted PDF was refused rather than produced, with the reason. No document content. |
| `INBOX_MESSAGE_READ`                | InboxMessage | User marked an inbox notification as read           |
| `INBOX_MESSAGE_ARCHIVED`            | InboxMessage | User archived an inbox notification                 |
| `OPENSEARCH_DATASOURCE_CREATE` / `_UPDATE` / `_DELETE` | OpenSearchDataSource | Data source registered, edited (with `passwordChanged` boolean), or removed (see [Data sources](data-sources.md)) |
| `ELASTICSEARCH_DATASOURCE_CREATE` / `_UPDATE` / `_DELETE` | ElasticsearchDataSource | Same shape as OpenSearch — separate collection, separate audit lineage |
| `S3_DATASOURCE_CREATE` / `_DELETE`  | S3DataSource | S3 data source registered or removed                |
| `RDB_DATASOURCE_CREATE` / `_UPDATE` / `_DELETE` | RelationalDbDataSource | Relational database data source registered or removed |
| `RDB_DANGEROUS_SQL_BLOCKED`         | RelationalDbDataSource | An RDB save was rejected because the SQL contains a disallowed keyword (`DELETE`, `TRUNCATE`, `DROP`). Payload includes the matched keywords and offending SQL; entityId is `null` because nothing was saved. |
| `LOCAL_DATASOURCE_CREATE` / `_DELETE` | LocalDirectoryDataSource | Local directory data source registered or removed |
| `RDB_DANGEROUS_JDBC_URL_BLOCKED`    | RelationalDbDataSource | An RDB save was refused because its JDBC URL is not allowed; the URL is recorded without credentials |
| `RDB_WATERMARK_ADVANCE` / `_RESET` / `_SET_MANUAL` | RelationalDbDataSource | The incremental-import watermark moved after an import, was reset, or was set by an admin |
| `LOCAL_DESTINATION_CREATE` / `_UPDATE` / `_DELETE` | LocalDirectoryDestination | Local directory destination added, edited, or removed |
| `BATCH_DOMAIN_CHANGE`               | Batch       | Old → new domain                                    |
| `BATCH_DESTINATION_CHANGE`          | Batch       | The batch's delivery [destination](destinations.md#delivering-finalized-documents) chosen or cleared: previous and new destination type and id |
| `BATCH_RULES_ADD` / `_UPDATE` / `_REMOVE` | Batch | An approval rule set attached to, changed on, or removed from a batch, including whether it reserves the second approval for an approver (`requireApprover`) |
| `BATCH_EXPORT`                      | Batch       | A batch export written to a destination, or failing to be; with the format, destination, document count, whether documents with redactions no person decided were left out, and how many |
| `DOCUMENT_QUEUED`                   | Document    | A web upload queued for redaction; the filename is recorded as a hash, with the case type when one was given |
| `DOCUMENT_COMMENT_ADD`              | Document    | A comment added to a document; its length, not its text |
| `DOCUMENT_SEARCH`                   | Document    | A full-text search; the query is recorded as a hash, with its length and the result count |
| `SPAN_CREATE` / `SPAN_DELETE` / `SPAN_RESET` | Span | A reviewer added, deleted, or reset a span      |
| `SPAN_ADJUST`                       | Span        | A span's boundaries moved by a reviewer, or back to where it was detected by **Reset**: the start and end before and after, where it was detected, and its new length, never its text. Moving a span also accepts it, recorded as a `SPAN_UPDATE` (or `SPAN_ADJUDICATE`) |
| `FINALIZATION_POLICY_CREATE` / `_UPDATE` / `_DELETE` | FinalizationPolicy | Batch finalization policy management |
| `COMPLIANCE_PROFILE_CREATE` / `_EDIT` / `_ARCHIVE` / `_DELETE` | ComplianceProfile | Compliance profile management |
| `WEIGHT_SET_CREATE` / `_UPDATE` / `_DELETE` | WeightSet | Weight set management                         |
| `INGEST_QUEUE_REMOVE`, `INGEST_QUEUE_FAILURE_CLEAR`, `INGEST_QUEUE_FAILURES_CLEAR_ALL` | Document / Settings | An admin removed a queued document or cleared ingest failures; filenames are recorded as hashes |
| `INGEST_QUEUE_RETRY`, `INGEST_QUEUE_RETRY_ALL` | Document / Settings | An admin put a failed document, or every failed document that can be redacted again, back in the queue; with the filename as a hash, or the number queued and the number that could not be |
| `OLLAMA_INSTANCE_CREATE` / `_DELETE` / `_TEST` | OllamaInstance | An Ollama instance added, removed, or tested    |
| `LLM_JUDGE_DEFAULTS_CHANGE`         | Settings    | The default Ollama instances for LLM-as-a-Judge changed |
| `CASE_TYPE_POLICY_CHANGE`           | Settings    | A case type mapped to a policy, its policy replaced, or its mapping removed (`policyName` empty), with the previous policy |
| `SEARCH_INDEX_REBUILD_START`, `SEARCH_INDEX_REBUILD_FINISH` | Settings | An administrator rebuilt the [full-text index](full-text-search.md#rebuilding-the-index): the backend and document count when it started; the documents indexed and skipped, with the outcome, when it finished |
| `GENERAL_SETTINGS_CHANGE`           | Settings    | An Admin → General setting saved: the Philter Workspace URL, upload size limit, data-import concurrency, time zone, full-text search, or detection rule settings, with the previous value |
| `SECURITY_SETTINGS_CHANGE`          | Settings    | The data-source host allow-list turned on or off     |
| `NOTIFICATION_SETTINGS_TEST`        | Settings    | A test email sent from the notification settings     |
| `USER_SETTINGS_CHANGE`              | User        | A person's review preferences saved under Settings   |
| `POLICY_TRY`                        | Policy      | A policy tried under Try a Policy: the policy, `kind` (`text` or `pdf`), and the size in characters or bytes. Never what was sent or returned. Philter does not audit this kind of redaction. |
| `ADMIN_TOOLS_CLEANUP_DATA_IMPORTS`  | Tools       | Finished data-import jobs and their logs deleted under Admin → Tools |

Each entry stores:

- `timestamp` (UTC `Instant`)
- `userEmail` and `userId` (when the actor is signed in)
- `action`, `resourceType`, `resourceId`
- `outcome` (`SUCCESS` / `FAILURE`)
- `ipAddress` (honors `X-Forwarded-For` first hop)
- `details` (per-action contextual map; never includes secrets)

## Philter Workspace's and Philter's

Each action Philter Workspace audits is in exactly one of the two logs. The table above lists
Philter Workspace's. Earlier versions also wrote the actions below, which Philter's audit log
records, naming the same person, so Philter Workspace no longer writes them. Entries already written
are kept, and are queried and exported like any other.

| No longer written                    | Recorded by Philter as                                            |
| ------------------------------------ | ----------------------------------------------------------------- |
| `LOGIN` (success)                    | `sign_in_succeeded`                                               |
| `LOGIN` (failure: `BadCredentials`, `Locked`, `RateLimited`, `MfaLocked`) | `sign_in_failed`, `sign_in_locked`, `sign_in_rate_limited`, `user_mfa_locked` |
| `LOGOUT`                             | `api_key_deleted` (the session key revoked) or `api_key_expired`  |
| `PASSWORD_CHANGE`                    | `user_password_changed`                                           |
| `USER_CREATE`                        | `user_created`                                                    |
| `USER_UPDATE` (Philter role or email) | `user_role_changed`, `user_email_changed`                        |
| `USER_PASSWORD_RESET`                | `user_password_reset`, or `user_password_set` for a user who had no password |
| `USER_DEACTIVATE` / `_REACTIVATE`    | `user_deactivated`, `user_reactivated`                            |
| `USER_UNLOCK`                        | `sign_in_unlocked`                                                |
| `USER_MFA_UNLOCK` / `_REMOVE`        | `user_mfa_unlocked`, `user_mfa_removed`                           |
| `USER_SESSIONS_REVOKE`               | `api_key_deleted`                                                 |
| `MFA_ENROLL` / `MFA_REMOVE`          | `user_mfa_enrolled`, `user_mfa_removed`                           |
| `API_KEY_SCOPES_CHANGE` / `API_KEY_REVOKE` | `api_key_scopes_changed`, `api_key_deleted`                 |
| `WEBHOOK_SET` / `WEBHOOK_REMOVE`     | `webhook_configured`, `webhook_removed`                           |
| `LEGAL_HOLD_SET` / `LEGAL_HOLD_RELEASE` | `legal_hold_set`, `legal_hold_released` (and `admin_cross_user_access` naming an administrator acting for another user) |
| `LEDGER_EXPORT` / `LEDGER_DELETE`    | `redaction_ledger_exported`, `redaction_ledger_deleted`           |
| `PHILTER_SETTINGS_UPDATE`            | `settings_updated`                                                |
| `PHILTER_AUDIT_EXPORT`               | `audit_log_exported`                                              |
| `API_KEY_GENERATE` / `API_KEY_REVOKE` (Philter Workspace's own keys) | Philter Workspace no longer has API keys of its own; Philter's keys are audited as `api_key_created` and `api_key_deleted` |

## Export

The form has:

- **Start time** / **End time** — required, interpreted in the **server's**
  local timezone. The page pre-fills the past 24 hours.
- **User email** — optional exact match.
- **Resource type** — optional, one of User / Group / Batch / Document /
  Span (or "All").
- **Resource ID** — optional exact match.
- **Preview** — runs the same query as the export and shows the first 10
  matching entries inline so you can sanity-check filters before committing
  to a download. If the preview is empty, the download will be empty too —
  widen the time range or relax filters.
- **Download JSON** / **Download CSV** — same filters, two formats.

Both formats include every column above. CSV embeds the per-action `details`
map as a JSON-encoded string in a single quoted column so spreadsheet readers
preserve it.

The export is capped at 100,000 rows per request. Narrow the time range or
filters if you hit the cap.

Each download is recorded as an `AUDIT_LOG_EXPORT` entry, after the query, so
it is not in the file it describes. The user and resource ID filters are
recorded as keyed hashes, as search queries are: an investigator holding the
server's key can hash a value and match it. The preview is not recorded.

## Per-document audit log

Every row in the **Document Queue** (`/queue`) has an **Audit Log** button
that opens a modal showing the full history for that one document. This
includes both document-level events (ingest, status changes, finalization)
and all events on spans that belong to the document (status changes, type
changes, manual creation, deletion, second-opinion requests). Entries are
sorted **newest first** and paginated 10 per page.

**Admin only.** The underlying `GET /api/v1/documents/{id}/history` endpoint
requires `ROLE_ADMIN` because the history includes raw PII span text. Non-admin
reviewers receive `403` if they call the endpoint directly; the popup button
is hidden for non-admins in the UI.

### Download CSV

The popup has a **Download** button that exports the document's full audit
history (not just the page currently shown) as a CSV file, sorted newest to
oldest. The file is named `audit-log-<documentId>.csv`.

**Only administrators can download the audit log.** For non-admin users the
Download button is rendered disabled with a tooltip explaining the
restriction; the underlying API endpoint
(`GET /api/v1/documents/{id}/history.csv`) also rejects non-admin requests
with HTTP 403.

The export itself is audited. Before generating the CSV, Philter Workspace writes a
`DOCUMENT_AUDIT_EXPORT` entry capturing the admin who initiated the
download and the timestamp. Because the entry is recorded *before* the
audit log is queried, and the CSV is sorted newest-first, the export event
appears as the top row of every downloaded file — so each export is
self-attesting.

The CSV is intended as a chain-of-custody artifact and is **redacted on
export**: the PII text of each span is omitted. Instead, span entries
include the span's **location** so you can correlate the entry back to the
original document without leaking the PII value itself.

Columns:

| Column                | Notes                                                        |
| --------------------- | ------------------------------------------------------------ |
| `timestamp`           | ISO-8601 UTC instant                                         |
| `actor`               | Email address of the user who performed the action (blank if system). The CSV always uses the email address; the JSON history endpoints (`GET …/history` and `GET …/spans/{id}/history`) use the MongoDB user ID by default — pass `?resolveActors=true` (admin only) to receive email addresses there instead. |
| `action`              | The action code (e.g. `SPAN_UPDATE`, `DOCUMENT_INGEST`)      |
| `resourceType`        | `Document` or `Span`                                         |
| `resourceId`          | The document or span ID                                      |
| `spanType`            | PII type — populated only for span entries                   |
| `spanCharacterStart`  | Inclusive start offset in the document text (span entries)   |
| `spanCharacterEnd`    | Exclusive end offset in the document text (span entries)     |
| `spanPage`            | 1-based page number for PDF documents (span entries)         |
| `details`             | The per-action `details` map, JSON-encoded in a quoted field |

`spanText` is **never** present in the CSV. If you need to correlate an
entry back to the actual PII value, do so against your secured copy of the
original document using the character offsets and page number.

## Audit trail with Philter's ledger

The audit trail joins Philter Workspace's audit events with the entries of
Philter's redaction ledger for the same documents, oldest first, as CSV or
JSON. It is for administrators and auditors.

- **One document**: **Audit trail: CSV / JSON** on the Document Queue's audit
  log popup (`GET /api/v1/documents/{id}/audit-trail?format=csv|json`). It
  holds every event about the document or its spans, and the entries of the
  chain of each time Philter redacted it.
- **A time range**: the **Audit trail with Philter's ledger** buttons under
  **Admin → Audit Log → Export** (`GET /admin/audit/trail?startTime=&endTime=&format=`).
  It holds every Philter Workspace event in the range, and the ledger entries
  in the range of the documents those events concern. The actor and resource
  filters do not apply. It reads the ledger of at most 500 documents, and at
  most 10,000 events; when either limit is reached, the JSON's
  `documentsTruncated` or `eventsTruncated` is `true` and the CSV ends with a
  `TRAIL_TRUNCATED` row.

Philter Workspace reads the ledger with `PHILTER_WORKSPACE_PHILTER_API_KEY`,
whose Philter user owns the chains of the documents it redacted, so no
cross-user access is needed. The key needs the `ledger:read` scope. Philter's
own audit log therefore names that key's user, and Philter Workspace records
who asked: `PHILTER_LEDGER_READ` when the Review page shows a document's
ledger, and `AUDIT_TRAIL_EXPORT` for each trail exported. A one-document export
is recorded before the trail is read, so it appears in its own trail.

**Verification.** For each chain, the trail states whether Philter verified
it: the hash chain and every entry's signature (`verified`, with
`hashChainValid`, `signaturesValid`, and the signed and unsigned entry counts).
The CSV gives each chain a `PHILTER_LEDGER_CHAIN` row first, with the outcome
`VERIFIED` or `NOT_VERIFIED`, and each ledger row carries `chainVerified`. A
chain that could not be read says why instead: `NO_CHAIN` (the batch's context
does not keep the ledger, or the chain was deleted), `KEY_LACKS_SCOPE` (the
key has no `ledger:read`), `KEY_REJECTED`, or `UNREACHABLE`. At most 5,000
entries are read from one chain; `entriesTruncated` says when there were more.

**What a ledger entry carries.** Its time, PII type, position, hashes
(`hash`, `previousHash`, `documentHash`, and `effectiveHash` when present),
policy name, version, and content hash, whether it is signed and by which key,
and a read error when Philter could not read it. It never carries the original
value (Philter returns that only in a ledger export, which needs
`ledger:export`), the replacement, which can be reversible (an encryption) or
guessable (a hash of an ID number), or the filename. Philter Workspace's events
are as the audit log stores them, so the trail holds no personal information
the audit log does not already hold.

CSV columns: `timestamp`, `source` (`PHILTER_WORKSPACE`, `PHILTER_LEDGER`, or
`PHILTER_LEDGER_CHAIN`), `documentId`, `actor`, `action` (`REDACTION` for a
ledger entry, or `CHAIN_START` for the entry Philter opens each chain with),
`outcome`, `resourceType`, `resourceId`, `details` (JSON),
`philterDocumentId`, `piiType`, `startPosition`, `hash`, `previousHash`,
`documentHash`, `policyName`, `policyVersion`, `signed`, `signingKeyId`,
`readError`, `chainVerified`.

## LLM-as-a-Judge events

Every call that sends document content to an Ollama instance produces a
`DOCUMENT_PII_SENT_TO_LLM` entry. The event is written **before** the HTTP
request leaves Philter Workspace so the record exists even when Ollama is unreachable
or returns an error.

| Field in `details`  | Content                                                         |
| ------------------- | --------------------------------------------------------------- |
| `instanceId`        | MongoDB ID of the Ollama instance                               |
| `instanceName`      | Display name of the Ollama instance                             |
| `model`             | Model name sent to Ollama (e.g. `llama3`)                       |
| `spanCount`         | Number of PII spans included in the prompt (Explain calls only) |
| `spanId`            | ID of the span being evaluated (Second Opinion calls only)      |
| `spanType`          | PII type of that span (Second Opinion calls only)               |

The resource type is always `Document` and the resource ID is the parent
document's ID — even for Second Opinion calls, which originate from a span —
because the full document text is always included in the prompt.

!!! warning "Ollama logging"
    Philter Workspace sends the full unredacted document text and all PII span values
    to Ollama in the request body. **Ollama must be deployed with request-body
    logging disabled.** If `OLLAMA_DEBUG=1` is set or your Ollama deployment
    forwards request bodies to an external logging sink, PII will appear in
    logs outside Philter Workspace's security boundary. See
    [Admin → LLM-as-a-Judge](../admin/security.md) for configuration
    requirements.

## Storage and retention

Audit entries are stored in MongoDB and are indexed by timestamp, the actor's
email, action, resource type, and resource ID so the Audit Log search remains
fast as the collection grows. Philter Workspace does not automatically expire entries —
set up a database-level TTL on the timestamp field or a periodic deletion job
if you need bounded retention.

## Failure modes

If writing an audit entry fails (e.g., MongoDB is unavailable for a moment),
the action it describes still succeeds — the audit service swallows the
exception and logs a warning. This trades audit completeness for application
availability; review logs from the Philter Workspace process for any
"Failed to write audit log entry" warnings.

## Philter's audit log

The **Philter** tab of the Audit Log page (`/admin/audit/philter`) shows Philter's own audit log:
every request, sign-in, and change Philter recorded, for every Philter user, newest first. The
**Philter Workspace** tab is the log above. Philter needs you to be a Philter administrator, so
Philter Workspace auditors, who are Philter users, cannot see it. Philter records each reading and
export in its log, as you.

- **Filter** by event type (one Philter records, such as `sign_in_failed`, `settings_updated`, or
  `legal_hold_set`), by whole days in UTC, and by the user who acted. Filtering by a user other
  than you needs `ADMIN_CROSS_USER_ACCESS_ENABLED=true` in Philter.
- **Export to CSV** downloads whole days in UTC, the last at most 30 days after the first. Philter
  Workspace reads Philter's export a page at a time, so each event appears once, and a download
  holds at most 100,000 events; choose a shorter range for more. Times in the file are UTC.
