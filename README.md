# API Builder (microservices)

Turn a database query into a REST endpoint without writing code or SQL. You compose a query in a web page, publish it as `GET /api/{name}`, and callers fill in the parameters you chose.

Every publish is saved as a version, so you can compare changes, keep calling old versions, and roll back. A built-in cURL builder generates ready-to-run commands for any API.

The project is split into independently deployable Spring Boot services that talk to each other over HTTP.

## Architecture

```text
                         +-----------------------------+
  browser / curl  -----> |  gateway  :8080             |  serves the UI and is the
                         |  (reverse proxy + web UI)   |  only public entry point
                         +--------------+--------------+
                        /builder/**     |     /api/**
                 +----------------------+----------------------+
                 v                                             v
   +--------------------------+                 +--------------------------+
   | builder-service  :8081   |  /internal      | runtime-service  :8082   |
   | compose, version, store, | <-------------- | serve published APIs:    |
   | preview, diff, curl,     |  definition JSON| compile + run the query  |
   | time machine             |                 |                          |
   +------------+-------------+                 +------------+-------------+
                | metadata (BUILDER schema)                  | reads target schema
                +---------------------+------------------------+
                                      v
                           database (H2 by default)
```

| Module | Port | Responsibility |
|---|---|---|
| **gateway** | 8080 | Serves the single-page UI and reverse-proxies `/builder/**` to the builder and `/api/**` to the runtime. The browser only ever sees one origin. |
| **builder-service** | 8081 | Composes, validates, versions and stores APIs. Owns the Time Machine, diff, preview and cURL builder. Serves each definition to the runtime over an internal endpoint. |
| **runtime-service** | 8082 | Serves every published API. It holds no definitions itself: it asks the builder for the definition behind a slug, compiles it to SQL and runs it. |
| **shared** | — | A library (not a service) with the query model, the SQL compiler, schema introspection and the row runner, reused by the builder and the runtime. |

Why this shape: the builder is write-heavy and stateful (it owns the history), while the runtime is read-heavy and stateless and can be scaled on its own. Because the runtime fetches definitions over HTTP instead of reading the builder's tables, the two stay loosely coupled and the builder's metadata is never exposed through a published API.

Built with Spring Boot 4, Spring JDBC, Spring MVC `RestClient` and an H2 database.

## Features

- **Query builder:** pick a table, columns, filters, sort order and row limit, then preview the generated SQL and rows before publishing.
- **Caller parameters:** a filter can use a fixed value or a parameter the caller supplies, for example `/api/high-balance?minimum=1000`. Optional parameters drop their filter when omitted.
- **Time Machine:** every publish creates an immutable version. Compare any two versions, view old definitions, and roll back.
- **Pinned versions:** `/api/{name}/v/{n}` keeps serving version `n` after newer versions are published.
- **cURL builder:** fill in parameter values and get a copyable command for bash or PowerShell, optionally pinned to a version.
- **Safe by construction:** callers never send SQL. Table and column names must exist in the live schema and every value is bound as a statement parameter.

## Quick start

Requirements: JDK 17 or newer. Maven is not needed; the project includes the Maven wrapper.

Build everything once:

```powershell
.\mvnw.cmd -DskipTests package
```

Then start the three services, each in its own terminal (on macOS or Linux use `java -jar` the same way):

```powershell
java -jar builder-service\target\builder-service-0.0.1-SNAPSHOT.jar   # :8081, start this first
java -jar runtime-service\target\runtime-service-0.0.1-SNAPSHOT.jar   # :8082
java -jar gateway\target\gateway-0.0.1-SNAPSHOT.jar                   # :8080
```

Start the **builder first** - it creates and seeds the database. Then open <http://127.0.0.1:8080/>.

The database is seeded with two sample tables, `ACCOUNTS` and `TRANSACTIONS`, so you can try it straight away:

1. On the **Build** tab, name your API `high-balance`, keep the columns you want, and add a filter `BALANCE > ` caller parameter `minimum`.
2. Enter a value for `minimum`, click **Preview**, then **Publish API**.
3. Call it through the gateway: `curl "http://127.0.0.1:8080/api/high-balance?minimum=1000"`.

