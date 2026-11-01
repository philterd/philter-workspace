# Developer guide

Engineering-side notes that complement the user-facing [README](README.md)
and the in-app docs under [`docs/docs/index.md`](docs/docs/index.md).

## Modules

Three Maven modules, dependency order top-to-bottom:

| Module             | Owns                                                                  |
| ------------------ | --------------------------------------------------------------------- |
| `philter-workspace-domain`   | POJOs: `model/`, `dto/`, `util/`, `core/model/`, `philter/`           |
| `philter-workspace-platform` | Spring services, repositories, and the MongoDB and SQLite stores. No controllers |
| `philter-workspace-webapp`   | Spring Boot entry point, MVC + REST controllers, security, templates  |

Module boundaries are organisational, not visibility — `@ComponentScan`
covers `ai.philterd.workspace` regardless of where a class compiles from.

## Build, run, test

```sh
mvn package                 # full reactor build
mvn test                    # all modules
mvn verify                  # also the *IT integration tests; needs Docker
docker compose -f docker-compose.yaml up --build   # full stack: OpenSearch + ES + Valkey + app on :8080, SQLite store
PHILTER_WORKSPACE_STORE=mongodb docker compose -f docker-compose.yaml --profile mongodb up --build   # same, on MongoDB
./build-image.sh            # multi-arch (amd64 + arm64) image, tagged with the pom version
./push-image.sh             # publish it to philterd/philter-workspace, by hand
```

`mvn verify` runs the integration tests (`*IT`, with Maven Failsafe) as well as
the unit tests. `PhilterSignInIT` starts the real Philter Workspace on SQLite with in-memory
sessions against a `philterd/philter:4.0.0` container, started with
Testcontainers, and covers sign-in through Philter, MFA, forced password change,
expiry, revocation, sign-out, the Users page, Admin → Philter's key check, and
redaction through that Philter. It takes about a minute and a
half, most of it waiting for Philter's one-minute idle timeout. The Philter image
is amd64-only, so on an ARM machine it runs under emulation and is slower.

The store behaviour both databases must share is one suite, `StoreContract`
(`philter-workspace-platform`, under `store/`): derived queries, paging and
sorting, unique and partial indexes, `@Version` conflicts, concurrent claims,
document locks, the audit queries, and encryption at rest.
`SqliteStoreContractTest` runs it on a SQLite file in `mvn test`, and
`MongoStoreContractIT` runs it on a real MongoDB server (`mongo:8.2.12`, started
with Testcontainers) in `mvn verify`. `MongoStoreStartupIT` starts the whole
application with `philter-workspace.store=mongodb` against the same image. A
new store behaviour gets a test in `StoreContract`, not in one store's tests.
Without Docker the MongoDB tests are skipped with a message, except when `CI`
is `true`, where they fail.

`build-image.sh` loads `philterd/philter-workspace:<version>-amd64` and
`-arm64` locally; `push-image.sh` pushes those two and joins them into the
plain `<version>` tag in the registry. Both take a version argument; with
none they read the Maven project version from the root `pom.xml`, so the
tag matches what the jar reports at `/api/health`. Nothing publishes an
image automatically: run `push-image.sh` yourself from a machine holding the
Docker Hub credential. Set `ARCHES=amd64` to build just one architecture.

Running the webapp from the IDE needs Valkey reachable, plus MongoDB unless
you set `PHILTER_WORKSPACE_STORE=sqlite`. Compose reads `.env` (run
`./quickstart.sh` once, or create it as the README shows), publishes both on
`127.0.0.1`, and starts Valkey with a password, so set
`SPRING_DATA_REDIS_PASSWORD` to the `VALKEY_PASSWORD` in `.env`:

```sh
docker compose -f docker-compose.yaml up -d valkey
# then run ai.philterd.workspace.webapp.PhilterWorkspaceApplication with PHILTER_WORKSPACE_STORE=sqlite,
# or start MongoDB too and keep the default store:
docker compose -f docker-compose.yaml --profile mongodb up -d mongodb valkey
```

