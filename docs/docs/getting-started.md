# Getting started

## Prerequisites

- A running MongoDB instance Philter Workspace can reach, or nothing extra with the
  SQLite store (see [Database](reference/database.md))
- A running Valkey instance for sessions
- Nothing extra for full-text search, which is off by default and uses an index
  inside Philter Workspace; an OpenSearch cluster is optional
- A running Philter 4.x that Philter Workspace can call (`docker-compose.yaml`
  starts one for development)

## Configuration

Philter Workspace reads its settings from `application.properties` (or matching
environment variables); see [Configuration](reference/configuration.md) for the
full reference. The settings most operators need to set are:

| Property                                  | Purpose                                                |
| ----------------------------------------- | ------------------------------------------------------ |
| `philter-workspace.store`                           | `mongodb` (default) or `sqlite`. See [Database](reference/database.md). |
| `philter-workspace.sqlite.path`                     | The SQLite database file, with `philter-workspace.store=sqlite` (default `./data/philter-workspace.db`) |
| `spring.mongodb.uri`                      | Connection URI for MongoDB                             |
| `spring.data.mongodb.database`            | Database name (default: `philter-workspace`)                     |
| `philter-workspace.philter.url`                     | **Required.** The URL of Philter Workspace's Philter (`PHILTER_WORKSPACE_PHILTER_URL`), such as `http://philter:8080` |
| `philter-workspace.philter.api-key`                 | **Required.** The Philter API key for background work such as ingest (`PHILTER_WORKSPACE_PHILTER_API_KEY`), for a Philter user who is not an administrator. Set it through the environment. |
| `philter-workspace.crypto.secret`                   | **Required.** Base64-encoded 32 random bytes used as the AES-256 key for encrypting data-source credentials and other stored secrets at rest. Generate with `openssl rand -base64 32`. Philter Workspace refuses to start if this is unset, not valid base64, or doesn't decode to exactly 32 bytes. |
| `philter-workspace.ingest-queue.poll-millis`        | Worker poll interval (default `5000`)                  |
| `philter-workspace.demo-data.enabled`               | Load sample files at startup if collections are empty  |
| `philter-workspace.demo-data.directory`             | Directory of files to seed (default: `sample-files`)   |

