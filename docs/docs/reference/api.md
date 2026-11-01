# REST API

Philter Workspace exposes a JSON-over-HTTP API under `/api/v1`. The API surface is split
into two categories that authenticate differently. A few paths sit outside
both: the unauthenticated [health and metrics endpoints](#health-and-metrics).

Every `POST`, `PUT`, `PATCH`, and `DELETE` under `/api/v1` requires
`Content-Type: application/json`, including requests with an empty body, and
returns `415 Unsupported Media Type` without it.

## Authentication

### Philter API keys

Programmatic callers authenticate with a **Philter API key** that holds Philter's `users:read`
scope, sent on every request:

```http
Authorization: Bearer <philter-api-key>
```

Philter Workspace checks the key with Philter and runs the request as the Philter user that owns it, with
that person's Philter Workspace role and groups. Philter Workspace has no API keys of its own. See
[API keys](../security.md#api-keys) for how the key is checked and cached.

| Status | Meaning |
|---|---|
| `401 Unauthorized` | No key, or Philter does not accept the key, or its user is deactivated. |
| `403 Forbidden` | The key does not hold `users:read`, or the user's role does not allow the call. |
| `503 Service Unavailable` | Philter could not check the key: it was unreachable, answered with a server error, or rate limited the check. |

### Programmatic (Bearer-only)

These endpoints are intended for scripts, integrations, and downstream consumers. They accept
**only** a Philter API key: session cookies are explicitly stripped by the security filter chain so
a logged-in admin's browser session cannot be CSRF'd into reaching them. The Bearer-only set is
small and stable:

| Method | Path |
|---|---|
| `POST` | `/api/v1/ingest` |
| `POST` | `/api/v1/search` |
| `POST` | `/api/v1/documents/{id}/finalize` |
| `GET`  | `/api/v1/documents/{id}/audit` |

### Session-allowed (browser-UI shared)

The remaining `/api/v1/**` endpoints are used by Philter Workspace's own web UI and accept either a Philter
API key or the browser's session cookie. Cross-origin abuse is blocked by the browser's same-origin
policy plus the absence of a permissive CORS configuration. Endpoints in this category are called
out below per-section.

## Health and metrics

Philter Workspace serves three unauthenticated paths for monitoring: `/api/health`,
`/actuator/health`, and `/actuator/prometheus`. All three accept `GET` only.
They are reachable before any credential exists so a container runtime, load
balancer, uptime monitor, or Prometheus scraper can call them.

### `GET /api/health`

The health endpoint every Philterd product exposes. It sits outside `/api/v1`
because it is not a versioned business endpoint.

```json
{
  "status": "UP",
  "applicationVersion": "1.0.0-SNAPSHOT"
}
```

`status` is Philter Workspace's aggregate health, so a broken dependency is visible here
rather than being masked by the fact that the process still answers. `UP`
returns `200`; any other status returns `503` with that status in the body, so
a monitor that reads only the status code still sees the fault.

These contribute to the aggregate:

| Component | Reports `DOWN` when |
|---|---|
| MongoDB | the database does not answer a ping |
| Full text search (`fullTextSearch`) | full text search is enabled and its backend cannot be used: the embedded index cannot be opened, or the OpenSearch cluster does not answer |
| Disk space | free space on the data partition is nearly exhausted |
| Valkey | the session store does not answer |

One caveat on timing: when MongoDB itself is unreachable, the health endpoints
answer `503` only after the MongoDB driver's server-selection timeout (30
seconds by default), because that is how long the driver waits before it
declares the server unusable. Monitors with a shorter timeout see the request
time out, which they should also treat as unhealthy. Add
`?serverSelectionTimeoutMS=5000` to `spring.mongodb.uri` if you want a
faster answer, keeping in mind it applies to every Philter Workspace query, not just
health.

A deployment that runs with full text search turned off is not marked down for
its search backend. One that leaves it on with a backend it cannot use is:
search and indexing are degraded, so fix the backend, or turn the feature off
under **Admin → General**. See
[Full text search](../admin/full-text-search.md).

`applicationVersion` is the build version recorded in the jar at package time.
It reads `unknown` when Philter Workspace runs from classes built outside Maven (an IDE,
for example), where no build metadata file exists.

The response names no component behind the status and carries no deployment or
document state, but it does disclose the running build version to anyone who
can reach the port. Keep Philter Workspace's port on an internal network, or block the
path at your reverse proxy if you do not want that version visible.

### `GET /actuator/health`

Spring Boot Actuator's own view of the same aggregate, for tooling that already
speaks Actuator:

```json
{
  "status": "UP"
}
```

`200` when the status is `UP`, `503` when it is `DOWN`. It carries no
`applicationVersion`, so monitoring that wants the running version should call
`/api/health` instead. Per-component detail is switched off, so an
unauthenticated caller never learns which dependency is down or what address it
sits at; read the application log for that.

### `GET /actuator/prometheus`

Metrics in Prometheus text exposition format, for a scrape config:

```
# HELP jvm_memory_used_bytes The amount of used memory
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 1.34217728E8
http_server_requests_seconds_count{method="GET",status="200",uri="/api/health"} 42.0
```

The metrics are JVM gauges and HTTP request counts and timings. Request URIs
appear as route templates (`/documents/{id}`), never as the concrete paths a
reviewer visited, and no document text, entity value, or user identity is
exported.

Scraping it does disclose how Philter Workspace is being used: request volumes, error
rates, and which routes are exercised. Treat the endpoint as internal, and
block `/actuator` at your reverse proxy if your scraper does not need it.

Health and `prometheus` are the only Actuator endpoints Philter Workspace exposes.
Everything else Actuator can serve, including `env`, `configprops`, `beans`,
`loggers`, and `heapdump`, returns `404`, as do the `/actuator` links page and
the per-component paths under `/actuator/health/`.

## Document ingestion

### `POST /api/v1/ingest`

Bearer-only. Submit a plain-text document. Ingestion is **asynchronous**: the document is
persisted in `PENDING` and placed on the redaction queue. A background worker
runs Philter in arrival order; once redaction completes, the document moves to
`REVIEW_REQUIRED` (PII detected with low-confidence spans), `AUDIT_REQUIRED`
(eligible for auto-approval but sampled for review per the batch's audit
sampling rate), or `AUTO_APPROVED`.

Request body:

```json
{
  "batchId":  "string",
  "name":     "string",
  "text":     "string",
  "priority": 2,
  "caseMetadata": {
    "caseNumber": "1:26-cv-01234",
    "caseType":   "civil",
    "filer":      "Acme Dental LLC",
    "feeStatus":  "paid",
    "ifp":        false
  }
}
```

`caseMetadata` is optional; see [Case metadata](case-metadata.md) for its
fields. Invalid metadata is refused with `400` and a body of
`{"error": "...", "problems": [...]}`, before anything is stored.

`priority` is optional. It accepts an integer in `1..3` (`1` Low, `2` Normal,
`3` High); omitting it or sending `null` defaults to Normal. The value is
stored on the document and surfaced as a chevron icon on the
[Document Queue](../user-guide/queue.md#priority-column). It does not affect
ingest ordering — redaction still runs oldest-first.

| Status | Meaning                                                                |
| ------ | ---------------------------------------------------------------------- |
| `202`  | Accepted; body `{"taskId": "..."}`. Redaction runs asynchronously.     |
| `400`  | `batchId` does not exist (or required fields missing/invalid, including `priority` outside `1..3`). |
| `403`  | Caller does not have access to that batch.                             |
| `409`  | Batch is closed; body includes `"closed": true`.                       |

The returned `taskId` is the document's id. Poll
[`GET /api/v1/documents/{id}/spans`](#get-apiv1documentsidspans) or
[`GET /api/v1/queue`](#get-apiv1queue) to track its progress out of `PENDING`.

A SHA-512 hash of the submitted `text` (UTF-8 bytes) is recorded on the
document at ingest time — see
[Security · Document content integrity](../security.md#document-content-integrity).

### `GET /api/v1/documents/{id}/case-metadata`

Session-allowed. A document's [case metadata](case-metadata.md), and the policy
Philter last reported redacting it with:

```json
{
  "caseMetadata": {"caseNumber": "1:26-cv-01234", "caseType": "civil", "filer": "Acme Dental LLC",
                   "feeStatus": "paid", "ifp": false},
  "appliedPolicyName": "civil-filings",
  "appliedPolicyVersion": 3
}
```

`caseMetadata` is `null` when the document has none. `404` if the document is
missing or the caller cannot see it.

### `PUT /api/v1/documents/{id}/case-metadata`

Session-allowed. Replaces the document's case metadata with the body, an object
with the [case metadata fields](case-metadata.md#schema); `{}` clears it.
Requires `Content-Type: application/json`. The change is audited as
`DOCUMENT_CASE_METADATA_UPDATE` and does not redact the document again.

Response: `{"changed": true, "caseMetadata": {...}}`. `400` with `error` and
`problems` for invalid values, `409` if the document is finalized, is still
waiting to be redacted (`PENDING` or `PROCESSING`), or changed while the request
was saved, and `404` if the document is missing or the caller cannot see it.

## Triage

### `GET /api/v1/queue`

Session-allowed. List documents the caller can see, paged by sort field.

| Query param      | Default       | Meaning                                                |
| ---------------- | ------------- | ------------------------------------------------------ |
| `page`           | `0`           | Zero-indexed page (negative values are clamped to `0`)  |
| `size`           | `10`          | Page size, clamped to the range `[1, 100]`              |
| `batchId`        | —             | Filter to one batch                                    |
| `status`         | —             | Filter to one status                                   |
| `filename`       | —             | Substring match on filename, case-insensitive          |
| `myGroupsOnly`   | `false`       | Admin opt-in: restrict admins to their own groups      |
| `sort`           | `riskScore`   | One of `riskScore`, `status`, `batchId`, `filename`, `priority` |
| `dir`            | `desc`        | `asc` or `desc`                                        |

`size` is hard-capped at 100 — values above that are silently lowered, and
values below `1` are raised to `1`. Page through larger result sets with
successive `page` values rather than a larger `size`.

Non-admins are always restricted to their groups; the `myGroupsOnly` parameter
only affects admin callers.

Response is a Spring `Page<Map>` shape:

```json
{
  "content": [
    {
      "id": "string",
      "filename": "string",
      "status": "PENDING|REVIEW_REQUIRED|AUDIT_REQUIRED|AUTO_APPROVED|APPROVED|REJECTED|FAILED",
      "riskScore": 0.0,
      "batchId": "string",
      "batchName": "string",
      "lowRisk": false,
      "documentThreshold": 0.25,
      "priority": 2
    }
  ],
  "totalElements": 0,
  "totalPages": 0,
  "number": 0,
  "size": 10
}
```

`lowRisk` is true when the document's risk score is at or below its batch's
`documentThreshold`. It is always false for documents with no risk score: those
not yet redacted (`PENDING`, `PROCESSING`) and those never redacted (`FAILED`,
`SKIPPED`). It is a risk indicator only and approves nothing; `status`
is the document's stored status. It replaced the `autoApproved` flag, which
was true for documents that were not approved.

### `GET /api/v1/batches`

Session-allowed. List batches the caller can target. Honors the same
`myGroupsOnly` query param. Returns a JSON array of `{id, name, label}`. Batch
names are unique only within a group, so `label` is the name followed by the
batch's group in parentheses when another batch in the response has the same
name, and the name alone otherwise.

## Documents

### `GET /api/v1/documents/{id}/spans`

Session-allowed. Return every `Span` row in the document. Useful for building
a custom review client or for reconciling the redactor's output with
downstream systems.

`404` if the document doesn't exist or the caller lacks group access.

### `POST /api/v1/documents/{documentId}/spans`

Session-allowed. Manually create a span at an explicit character range. Used
by the review UI when a reviewer highlights uncovered PII; the API is also
available to clients.

```json
{ "type": "ssn", "start": 42, "end": 53 }
```

`type` is validated against the [PII types list](pii-types.md). The new span is
persisted with `confidence: 1.0`, `status: APPROVED`, and `manuallyCreated:
true`.

| Status | Meaning                                                              |
| ------ | -------------------------------------------------------------------- |
| `200`  | Returns the saved `Span` JSON.                                       |
| `400`  | Missing/invalid `type`, `start`, or `end`; range exceeds the text.   |
| `404`  | Document not found or caller lacks access.                           |
| `409`  | Document is in a terminal state and cannot be edited.                |

### `POST /api/v1/documents/{id}/finalize`

Bearer-only. Finalize an approved document, exactly as the review page's
Finalize button does. The response is the redacted text: the document's text
with each approved span replaced by its type in double angle brackets, such as
`<<SSN>>`. On success the document is `FINALIZED`, the redacted text is kept on
the document so a later download still works even if a finalization policy
clears the source text, a [certificate of redaction](#get-apiv1documentsidcertificate)
is issued, `DOCUMENT_FINALIZE` is recorded in the
[audit log](../admin/audit-log.md) with `via` set to `api`, and the batch's
finalization policy is applied. When the document's batch has a destination,
the redacted text is then delivered to it in the background (see
[Delivering finalized documents](../admin/destinations.md#delivering-finalized-documents)).

The request has no body but requires `Content-Type: application/json`.

```json
{ "finalizedText": "string" }
```

| Status | Meaning                                                                                |
| ------ | -------------------------------------------------------------------------------------- |
| `200`  | Returns `{ "finalizedText": "..." }` and the document is now `FINALIZED`.               |
| `404`  | Document not found, or the caller lacks group access.                                   |
| `415`  | The `Content-Type: application/json` header is missing.                                 |
| `409`  | Document is not in `APPROVED`, another person holds its review lock, **or** its source text is unavailable (e.g. cleared by a finalization policy on a prior pass) and cannot be re-finalized. |

### `GET /api/v1/documents/{id}/audit`

Bearer-only. Return a redaction audit trail — every span on the document
with its `text`, `type`, `confidence`, and current `status`. Useful for
after-the-fact review or compliance reporting.

```json
[
  { "text": "...", "type": "ssn", "confidence": 0.92, "status": "APPROVED" }
]
```

`404` if the document doesn't exist or the caller lacks group access.

### `GET /api/v1/documents/{id}/history`

Session-allowed. Return the full audit history for a document — document-level
events and all span events — as a JSON array sorted newest first. Powers the
Audit Log popup on the Document Queue.

**Restricted to `ROLE_ADMIN` or `ROLE_AUDITOR`.** Returns `403` for any other
caller because the history includes raw PII span text.

Each element:

```json
{
  "timestamp": "2026-05-01T12:00:00Z",
  "actor":     "<mongodb-user-id>",
  "action":    "SPAN_UPDATE",
  "resourceType": "Span",
  "resourceId":   "...",
  "details":   {}
}
```

The `actor` field defaults to the MongoDB user ID. Pass `?resolveActors=true`
to receive the user's email address instead — this parameter requires
`ROLE_ADMIN` or `ROLE_AUDITOR` and returns `403` otherwise.

### `GET /api/v1/documents/{id}/history.csv`

Session-allowed. Download the document's full audit history (document-level
events plus all events on its spans) as a CSV, sorted newest first. Powers
the **Download** button on the Document Queue's Audit Log popup. See
[Audit log](../admin/audit-log.md#download-csv) for the column list.

**Restricted to `ROLE_ADMIN` or `ROLE_AUDITOR`.** Returns `403` for any other
caller. The CSV deliberately omits PII text — span entries include
`spanCharacterStart`, `spanCharacterEnd`, and `spanPage` instead. The `actor`
column contains the actor's email address (the CSV is admin/auditor-only, so
email exposure is appropriate).

### `GET /api/v1/documents/{id}/ledger`

Session-allowed. The document's chains in Philter's ledger, read with
Philter Workspace's API key: `chains` (each with `philterDocumentId`, `state`,
`verified`, `hashChainValid`, `signaturesValid`, `signedEntries`,
`unsignedEntries`, `validationError`, `entries`, `entriesTruncated`, and
`summary`) and `entries` (without original values, replacements, or
filenames). Restricted to `ROLE_ADMIN` or `ROLE_AUDITOR`; `403` otherwise.
Audited as `PHILTER_LEDGER_READ`. See
[Audit trail with Philter's ledger](../admin/audit-log.md#audit-trail-with-philters-ledger).

### `GET /api/v1/documents/{id}/audit-trail`

Session-allowed. The document's audit trail: its Philter Workspace events and
its ledger entries, oldest first, with how each chain verified. `format` is
`csv` (the default) or `json`. Restricted to `ROLE_ADMIN` or `ROLE_AUDITOR`;
`403` otherwise. Audited as `AUDIT_TRAIL_EXPORT`.

### `GET /api/v1/documents/{id}/certificate`

Session-allowed. Return the redaction certificate for a finalized document —
a JSON object summarising the document hash, finalize timestamp, and span
counts. Powers the Certificate popup on the Document Queue. The caller must
have group access to the document; `404` is returned if the document doesn't
exist or the caller lacks access.

### `GET /api/v1/documents/{id}/comments`

Session-allowed. Return reviewer comments left on the document, oldest first.

```json
[
  { "id": "...", "userEmail": "user@example.com",
    "timestamp": "2026-05-04T13:00:00Z", "text": "..." }
]
```

### `POST /api/v1/documents/{id}/comments`

Session-allowed. Add a comment to the document. The request body is a JSON
object with a single `text` string (max 4 000 characters; surrounding
whitespace is trimmed). Returns the saved comment in the same shape the GET
above produces.

```json
{ "text": "..." }
```

## Spans

### `PATCH /api/v1/spans/{id}`

Session-allowed. Update a span's status, type, or boundaries.
Any span change (this one, creating, deleting, resetting, or Redact All Like
This) is refused with `409` while another person holds the document's review
lock, and clears the approvals the document has so far, audited as
`DOCUMENT_APPROVALS_CLEARED`.

```json
{
  "status":        "APPROVED|REJECTED|PENDING|NEEDS_SECOND_OPINION",
  "type":          "ssn",
  "reason":        "...",
  "exemptionCode": "...",
  "adjudicate":    true,
  "start":         12,
  "end":           24
}
```

| Field           | Required | Notes                                                                                     |
| --------------- | -------- | ----------------------------------------------------------------------------------------- |
| `status`        | optional | One of the allowed statuses. Sending none of `status`, `type`, or `start` and `end` returns `400`. |
| `type`          | optional | New PII type; validated against the [PII types list](pii-types.md).                        |
| `reason`        | optional | Required when **overturning** another reviewer's prior `APPROVED` decision (changing status away from `APPROVED` while the prior approval was recorded by a different actor). Returns `409 OVERTURN_REASON_REQUIRED` otherwise. Recorded in the audit trail. |
| `exemptionCode` | optional | Free-form string applied only when the new `status` is `APPROVED`. Cleared automatically when the span moves out of `APPROVED`. |
| `start`, `end`  | optional | Move the span to characters `[start, end)` of the document's original text ([adjusting boundaries](../user-guide/reviewing.md#adjusting-a-spans-boundaries)). Sent together, and accept the span: `status` must be absent or `APPROVED`. `400` for a range outside the text or covering only whitespace; `409` for a range overlapping another span, or for a span in a PDF. The span records where it was detected (`originalCharacterStart`, `originalCharacterEnd`), `adjusted`, `adjustedBy`, and `adjustedAt`; the move is audited as `SPAN_ADJUST`. |
| `adjudicate`    | optional | `true` makes the change an approver's final decision on the span ([adjudication](../user-guide/reviewing.md#adjudicating-a-span)). Requires an approver of the document's group or an administrator, and a `status` of `APPROVED` or `REJECTED`. Sets `adjudicatedBy` and `adjudicatedAt`, and is audited as `SPAN_ADJUDICATE`. |

Returns the updated `Span` object.

`409` if the parent document is in a terminal state, if another person holds its review lock, or if an overturn is
attempted without a `reason`. `400` if `adjudicate` is sent without a `status`
of `APPROVED` or `REJECTED`. `403` if `adjudicate` is sent by someone who is
not an approver of the document's group, or if anyone other than such an
approver changes a span that has `adjudicatedBy` set; an approver's change to
such a span is a new adjudication. The same `403` applies to `reset`,
`DELETE`, and `redact-like` on an adjudicated span, and `redact-like` leaves
other adjudicated spans unchanged and counts them in `adjudicatedSkipped`.

### `DELETE /api/v1/spans/{id}`

Session-allowed. Hard-delete a span. **Only manually-created spans can be
deleted** — for spans the redactor produced, flip status to `REJECTED`
instead.

```json
{ "id": "...", "deleted": true }
```

`400` if the span was redactor-created. `409` if the parent document is
terminal.

### `POST /api/v1/spans/{id}/redact-like`

Session-allowed. Find every other occurrence of the source span's text in the
parent document and approve each match with the source span's PII type. New
`Span` rows are created where matches don't already have one; existing spans
at exact ranges are flipped to `APPROVED` and aligned to the source type.
Overlapping non-exact matches are skipped to avoid duplicate spans.

Requires `Content-Type: application/json` (the request body itself is
ignored; the JSON content type is enforced as a CSRF defence so cross-site
form posts can't trigger this endpoint).

Response:

```json
{ "created": 0, "approved": 0, "adjudicatedSkipped": 0 }
```

`created` is the number of new spans inserted. `approved` is the number of
existing spans flipped to approved. `adjudicatedSkipped` is the number of
matching spans an approver adjudicated, which are left unchanged.

`400` if the source span has empty text. `404` if the span or its document is
missing.

### `POST /api/v1/spans/{id}/rule`

Session-allowed. Make a [detection rule](../user-guide/detection-rules.md) from
the span: `NEVER` ("Don't flag this again") from a refused detected span, or
`ALWAYS` ("Always flag this") from a span added by hand. Allowed whatever the
document's status. Requires `Content-Type: application/json`.

Request:

```json
{ "kind": "NEVER" }
```

Response:

```json
{ "status": "ACTIVE", "message": "Rule confirmed: Philter will never flag this value in documents redacted from now on." }
```

`status` is `ACTIVE` when the caller may confirm rules and the rule was written
to Philter, or `PROPOSED` when it waits for confirmation. `409` with a
`message` when no rule was made: the span is not eligible, the value is too
long or ends in `:fuzzy`, the other kind of rule exists for the value, or
Philter refused the change. `404` if the span or its document is missing.

### `POST /api/v1/spans/{id}/reset`

Session-allowed. Revert a span back to its previous status. The intended use
is "I clicked Approve / Reject by mistake" — the endpoint moves a span out of
its terminal state and back into review. The optional JSON body
`{"originalStatus": "PENDING|REVIEW_REQUIRED"}` selects the target status; an
empty body falls back to the span's prior status as recorded in the audit log.

Requires `Content-Type: application/json`. Returns the updated `Span` JSON.

`404` if the span doesn't exist or the caller lacks group access. `409` if
the parent document is in a terminal state.

### `GET /api/v1/spans/{id}/history`

Session-allowed. Return the audit history for a single span as a JSON array
sorted newest first. Accessible to any authenticated user with group access
to the span's parent document.

```json
[
  {
    "timestamp":    "2026-05-01T12:00:00Z",
    "actor":        "<mongodb-user-id>",
    "action":       "SPAN_UPDATE",
    "resourceType": "Span",
    "resourceId":   "...",
    "details":      {}
  }
]
```

The `actor` field defaults to the MongoDB user ID of the actor to avoid
leaking email addresses to other reviewers. Pass `?resolveActors=true` to
receive email addresses instead — this parameter requires `ROLE_ADMIN` or
`ROLE_AUDITOR` and returns `403` otherwise.

`404` if the span doesn't exist or the caller lacks group access.

## Search

### `POST /api/v1/search`

Bearer-only. Full-text search across the [full-text index](../admin/full-text-search.md) of ingested documents. Each
document is indexed at ingest time with its filename, batch, status, and full
original text.

The query is sent in a JSON body, with `Content-Type: application/json`, so it
stays out of URLs, where proxy and load-balancer access logs would record it.

```json
{ "q": "string", "offset": 0, "size": 10 }
```

| Field    | Default | Meaning                                          |
| -------- | ------- | ------------------------------------------------ |
| `q`      | None    | Match query (required, runs against the text). A blank query is answered with `400`. |
| `offset` | `0`     | First hit to return                              |
| `size`   | `10`    | Max hits per page (capped at 100)                |

`GET /api/v1/search?q=…` was removed: it is answered with `405`, `Allow: POST`,
and an `error` message saying to use POST, and the query is not run.

Response:

```json
{
  "query": "...",
  "offset": 0,
  "size": 10,
  "total": 42,
  "hits": [
    {
      "id": "...",
      "batchId": "...",
      "filename": "...",
      "status": "AUTO_APPROVED",
      "highlights": ["… <em>match</em> snippet …"]
    }
  ]
}
```

Results are pre-filtered to batches the caller can access. Non-admin callers
only see hits from their own group's batches; `total` reflects that filtered
count, not the full index size. Inaccessible hits are silently excluded — they
are never returned as placeholder rows.

If the search backend cannot be used, a query returns an empty result set rather than
failing the request — search is best-effort.

## LLM-as-a-Judge

These endpoints proxy a configured Ollama instance to provide an LLM second
opinion on the redactor's output. Configuration lives under
**Admin → LLM-as-a-Judge**.

!!! warning "PII leaves Philter Workspace on every call"
    Both `explain` and `second-opinion` send the full unredacted document text
    and all PII span values to Ollama. Each call is recorded in the audit log
    as `DOCUMENT_PII_SENT_TO_LLM` **before** the HTTP request is sent, so the
    entry exists even if Ollama is unreachable. Ollama must be deployed with
    request-body logging disabled (`OLLAMA_DEBUG=0`) to prevent PII from
    appearing in Ollama's logs.

### `GET /api/v1/ollama/{instanceId}/models`

Session-allowed. List the models installed on a configured Ollama instance.

```json
{ "instanceId": "...", "instanceName": "...", "models": ["llama3", "mistral"] }
```

`404` if the instance id is unknown. `502` if Ollama is unreachable.

### `POST /api/v1/documents/{documentId}/explain`

Session-allowed. Ask the LLM to explain the PII risk in a document.

```json
{ "instanceId": "...", "model": "llama3" }
```

Response:

```json
{ "instanceName": "...", "model": "llama3", "response": "..." }
```

### `POST /api/v1/spans/{spanId}/second-opinion`

Session-allowed. Ask the configured Second Opinion default Ollama
instance/model whether the named span is genuinely PII or a likely false
positive. The instance and model are chosen from the LLM-as-a-Judge defaults
— the request body is empty but the `Content-Type: application/json` header
is required (CSRF defence).

```json
{ "instanceName": "...", "model": "...",
  "sourceText": "...", "sourceType": "ssn",
  "response": "..." }
```

`400` if no Second Opinion default is configured.

## Policies

These endpoints list the policies batches can use, those of the Philter user that owns
`PHILTER_WORKSPACE_PHILTER_API_KEY`, for the batch dialogs. The framework gates them to `ROLE_ADMIN` or
`ROLE_AUDITOR` for reads — non-admin callers get `403`.

### `GET /api/v1/policies`

Session-allowed. List the policies in Philter Workspace's Philter that
`PHILTER_WORKSPACE_PHILTER_API_KEY` can use.

Response:

```json
{ "policies": ["claims", "..."] }
```

`502` when Philter is unreachable.

### `GET /api/v1/policies/content`

Session-allowed. Fetch the JSON content of one named policy from Philter.

| Query param  | Required | Meaning                                              |
| ------------ | -------- | ---------------------------------------------------- |
| `name`       | yes      | Policy name; restricted to letters, digits, hyphens, and underscores (1–64 chars). |

Response:

```json
{ "name": "claims", "content": "{ ...JSON policy... }" }
```

`content` is Philter's policy JSON as a string, returned verbatim.

`400` for malformed names (the regex check rejects path-traversal attempts).
`404` when Philter has no such policy. `502` when Philter is unreachable.

## Browser-only internal endpoints

The following paths exist under `/api/v1/**` but are intended only for the
in-page JavaScript that drives the review UI. They are session-callable, do
nothing harmful in isolation, and are documented here purely so that traffic
from philter-workspace's own UI doesn't look surprising in HTTP logs:

| Path | Purpose |
|---|---|
| `POST /api/v1/review/{documentId}/pulse` | Sliding-expiry heartbeat sent every ~30 s by the review page so the document's pessimistic edit lock doesn't expire while the reviewer is still on the page. Operates on the caller's own lock only. |
| `POST /api/v1/review/{documentId}/release` | `navigator.sendBeacon`'d when the reviewer leaves the page. Releases the caller's own lock. |
| `GET /api/v1/review/{documentId}/similar` | Backs the "Find similar documents" button on the review page. Returns up to 10 hits filtered to the caller's accessible batches. |

There's no programmatic-API reason to call these — use `PATCH /spans/{id}` and
`POST /search` instead.

## Errors

Error responses have a JSON body with at least an `error` field describing the
issue. Status codes follow the table per endpoint above.