Defaults in `philter-workspace-webapp/src/main/resources/application.properties`
point at `localhost`; the only env vars worth knowing about are
`PHILTER_WORKSPACE_STORE`, `SPRING_MONGODB_URI`, `SPRING_DATA_REDIS_HOST`/`_PORT`/`_PASSWORD`, and
**`philter-workspace.crypto.secret`** (now required: base64 of 32 random bytes,
e.g. `openssl rand -base64 32`; Philter Workspace refuses to start if it isn't
set or has the wrong shape). The OpenSearch endpoint used for
full-text search is configured at runtime under
**Admin → Settings → Full text search** rather than via an env var.

## Tests

JUnit 5 + Mockito. Mostly unit-style; `@WebMvcTest` slices for the
security/filter integration cases (`AuthorizationIntegrationTest`,
`RedactionControllerTest`).

A few patterns that bite:

- **`@WebMvcTest` slices must `@MockitoBean` every Mongo repository in
  the app**, not just the ones the controller calls. With
  `auto-index-creation=true`, Spring tries to create every repo's
  indexes on context load and fails if one is unmocked. Copy the list
  from `AuthorizationIntegrationTest`.
- **Repositories must work on both stores.** They extend
  `WorkspaceRepository` and declare derived queries only; a MongoDB
  `@Query` string is refused by the SQLite store. Anything a derived query
  can't express (ranges on one field, atomic status flips, partial updates)
  goes in a store interface under `store/` with a MongoDB implementation in
  `store/mongo` and a SQLite one in `store/sqlite`. `StoreContract` creates
  every repository on both stores, so an unsupported derived query fails the
  build.
- **Atomic store updates leave `@Version` alone.** Lock changes, claims, and
  requeues do not change a document's version; only saving the entity does.
  Spring Data adds a version increment to any MongoDB update that does not
  touch the version field, so the MongoDB stores pass their updates through
  `MongoDocumentLockService.keepVersion`.
- **The embedded Phileas matches Philter's.** Philter Workspace uses Phileas only
  to draw the redaction boxes on a reviewed PDF (`RedactionServiceImpl.getRedactedPdf`),
  so `phileas.version` is the version the Philter image in `PhilterSignInIT`
  bundles, and that test fails when they differ. Reviewed spans go to Phileas as
  the policy's bounding boxes: Phileas matches spans passed as spans to text by a
  line hash from its own detection, so they would cover nothing.
- **Unique fields are unique among records that have a value.** MongoDB's
  unique `@Indexed` fields are `sparse = true`, matching SQLite, where a
  unique index never counts a missing value. An empty string is a value.
- **Changing an index definition needs a new index name.** Both stores read
  `@Indexed` and `@CompoundIndex`, and the SQLite store creates indexes with
  `CREATE INDEX IF NOT EXISTS`, so an existing index keeps its old
  definition until it is renamed (or dropped by hand).
- **Tests read the real `application.properties`.** Test-only overrides live in
  `philter-workspace-webapp/src/test/resources/config/application.properties`,
  which Spring Boot reads on top of `src/main/resources/application.properties`.
  Do not add a test `application.properties` at the classpath root: it would
  replace the main file, and the tests would stop exercising production settings.
- **Spring Boot 4 specifics.** Slice tests mock beans with `@MockitoBean`
  (`@MockBean` is gone), and `@WebMvcTest` comes from `spring-boot-webmvc-test`;
  security and Thymeleaf join the slice through their own test starters. Boot's
  `HealthDescriptor` is sealed, so tests build a real one with `TestHealth`.
  Auto-configuration classes moved into per-technology modules, so a name spelled
  out as a string (`StoreEnvironmentPostProcessor`, `spring.autoconfigure.exclude`)
  must use the Boot 4 name, or Boot either ignores it or refuses to start.
- **Jackson 3.** JSON code uses `tools.jackson` (annotations stay in
  `com.fasterxml.jackson.annotation`), and its exceptions are unchecked
  (`JacksonException`). Mappers whose output is stored or exported (the SQLite
  store, the exports, sidecars, the search index) are built with
  `JsonMapper.builderWithJackson2Defaults()`, and the REST API sets
  `spring.jackson.use-jackson2-defaults=true`, so their JSON did not change in the
  move to Jackson 3.