> During development you can also run a single service with `.\mvnw.cmd -pl builder-service spring-boot:run` (repeat for `runtime-service` and `gateway`).

## Using the web page

| Tab | What it does |
|---|---|
| **Build** | Compose, preview and publish an API. Selecting an existing API loads its current version; publishing again creates a new version. |
| **Time Machine** | Lists all versions with their notes. Compare two versions, view a version's definition, or roll back to it. |
| **cURL Builder** | Generates a command for the selected API and version, and can send the request and show the response. |

Publishing a version identical to the current one is refused, so history only contains real changes. Rolling back publishes the old version's contents as a new latest version; nothing is rewritten.

## Published API (runtime-service, via the gateway)

```http
GET /api/{name}
GET /api/{name}/v/{version}
```

Query parameters are the ones defined by the API's filters. Unknown parameters, missing required parameters and values of the wrong type return `400`.

```json
{
  "api": "high-balance",
  "version": 3,
  "count": 2,
  "data": [
    { "OWNER": "Ishita Rao", "CITY": "Bengaluru", "BALANCE": 127500.00 },
    { "OWNER": "Meera Nair", "CITY": "Kochi", "BALANCE": 48900.75 }
  ]
}
```

Dates and timestamps are returned exactly as stored, without time-zone shifting. Errors use the standard problem-details format (`status`, `title`, `detail`); when the runtime cannot reach the builder it returns `503`.

## Management API (builder-service, via the gateway)

The web page is a client of these endpoints, so you can script them too.

| Method and path | Purpose |
|---|---|
| `GET /builder/schema` | Tables and columns that can be queried |
| `POST /builder/preview` | Run a definition without publishing: `{ "definition": {...}, "params": {...} }` |
| `GET /builder/apis` | List published APIs |
| `POST /builder/apis` | Create an API (version 1): `{ "slug", "description", "definition", "note" }` |
| `GET /builder/apis/{name}` | Current version of an API |
| `PUT /builder/apis/{name}` | Publish a new version: `{ "description", "definition", "note" }` |
| `DELETE /builder/apis/{name}` | Delete the API and all its versions |
| `GET /builder/apis/{name}/versions` | All versions, newest first |
| `GET /builder/apis/{name}/versions/{n}` | One version |
| `POST /builder/apis/{name}/versions/{n}/rollback` | Publish version `n` again as the newest version |
| `GET /builder/apis/{name}/diff?from=1&to=2` | What changed between two versions |
| `POST /builder/apis/{name}/curl` | Generate a command: `{ "version", "params", "shell" }` where `shell` is `BASH` or `POWERSHELL` |

The builder also exposes `GET /internal/apis/{name}/resolve` for the runtime service. It is **not** routed through the gateway and is not meant for public use.

### Query definition

```json
{
  "table": "ACCOUNTS",
  "columns": ["OWNER", "CITY", "BALANCE"],
  "filters": [
    { "column": "BALANCE", "operator": "GT", "param": "minimum" },
    { "column": "CITY", "operator": "EQ", "param": "city", "required": false },
    { "column": "ACTIVE", "operator": "EQ", "value": "true" }
  ],
  "sort": [{ "column": "BALANCE", "direction": "DESC" }],
  "limit": 100
}
```

- **`operator`:** `EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE`, `CONTAINS`, `STARTS_WITH`. The last two work on text columns only and are case-insensitive.
- **`value` or `param`:** each filter has exactly one. A parameter is required unless you set `"required": false`.
- **Parameter names:** a letter followed by up to 49 letters, digits or underscores.
- **`limit`:** defaults to 100, maximum 1000.
- **Names:** an API name is 1-63 characters of lowercase letters, digits and hyphens.
- **Filter semantics:** all filters must match.

Table and column names are matched case-insensitively and stored in their canonical form.

## Configuration

Each service has its own `src/main/resources/application.properties`.