Philter Workspace needs a running Philter 4.x; it does not detect PII itself.
See [Configure Philter](workflow.md#1-configure-philter).

Full-text search is off until an administrator turns it on under
**Admin → General → Full text search**. By default its index is kept inside
Philter Workspace, encrypted, in `PHILTER_WORKSPACE_SEARCH_INDEX_DIR`; set
`PHILTER_WORKSPACE_SEARCH_BACKEND=opensearch` to use an OpenSearch cluster,
configured on the same page. See [Full text search](admin/full-text-search.md).

## First run

Philter Workspace creates no accounts: people sign in with their Philter username and password, and the
first administrator is Philter's `admin`. Give it a password with `PHILTER_BOOTSTRAP_API_KEY`, then
sign in at `/login` as `admin`; see [First sign-in](security.md#first-sign-in).

For development, `quickstart.sh` in the repository root does both: it generates the stack's
keys into `.env` (Philter Workspace's encryption key, the Valkey password, and Philter's
encryption and bootstrap keys), starts the `docker-compose.yaml` stack, waits until Philter
Workspace is healthy, and gives `admin` its first password, printing it when it finishes.
`PHILTER_WORKSPACE_PORT` changes the host port from 8080, and `ADMIN_PASSWORD` sets the
password instead of generating one.

The development stack is not for anything reachable from a network. It loads demo data,
serves plain HTTP, and its demo services (MinIO, PostgreSQL, MongoDB, OpenSearch) run with
fixed or no credentials, so it publishes every port on `127.0.0.1` only. Reach it on a remote
machine through an SSH tunnel, for example `ssh -L 8080:localhost:8080 <host>`.
`PHILTER_WORKSPACE_BIND=0.0.0.0` opens Philter Workspace and the policy editor to the network
instead; `quickstart.sh` warns when it is set. For a deployment, use `docker-compose.prod.yaml`.

On the first start with **empty `batches` / `documents` / `spans` collections and demo data
enabled**, Philter Workspace loads any files under the configured demo directory into a sample batch and runs
them through the redactor so the queue isn't empty.

## What you'll see after sign-in

| Area               | URL                       | Who can reach it                |
| ------------------ | ------------------------- | ------------------------------- |
| Dashboard          | `/`                       | All authenticated users         |
| Inbox              | `/inbox`                  | All authenticated users         |
| Batches            | `/batches`                | All authenticated users         |
| Document Queue       | `/queue`                  | All authenticated users         |
| Search             | `/search`                 | All authenticated users         |
| Add Documents      | `/upload`                 | All authenticated users         |
| Background Jobs    | `/jobs`                   | All authenticated users         |
| Personal settings  | `/settings`               | All authenticated users         |
| Try a policy       | `/try`                    | All authenticated users         |
| Documentation      | `/docs/`                  | Public (no login required)      |
| Ingest Queue       | `/admin/ingest-queue`     | Admin role only                 |
| Approval Rules     | `/admin/rules`            | Admin role only                 |
| Data Sources       | `/admin/data-sources`     | Admin role only                 |
| Reports            | `/reporting`              | Admin role only                 |
| Policies           | `/policies`               | Admin role; auditors read-only  |
| Admin settings     | `/admin/users` …          | Admin role only                 |

Group membership scopes what reviewers see. By default reviewers only see
batches and documents in groups they belong to; admins can toggle a "Limit to
my groups" checkbox to switch between scoped and global views.

## Upgrading: data source groups and Local Directory roots

This version scopes data sources to groups and confines Local Directory paths:

- **Data sources start as administrators only.** Until an administrator sets a
  source's groups under **Admin → Data Sources**, only administrators can import
  from it. Team leads of a chosen group can then import into its batches;
  reviewers no longer import. See
  [Who can import from a source](admin/data-sources.md#who-can-import-from-a-source).
- **Local Directory needs allowed roots.** Set
  `PHILTER_WORKSPACE_LOCAL_DIRECTORY_ALLOWED_ROOTS` to the folders Local Directory
  sources and destinations use, or they are refused, including existing ones.

## Upgrading from Arbiter

Philter Workspace was named Arbiter. Moving an Arbiter deployment to Philter Workspace changes the
following. The old names are not read.

- **Environment variables and properties.** Every `ARBITER_*` variable is now
  `PHILTER_WORKSPACE_*` (for example `PHILTER_WORKSPACE_CRYPTO_SECRET` and
  `PHILTER_WORKSPACE_PHILTER_URL`), and every `arbiter.*` property is now `philter-workspace.*`.
  Keep the crypto secret's value: it decrypts the data already stored.
- **Docker image.** `philterd/philter-workspace` replaces `philterd/arbiter`.
- **Compose.** The service is `philter-workspace`, its container `philter-workspace-app`, its network
  `philter-workspace`, and its data volume `philter-workspace-data`. The SQLite file is
  `/app/data/philter-workspace.db` and the MongoDB database is `philter-workspace`. To keep existing
  data, stop Arbiter, then copy its SQLite files (the database and any `-wal` and `-shm` files
  beside it) into the new volume under the new name (`docker volume ls` shows both volumes' names),
  or for MongoDB set `SPRING_MONGODB_URI` to the old database
  (`mongodb://mongodb:27017/arbiter`):

    ```sh
    docker run --rm -v <old>_arbiter-data:/from -v <new>_philter-workspace-data:/to alpine \
      sh -c 'for f in /from/arbiter.db*; do cp "$f" "/to/philter-workspace.db${f#/from/arbiter.db}"; done'
    ```

- **Philter Workspace URL.** The URL saved under **Admin → General** is not carried over; save it
  again.
- **Full text search is off by default.** It indexes each document's original, unredacted
  text, so an administrator now turns it on under **Admin → General → Full text search**. An
  Arbiter install that saved its general settings keeps the value it saved; one that never
  saved them starts with full text search off.
- **Full text search index.** The default index name is `philter-workspace-documents`. A deployment that
  used the old default, `arbiter-documents`, sets that name under **Admin → General → Full text
  search** to keep its index.
- **Policies kept in Arbiter.** Arbiter's own copies of redaction policies are no longer read;
  the Policies page manages Philter's policies instead (see
  [Create PII redaction policies](workflow.md#2-create-pii-redaction-policies)). Those copies
  never affected redaction. To keep one, read it from the
  old database and create it on the Policies page: on SQLite, `SELECT doc FROM policies`, where
  each row is JSON with the policy's `name` and its JSON as `content`; on MongoDB, the `policies`
  collection.
- **Unchanged.** Stored PII stays readable with the same crypto secret, and the audit log's hash
  chain continues.
- **Documentation** is at <https://philterd.github.io/philter-workspace/>.

## Next steps

- New reviewer? Read [Concepts](concepts.md) and the User Guide.
- New admin? Read [Concepts](concepts.md) then jump to
  [Users and Groups](admin/users-and-groups.md).
- Integrating from another system? See [REST API](reference/api.md).