- For executor-driven verifications, prefer
  `verify(mock, timeout(2000)).method(...)` over `Thread.sleep`.

## Vulnerability scan

`.github/workflows/security.yml` builds the image and scans it with Trivy on every
push and pull request, and weekly. It fails on a high or critical vulnerability
that has an upstream fix, in the operating system packages or in any library in the
application jar. To run the same scan locally:

```sh
docker build -t philter-workspace:scan .
trivy image --scanners vuln --ignore-unfixed --severity HIGH,CRITICAL philter-workspace:scan
```

Suppress a finding that cannot be acted on in `.trivyignore`, with a reason and a
date to revisit.

## Architectural patterns to match

When adding similar functionality, follow the existing patterns —
they're load-bearing.

- **Atomic claims** for shared queues: see `DocumentQueueStore` and
  `BackgroundJobStore`. A single conditional update flips a status field
  (`findAndModify` on MongoDB, `UPDATE ... RETURNING` on SQLite), so
  concurrent workers never both win.
- **Partial unique indexes** for "at most one in-flight" rules: see
  `BackgroundJob`'s `uniq_running_data_import_per_batch_v2`. Race-collide
  on the index, catch `DuplicateKeyException`, treat as "someone else
  got there first."
- **AES-GCM at-rest encryption** for credentials via `SymmetricCipher`.
  Store ciphertext on the entity, log only a `passwordSet` /
  `credentialsSet` boolean, render `Configured` / `Ambient` /
  `••••••` in the UI. Plaintext never leaves memory.
- **Audit-log every admin write path** through `AuditLogService.log(...)`.
  The action-name canon is in
  [`docs/docs/admin/audit-log.md`](docs/docs/admin/audit-log.md).

## Shared fragments for Philter pages

Pages that list and change items kept in Philter (policies, contexts,
lists, legal holds, ledgers, keys, webhooks, the audit log, settings) build
on fragments under `templates/fragments/` and helpers in
`ai.philterd.workspace.webapp.ui`. The Users page
(`PhilterUsersController`, `admin-philter-users.html`) uses all of the list
and confirm fragments, and `PhilterFragmentsWebTest` renders every fragment.

**The list state** is a `ListView`, read from the request. It reads
`offset`, `limit` (25 by default, at most Philter's 100), `sort` (one of
the fields you allow), `order`, `q`, and the filters you name, and builds
every link so the search, filters, owner, and sort survive paging.

```java
final ListView query = ListView.of(request, "/policies", Set.of("name", "created"), "name",
        List.of("status"))
        .withOwner(OwnerFilter.from(authentication, request.getParameter("owner"),
                request.getParameter("all")));
final ListOptions options = query.options();          // offset, limit, sort, order, search
final OwnerFilter owner = query.owner();
final Page<PolicySummary> page = owner.everyone()
        ? client.listPoliciesAcrossUsers(options)
        : client.listPolicies(owner.username(), options); // null is the caller's own
model.addAttribute("list", query.withTotal(page.getTotal()));
model.addAttribute("policies", page.getItems());
```

**Paged table.** Pass the header cells and rows as markup. The markup is
evaluated inside the fragment, so it sees the fragment's parameters
(`list`, `tableHead`, `tableRows`, `emptyText`) in place of model
attributes with those names.

```html
<div th:replace="~{fragments/list :: paged-table(${list}, ~{::policy-head}, ~{::policy-rows}, 'No policies yet.')}">
    <th:block th:ref="policy-head">
        <th th:replace="~{fragments/list :: sort-header(${list}, 'Name', 'name')}"></th>
        <th th:replace="~{fragments/list :: plain-header('Description')}"></th>
    </th:block>
    <th:block th:ref="policy-rows">
        <tr th:each="p : ${policies}"><td th:text="${p.name}"></td><td th:text="${p.description}"></td></tr>
    </th:block>
</div>
```

