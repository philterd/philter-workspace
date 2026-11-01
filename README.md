# Philter Workspace

Philter Workspace is a human-in-the-loop deidentification platform. It runs your
documents through the [Philter / Phileas](https://www.philterd.ai) PII
detector and gives reviewers a fast, focused workflow for confirming or
correcting every detection before the document leaves the redactor.

For full documentation — installation, configuration, user and admin
guides, REST API reference, and more — see
<https://philterd.github.io/philter-workspace>.

Questions, feedback, or want to contribute? [Join the Philterd Slack
workspace](https://philterd.ai/slack/) to connect with other users and
developers.

![Philter Workspace](philter-workspace.png)

## Quick start

`quickstart.sh` builds and starts the development stack in `docker-compose.yaml`, waits
until Philter Workspace is healthy, and gives Philter's `admin` user its first password:

```shell
./quickstart.sh
```

It needs Docker with Compose v2 and `openssl`, and prints the URL and `admin`'s password
when it finishes. The first build takes several minutes. Set these to change what it does:

| Variable                  | Default     | Effect                                                     |
|---------------------------|-------------|------------------------------------------------------------|
| `PHILTER_WORKSPACE_PORT`  | `8080`      | The host port Philter Workspace is published on.           |
| `PHILTER_WORKSPACE_BIND`  | `127.0.0.1` | The host IPv4 address of Philter Workspace and the policy editor. `0.0.0.0` opens them to the network. |
| `ADMIN_PASSWORD`          | generated   | `admin`'s first password, 16 characters to 72 bytes.       |
| `PHILTER_WORKSPACE_STORE` | `sqlite`    | `mongodb` to use MongoDB instead.                          |
| `OPENSEARCH_PORT`, `POLICY_EDITOR_PORT`, `MINIO_PORT`, `MINIO_CONSOLE_PORT`, `POSTGRES_PORT`, `MONGODB_PORT`, `VALKEY_PORT` | `9200`, `8081`, `9000`, `9001`, `5432`, `27017`, `6379` | The host ports of the stack's other services. OpenSearch and MongoDB start only with their Compose profiles. |

Before starting anything, it checks that every host port the stack publishes is free and
names the variable that moves each one in use.

It creates `.env` and generates the stack's keys into it: `PHILTER_WORKSPACE_CRYPTO_SECRET`,
`VALKEY_PASSWORD`, `PHILTER_ENCRYPTION_KEY`, and `PHILTER_BOOTSTRAP_API_KEY`. It can be run
again: it keeps the keys already in `.env` and never replaces a password `admin` already has.
Stop the stack with `docker compose down`, adding `-v` to delete its data.

The development stack is not for anything reachable from a network. It loads demo data,
serves plain HTTP, and its demo services (MinIO, PostgreSQL, MongoDB, OpenSearch) run with
fixed or no credentials, so every port is published on `127.0.0.1` only. On a remote machine,
use an SSH tunnel (`ssh -L 8080:localhost:8080 <host>`). For a deployment, see
`docker-compose.prod.yaml`.

### By hand

**1. Create your `.env` file with the stack's keys.**

```shell
echo "PHILTER_WORKSPACE_CRYPTO_SECRET=$(openssl rand -base64 32)" > .env
echo "VALKEY_PASSWORD=$(openssl rand -hex 24)" >> .env
echo "PHILTER_ENCRYPTION_KEY=$(openssl rand -base64 32)" >> .env
echo "PHILTER_BOOTSTRAP_API_KEY=sk_$(openssl rand -hex 16)" >> .env
```

`PHILTER_WORKSPACE_CRYPTO_SECRET` must be a base64-encoded 32-byte value. Philter Workspace
refuses to start if it is missing, not valid base64, or does not decode to
exactly 32 bytes. Compose refuses to start while any of the four is missing. Philter reads
`PHILTER_BOOTSTRAP_API_KEY` only on its first start, so keep it. `.env.example` lists the
other settings `.env` can hold.

**2. Build the Docker image.**

```shell
docker compose build
```

**3. Start Philter Workspace.**

```shell
docker compose up
```

Philter Workspace will be available at <http://localhost:8080>, on this machine only. People sign in with
their Philter username and password; to sign in as Philter's first
administrator, `admin`, give it a password as
[First sign-in](https://philterd.github.io/philter-workspace/security/#first-sign-in) shows.

Philter Workspace sends documents to the Philter that `docker-compose.yaml` also starts,
set by `PHILTER_WORKSPACE_PHILTER_URL` and `PHILTER_WORKSPACE_PHILTER_API_KEY`. That key is
Philter's bootstrap key, which belongs to an administrator; for a deployment, see `docker-compose.prod.yaml` and
[Configure Philter](https://philterd.github.io/philter-workspace/workflow/#1-configure-philter).

## Building the Docker image

`build-image.sh` builds the image for `linux/amd64` and `linux/arm64`.
`push-image.sh` publishes it to
[`philterd/philter-workspace`](https://hub.docker.com/r/philterd/philter-workspace) on Docker
Hub. Both take a version as their first argument; with none, they use the
Maven project version from `pom.xml`, so an unversioned build is tagged with
what it actually contains.

```shell
./build-image.sh 1.0.0
./push-image.sh 1.0.0
```

`build-image.sh` loads each architecture under its own tag (`1.0.0-amd64`,
`1.0.0-arm64`), so both are available locally to run and test.
`push-image.sh` pushes those two tags and joins them into the `1.0.0` tag
that users pull. It builds nothing, so what is published is what was built
and tested.

Set `ARCHES` to build a single architecture:

```shell
ARCHES=amd64 ./build-image.sh
```

Pass `latest` explicitly to publish under that tag:

```shell
./build-image.sh latest
./push-image.sh latest
```

## Related Philterd open source projects

Philter Workspace is part of a wider family of open source data-privacy tools from
Philterd. The full catalog lives at
<https://www.philterd.ai/open-source-software/>.

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