| Service | Property | Default | Notes |
|---|---|---|---|
| gateway | `server.port` | `8080` | Public entry point. Bind `server.address` to `0.0.0.0` to expose it on your network (there is no authentication yet, so keep it private or put TLS/auth in front). |
| gateway | `gateway.builder-url` | `http://127.0.0.1:8081` | Where `/builder/**` is routed. |
| gateway | `gateway.runtime-url` | `http://127.0.0.1:8082` | Where `/api/**` is routed. |
| builder | `spring.datasource.url` | `jdbc:h2:file:~/.api-builder/apibuilder;AUTO_SERVER=TRUE` | Metadata and (for the demo) the sample tables. `AUTO_SERVER` lets the runtime share the same file. |
| builder | `app.target-schema` | `PUBLIC` | The schema published APIs may read. The builder's own `BUILDER` schema is never exposed. |
| builder | `app.public-base-url` | `http://localhost:8080` | The public URL that generated curl commands point at (the gateway). |
| runtime | `spring.datasource.url` | `jdbc:h2:file:~/.api-builder/apibuilder;AUTO_SERVER=TRUE` | Same database as the builder; the runtime only ever reads the target schema. |
| runtime | `builder.base-url` | `http://127.0.0.1:8081` | Where the runtime resolves definitions. |

### Pointing at your own database

1. Add the JDBC driver (for example PostgreSQL or MySQL) to `builder-service/pom.xml` and `runtime-service/pom.xml`.
2. Set `spring.datasource.url`, `spring.datasource.username` and `spring.datasource.password` on **both** the builder and the runtime to your database. Use a **read-only** account for the runtime.
3. Set `app.target-schema` to the schema whose tables you want to expose. Every table there becomes available to the builder.
4. Keep the builder's metadata separate. The `BUILDER` schema (tables `API_ENDPOINT` and `API_VERSION` from [builder-schema.sql](builder-service/src/main/resources/builder-schema.sql)) must exist for the builder; it is never reachable through a published API.

For the H2 demo, the database lives in `~/.api-builder/`. Delete that folder to reset.

## Project layout

```text
pom.xml                         Parent (aggregator) build
shared/                         Library reused by the builder and runtime
  .../shared/QueryDefinition.java   Structured query stored as JSON
  .../shared/QueryCompiler.java     Validates a definition and compiles it to parameterized SQL
  .../shared/SchemaService.java     Reads the queryable tables and column types
  .../shared/QueryRunner.java       Executes a compiled query and maps rows
  .../shared/ApiErrors.java         Problem-detail helpers
  resources/sample-schema.sql, sample-data.sql
builder-service/                Management + storage + time machine (:8081)
  .../builder/ApiBuilderService.java      Create, publish, roll back, preview, diff, resolve
  .../builder/ApiRepository.java          Versioned storage (insert-only history)
  .../builder/VersionDiff.java            Compares two versions
  .../builder/CurlBuilder.java            Builds curl commands
  .../builder/BuilderController.java      /builder/** endpoints
  .../builder/InternalApiController.java  /internal resolve endpoint for the runtime
  resources/builder-schema.sql
runtime-service/                Serves published APIs (:8082)
  .../runtime/BuilderClient.java          Resolves definitions from the builder over HTTP
  .../runtime/RuntimeService.java         Compile + run
  .../runtime/PublishedApiController.java  /api/** endpoints
gateway/                        Reverse proxy + UI (:8080)
  .../gateway/ProxyController.java         Forwards /builder/** and /api/**
  resources/static/index.html             The web page
```

## Tests

```powershell
.\mvnw.cmd test
```

- **builder-service** tests cover preview, injection safety, the time machine (versions, diff, rollback), the internal resolve endpoint and the cURL builder.
- **runtime-service** tests mock the builder client and verify parameter handling, injection safety, date round-tripping and not-found propagation.

All tests use an in-memory H2 database, so they never touch the demo database in `~/.api-builder/`.

## Limitations

- No authentication or per-API permissions. The services bind to localhost for this reason.
- One table per API; joins and aggregates are not supported.
- Versions record when they were published and an optional note, but not who published them.
- Results are limited to 1000 rows per request, with no pagination.
- No field-level encryption yet.