It shows an empty state (with a Clear link when a search or filter is
set), a "past the end" state, and the pager (`fragments/list :: pager`).

**Filter bar and owner filter.** The filter bar is a GET form with the
search box, your filter controls (or `~{}` for none), and, for
administrators, the owner filter: a username, or "Every user's". Others
never see it, and `OwnerFilter.from` ignores their `owner` and `all`
parameters, so the server enforces it too. For a list Philter gives one
owner at a time (deleted policies, for example), `OwnerFilter.fromOneUser`
leaves out "Every user's".

```html
<form th:replace="~{fragments/list :: filter-bar(${list}, ~{::user-filters})}">
    <th:block th:ref="user-filters">
        <select name="role">
            <option value="">Any</option>
            <option value="admin" th:selected="${list.filter('role') == 'admin'}">Administrator</option>
        </select>
    </th:block>
</form>
```

Name the filter in `ListView.of`, then pass only values Philter accepts:

```java
if ("admin".equals(query.filter("role")) || "user".equals(query.filter("role"))) {
    options.role(query.filter("role"));
}
```

**Form errors.** Turn a failed Philter call into `FormErrors`, with your
own messages for the reasons and fields you expect. Philter's message text
is never shown or logged, since it can repeat submitted text. A change
since the form was read (`policy_changed`, `changed_concurrently`,
`redact_list_changed`, or 412) asks the person to reload. `FormErrors` is
serializable, so it can be a flash attribute.

```java
} catch (Exception e) {
    flash.addFlashAttribute("errors", FormErrors.from(e,
            Map.of("policy_exists", "You already have a policy with that name."),
            Map.of("name", "Choose another name.")));
}
```

```html
<div th:replace="~{fragments/forms :: errors(${errors})}"></div>
<input id="name" name="name" aria-describedby="name-error">
<p th:replace="~{fragments/forms :: field-error(${errors}, 'name')}"></p>
```

**Confirm dialog.** Build a `Confirmation` once and use it for the dialog
and for the page a browser without JavaScript opens instead: a GET handler
at its `pageUrl` that adds it as `confirmation` and returns `confirm`.

```html
<th:block th:replace="~{fragments/confirm :: confirm(|delete-${s.index}|, ${row.deletion}, 'Delete', 'text-red-600')}"></th:block>
```

```java
@GetMapping("/policies/{name}/confirm-delete")
public String confirmDelete(@PathVariable final String name, final Model model) {
    model.addAttribute("confirmation", deletion(name));
    return "confirm";
}
```

**JSON editor.** A monospaced text area. Check the text with
`JsonInput.objectError` before sending it to Philter and pass the message
as `error`; with JavaScript on, it is also checked as it is typed. Neither
message quotes the text.

```html
<div th:replace="~{fragments/forms :: json-editor('policy', 'policy', ${policyJson}, ${policyError})}"></div>
```

**Generated secret.** A `<button type="button" data-generate-secret="field-id" hidden>`
fills that field with a random 32-character secret when clicked. The script
shows the button, so without JavaScript it stays hidden and the person types
a secret.

**Scripts.** A page that uses the confirm, JSON editor, or generated secret
fragments includes the shared script once, at the bottom:

```html
<script th:replace="~{fragments/scripts :: philter-ui}"></script>
```

## Conventions

- `final` on parameters and locals.
- Comments on hidden invariants only — no restating-the-code.
- Tailwind utility classes in templates; avoid custom CSS.
- Plain JS in templates, inline at the bottom; no frameworks. Every
  `<script>` carries `th:nonce="${cspNonce}"`, and nothing uses inline
  event handlers (`onclick`, `onsubmit`): the CSP refuses them, so bind
  with `addEventListener`.
- Don't add features beyond what the task requires.

## Documentation

The mkdocs-material site under `docs/` is bundled into the jar at
`classpath:/static/docs/` and served at `/docs/`. Build locally with
`mkdocs build --strict` (the Docker `docs` stage runs the same
command) or preview with `mkdocs serve`.

Update the docs alongside the code. The strict build catches broken
cross-references.
