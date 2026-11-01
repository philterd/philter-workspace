# Security

This page summarizes the security model: how Philter Workspace authenticates users,
how it scopes what they can see, and how it stores secrets.

## Authentication

People sign in to Philter Workspace through its Philter, and Philter is the only user store. There are two
ways to authenticate:

1. **Sign-in** with a Philter username and password, at `/login`. See
   [Sign-in through Philter](#sign-in-through-philter). CSRF protection is enabled for all non-API
   form posts.
2. **A Philter API key** on Philter Workspace's API, as `Authorization: Bearer <key>` on requests to
   `/api/**`. See [API keys](#api-keys).

Philter Workspace has no accounts, passwords, MFA, or API keys of its own.

#### Session storage

Form-login sessions live in **Valkey** (Redis-protocol compatible) via
Spring Session, so multiple Philter Workspace replicas behind a load balancer all
read and write the same `HttpSession`. Two consequences:

- The load balancer doesn't need sticky sessions — any replica can serve
  any request.
- A signed-in user keeps their session if a replica restarts.

The connection is configured by `spring.data.redis.host`,
`spring.data.redis.port`, and `spring.data.redis.password`. Both compose files
start Valkey with a password (`VALKEY_PASSWORD`); the development stack
publishes it on `127.0.0.1` only, and the production stack does not publish
it. Session timeout is `spring.session.timeout`, default 30 minutes.

Sessions are stored with Java serialization. Whoever can write to Valkey can
forge a signed-in session for anyone, whatever the format, so the protection is
access to Valkey, not the format: give it a password, keep it on a private
network, and do not publish its port. A JSON format would remove Java
deserialization from the risk but not the forgery, and would need every type
kept in a session or flashed between requests to be allowlisted.

### Sign-in through Philter

People sign in to Philter Workspace with their Philter username and password. Philter keeps the passwords,
MFA, lockout, and rate limiting; see Philter's
[Sign-in Security](https://philterd.github.io/philter/latest/sign_in_security.html). Philter must run
with `PASSWORD_SIGN_IN_ENABLED=true`, and Philter Workspace reaches it at `PHILTER_WORKSPACE_PHILTER_URL` (see
[Philter integration](reference/configuration.md#philter-integration)).

- **Sign-in.** The sign-in form sends the username and password to
  Philter's `POST /api/sign-in`, with the person's address and user agent.
  Philter Workspace does not store or log the password.
- **Session key.** Philter returns a session key for the person. Philter Workspace
  keeps it only in the server-side session, encrypted with
  `PHILTER_WORKSPACE_CRYPTO_SECRET`, and never sends it to the browser.
- **Further steps.** A person enrolled in MFA enters a code
  (`POST /api/sign-in/mfa`). A person whose password an administrator set,
  or who must enroll in MFA, does that first. They are not signed in to
  Philter Workspace until every step is done. Changing the password or confirming
  enrollment ends their Philter sessions, and they sign in again.
- **Session end.** Philter Workspace checks the key with Philter at most once a
  minute (`philter-workspace.sign-in.check-interval-seconds`; see
  [Sign-in](reference/configuration.md#sign-in)) while the person uses Philter Workspace, which also keeps Philter's idle
  timeout from expiring. When Philter rejects the key (idle timeout,
  maximum lifetime, a password reset, revocation, or deactivation), Philter Workspace
  ends the session on the next check and returns the person to sign in. If
  Philter cannot be reached, the session keeps working for five minutes after
  its last successful check (`philter-workspace.sign-in.unreachable-grace-seconds`),
  and each request checks again. After that, requests are refused with 503
  until a check succeeds, so a person deactivated in Philter cannot keep their
  session through an outage. The session is kept, and the person carries on
  without signing in again once Philter is back.
- **Sign-out.** Signing out of Philter Workspace revokes the key with
  `DELETE /api/api-keys/current`.
- **Philter version.** Philter Workspace refuses sign-in to a Philter whose major version it does not
  support, before sending the password. See
  [Supported Philter versions](workflow.md#supported-philter-versions).
- **Refusals.** A wrong password, an unknown username, and a deactivated
  user get the same message. A locked username, an address over Philter's
  rate limit, and locked MFA get the same "locked" message, whether or not
  the username exists.
- **Roles.** A Philter `admin` is a Philter Workspace `ADMIN`. A Philter `user` is an
  Philter Workspace `USER`, or `AUDITOR` if a Philter Workspace administrator sets that. The
  role is set at sign-in. When it changes, in Philter or under **Admin →
  Users**, or the person's Philter Workspace record is deleted, Philter Workspace ends their
  session at its next check and they sign in again with the new role. Philter Workspace keeps a record for each person,
  named by their Philter username, for group membership and for the user
  recorded on batches, documents, and the audit log. It holds no password,
  MFA secret, or API key.
- **Users.** The Users page manages Philter's users through its Users API, with the signed-in
  administrator's session key (see
  [Users](admin/users-and-groups.md#users-adminusers)).
- **Audit.** Philter records sign-ins (`sign_in_succeeded`, `sign_in_failed`, and the locks)
  and sign-outs (the session key's `api_key_deleted`) in its audit log, readable from **Audit
  Log → Philter**. Philter Workspace's log records only a sign-in Philter never saw, as a `LOGIN`
  failure with `method: philter` and a `reason` of `UnsupportedPhilter`, `Unavailable`,
  `SignInDisabled`, or `NoSessionKey`. A session Philter Workspace ends because Philter rejected its
  key, the person's role changed, or it was left from the removed local sign-in also revokes the
  key, unless Philter had already rejected it; the application log notes which. See
  [Audit log](admin/audit-log.md#philter-workspaces-and-philters).

### First sign-in

A new Philter has one administrator, `admin`, with no password. Give it one with
`PHILTER_BOOTSTRAP_API_KEY`, then sign in to Philter Workspace as `admin`, which is a Philter Workspace administrator
too. With `docker-compose.prod.yaml`, which does not publish Philter's port, run `curl` on the
compose network (`docker network ls` shows its name):

```sh
docker run --rm --network philter-workspace_philter-workspace curlimages/curl:8.10.1 -s -X PUT "http://philter:8080/api/users/admin/password" \
  -H "Authorization: Bearer $PHILTER_BOOTSTRAP_API_KEY" -H "Content-Type: application/json" \
  --data '{"password":"a password of 16 or more characters"}'
```

This sets only `admin`'s first password; change it later from **Settings**. Add other people under
**Admin → Users**.

The bootstrap key holds every scope. While it is active, administrators see a warning on the
dashboard and under **Admin → Philter**, with a link to Philter's
[instructions for retiring it](https://philterd.github.io/philter/latest/account/api_keys.html#bootstrapping-an-api-key-for-automation):
create an administrator key of your own holding every scope, then revoke the bootstrap key with it.
Philter Workspace checks with the signed-in administrator's session key and caches the answer for a minute, so
the warning clears within a minute of the revocation.

### Upgrading from local sign-in

Earlier versions of Philter Workspace could keep their own accounts (`PHILTER_WORKSPACE_SIGN_IN_MODE=local`, the old
default). That mode is removed:

- **Settings.** Remove `PHILTER_WORKSPACE_SIGN_IN_MODE` and `PHILTER_WORKSPACE_ADMIN_INITIAL_PASSWORD`. Philter Workspace refuses
  to start with `PHILTER_WORKSPACE_SIGN_IN_MODE=local`. Set `PHILTER_WORKSPACE_PHILTER_URL` and run Philter with
  `PASSWORD_SIGN_IN_ENABLED=true`.
- **People.** Create a Philter user for each person, with their Philter Workspace email address as the
  username. Philter allows an email address as a username, and Philter Workspace keys each person's record
  by username, so their groups, team leads, Auditor role, and the batches, reviews, and audit
  entries attributed to them stay theirs. Make a person a Philter `admin` to keep them a Philter Workspace
  administrator. A person created with any other username starts with a new record.
- **Credentials.** On its first start after the upgrade, Philter Workspace rewrites every user record
  without the password hash, MFA secret, and API key that local sign-in kept, and logs how many
  it rewrote. People enroll in MFA again in Philter.
- **API keys.** Philter Workspace's own API keys stop working. Use Philter API keys instead; see
  [API keys](#api-keys).
- **Sessions.** A session signed in with a local account is ended at its next request, and the
  person signs in through Philter.

## API keys

Philter Workspace's API accepts a Philter API key as `Authorization: Bearer <key>` on `/api/**`. Philter Workspace asks
Philter whose key it is with `GET /api/users/me`, so the key must hold Philter's `users:read`
scope, and the request runs as that Philter user with the role Philter Workspace gives them (see **Roles**
above). Philter Workspace's own calls to Philter use `PHILTER_WORKSPACE_PHILTER_API_KEY`, so the key needs no other
scope. Create a key with only `users:read` for an integration that calls Philter Workspace.

| Response | When |
|---|---|
| `401` | Philter does not accept the key, or its user is deactivated. |
| `403` | The key does not hold `users:read`, or the user's Philter Workspace role does not allow the call. |
| `503` | Philter could not check the key: it was unreachable, answered with a server error, or rate limited the check. |

Philter's answer is cached for `philter-workspace.api-keys.cache-seconds` (default 30), keyed by a hash of
the key, so a revoked key, a deactivation, or a role change applies within that time. Philter Workspace does
not store or log the key. A Bearer key is checked only on `/api/**`; on other paths it is ignored.
A key replaces a session cookie on the same request.

Philter Workspace's other calls to Philter, such as redaction at ingest and policy
listing, still use `PHILTER_WORKSPACE_PHILTER_API_KEY`, not a person's
session key.

## Authorization

Three roles, applied per-endpoint:

- `ROLE_USER` — default for non-admin accounts. Limited to batches in groups
  they belong to.
- `ROLE_ADMIN` — required for everything under `/admin/**`, the batch create
  and close endpoints, and for the unscoped (`myGroupsOnly=false`) view on
  the batches and queue pages.
- `ROLE_AUDITOR` — read-only counterpart to `ROLE_ADMIN`. Sees the same
  cross-group data an admin sees but cannot mutate state. Enforced by the
  `AuditorWriteRejectFilter`, which 403s any non-safe HTTP method outside a
  small self-management allow-list (`/login`, `/logout`, `/settings`). See [Roles and permissions](reference/roles.md) for the
  full feature matrix.

In addition, any `ROLE_USER` may be designated a **team lead** for one or
more groups. Team leadership is a per-group attribute stored on the group
itself (the leaders are a subset of the group's members), not a separate
role. The framework matchers therefore do not gate `/batches` to admins
alone — that endpoint is open to any authenticated user, and the per-resource
authorization happens in the controller via `BatchAccessService.canLeadBatch`,
which permits the request only if the caller is an admin or leads the
batch's group. Reassigning a batch to a different group remains admin-only.

**Group scoping** is enforced server-side regardless of the UI. Whenever a
non-admin attempts to:

- View a batch outside their groups → not in the list.
- View a document whose batch is outside their groups → returns 404 (not
  403, to avoid leaking existence).
- Modify a span / document outside their groups → 404.
- Ingest into a batch outside their groups → API returns 403.

The "Limit to my groups" toggle on the queue and batches pages is purely a
UI affordance for admins; revoking the role at the back end immediately
re-applies group restrictions.

## Unauthenticated endpoints

Three `GET` paths answer without a credential, so that a container runtime,
load balancer, or Prometheus scraper can reach Philter Workspace before any account
exists: `/api/health`, `/actuator/health`, and `/actuator/prometheus`. One
`POST` path, `/api/philter/webhook`, takes Philter's webhook deliveries; Philter
signs each one instead of authenticating. Every other path, API and UI alike,
requires authentication.

The webhook path answers `404` unless `PHILTER_WORKSPACE_PHILTER_WEBHOOK_SECRET` is
set. With it set, a delivery is handled only when its HMAC-SHA256 signature matches
the secret and its timestamp is within five minutes, which also stops a captured
delivery from being replayed. Bodies over 64 KiB are refused unread. See
[Philter webhook](reference/configuration.md#philter-webhook).

What they disclose:

- The build version (`/api/health`), which tells a caller which release is
  running and therefore which published issues apply to it.
- An aggregate health status, never the components behind it. Which
  dependency is down, and the addresses of MongoDB, Valkey, and OpenSearch,
  stay out of the response.
- JVM and HTTP request metrics. Request URIs are route templates
  (`/documents/{id}`), so no document identifier, document text, entity
  value, or user identity is exported, but request volumes and error rates
  are visible.

No other Actuator endpoint is exposed; `env`, `configprops`, `beans`,
`loggers`, and `heapdump` all return `404`, as do the `/actuator` links page
and the per-component paths under `/actuator/health/`.

Keep Philter Workspace's port on an internal network. Where the deployment terminates
TLS at a reverse proxy, block `/actuator` there unless your monitoring needs
it, and restrict `/api/health` to the monitors that call it if the build
version should not be visible.

## SMTP credentials

The notification settings include an SMTP password used to talk to a mail
relay. That password is stored as-is in the `settings` collection so
Philter Workspace can present it at send time. Treat the database as sensitive
storage — encrypt at rest at the storage layer or front it with a secret
manager if your deployment policy demands it.

## Philter API key

Philter Workspace sends `PHILTER_WORKSPACE_PHILTER_API_KEY` to Philter as `Authorization: Bearer …`
on its calls that are not made with a person's session key. The key is read from
the environment at startup and is not stored in Philter Workspace's database, logged, or
shown in the UI. Supply it through a secrets mechanism.

The key must not belong to a Philter administrator, since Philter Workspace holds it
for background work such as ingest. Philter Workspace checks the role Philter reports for
it (`GET /api/limits`) at startup and on **Test** under **Admin → Philter**,
fails the Test for an administrator's key, and flags the key there and in the
log until its user is no longer an administrator. Give Philter Workspace a key with only
the `redact` and `policies:read` scopes, plus `lists:read` and `lists:write` for
[detection rules](user-guide/detection-rules.md) and `ledger:read` for Philter's ledger in the
[audit trail](admin/audit-log.md#audit-trail-with-philters-ledger). For a deployment already using an
administrator's key, remove that user's administrator role rather than switching
users, since Philter's policies and contexts belong to the key's user; see
[Philter API key for Philter Workspace](workflow.md#philter-api-key-for-philter-workspace).

## Encrypted secrets

Secrets Philter Workspace stores, such as data source credentials, are **encrypted with
AES-GCM** before being written to the database:

- 32-byte AES key loaded from the `philter-workspace.crypto.secret` property. The
  property must be a **base64-encoded value of exactly 32 random bytes**
  — generate one with `openssl rand -base64 32` (or
  `head -c 32 /dev/urandom | base64`).
- 12-byte random IV per encryption; ciphertext + GCM auth tag stored as
  base64.

Anything else — an unset property, a passphrase, base64 of the wrong
length — fails fast at startup with a descriptive error. There is no
silent fallback to a public dev key, and no SHA-256-of-passphrase
derivation: a leaked database paired with a low-entropy passphrase used
to be brute-forceable in seconds, so passphrase-style secrets are no
longer accepted.

## Data source credentials

Credentials configured under **Admin → Data Sources** — OpenSearch passwords,
S3 access and secret keys, and relational-database usernames and passwords —
are stored using the AES-GCM encryption scheme described in
[Encrypted secrets](#encrypted-secrets). The plaintext is never returned by the UI or logged in the
audit trail; the listing tables show only a status (`Configured`, `Ambient`,
`From URL`, `••••••`). The OpenSearch **username** field is the one
exception: it is stored as plaintext on the document row and visible in raw
Mongo documents. Local-directory sources have no credentials at all — files
are read with the application's process identity. See
[Data sources](admin/data-sources.md) for the per-type field list.

## PII at rest

Document text and the individual PII spans detected inside it are **encrypted
on disk** in the collections that hold them, in MongoDB or SQLite alike. The encryption uses the
same AES-GCM construction described above for Philter API keys, with the same
key (`philter-workspace.crypto.secret`) — there is no separate per-collection key.

The fields encrypted at rest are:

| Collection         | Field            | What it carries                                                       |
|--------------------|------------------|-----------------------------------------------------------------------|
| `documents`        | `originalText`   | Source document text being redacted — the raw PII.                    |
| `documents`        | `redactedText`   | Rendered redacted output persisted at finalize time.                  |
| `documents`        | `failureMessage` | Error messages may quote spans of the source document.                |
| `documents`        | `caseMetadata.caseNumber`, `caseMetadata.filer` | A filing's case number and filer, which can identify the people in a case. Its case type, fee status, and IFP flag are not encrypted. |
| `spans`            | `text`           | The literal PII string detected by Philter (e.g. `alice@example.com`).|
| `document_comments`| `text`           | Reviewer-supplied free text that may reference PII.                   |
| `redaction_certificates` | `documentFilename` | Filenames often carry names or record numbers.          |
| `redaction_certificates` | overturned span text | The literal PII on each overturned decision.             |
| `redaction_certificates` | overturn reason | The reason a reviewer gave for overturning a decision, which can quote PII. |
| `audit_log`        | `details.reason` | A reason a person typed, such as for overturning another reviewer's decision. The entry's other details are not encrypted. |
| `pending_uploads`  | `content`        | The bytes of an uploaded file (text or PDF) waiting in the ingest queue. Removed once the document is redacted; kept, still encrypted, while a failed document remains. |
| `detection_rules`  | `text`           | A value reviewers decided Philter should never, or always, flag. Found by a keyed hash of its lower-cased form (`textKey`), not by the value. |
| `settings`         | `opensearchPassword` | The OpenSearch basic-auth password (the general settings).   |
| `settings`         | `password`       | The SMTP password (the notification settings). Never shown back on Admin → Notifications. |
| `background_jobs`  | `errorMessage`, `failureMessages` | Why an import or export failed, which can quote values from the source, such as a database error naming a row's value. |
| `data_import_log_entries` | `message` | Why one item of an import failed, for the same reason. |

Other fields on these documents (status, timestamps, source attribution,
hashes, lock metadata, span coordinates, reviewer attribution) are stored
unchanged. Encrypting only the body fields keeps the database queryable for
status filters, joins, and audits.

### How the encryption is applied

Encryption and decryption happen at the persistence layer: through Spring Data
Mongo lifecycle callbacks with MongoDB, and around every write and read in the
SQLite store. Application code reads and writes plaintext on
the in-memory entity, and the callbacks transparently encrypt before save and
decrypt after load. The on-disk form of an encrypted field carries an
`enc:v1:` prefix marker followed by the base64-encoded ciphertext (12-byte
random IV concatenated with the GCM ciphertext + 16-byte auth tag), e.g.
`enc:v1:bXlJVi4uLg==`. Uploaded bytes are stored as binary in the same
form without the base64: the `enc:v1:` bytes, the IV, then the ciphertext
and tag.

### Backwards compatibility

The marker prefix lets the read path tell encrypted values from values that
predate the rollout. Any field without the prefix, including an upload's
bytes, is returned to the application as-is, so a database that was populated before this feature was
turned on continues to read normally. The first save of any such row
re-writes the affected field as ciphertext. There is no offline migration —
encryption rolls forward as documents are touched.

### Values that look encrypted, and values that do not decrypt

The prefix only tells a read what to decrypt; a write never trusts it. Every
value is encrypted when it is saved, whatever it starts with, so a comment,
an ingested text, or an upload that starts with `enc:v1:`, or that is
ciphertext copied from a backup, is stored encrypted and reads back exactly
as it was written. Philter Workspace never decrypts a value on behalf of the
person who supplied it.

A stored value that has the prefix but does not decrypt (written under a
different key, tampered with, or a comment that started with `enc:v1:` saved
by an earlier version) reads as `[This value could not be decrypted]`, and the
log names the record type and id, never the value. The rest of the record,
and the list it belongs to, still read. A record holding that placeholder
cannot be saved, so the stored value is never overwritten, and the
placeholder is never copied out: such a document is not finalized, exported,
or delivered (an export counts it as failed), and no detection rule is made
from such a span.

The OpenSearch password is in the general settings, which every section of
**Admin → General** saves. If it does not decrypt, enter it again or clear it
under **Full text search**; until then no section of that page can be saved.

### What is **not** encrypted

The following fields are **not** part of the at-rest encryption boundary:

- **Span location** (`location.characterStart`, `location.characterEnd`,
  page, coordinates). The offsets reveal the position of redactions inside
  the document but contain no PII themselves; keeping them queryable lets
  the review UI render the highlighted regions without an extra decrypt.
- **Reviewer attribution** (`statusChangedBy`, `userEmail`, `approvedBy`,
  audit-log actors). An admin investigation needs these in plaintext to
  attribute decisions; they are not PII for our purposes.
- **Search index**. With full text search on and the default embedded
  backend, document text is indexed in a directory whose files are encrypted
  with AES-256 under a key derived from `philter-workspace.crypto.secret`. With
  the OpenSearch backend, the document content is written to OpenSearch, a store
  **outside** the at-rest encryption boundary documented here. If your deployment
  indexes documents into OpenSearch, configure encryption-at-rest on the
  OpenSearch cluster as well; the application does not double-encrypt
  payloads sent over the indexing path. Full text search is off by default
  for this reason. See [Full text search](admin/full-text-search.md).

### Key management

The same `philter-workspace.crypto.secret` that protects credentials and API keys also
protects PII at rest. Losing the key means **losing access to every
encrypted field** — back it up at the same operational tier as your database
backups. Starting with a different key shows every encrypted field as
`[This value could not be decrypted]`, and records holding one cannot be saved
until the right key is restored. Rotating the key requires re-encrypting every existing row; this is
intentionally out-of-band today (no UI affordance) so that a sloppy rotation
does not leave half the corpus unreadable.

## PII never appears in application logs

Philter Workspace's logging policy is that **document content is never written to a log
file or to standard out**, regardless of log level. The fields covered are
the same ones encrypted at rest in MongoDB:

- Document `originalText` (the source text being redacted) and
  `redactedText` (the rendered output).
- Document `failureMessage` text.
- Span `text` (the literal PII string detected).
- Comment `text`.
- User-supplied search queries (a reviewer searching for an email or phone
  number must not pin that string in the log file).
- Exception text from an import, export, or the ingest queue. A database
  driver or JSON parser error can quote the value it failed on, so these
  failures are logged with the job, its type and source, and the exception's
  class and its causes' classes, with no message and no stack trace at any log
  level. The full reason is kept, encrypted, on the job and its import log,
  where administrators see it on the Jobs page. A failed call to an LLM for
  an explanation or a second opinion is logged the same way, since the error
  can quote the model's reply.

Network call sites that interact with stores carrying this content
(OpenSearch indexing, OpenSearch search, OpenSearch `more_like_this`,
OpenSearch and Elasticsearch ingest) deliberately log only metadata —
status code, body length, document id, batch id, error class — when they
fail. Response bodies and query strings are summarised by length, never
echoed verbatim. Exceptions thrown from those layers carry the same
metadata-only message so a downstream `log.warn(..., e.getMessage())` does
not regress this contract.

If a richer dump is needed for diagnosis, read the **OpenSearch /
Elasticsearch cluster's own logs** rather than Philter Workspace's — those stores
have their own access controls and are part of the same security boundary
documented above. The bootstrap admin notice on first start is the only
deliberate write to standard out, and it carries no document PII — just
the configured admin email and a generated initial password.

## Document content integrity

Every document Philter Workspace ingests — whether through the web upload form or
through `POST /api/v1/ingest` — has its raw content **SHA-512 hashed** at
ingest time and the hash recorded on the document row in the `documents`
collection (field `contentSha512`, lowercase hex). The hash is computed from
the bytes you submitted: UTF-8 bytes for text uploads, the raw file bytes
for PDFs.

The hash is set once at ingest and is never overwritten thereafter. Because
the per-document audit log includes the ingest event with its timestamp and
the actor, you can pair the two to attest "user X ingested a document with
this exact content at this time" — useful for chain-of-custody, deduplication,
and tamper detection against a separately-archived original.

The hash is *not* surfaced in the UI today; query MongoDB directly if you
need it for an out-of-band reconciliation.

## CSRF and CORS

CSRF protection is enabled for HTML form posts and disabled for the
`/api/**` paths. The API surface is split into two categories:

- **Browser-UI endpoints** (`/api/v1/queue`, `/api/v1/batches`,
  `/api/v1/documents/{id}/spans|comments|history|certificate|explain`,
  `/api/v1/spans/**`, `/api/v1/policies/**`, `/api/v1/ollama/**/models`,
  `/api/v1/review/**`) accept the same session cookie the surrounding HTML
  page uses. The in-page JavaScript calls them with `fetch`. Cross-origin
  abuse is prevented by the browser's same-origin policy plus the absence
  of a permissive CORS configuration — Philter Workspace does not expose
  `Access-Control-Allow-Origin` for any other origin, so a malicious page
  cannot read responses from these endpoints even if it happened to send
  the user's session cookie.
- **Programmatic-only endpoints** (`/api/v1/ingest`, `/api/v1/search`,
  `POST /api/v1/documents/{id}/finalize`, `GET /api/v1/documents/{id}/audit`)
  are Bearer-only: the `ApiSessionRejectingFilter` drops session-cookie
  authentication for these paths so they can only be reached with an
  `Authorization: Bearer <api-key>` header. The filter matches the decoded
  path with the same path patterns the request is dispatched by, so a
  percent-encoded form of one of these paths is treated the same. This is the
  canonical defense against a logged-in admin being CSRF-pushed into ingesting
  fake content or exporting redacted data through their browser.

There is no CORS configuration shipped — if you front Philter Workspace with a
different origin, configure CORS in your reverse proxy or extend
`SecurityConfig`.

## Audit visibility

Everything that changes server-side state is captured in the
[audit log](admin/audit-log.md) with the actor's email, IP address (honoring
`X-Forwarded-For` first hop), the resource type and id, and per-action
context. Audit writes never include passwords, API keys, or SMTP passwords.
