# Data sources

Data sources let admins point Philter Workspace at an external place where documents
already live — an OpenSearch or Elasticsearch index, an S3 bucket, a
relational database, or a local filesystem directory — instead of (or in
addition to) uploading files one at a time. Once registered, a data source
becomes selectable on the **Add Documents** page.

The page is at `/admin/data-sources`, also reachable from the **Data Sources**
link in the sidebar's Administration section. ROLE_ADMIN only.

Data sources are the *input* half of Philter Workspace's I/O. For where finalized
redacted documents go *out*, see [Destinations](destinations.md).

> **Status:** All five source types — **OpenSearch**, **Elasticsearch**,
> **S3**, **Relational Database**, and **Local Directory** — are fully wired
> up. Clicking *Ingest from …* on the Add Documents page kicks off a
> background job that pulls documents into the redaction queue; progress is
> visible on the Background Jobs page.

## Source types

The page is split into five sections, one per type. Names must be unique
*within* a section (case-insensitive) — so an OpenSearch source and an S3
source can both be named `archive` without conflict.

### OpenSearch

| Field          | Required | Notes                                                                         |
| -------------- | -------- | ----------------------------------------------------------------------------- |
| Name           | yes      | Display name; unique among OpenSearch sources (case-insensitive)              |
| Endpoint       | yes      | Cluster URL, e.g. `http://localhost:9200`                                      |
| Query          | yes      | Query that returns the documents to import — typically the JSON body of an `_search` request, prefixed with the index path (e.g. `contracts/_search { "query": { "match_all": {} } }`). The body's `size` is **always overridden to 100** for paging — see [Paged ingest](#paged-ingest) below. |
| Text field     | yes      | Field name within each hit's `_source` that holds the document text to import (e.g. `body`, `content`). |
| Filename field | no       | Optional. Field within each hit's `_source` whose value is used as the imported document's filename. If blank, the OpenSearch `_id` is used as the filename. |
| Username       | no       | Stored as plaintext on the document row.                                       |
| Password       | no       | Encrypted at rest; see [Credential encryption](#credential-encryption).        |

The listing table shows **Name**, **Endpoint**, **Text field**, and
**Actions**. Auth and the saved query are not surfaced inline; the saved
query is editable through the **Edit** action button on each row.

#### Edit a saved source

Each row has an **Edit** button that opens a popup pre-filled with the
saved values (the **Name** is read-only; everything else can be changed).
The Password field is left blank by default — **leaving it blank keeps the
existing password**, typing a new value replaces it, and the **Clear the
stored password** checkbox wipes it. Saving fires an
`OPENSEARCH_DATASOURCE_UPDATE` audit event with the new values plus a
`passwordChanged` boolean.

#### Test the connection

Two **Test** buttons are available:

- The **Test** button beside the **Add** button on the form sends the
  **current form values** (endpoint, query, optional username/password) to
  Philter Workspace — handy while you're tuning a new source before saving it.
- A **Test** action button on each saved row sends the stored values
  (Philter Workspace decrypts the saved password server-side; the cleartext is never
  handed back to the browser).

Either way, Philter Workspace connects to OpenSearch, runs the query, and shows up to
the **first 10 hits** in a popup along with the reported total. A green
*Success* bar tops the popup when the query worked; otherwise a red error
bar shows the reason. Test does not save anything, so it's safe to use
repeatedly.

### Elasticsearch

Elasticsearch data sources behave **identically to OpenSearch**: same
fields, same scrolling ingest, same Test / Edit affordances. They live in
their own collection, so the same name can be used for one OpenSearch and
one Elasticsearch source without conflict.

| Field          | Required | Notes                                                                         |
| -------------- | -------- | ----------------------------------------------------------------------------- |
| Name           | yes      | Display name; unique among Elasticsearch sources (case-insensitive)           |
| Endpoint       | yes      | Cluster URL, e.g. `http://localhost:9200`                                      |
| Query          | yes      | Same shape as OpenSearch — `<index>/_search { … JSON body … }`. The body's `size` is overridden to 100. |
| Text field     | yes      | Field name within each hit's `_source` that holds the document text.          |
| Filename field | no       | Optional. Field within each hit's `_source` whose value is used as the imported document's filename. If blank, the Elasticsearch `_id` is used. |
| Username       | no       | Stored as plaintext on the document row.                                       |
| Password       | no       | Encrypted at rest; see [Credential encryption](#credential-encryption).        |

### Paged ingest

OpenSearch and Elasticsearch ingest jobs use the **scroll API** so they
can pull arbitrarily large result sets without exhausting memory:

1. The first request opens a scroll context (`?scroll=1m`) with the saved
   query body, **forcing `"size": 100`** so each page is bounded — even if
   the saved body says `"size": 5000`, only 100 hits land per page.
2. The worker walks the page, pulls each hit's text-field value, sets
   traceability fields (see below), and pushes the document onto the
   Philter Workspace ingest queue.
3. It then asks the server for the next scroll page and keeps going until a
   page returns zero hits, at which point it deletes the scroll context.

Each imported document records traceability back to its source: the
`sourceSystem` (`OPENSEARCH` or `ELASTICSEARCH`), the cluster URL, the
index the hit came from (preferred from each hit's `_index`, falling back
to the configured query path), the OpenSearch / Elasticsearch `_id`, and an
**Import Timestamp** of when Philter Workspace pulled it. Reviewers see all of this
on the **Document Information** popup on the Review page.

Job progress is visible in real time on the **Background Jobs** page (under
the Redaction sidebar group). Each job tracks status (`PENDING` /
`RUNNING` / `COMPLETED` / `FAILED`), how many documents have been
processed vs. the total, and — when individual hits fail — a list of
per-hit reasons accessible behind a *Show failure details* disclosure.

### S3-Compatible

| Field          | Required | Notes                                                                  |
| -------------- | -------- | ---------------------------------------------------------------------- |
| Name           | yes      | Unique among S3 sources                                                 |
| Endpoint URL   | no       | S3 API endpoint to target. Leave blank for Amazon S3; set to e.g. `http://minio:9000` for MinIO, or to the API endpoint published by Cloudflare R2 / Backblaze B2 / any other S3-compatible storage. |
| Bucket name    | yes      | The bucket to read from                                                 |
| Bucket key     | yes      | Object-key prefix under which to look (e.g. `archive/2026/`). Leave blank to read from the bucket root. |
| Filename glob  | yes      | Filter applied within the prefix, e.g. `*.txt`, `**/*.pdf`              |
| Access key     | no       | Encrypted at rest                                                       |
| Secret key     | no       | Encrypted at rest                                                       |

Access key and secret key are validated as a pair — provide both or neither.
Leaving them blank means the runtime will use whatever ambient AWS credentials
the application process has (environment variables, instance profile, etc.).
Credentials are not shown in the listing table. The Endpoint URL column shows
"AWS default" in italics when the field is left blank.

The shipped Docker compose stack registers a **Demo MinIO (S3-compatible)**
data source pointing at the bundled `minio` service (`http://minio:9000`,
bucket `philter-workspace-demo`) so the S3 path can be exercised without an AWS
account. See [Getting started](../getting-started.md) for the full demo
layout.

#### Recommended IAM policy

Grant the configured access key **read-only access scoped to the bucket and
key prefix** above — and nothing else. Ingestion needs `s3:GetObject` on the
objects under the prefix, and `s3:ListBucket` (constrained by an
`s3:prefix` condition) to enumerate them. No write, delete, or bucket-admin
permissions are required.

The following policy is a starting point — replace `my-bucket` and
`archive/2026/` with your actual bucket name and key prefix:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "WorkspaceListPrefix",
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::my-bucket",
      "Condition": {
        "StringLike": {
          "s3:prefix": ["archive/2026/*"]
        }
      }
    },
    {
      "Sid": "WorkspaceReadObjects",
      "Effect": "Allow",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::my-bucket/archive/2026/*"
    }
  ]
}
```

This is a suggestion, not a substitute for AWS's own guidance. For
authoritative details on writing least-privilege S3 policies — including
prefix conditions, object-tag conditions, SSE-KMS key access, and the
permission semantics for non-AWS S3-compatible services (MinIO, Cloudflare
R2, Backblaze B2, etc.) — refer to the AWS documentation:

- [Identity and access management in Amazon S3](https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-access-control.html)
- [Bucket policies and user policies](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-iam-policies.html)
- [Actions, resources, and condition keys for Amazon S3](https://docs.aws.amazon.com/service-authorization/latest/reference/list_amazons3.html)

For non-AWS S3-compatible services, consult that vendor's documentation —
the action names and ARN format are usually compatible, but the policy
attachment mechanics differ.

### Relational Database

| Field             | Required | Notes                                                                     |
| ----------------- | -------- | ------------------------------------------------------------------------- |
| Name              | yes      | Unique among RDB sources                                                   |
| JDBC URL          | yes      | Standard JDBC URL, e.g. `jdbc:postgresql://host:5432/dbname`               |
| SQL query         | yes      | `SELECT` whose **first column** holds the document text. An optional column named `filename` (case-insensitive) supplies the per-row filename. See [How rows map to documents](#how-rows-map-to-documents) below. |
| Watermark column  | no       | Result-column name whose value Philter Workspace reads from each row and advances the per-source watermark to on a successful run. Pair with a `:lastKey` placeholder in the SQL above for incremental ingest — see [Incremental ingest with watermarks](#incremental-ingest-with-watermarks) below. Leave blank for a full-scan ingest each time. |
| Username          | no       | Encrypted at rest                                                          |
| Password          | no       | Encrypted at rest                                                          |

Username and password are validated as a pair — provide both or neither.
Leaving them blank means the runtime will rely on credentials embedded in the
JDBC URL or the driver's ambient authentication. Credentials are not shown
in the listing table.

#### Read-only safeguard

Relational data sources are intended for **reading documents**, never
mutating them. Before saving, Philter Workspace scans the SQL for the keywords
**`DELETE`**, **`TRUNCATE`**, and **`DROP`** as whole words
(case-insensitive). If any of those appear, the data source is **rejected**
— nothing is saved — and the admin sees an error like *"SQL query contains
disallowed keyword(s) DELETE. Data sources must use read-only queries."*
The matched keywords plus the offending SQL are recorded in the audit log
under `RDB_DANGEROUS_SQL_BLOCKED` (see the [Audit trail](#audit-trail)
section below).

The match is whole-word-only, so legitimate column names like
`dropoff_count` or `deleted_at` do not trigger the safeguard.

#### How rows map to documents

Each row in the result set becomes one document. Philter Workspace reads two
things from every row:

- **Document text** — the value of **column 1** of the SELECT list,
  whatever the admin called it. This is a *positional* lookup: the
  column's name is irrelevant; only its position matters. A row whose
  first column is `NULL` or empty is recorded as a failure with reason
  *"Row N has a null/empty first column (no document text)"* and never
  becomes a document.

- **Filename** — the value of a column named `filename`, looked up by
  name (case-insensitive — `Filename`, `FILENAME`, and `filename` all
  match). The value is used verbatim — no extension is added, no
  characters are stripped. When the column is absent, or present but
  null/empty for that row, the worker synthesises `row-1.txt`,
  `row-2.txt`, … using the 1-based row index within the current run.

The conventional query shape:

```sql
SELECT body, doc_path AS filename, ...
       ^^^^                          ← text (column 1, name irrelevant)
              ^^^^^^^^^^^^^^^^^^^^^  ← optional, looked up by name
FROM documents WHERE imported_at IS NULL
```

A `SELECT *` works as long as the schema happens to put the text column
first; explicit aliasing is safer.

> **Filenames are the dedupe key.** Philter Workspace records
> `(data-source id, filename)` for every imported row and skips
> subsequent rows with the same key. If you rely on the synthesised
> `row-N.txt` fallback and the underlying table is later reordered or
> rows are inserted in the middle of the existing set, the same
> *content* will be assigned a *different* synthesised filename on the
> next run and re-imported. Returning a stable per-row identifier
> (primary key, content hash, etc.) as `filename` is strongly preferred
> for any source you intend to re-ingest incrementally.

#### Incremental ingest with watermarks

A source that fills in the **Watermark column** field uses a keyset
cursor instead of a full table scan. Each run only sees rows the
previous run hasn't already imported, so a daily-scheduled ingest
against a 10-million-row table doesn't re-read the whole 10M every
night — it picks up only what's new.

The mechanism has three moving parts:

1. **The `:lastKey` placeholder in the SQL.** Reference it in a `WHERE`
   predicate against the same column you name as the watermark.
   Philter Workspace substitutes the stored watermark value at execution time:
   - On the **first run** (or after a reset), `:lastKey` becomes the
     SQL literal `NULL`. Use `COALESCE(:lastKey, …)` to pick a starting
     floor.
   - On every **subsequent run**, `:lastKey` becomes a bound JDBC
     parameter holding the value of the watermark column from the
     last row of the previous run.

2. **The Watermark column.** Names the result column whose value
   Philter Workspace reads from each row. After a successful run completes,
   the source's stored watermark is advanced to that column's
   last-seen value (which, given `ORDER BY` on the same column, is
   also the largest value seen) and an `RDB_WATERMARK_ADVANCE` audit
   row is written. Only successful runs advance the watermark — a
   crash mid-cursor leaves it untouched, and the next run resumes
   from where the previous one started.

3. **The `ORDER BY` on the watermark column.** Without it the
   "last-seen value" is whatever the database happened to emit last,
   which is undefined for a `SELECT` without an `ORDER BY`. Philter Workspace
   does not enforce this — it's the admin's responsibility. A missing
   `ORDER BY` won't break ingest, but the watermark will advance
   erratically and miss rows.

The canonical incremental-ingest shape:

=== "PostgreSQL"
    ```sql
    SELECT body,
           doc_path                              AS filename,
           id
      FROM documents
     WHERE id > COALESCE(:lastKey::bigint, 0)
     ORDER BY id
    ```
    Watermark column: `id`.

=== "MySQL / MariaDB"
    ```sql
    SELECT body,
           doc_path                              AS filename,
           id
      FROM documents
     WHERE id > COALESCE(CAST(:lastKey AS UNSIGNED), 0)
     ORDER BY id
    ```
    Watermark column: `id`. Note the explicit `CAST` — MySQL's
    implicit coercion against a bound `String` is unreliable for
    range comparisons against an integer column.

=== "SQL Server"
    ```sql
    SELECT body,
           doc_path                              AS filename,
           id
      FROM documents
     WHERE id > COALESCE(CAST(:lastKey AS BIGINT), 0)
     ORDER BY id
    ```
    Watermark column: `id`. SQL Server **refuses** implicit
    String → BIGINT casts, so the explicit `CAST` is mandatory rather
    than recommended.

=== "Oracle"
    ```sql
    SELECT body,
           doc_path                              AS filename,
           id
      FROM documents
     WHERE id > COALESCE(TO_NUMBER(:lastKey), 0)
     ORDER BY id
    ```
    Watermark column: `id`.

The watermark column doesn't have to be an integer. A monotonic
timestamp works the same way (`WHERE updated_at > COALESCE(:lastKey,
'1970-01-01')`), as does a UUIDv7 PK ordered lexically. The only hard
requirements are that the column is **strictly monotonic across
inserts you want to ingest** and that the SQL's `ORDER BY` matches.

##### Changing the SQL or watermark column on an active source

When a source has already advanced its watermark and the admin edits
either the SQL or the watermark column, Philter Workspace refuses the save
until the **Reset watermark** confirmation is checked on the Edit
form. The reasoning is symmetric:

- If the new SQL keeps the watermark **and** the meaning is
  unchanged, the old value is still valid and the change shouldn't
  reset it (so the operator unchecks the confirmation, which can't
  be done unless they leave the relevant fields alone).
- If the new SQL means something different — different table,
  different ordering, different predicate — the old watermark would
  either miss new rows or re-import old ones. The reset is then the
  right call, and the explicit confirmation is the operator's
  acknowledgement that they understand.

The Edit modal shows the confirmation row only when the SQL or
watermark column has actually changed on a source that has an active
watermark; URL and credential edits don't require it.

##### Manual watermark adjustments

Two operator overrides are available on the Data Sources page:

- **Reset watermark** clears the stored value, so the next run starts
  from scratch (already-imported documents are still skipped via the
  `(source-id, filename)` dedupe). Audited as `RDB_WATERMARK_RESET`.
- **Set watermark manually** writes a specific value to the source.
  Useful for skipping past a known-bad range, or seeding the cursor
  before the first run so older rows are ignored. Audited as
  `RDB_WATERMARK_SET_MANUAL`.

##### When NOT to use a watermark

If your source already filters to "new rows" some other way — e.g.
the SQL has `WHERE imported_flag = 0` and your application sets
`imported_flag = 1` out of band — leave **Watermark column** blank.
The two mechanisms are independent; mixing them adds complexity for
no benefit. The 100,000-row per-run cap still applies, so a source
that needs to drain a backlog larger than that should use one of the
two approaches (out-of-band flag or watermark) rather than relying
on the cap to throttle.

#### Running an ingest

When the user clicks **Ingest from Relational Database** on the Add
Documents page, the worker opens a JDBC connection using the stored URL
and credentials, runs the saved SQL, and enqueues one document per row
according to the column mapping above. The job is subject to:

- **Dedupe** keyed on `(data-source id, filename)`. New rows go in;
  previously-seen filenames are recorded as **Skipped** rather than
  re-imported.
- **Cap**: at most 100,000 rows per run, enforced via
  `Statement.setMaxRows(…)` at the JDBC protocol level so the database
  doesn't stream more data than the ingest will use.
- **Timeouts**: 15s connection timeout, 5min per-statement query timeout.
  An accidentally-broad query against a huge table can't pin a worker
  thread indefinitely.

The job is a [Background Job](../user-guide/background-jobs.md) and
reports `Processed N / Failed M / Skipped K` the same way every other
ingest does. If the JDBC connection fails (host unreachable, bad
credentials, table doesn't exist) the job is marked FAILED with the
underlying SQLSTATE plus driver message so operators can diagnose without
hunting through the application log.

The shipped Docker compose stack registers a **Demo PostgreSQL** data
source pointing at a bundled `postgres` container, whose `documents`
table is seeded with synthetic PII-shaped rows so the RDB ingest path
can be exercised without standing up a database by hand. See
[Getting started](../getting-started.md) for the full demo layout.

#### Example SQL queries

The queries below cover the common shapes Philter Workspace operators reach for.
Every example follows the two contracts laid out in [How rows map to
documents](#how-rows-map-to-documents): **column 1 is the document
text** (positional, name irrelevant), and the optional **`filename`
column** supplies the per-row filename (named lookup, case-insensitive).

##### Basic — one row per `documents` table row

The simplest shape. Use this when there's a column that already holds
the full document text and a separate column for the filename, and you
want to ingest the whole table once (no incremental support).

```sql
SELECT body,
       doc_path AS filename
  FROM documents
```

If you want re-runs to skip already-imported rows without a watermark,
either keep the SQL filter on an out-of-band flag (`WHERE imported_at
IS NULL`) and update the flag yourself, or rely on the
`(source-id, filename)` dedupe — which only works when `filename`
returns stable, distinct values per row.

##### Composing the text from multiple columns

When the document text lives in several columns (e.g. a CRM ticket
with separate `subject`, `body`, and `notes`), concatenate them in the
SELECT. The redactor then sees the full composed text per row.

=== "PostgreSQL / SQL Server / Oracle (ANSI)"
    ```sql
    SELECT subject || E'\n\n' || body || E'\n\nNotes:\n' || COALESCE(notes, '')
                                                       AS document_text,
           ticket_id::text                             AS filename
      FROM support_tickets
     WHERE status = 'closed'
    ```

=== "MySQL / MariaDB"
    ```sql
    SELECT CONCAT(subject, '\n\n', body, '\n\nNotes:\n', COALESCE(notes, ''))
                                                       AS document_text,
           CAST(ticket_id AS CHAR)                     AS filename
      FROM support_tickets
     WHERE status = 'closed'
    ```

The first column (`document_text`) is whatever the concatenation
expression returns; its name and alias are decorative — the worker
reads column **1** by position.

##### Joining a documents table with a per-row metadata table

Sometimes the filename lives elsewhere — e.g. an attachment table
keyed on `document_id`. The join is yours to write; Philter Workspace only
needs the two contractual columns on the result.

```sql
SELECT d.body,
       COALESCE(a.original_filename, d.id::text)  AS filename
  FROM documents d
  LEFT JOIN attachments a
    ON a.document_id = d.id AND a.role = 'original'
 WHERE d.imported_into_philter_workspace = FALSE
```

The `LEFT JOIN` keeps documents without a matching attachment;
`COALESCE` falls back to the document id (cast to text) so every row
has a stable filename for the dedupe key.

##### Filtering by status

To pick up only documents in a particular workflow state, add a `WHERE`
clause. The validator allows arbitrary `WHERE` / `JOIN` / `GROUP BY` /
`HAVING` / `ORDER BY` / `LIMIT` — only `INSERT`/`UPDATE`/`DELETE` and
their kin are refused.

```sql
SELECT body,
       case_number AS filename
  FROM legal_cases
 WHERE status IN ('NEEDS_REVIEW', 'PENDING_REDACTION')
   AND created_at >= CURRENT_DATE - INTERVAL '90 days'
```

##### Incremental ingest with an integer primary key

The canonical keyset-paginated shape. Set **Watermark column** to
`id`; Philter Workspace advances it after each successful run. See
[Incremental ingest with watermarks](#incremental-ingest-with-watermarks)
for the cross-driver variants and the `COALESCE` first-run idiom.

```sql
SELECT body,
       doc_path AS filename,
       id
  FROM documents
 WHERE id > COALESCE(:lastKey::bigint, 0)
 ORDER BY id
```

##### Incremental ingest by timestamp

When the table doesn't have a monotonic integer PK but does have a
reliable `updated_at` column, use that as the watermark. Set
**Watermark column** to `updated_at`. The placeholder substitutes a
String, so cast it back to the column type in the predicate.

=== "PostgreSQL"
    ```sql
    SELECT body,
           filename,
           updated_at
      FROM documents
     WHERE updated_at > COALESCE(:lastKey::timestamptz, '1970-01-01'::timestamptz)
     ORDER BY updated_at, id        -- tiebreak on id so rows with the same
                                    -- updated_at advance deterministically
    ```

=== "MySQL / MariaDB"
    ```sql
    SELECT body,
           filename,
           updated_at
      FROM documents
     WHERE updated_at > COALESCE(CAST(:lastKey AS DATETIME), '1970-01-01')
     ORDER BY updated_at, id
    ```

> **Caveat.** A timestamp watermark misses rows whose `updated_at`
> equals the watermark from the previous run's last row, because the
> predicate is strictly `>`. The tiebreaker on `id` only orders the
> rows correctly for the `ORDER BY`; the next run starts from
> `updated_at > X`, which skips any other row at exactly time `X`.
> For tables with multiple rows per timestamp, prefer an integer PK
> watermark, or design the predicate as
> `(updated_at, id) > (:lastKey_ts, :lastKey_id)` — which Philter Workspace
> doesn't natively support (only a single `:lastKey` placeholder
> is substituted), so this is a documented limitation.

##### Using a CTE / `WITH` clause

The validator accepts `WITH … SELECT` as a first-class top-level
shape. CTEs are useful when the per-row logic is complex enough that
inlining it as a subquery becomes unreadable.

```sql
WITH redactable AS (
    SELECT d.id,
           d.body,
           d.filename,
           ROW_NUMBER() OVER (PARTITION BY d.tenant_id ORDER BY d.id) AS rn
      FROM documents d
     WHERE d.body IS NOT NULL
)
SELECT body, filename, id
  FROM redactable
 WHERE rn <= 1000              -- one per tenant per run, oldest first
 ORDER BY id
```

> **Note.** Any mutating keyword inside the `WITH` block (e.g.
> PostgreSQL's `WITH d AS (DELETE … RETURNING *) SELECT …`) is
> refused by [the read-only safeguard](#read-only-safeguard) — the
> CTE-with-DELETE pattern is exactly what the safeguard's
> whole-word keyword scan was built to catch.

##### What NOT to do

```sql
-- Refused: leading keyword must be SELECT or WITH.
EXPLAIN SELECT body FROM documents

-- Refused: multi-statement input. Remove the trailing semicolon.
SELECT body FROM documents;

-- Refused: mutating keyword anywhere in the statement.
WITH purged AS (DELETE FROM documents RETURNING *)
SELECT body FROM purged

-- Refused: SQL Server / MySQL write-via-SELECT primitive.
SELECT body FROM documents INTO OUTFILE '/tmp/exfil.csv'

-- Refused: PostgreSQL filesystem read primitive.
SELECT pg_read_file('/etc/passwd')

-- Accepted, but you almost certainly didn't mean it: the watermark
-- column is set but the SQL doesn't reference :lastKey. The
-- watermark advances after each run but does nothing to filter
-- the cursor — every run re-reads the entire table (capped at
-- 100,000 rows) and re-pays the dedupe cost.
SELECT body, id AS filename FROM documents ORDER BY id
```

### Local Directory

| Field          | Required | Notes                                                                |
| -------------- | -------- | -------------------------------------------------------------------- |
| Name           | yes      | Unique among local sources                                           |
| Directory path | yes      | Absolute path on the **application server's** filesystem              |
| Filename glob  | yes      | e.g. `*.txt` (top level only), `**.pdf` (any depth — `**` greedily spans `/`)              |

No credentials — the directory is read with the application's process
identity, so make sure the path is reachable and readable by that user.

When the user clicks **Ingest from Local Directory**, the worker walks the
configured path and queues every regular file whose **relative** path
matches the configured glob. `.pdf` files are queued as binary uploads;
everything else is read as UTF-8 text. The job runs as a
[Background Job](../user-guide/background-jobs.md) and reports progress
the same way the OpenSearch/Elasticsearch ingests do. If the directory is
missing, isn't a directory, or isn't readable by the application user, the
job fails up front with a clear error and no documents are queued.

Re-running an import is safe: the worker dedupes by
`(directory path, relative file path)`, so files that have already been
imported from the same directory are recorded as **Skipped** and not
re-enqueued.

### Case metadata sidecars

Local Directory and S3-Compatible sources read a document's case metadata from a
sidecar file beside it, named after the document with `.case.json` added (for
`motion.pdf`, `motion.pdf.case.json`). Sidecars are never imported as documents,
and a document with an invalid sidecar is not imported. See
[Case metadata](../reference/case-metadata.md#sidecar-files).

## Trust model and host allow-list

Data sources are administered by users with `ROLE_ADMIN`. The product
intentionally **trusts admins** to point Philter Workspace at internal services —
that's what the feature is for — and the Test buttons issue HTTP / JDBC
calls to whatever URL the admin types. In a typical deployment this is
fine: admins run Philter Workspace and have at least as much network access as the
application process.

In a multi-tenant or zero-trust deployment where the application server
has access to internal hosts that the admin role should *not* be able to
reach (the [SSRF](https://owasp.org/www-community/attacks/Server_Side_Request_Forgery)
risk), there is a defense-in-depth setting that pins acceptable hosts:

```properties
# application.properties / philter-workspace-webapp
philter-workspace.data-sources.allowed-hosts=opensearch.internal,*.search.example.com
```

The format is a comma-separated list. Each entry is either an exact
hostname or a leading-wildcard pattern (`*.foo.com`) that matches one-or-
more left-side labels (so `a.foo.com` and `a.b.foo.com` both match) and
also the bare apex (`foo.com`).

### When it is checked

The list, and the private-range default-deny below, apply to OpenSearch,
Elasticsearch, S3, and relational database data sources, and to Ollama
instances (**Admin → LLM-as-a-Judge**). An endpoint or JDBC URL is checked:

- when a data source is saved or edited;
- by each **Test** click and each relational database **Preview**;
- when an import is queued;
- and again while the import runs: before every request to OpenSearch,
  Elasticsearch, or an S3 endpoint, and before a relational database
  connection is opened.

Checking again at connect time means an endpoint edited after an import was
queued, a host removed from the list since, or a hostname whose DNS answer
has changed is refused rather than connected to. An S3 data source without
an endpoint uses AWS's own endpoints and is not checked. Philter's URL is set
in the environment (`PHILTER_WORKSPACE_PHILTER_URL`) and is not checked. A
refused host is answered with *"Endpoint host is not permitted."*

A hostname is resolved and **every** address it resolves to is checked; one
private address is enough to refuse it. The Java runtime caches the answer
(30 seconds by default, `networkaddress.cache.ttl`), so the connection made
straight after a check uses the addresses that were checked. The one gap
left is the cache entry expiring in the moment between the check and the
connection.

### JDBC URLs

A JDBC URL must name a single host in the URL itself, in one of these forms:

| Driver | Form |
| --- | --- |
| PostgreSQL | `jdbc:postgresql://host[:port]/database[?properties]` |
| MySQL, MariaDB | `jdbc:mysql://host[:port]/database[?properties]`, `jdbc:mariadb://...` |
| SQL Server | `jdbc:sqlserver://host[\instance][:port][;properties]` |
| DB2 | `jdbc:db2://host[:port]/database[:properties;]` |
| Oracle | `jdbc:oracle:thin:@host:port:sid`, `@//host:port/service`, or `@host:port/service` |

URLs that let the driver connect somewhere the URL does not show are
refused: no host (PostgreSQL then connects to `localhost`), more than one
host, MySQL's key-value `address=(host=...)` form, sub-protocols such as
`loadbalance:`, `replication:`, and `srv:` (hosts named by a DNS SRV record),
Oracle connect descriptors (`(DESCRIPTION=...)`), LDAP lookups, and TNS aliases
read from `tnsnames.ora`. So are properties that change the host, port, or
service, whatever their case and whether or not they are percent-encoded:
PostgreSQL's `PGHOST`, `PGPORT`, `PGDBNAME`, and `service`; SQL Server's
`serverName` (and `server`, `address`, `addr`, `network address`),
`portNumber`, `instanceName`, and `failoverPartner`; DB2's
`clientRerouteAlternateServerName` and `clientRerouteAlternatePortNumber`;
MySQL and MariaDB's socket and pipe settings (`localSocket`, `pipe`,
`namedPipePath`, `protocol`, `socket`); and Oracle's `TNS_ADMIN`.

### Private-range default-deny

Private-range addresses are blocked by default. Philter Workspace resolves the
configured hostname and rejects it if any address it resolves to is private
or reserved:

- **IPv4:** `0.0.0.0/8`, `10.0.0.0/8`, `100.64.0.0/10` (carrier-grade NAT,
  which holds some clouds' metadata addresses, such as `100.100.100.200`),
  `127.0.0.0/8`, `169.254.0.0/16` (link-local), `172.16.0.0/12`,
  `192.0.0.0/24` (which holds `192.0.0.192`), `192.168.0.0/16`,
  `198.18.0.0/15`, and `224.0.0.0` and above (multicast, reserved, broadcast).
- **IPv6:** `::`, `::1`, `fe80::/10`, `fec0::/10`, `fc00::/7` (which holds
  AWS's `fd00:ec2::254`), `ff00::/8`, and `64:ff9b:1::/48`.
- **IPv6 that carries an IPv4 address,** judged by that address:
  IPv4-mapped (`::ffff:a.b.c.d`), IPv4-compatible (`::a.b.c.d`), NAT64
  (`64:ff9b::/96`), 6to4 (`2002::/16`), and Teredo (`2001::/32`, its server
  and client). So `64:ff9b::a9fe:a9fe`, which reaches `169.254.169.254`
  through NAT64, is refused, while `64:ff9b::808:808` is not.

Numeric addresses are read by the Java runtime exactly as a connection
reads them, so a literal private IP (`http://127.0.0.1:9200`,
`http://169.254.169.254/`) or another way of writing one (`127.1`,
`2130706433`) cannot bypass the check by skipping DNS resolution.

The reason this exists by default — even before any operator has set
`philter-workspace.data-sources.allowed-hosts` — is **server-side request forgery
(SSRF)**. The application process can usually reach private addresses
the operator does not intend to expose through admin-supplied URLs:
cloud instance-metadata endpoints (`169.254.169.254` on AWS / GCP /
Azure, which can leak role credentials), MongoDB / Redis / Philter on
loopback, intranet HTTP services on RFC-1918, and other tenants on the
same VPC. The default-deny on private ranges closes that surface
without requiring any configuration.

The default-deny is **not absolute**. A private host is admitted by naming it:

1. **Add it to `philter-workspace.data-sources.allowed-hosts`.** When the
   property is set, Philter Workspace checks the resolved host against every
   pattern — including private addresses. So
   `philter-workspace.data-sources.allowed-hosts=opensearch.internal,192.168.1.100`
   admits exactly those two hosts (one of which is private). Hostnames
   that resolve to a private address are admitted the same way, as long
   as the hostname matches a pattern. This is the supported way to
   reach a legitimate internal OpenSearch / Elasticsearch / Philter /
   Ollama from the data-source UI.
Turning the allow-list off under Admin → Security does **not** admit a
private host; see [Master switch (Admin → Security)](#master-switch-admin-security).

### Allow-list behavior summary

| `philter-workspace.data-sources.allowed-hosts` | Public host | Private host |
| --- | --- | --- |
| unset / blank (default) | accepted | rejected |
| set, host matches a pattern | accepted | accepted |
| set, host doesn't match | rejected | rejected |

Use the property to restrict which public hosts are reachable in
multi-tenant or zero-trust deployments, or to admit specific private
hosts (an internal OpenSearch, a bastion-routed Philter) the operator
genuinely needs to reach.

### Master switch (Admin → Security)

**Admin → Security → Enable host allow-list** turns off the
property-driven host patterns for **public** hosts: with it off, any public
host is accepted. It never turns off the private-range default-deny, so a
private host is still refused unless it is named in
`philter-workspace.data-sources.allowed-hosts`. The setting is stored on the
global settings document, defaults to **enabled**, and is read at request
time, so a toggle takes effect on the next outbound check without
restarting.

Disabling the master switch is **not recommended** where the patterns
restrict which public hosts may be reached. See
[Security settings → Data-source host allow-list](security.md#data-source-host-allow-list)
for the rationale and the audit-trail entry that records each toggle.

## Credential encryption

Every credential that admins type into the Data Sources page is encrypted with
AES-GCM before being written to MongoDB. See
[Security · Encrypted secrets](../security.md#encrypted-secrets) for
the full description (key derivation, IV format, base64 layout, and the
`philter-workspace.crypto.secret` property). The plaintext is never displayed back; the
table only shows a status (`Configured` / `Ambient` / `From URL` /
`••••••`).

The OpenSearch **username** is the one credential field stored as plaintext
on the document row. Treat it as you would any other identifier in the
database — Mongo-level encryption-at-rest still applies, but the value is
visible in raw documents.

## Removing a source

Each row has a **Remove** button that deletes the source after a confirmation
prompt. Removal is hard-delete; nothing is moved to a trash collection.

## Audit trail

Every change is recorded in the [audit log](audit-log.md) with the actor's
email and the affected source's id and name:

| Action                                | Resource                   | When fired                              |
| ------------------------------------- | -------------------------- | --------------------------------------- |
| `OPENSEARCH_DATASOURCE_CREATE`        | `OpenSearchDataSource`     | OpenSearch source added                  |
| `OPENSEARCH_DATASOURCE_UPDATE`        | `OpenSearchDataSource`     | OpenSearch source edited (payload includes a `passwordChanged` boolean) |
| `OPENSEARCH_DATASOURCE_DELETE`        | `OpenSearchDataSource`     | OpenSearch source removed                |
| `ELASTICSEARCH_DATASOURCE_CREATE`     | `ElasticsearchDataSource`  | Elasticsearch source added               |
| `ELASTICSEARCH_DATASOURCE_UPDATE`     | `ElasticsearchDataSource`  | Elasticsearch source edited              |
| `ELASTICSEARCH_DATASOURCE_DELETE`     | `ElasticsearchDataSource`  | Elasticsearch source removed             |
| `S3_DATASOURCE_CREATE`                | `S3DataSource`             | S3 source added                          |
| `S3_DATASOURCE_DELETE`                | `S3DataSource`             | S3 source removed                        |
| `RDB_DATASOURCE_CREATE`               | `RelationalDbDataSource`   | Relational database source added. Payload includes `watermarkColumn` (empty when watermarking is off). |
| `RDB_DATASOURCE_UPDATE`               | `RelationalDbDataSource`   | Relational database source edited. Payload includes `credentialsChanged`, `credentialsSet`, `watermarkColumn`, and a `watermarkReset` boolean (true when the edit changed the SQL or watermark column on an already-advanced source). |
| `RDB_DATASOURCE_DELETE`               | `RelationalDbDataSource`   | Relational database source removed       |
| `RDB_WATERMARK_ADVANCE`               | `RelationalDbDataSource`   | Auto-advanced after a successful incremental run. Payload includes `from`, `to`, and the row count processed in this run. |
| `RDB_WATERMARK_RESET`                 | `RelationalDbDataSource`   | Operator-initiated reset via the **Reset watermark** button. Payload includes the `previousKey`. |
| `RDB_WATERMARK_SET_MANUAL`            | `RelationalDbDataSource`   | Operator-initiated manual override of the watermark value. Payload includes `from` and `to`. |
| `RDB_DANGEROUS_SQL_BLOCKED`           | `RelationalDbDataSource`   | RDB source rejected for containing `DELETE`, `TRUNCATE`, or `DROP` in the SQL — entityId is `null` because nothing was saved. The payload includes the data-source name, JDBC URL, matched `keywords`, and the offending `sqlQuery`. |
| `RDB_DANGEROUS_JDBC_URL_BLOCKED`      | `RelationalDbDataSource`   | RDB source rejected by `JdbcUrlValidator` (driver-level RCE primitive, dangerous parameter, embedded credentials, host not on the allow-list, …). entityId is `null` on create; on edit it carries the existing source id. |
| `LOCAL_DATASOURCE_CREATE`             | `LocalDirectoryDataSource` | Local directory source added             |
| `LOCAL_DATASOURCE_DELETE`             | `LocalDirectoryDataSource` | Local directory source removed           |

The `*_CREATE` and `*_UPDATE` entries record the connection details
(endpoint / bucket / URL / path, query, table, glob, plus **text field**
and **filename field** for OpenSearch and Elasticsearch) along with a
boolean `credentialsSet` / `passwordSet` flag. The encrypted credential
values themselves are **never logged**. The **Test** actions do not
produce their own audit event — they don't save anything.
