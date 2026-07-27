# Liquibase → MariaDB Cloud (SkySQL serverless) CI/CD

## Introduction

This project manages the schema of a **MariaDB Cloud (SkySQL serverless)** database as version-controlled code using **Liquibase**. Database changes (tables, foreign keys, seed data, a stored procedure) are authored as XML changesets under [changelog/](changelog/) and orchestrated by [rootChangeLog.xml](rootChangeLog.xml), so every change is reviewable, repeatable, and rollback-aware. Deployment is fully automated through an **Azure DevOps CI/CD pipeline** ([azure-pipelines.yml](azure-pipelines.yml)): connection details are injected at runtime from a Variable Group into a tokenized [liquibase.properties](liquibase.properties), changes are validated and previewed as SQL first, and the actual deployment is protected by a gated approval. Authentication uses a database username/password over TLS — **no credentials are ever stored in the repository**.

## Pre-requisites / Manual Steps (before creating the pipeline from azure-pipelines.yml)

These must be completed in order for the pipeline to execute successfully and deploy to the target environment with gated approval.

### A. MariaDB Cloud — target environment

1. **SkySQL serverless service + database exist** — e.g. host `<your-server>.db2.skysql.com`, port `4048`, database `<your-database>`. (SkySQL serverless listens on a **custom port** — commonly `4048` — not the default `3306`. MariaDB does **not** allow `.` in database names.) The variable group below carries the actual values; they never appear in this repo.
2. **Database user + privileges** — create/confirm a user for the pipeline and grant it enough to run DDL, because the changelogs issue `CREATE TABLE`/`ALTER TABLE` and Liquibase itself creates its `DATABASECHANGELOG` / `DATABASECHANGELOGLOCK` tracking tables on first run:

   ```sql
   GRANT SELECT, INSERT, UPDATE, DELETE,
         CREATE, ALTER, DROP, INDEX, REFERENCES,
         CREATE ROUTINE, ALTER ROUTINE, EXECUTE
   ON `<your-database>`.* TO '<db-username>'@'%';
   FLUSH PRIVILEGES;
   ```

   - The **username** → variable `LIQUIBASE_COMMAND_USERNAME`.
   - The **password** → variable `LIQUIBASE_COMMAND_PASSWORD`.
3. **TLS is mandatory** — SkySQL rejects non-TLS logins. The JDBC URL in [liquibase.properties](liquibase.properties) carries `?sslMode=trust` (encrypts, skips certificate validation — fine for a lab). For production use `?sslMode=verify-full&serverSslCert=<path-to-skysql-chain.pem>` with the SkySQL CA chain.
4. **SkySQL IP Allowlist** — Microsoft-hosted agents have **dynamic** egress IPs, so add the agent's IP range to the SkySQL service **Allowlist**. Options: allow the documented Azure IP ranges for your region, temporarily open `0.0.0.0/0` for a short-lived lab, or run on a **self-hosted agent** with a stable egress IP and allowlist that single address (recommended for anything real).

### B. Azure DevOps — organization / project

5. **Repo available to Azure DevOps** — this repository pushed to Azure Repos in the target project (or GitHub with the Azure Pipelines app connected).
6. **Agent parallelism** — new/free orgs have no hosted parallelism by default; request the [free Microsoft-hosted parallelism grant](https://aka.ms/azpipelines-parallelism-request) or register a self-hosted agent. Without this, jobs queue forever.
7. **Create variable group `liquibase-mariadb`** — Pipelines → Library → **+ Variable group**, add:

   | Variable | Value | Secret? |
   |---|---|---|
   | `DB_HOST` | `<your-server>.db2.skysql.com` | No |
   | `DB_PORT` | `4048` | No |
   | `DB_NAME` | `<your-database>` | No |
   | `LIQUIBASE_COMMAND_USERNAME` | database username | **Yes** (lock icon) |
   | `LIQUIBASE_COMMAND_PASSWORD` | database password | **Yes** (lock icon) |

   Save. The group name must match the `- group: liquibase-mariadb` reference in [azure-pipelines.yml](azure-pipelines.yml). (There is no `DB_SCHEMA` variable — in MariaDB the schema **is** the database, already given by `DB_NAME`.)
8. **Create environment `mariadb-database` with an approval gate** — Pipelines → Environments → **New environment** (name: `mariadb-database`, resource: *None*). Then open it → **Approvals and checks** → **+** → **Approvals** → add the approver(s) → Create. This approval is what gates **Stage 2**; without it the stage runs unattended.
8b. **Create environment `mariadb-production` with its own approval gate** — same steps, name `mariadb-production`. This gates **Stage 4**. Keep the approver list separate from `mariadb-database` if test and production have different owners.
8c. **Replace the Stage 4 stub values** — [azure-pipelines.yml](azure-pipelines.yml) ships Stage 4 with `REPLACE-ME` placeholders and a guard step that fails the stage while any remain. Either edit the `PROD_*` variables in the stage, or (recommended) delete that block and add a `liquibase-mariadb-prod` variable group. See [Stage 4 — production deployment](#stage-4--production-deployment).

### C. Pipeline creation & first run

9. **Create the pipeline** — Pipelines → **New pipeline** → select this repo → **Existing Azure Pipelines YAML file** → branch `main`, path `/azure-pipelines.yml` → Save (or Run).
10. **Authorize resources on first run** — the first run pauses with "This pipeline needs permission to access a resource"; click **Permit** for both the variable group and the environment.
11. **Follow the run** — Validate stage runs `validate`/`status` and publishes the `sql-preview` artifact; review it, then approve the pending Deploy approval; Deploy stage runs `liquibase update` and then tags the deployment with the version from `metadata.xml`.

### D. Post-deployment sanity check

12. In the target database confirm the `DATABASECHANGELOG` table records changeset `1::VivekRamanavar` and the `Employee` table exists:

    ```sql
    SELECT ID, AUTHOR, FILENAME, DATEEXECUTED FROM DATABASECHANGELOG ORDER BY ORDEREXECUTED;
    SHOW TABLES;
    ```

13. Confirm the **version tag** from [metadata.xml](metadata.xml) was applied to the latest changelog row (`TAG` column) — this is what `liquibase rollback <version>` targets:

    ```sql
    SELECT ID, AUTHOR, DATEEXECUTED, TAG FROM DATABASECHANGELOG WHERE TAG IS NOT NULL ORDER BY ORDEREXECUTED;
    ```

## Changes

**[liquibase.properties](liquibase.properties)** — connection details replaced with simple tokens the pipeline substitutes:
- `url: jdbc:mariadb://#{DB_HOST}#:#{DB_PORT}#/#{DB_NAME}#?sslMode=trust`
- Added a comment block documenting the tokens ↔ variable-group mapping and how to run locally; kept the SkySQL SSL/credentials troubleshooting history (`LIQUIBASE_COMMAND_*` env vars).
- No `defaultSchemaName` — MariaDB treats the schema as the database.

**[azure-pipelines.yml](azure-pipelines.yml)** — a four-stage promotion path:

```
Stage 1 Validate ──▶ Stage 2 Deploy to Test ──▶ Stage 3 UnitTests ──▶ Stage 4 Deploy to Production
                     (gate: mariadb-database)                          (gate: mariadb-production)
```

- **Stage 1 — Validate**: checks out sources, substitutes tokens via inline `sed` (with a guard that fails the build if any `#{TOKEN}#` remains), then runs `liquibase validate`, `status --verbose`, and `update-sql`, publishing the generated SQL as a `sql-preview` artifact you can inspect before approving.
- **Stage 2 — DeployTest**: a `deployment` job bound to the `mariadb-database` ADO Environment (add an Approvals check there to gate it), which re-substitutes tokens, runs `liquibase update`, and then stamps the deployed state with a **version tag** (see below).
- **Stage 3 — UnitTests**: runs `mvn -PTest clean test` on the agent and publishes JUnit results to the ADO **Tests** tab. Failing tests block Stage 4. Details and an important caveat in [Stage 3 — what the unit tests actually cover](#stage-3--what-the-unit-tests-actually-cover).
- **Stage 4 — DeployProd**: a `deployment` job bound to the `mariadb-production` Environment. Mirrors Stage 2 (substitute → resolve driver → `update` → version tag) and adds a post-deploy smoke check. Ships with **stub connection values** and a guard that refuses to run until they are replaced — see [Stage 4 — production deployment](#stage-4--production-deployment).
- **Version tagging**: after `liquibase update` succeeds, the deploy stages parse the logical version from [metadata.xml](metadata.xml)'s comment (`{ ... "Version": "x.y.z" }`) and run `liquibase tag --tag=<version>`, writing that tag against the latest `DATABASECHANGELOG` row. This makes each release addressable for `liquibase rollback <version>`. Re-running with the same version is idempotent — Liquibase re-points the tag to the current last row. Bump the `Version` in [metadata.xml](metadata.xml) to trigger a re-run and stamp a new tag.
- Liquibase runs via the pinned `liquibase/liquibase:5.0` Docker image (matching the locally-verified 5.0.3); `-w /liquibase/changelog` makes the relative `changeLogFile`/`changelog/` includes resolve correctly. Stage 3 is the exception — it runs Maven natively, because the `liquibase-maven-plugin` carries its own driver.
- Secrets `LIQUIBASE_COMMAND_USERNAME`/`LIQUIBASE_COMMAND_PASSWORD` are mapped per-step via `env:` (ADO never auto-exports secrets) and forwarded into the container with `-e` flags — credentials never touch the properties file or disk.
- Triggers on `main` for changes to `changelog/**`, `rootChangeLog.xml`, `liquibase.properties`, `metadata.xml`, and — so test-harness edits also trigger a run — `changelog_mvn/**`, `rootMvnChangeLog.xml`, `pom.xml`, `src/**`.

**[pom.xml](pom.xml)** — serves two purposes:
- (a) the pinned manifest for the MariaDB JDBC driver, resolved at pipeline runtime and mounted into the Liquibase container (see the dedicated [driver management section](#jdbc-driver-management--pomxml-and-how-its-wired-into-the-pipeline) below);
- (b) a `Test` profile that runs Liquibase and JUnit assertions locally from the Maven CLI (see [Running Liquibase locally with Maven](#running-liquibase-locally-with-maven--the-test-profile)). It adds one test-scoped dependency (`junit-jupiter`) plus the `liquibase-maven-plugin` and `maven-surefire-plugin` — all itemised in [Dependencies the Test profile adds](#dependencies-the-test-profile-adds).

The profile is inert unless `-PTest` is passed and its dependency is `test`-scoped, so the pipeline path is unaffected.

**[src/test/java/local/pipeline/](src/test/java/local/pipeline/)** — the local test harness: `DepartmentCountTest` and `EmployeeCountTest` (one independent check each), `DbTestSupport` (JDBC helper that converts every failure into a readable assertion failure), and `TestOutcomeLogger` (prints one `PASSED`/`FAILED` line per test).

**[rootMvnChangeLog.xml](rootMvnChangeLog.xml)** — the changelog lineage used by the local Maven `Test` profile. Same changesets as [rootChangeLog.xml](rootChangeLog.xml) but with all includes active and the seed data sourced from [changelog_mvn/0001_Data.xml](changelog_mvn/0001_Data.xml).

**[changelog/](changelog/)** — ordered changesets, included by [rootChangeLog.xml](rootChangeLog.xml) (uncomment each `<include>` when ready to deploy it):
- `0001.xml` — `Employee` table.
- `0002.xml` — `Department` table + `DepartmentId` foreign key on `Employee`.
- `0003.xml` — seed data (5 departments, 10 employees — **fictional sample data**).
- `0004.xml` — `usp_GetEmployee` stored procedure (`runOnChange="true"`).

**[metadata.xml](metadata.xml)** — a metadata-only changelog (included last by [rootChangeLog.xml](rootChangeLog.xml)) whose comment records the logical database version as `{ "Database": "...", "Version": "x.y.z" }`. The Deploy stage parses this `Version` and applies it as a Liquibase tag after each successful `update`, so releases are addressable for rollback. Bump the `Version` here to publish a new tagged release.

## How the connection to MariaDB Cloud works — why no service connection is needed

An Azure DevOps **service connection is NOT required** for this pipeline.

A service connection is only needed when a **pipeline task talks to a cloud control plane** — e.g. `AzureCLI@2`, `AzurePowerShell@5`, or a Key Vault-linked variable group. This pipeline uses none of those. Instead it makes a **direct data-plane JDBC connection** from the build agent to the SkySQL endpoint, and the MariaDB driver authenticates itself with the username/password:

```
Variable group (secrets)
  LIQUIBASE_COMMAND_USERNAME ─┐
  LIQUIBASE_COMMAND_PASSWORD ─┤
                              ▼
  1. env: mapping ──▶ 2. docker -e ──▶ MariaDB JDBC driver ──▶ 4. TLS connection,
                          (container)     (?sslMode=trust)         username+password auth
                                                ▲                        ▼
                                                │                 <your-server>
                                    3. Liquibase reads          .db2.skysql.com:4048
                                       LIQUIBASE_COMMAND_*
                                       and hands them to JDBC
```

1. The variable-group secrets are mapped into the step environment via `env:`, then forwarded into the Liquibase container with `docker -e`.
2. Liquibase natively reads `LIQUIBASE_COMMAND_USERNAME`/`LIQUIBASE_COMMAND_PASSWORD` and hands them to the JDBC driver.
3. The MariaDB Connector/J driver opens a **TLS** connection (required by SkySQL; `?sslMode=trust` encrypts the channel) to the custom serverless port and authenticates with the username/password directly.
4. No cloud broker, token service, or IAM library is involved — which is why the **SkySQL IP allowlist** pre-requisite matters: the agent reaches the SkySQL public endpoint over the network, so its egress IP must be allowlisted.

**When a service connection WOULD be required:**
- Linking the variable group to **Azure Key Vault** (recommended hardening — secrets live in Key Vault, ADO fetches them through a service connection).
- Any pipeline step that manages cloud resources (provisioning the SkySQL service, editing allowlists via API, etc.).

## JDBC driver management — pom.xml and how it's wired into the pipeline

### The problem it solves

The `liquibase/liquibase` Docker image ships **without** the MariaDB JDBC driver — a bare pipeline run fails with:

```
Unexpected error running Liquibase: Cannot find database driver: org.mariadb.jdbc.Driver
```

Committing the driver `.jar` to the repo was rejected deliberately: binaries bloat git history and can't be code-reviewed.

### What pom.xml is

[pom.xml](pom.xml) is a **text manifest, not an application build**. For the pipeline's purposes it pins exactly one coordinate:

| Artifact | Version | Scope | Why |
|---|---|---|---|
| `org.mariadb.jdbc:mariadb-java-client` | `3.5.9` | `compile` | The MariaDB Connector/J JDBC driver |

SkySQL's plain username/password auth needs no companion library, so — unlike the Entra service-principal variant which additionally requires `msal4j` — the driver manifest has a single dependency. Verified: `mvn dependency:list -DincludeScope=runtime` resolves exactly one jar, so `drivers/` contains only `mariadb-java-client-3.5.9.jar`.

`compile` scope is deliberate. The pipeline resolves with `-DincludeScope=runtime`, which includes `compile` — moving this to `test` scope to "tidy up" would silently empty `drivers/` and break the deployment with `Cannot find database driver`.

> The same `pom.xml` additionally carries **test-only** dependencies and two plugins for the local `Test` profile. Those are inert for the pipeline (`test` scope is excluded from `-DincludeScope=runtime`, and profile plugins don't activate without `-PTest`) and are documented separately under [Dependencies the Test profile adds](#dependencies-the-test-profile-adds).

### How it's wired into the pipeline

```
pom.xml (checked in, text)
      │
      │  1. mvn dependency:copy-dependencies        (pipeline step, both jobs;
      ▼     -DoutputDirectory=drivers                Maven pre-installed on agents)
drivers/ on the agent  (git-ignored)
      │
      │  2. docker run -v "$(Build.SourcesDirectory)/drivers:/liquibase/lib"
      ▼
/liquibase/lib inside the container  ──▶  3. Liquibase auto-loads every jar
                                             on this path — driver found
```

1. **Resolve** — the step *"Resolve MariaDB JDBC driver"* in [azure-pipelines.yml](azure-pipelines.yml) runs:

   ```bash
   mvn -B -q dependency:copy-dependencies \
     -DoutputDirectory="$(Build.SourcesDirectory)/drivers" \
     -DincludeScope=runtime
   ```

2. **Mount** — every `docker run` (validate, status, update-sql, update) includes:

   ```bash
   -v "$(Build.SourcesDirectory)/drivers:/liquibase/lib"
   ```

   `/liquibase/lib` is the directory the Liquibase launcher scans and auto-loads jars from — no `--classpath` flag needed.

3. **Both jobs** — the resolve step runs in the Validate job *and* the Deploy job, because each pipeline job gets a fresh workspace.

The `drivers/` output folder is excluded via [.gitignore](.gitignore) — jars exist only on the agent for the lifetime of a run.

**To upgrade the driver**: bump the version in `pom.xml`. Nothing else changes.

## Stage 3 — what the unit tests actually cover

Stage 3 runs the same profile you use locally, with the connection details supplied by the variable group instead of the pom:

```bash
mvn -B -PTest clean test \
  -Ddb.url="jdbc:mariadb://$(DB_HOST):$(DB_PORT)/$(DB_NAME)?sslMode=trust"
```

- **`-Ddb.url` is not optional.** The `Test` profile in [pom.xml](pom.xml) hardcodes a developer's SkySQL URL. A CLI `-D` beats a profile `<properties>` value, so this override is what keeps CI pointed at the variable-group database. Verified: with a deliberately bogus host, both the Liquibase plugin and the tests reported that host, not the pom's.
- **`clean` matters on self-hosted agents**, which reuse the workspace. Without it, Surefire XML from an earlier run — including reports for deleted test classes — would be republished as current.
- **JDK 21 is pinned** via `JavaToolInstaller@0`, because [pom.xml](pom.xml) sets `maven.compiler.release=21`.
- **Results land in the ADO Tests tab** via `PublishTestResults@2` with `condition: always()`, so a failing run still publishes — that is the run worth inspecting.
- **No driver resolution step is needed.** Unlike the Docker-based stages, the `liquibase-maven-plugin` carries its own plugin-scoped copy of the JDBC driver.

### ⚠️ Stage 3 does not validate what Stage 4 deploys

This is a deliberate trade-off, recorded here rather than buried:

| | Changelog used | Result |
|---|---|---|
| Stage 3 (tests) | `rootMvnChangeLog.xml` — all four changesets active | `Employee`, `Department`, seed data, stored procedure |
| Stages 2 & 4 (deploy) | `rootChangeLog.xml` — [0002/0003/0004 still commented out](rootChangeLog.xml#L11-L13) | `Employee` + `metadata.xml` only |

So a green Stage 3 says nothing about the artifact that reaches production. Stage 3 is also **self-contained**: `mvn -PTest test` binds `dropAll` + `update` to `process-test-resources`, so it drops and rebuilds the test database, discarding what Stage 2 just deployed.

**To close the gap** when you're ready to deploy the full schema: uncomment the three `<include>` lines in [rootChangeLog.xml](rootChangeLog.xml), then add `-Dliquibase.skip=true` to the Stage 3 command so it asserts against the real deployment instead of building its own. The YAML carries this note at the exact spot.

## Stage 4 — production deployment

Gated on the `mariadb-production` Environment and reachable only when Stage 3 is green (`dependsOn: UnitTests` + `condition: succeeded()`).

Step for step it mirrors Stage 2 — substitute tokens, resolve the JDBC driver, `liquibase update`, stamp the version tag from `metadata.xml` — with two additions.

**1. Stub values and the guard.** The stage ships with placeholders:

```yaml
variables:
  - name: PROD_DB_HOST
    value: 'REPLACE-ME-prod.db2.skysql.com'
  - name: PROD_DB_NAME
    value: 'REPLACE_ME_ProdDatabase'
  # ... PROD_DB_PORT, PROD_LIQUIBASE_COMMAND_USERNAME, PROD_LIQUIBASE_COMMAND_PASSWORD
```

A guard step runs first and fails the stage while any `REPLACE-ME` remains. That is what makes committing placeholders safe — the first pipeline run **stops at this guard**, which is the intended outcome, not a failure to debug.

For real use, delete the whole `variables:` block and reference a secret store instead:

```yaml
variables:
  - group: liquibase-mariadb-prod   # DB_HOST, DB_PORT, DB_NAME, LIQUIBASE_COMMAND_* (secret)
```

A separate group keeps production credentials out of source control and means a leaked test credential cannot reach production.

**2. Smoke check.** After `update` and `tag`, the stage runs `liquibase status --verbose` and asserts nothing is pending. The logic is three-way and fails safe, using Liquibase 5.0.3's verified wording:

| Output | Result |
|---|---|
| `N changesets have not been applied to …` | **fail** — deployment incomplete |
| `…@… is up to date` | **pass** |
| anything else | **fail** — an unrecognised result must not read as healthy on a production gate |

## Running Liquibase locally with Maven — the `Test` profile

The pipeline runs Liquibase through Docker. For local work against the test database, [pom.xml](pom.xml) also carries a **`Test` profile** that drives the `liquibase-maven-plugin` directly and then asserts the result with JUnit tests.

### Machine pre-requisites

| Requirement | Why |
|---|---|
| A **JDK** (not a JRE) 21+ on `JAVA_HOME` | The JUnit tests must be compiled — a JRE has no `javac` — and [pom.xml](pom.xml) sets `maven.compiler.release=21`, so a JDK older than 21 also fails. The JRE bundled with the Liquibase CLI installer (`C:\Program Files\liquibase\jre`) is **not** sufficient. |
| **Maven 3.9+** on `PATH` | Verified against Apache Maven 3.9.16. |
| Maven able to reach Maven Central over TLS | Every test dependency and plugin is downloaded on first run — see [Dependencies the Test profile adds](#dependencies-the-test-profile-adds). Java validates certificates against its own `cacerts`, **not** the Windows certificate store. See the `PKIX path building failed` entry under [Troubleshooting](#troubleshooting) — HTTPS-scanning antivirus/proxies break this. |
| Your workstation's public IP on the SkySQL **Allowlist** | Same constraint as the hosted agents (pre-requisite A.4). |

Nothing else needs installing: no local database, no JUnit download, no driver jar on disk. Maven resolves the whole test toolchain from the coordinates in `pom.xml`.

### Run it

```powershell
# Credentials never live in pom.xml — same env vars the pipeline uses.
$env:LIQUIBASE_COMMAND_USERNAME = '<db-username>'
$env:LIQUIBASE_COMMAND_PASSWORD = '<db-password>'

mvn -PTest test
```

That single command executes, in lifecycle order:

```
process-test-resources ──▶ liquibase:dropAll   (wipes the target database)
                       ──▶ liquibase:update    (replays rootMvnChangeLog.xml)
test-compile           ──▶ compiles the test sources
test                   ──▶ DepartmentCountTest  (Department row count)
                       ──▶ EmployeeCountTest    (Employee row count)
```

`dropAll` runs first by design, so the run is **repeatable** — every invocation rebuilds the database from scratch. Read-only commands are available too:

```powershell
mvn -PTest liquibase:status      # pending changesets
mvn -PTest liquibase:updateSQL   # preview SQL, applies nothing
```

### Profiles vs. phases — naming

A common trip-up is `mvn PTest` → `Unknown lifecycle phase "PTest"`. Two distinct concepts:

| | Profile | Phase |
|---|---|---|
| Selected with | `-P<id>` | positional argument |
| Name | **free-form — you choose** | **fixed by Maven's lifecycle** |
| Defined in | `<profiles><profile><id>` in `pom.xml` | Maven core / packaging lifecycle mapping |

`Test` is a **profile id**. Renaming it to `TestV1` is a one-word edit in `pom.xml` (`<id>TestV1</id>`), then `mvn -PTestV1 test`. But `TestV1` can **never** be a phase — Maven 3's phase list is closed (`validate → … → process-test-resources → test-compile → test → package → …`), and adding one requires shipping a Maven extension with a custom lifecycle mapping. Always pair a profile with a real phase: `mvn -P<profile> <phase>`.

This is the intended lever for **versioned test environments**. Every environment-specific value lives in the profile's `<properties>` — `db.url`, `changelog.file`, and the expected row counts — so a second environment is a copy of that block:

```xml
<profile>
  <id>TestV2</id>
  <properties>
    <skipTests>false</skipTests>
    <changelog.file>rootMvnChangeLog.xml</changelog.file>
    <db.url>jdbc:mariadb://<other-host>:4048/VSCTestDatabaseV2?sslMode=trust</db.url>
    <expected.department.count>5</expected.department.count>
    <expected.employee.count>10</expected.employee.count>
  </properties>
  <!-- same <build><plugins> block as Test -->
</profile>
```

Profiles also compose: `mvn -PTestV1,SomeOtherProfile test`.

### Dependencies the `Test` profile adds

Everything needed by `mvn -PTest test` is declared in [pom.xml](pom.xml) and resolved from Maven Central on first run — there is nothing to install by hand beyond a JDK and Maven.

**Declared directly** — the only two coordinates written into the pom for testing:

| Artifact | Version | Scope | Why it is needed |
|---|---|---|---|
| `org.junit.jupiter:junit-jupiter` | `5.11.4` | `test` | JUnit 5 aggregate — the `@Test`, `@DisplayName`, `@ExtendWith`, `Assertions.*` and `TestWatcher` API the three test classes use. Pulls the API, params and engine together so no separate engine dependency is required. |
| `org.mariadb.jdbc:mariadb-java-client` | `3.5.9` | `compile` | Already present for the pipeline (see above). Reused as the JDBC driver for the tests' `DriverManager.getConnection(...)`, so **no second declaration** is needed. |

**Pulled in transitively** — resolved automatically, listed here for supply-chain visibility. Confirmed with `mvn -PTest dependency:list`:

| Artifact | Version | Comes from |
|---|---|---|
| `org.junit.jupiter:junit-jupiter-api` | `5.11.4` | `junit-jupiter` |
| `org.junit.jupiter:junit-jupiter-params` | `5.11.4` | `junit-jupiter` |
| `org.junit.jupiter:junit-jupiter-engine` | `5.11.4` | `junit-jupiter` |
| `org.junit.platform:junit-platform-commons` | `1.11.4` | `junit-jupiter-api` |
| `org.junit.platform:junit-platform-engine` | `1.11.4` | `junit-jupiter-engine` |
| `org.opentest4j:opentest4j` | `1.3.0` | `junit-jupiter-api` — supplies `AssertionFailedError`, the type behind the `expected: <4> but was: <5>` message |
| `org.apiguardian:apiguardian-api` | `1.1.2` | `junit-jupiter-api` — API-stability annotations only |

All nine are `test` scope, so `dependency:copy-dependencies -DincludeScope=runtime` ignores them and the pipeline's `drivers/` folder is unaffected.

**Plugins** — build-time only, never on the application classpath:

| Plugin | Version | Pinned where | Role |
|---|---|---|---|
| `org.liquibase:liquibase-maven-plugin` | `5.0.3` | `Test` profile (`${liquibase.version}`) | Runs `dropAll` + `update` in `process-test-resources`. Carries its **own** plugin-scoped copy of `mariadb-java-client`, because the plugin loads in a separate classloader that the project dependencies do not reach. |
| `org.apache.maven.plugins:maven-surefire-plugin` | `3.5.2` | `Test` profile | Runs the tests; supplies `db.url` and the expected counts via `<systemPropertyVariables>`, and applies `trimStackTrace` / `skipAfterFailureCount`. Detects the JUnit Platform automatically from `junit-jupiter` on the test classpath — no separate provider dependency needed. |
| `org.apache.maven.plugins:maven-compiler-plugin` | Maven default (`3.15.0` here) | not pinned | Compiles the test sources at `maven.compiler.release=21`. |

**Version properties** — all versions are centralised at the top of [pom.xml](pom.xml), so upgrades are one-line edits:

```xml
<liquibase.version>5.0.3</liquibase.version>        <!-- keep in step with the Docker image tag -->
<mariadb.driver.version>3.5.9</mariadb.driver.version>
<junit.version>5.11.4</junit.version>
<maven.compiler.release>21</maven.compiler.release>
```

To audit the full resolved set yourself:

```powershell
mvn -PTest dependency:list      # every jar with its scope
mvn -PTest dependency:tree      # who pulled in what
```

### The unit tests

Two **independent** tests, one per table, so each reports its own PASSED/FAILED result:

| Test | Checks | Expected value from |
|---|---|---|
| [DepartmentCountTest](src/test/java/local/pipeline/DepartmentCountTest.java) | rows in `Department` | `expected.department.count` |
| [EmployeeCountTest](src/test/java/local/pipeline/EmployeeCountTest.java) | rows in `Employee` | `expected.employee.count` |

They are separate classes holding **no shared state**, and each opens its own JDBC connection through [DbTestSupport](src/test/java/local/pipeline/DbTestSupport.java). A failure in one therefore has no effect on the other — Surefire runs both and reports both. Actual output with `expected.department.count` deliberately set to `4` against a database holding 5:

```
[TEST] Department row count matches the expected number of seeded departments :: FAILED - Department row count ==> expected: <4> but was: <5>
[TEST] Employee row count matches the expected number of seeded employees :: PASSED
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0
```

Those `[TEST]` lines come from [TestOutcomeLogger](src/test/java/local/pipeline/TestOutcomeLogger.java), a JUnit `TestWatcher` registered on both classes with `@ExtendWith`. It prints the outcome and, on failure, the reason — never an exception type or stack trace. Because it only observes results, it cannot influence whether a test passes.

**`Errors: 0` is the important column.** `DbTestSupport` catches every `SQLException` and every missing-configuration case and converts it into an assertion failure carrying a readable reason, so nothing ever propagates as a Java exception. Verified by running with a deliberately wrong password:

```
[TEST] Department row count ... :: FAILED - Could not read `Department`: (conn=8767) Access denied for user '...' (using password: YES)
[TEST] Employee row count ...   :: FAILED - Could not read `Employee`: (conn=8768) Access denied for user '...' (using password: YES)
[ERROR] Tests run: 2, Failures: 2, Errors: 0, Skipped: 0
```

The full set of handled conditions:

| Situation | Reported as |
|---|---|
| Database unreachable / wrong credentials | `FAILED — Could not read \`Department\`: <driver message>` |
| Table missing (update didn't run) | `FAILED — Could not read \`Employee\`: Table ... doesn't exist` |
| Run without `-PTest` | `FAILED — Configuration missing: system property 'db.url' is not set.` |
| Env vars not set in this shell | `FAILED — Configuration missing: environment variable LIQUIBASE_COMMAND_USERNAME is not set.` |

Two Surefire settings back this up: `<trimStackTrace>true</trimStackTrace>` keeps output to the one-line reason, and `<skipAfterFailureCount>0</skipAfterFailureCount>` guarantees the second class still runs after the first one fails.

Other details:

- Expected counts and the JDBC URL arrive as **system properties** from the Surefire `<systemPropertyVariables>` block, so they follow whichever profile is active — a `TestV2` profile can assert different numbers against a different database.
- Credentials are read from the **environment**, never from `pom.xml` or the command line.
- Tests are **skipped by default** (`skipTests=true` at project level); only the `Test` profile flips it to `false`. A bare `mvn test` or `mvn package` never opens a database connection, keeping the pipeline's `dependency:copy-dependencies` step safe.

To confirm an assertion actually runs rather than passing vacuously, force a mismatch on one table and watch the other stay green:

```powershell
mvn -PTest test "-Dexpected.employee.count=99"
# [TEST] Employee row count ...   :: FAILED - Employee row count ==> expected: <99> but was: <10>
# [TEST] Department row count ... :: PASSED
```

> **PowerShell quoting**: quote any `-D` argument containing a dot, e.g. `"-Dliquibase.skip=true"`. Unquoted, PowerShell splits it at the dot and Maven reports `Unknown lifecycle phase ".skip=true"`.

To exercise the tests **without** rebuilding the database — a read-only check against whatever is currently deployed — skip the Liquibase executions:

```powershell
mvn -PTest test "-Dliquibase.skip=true"
```

### Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Unknown lifecycle phase "PTest"` | `-P` selects a profile and still needs a phase: `mvn -PTest test`. |
| `Unrecognised tag: 'configuration'` | `<configuration>`/`<executions>` were placed at project level. They are only legal inside `<build><plugins><plugin>`. |
| `PKIX path building failed: unable to find valid certification path` | Java doesn't trust the TLS certificate Maven Central presents. Caused by HTTPS-scanning antivirus or a corporate proxy re-signing traffic (observed here: *AVG Web/Mail Shield*, which presents `Issuer: CN=AVG Web/Mail Shield Root` for `repo.maven.apache.org`). Browsers and PowerShell accept it because they use the Windows certificate store; Java uses its own `cacerts`. See the fix below. |
| `Cannot find database driver: org.mariadb.jdbc.Driver` | The plugin-scoped `<dependencies>` block inside the `liquibase-maven-plugin` declaration was removed. The project-level dependency alone does not reach the plugin's classloader. |
| `Access denied … (using password: NO)` | Env vars not set in *this* shell — they don't persist across windows. Note Maven passes the literal string `${env.X}` through when a variable is unset. |
| `Access denied … (using password: YES)` | `?sslMode=trust` missing from the URL — SkySQL requires TLS and Connector/J 3.x defaults to `sslMode=disable`. |
| `dropAll` refuses to run / asks for force | Newer Liquibase versions guard `dropAll`. Add `<dropAllRequireForce>false</dropAllRequireForce>` to the plugin `<configuration>`, or pass `-Dliquibase.dropAllRequireForce=false`. |
| Tests reported as skipped | By design outside `-PTest`. |
| `No compiler is provided in this environment` | `JAVA_HOME` is unset (or points at a JRE), so Maven falls back to the compiler-less Liquibase-bundled JRE (`C:\Program Files\liquibase\jre`) on PATH. Install a JDK 21 and point `JAVA_HOME` at it: `winget install EclipseAdoptium.Temurin.21.JDK`, then `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.x-hotspot'` and prepend `$env:JAVA_HOME\bin` to PATH. Verify with `mvn -v` (runtime must **not** be `...\liquibase\jre`) and `javac -version`. |

#### Fixing `PKIX path building failed` without touching the system JDK

Java must be told to trust the interceptor's root CA. Rather than modifying the shared JDK truststore (needs admin, affects every project), build a **scoped copy** and point Maven at it for this repo only:

```powershell
$jdk   = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'   # your JDK
$store = "$env:TEMP\maven-truststore.jks"

# 1. Export the interceptor's root CA from the Windows store
Get-ChildItem Cert:\LocalMachine\Root, Cert:\CurrentUser\Root |
  Where-Object { $_.Subject -like '*AVG*' } | Select-Object -First 1 |
  Export-Certificate -FilePath "$env:TEMP\avg-root.cer" -Type CERT

# 2. Copy the JDK truststore and add that CA to the copy
Copy-Item "$jdk\lib\security\cacerts" $store -Force
& "$jdk\bin\keytool.exe" -importcert -noprompt -trustcacerts `
    -keystore $store -storepass changeit -alias avg-web-shield -file "$env:TEMP\avg-root.cer"

# 3. Point Maven at the copy (this shell only)
$env:JAVA_HOME  = $jdk
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStore=$store -Djavax.net.ssl.trustStorePassword=changeit"
```

The alternative is exempting `repo.maven.apache.org` from the antivirus's HTTPS scanning. Either way, `JAVA_HOME` must point at a **JDK** — the JRE bundled with the Liquibase CLI installer has no `javac`, and Maven silently falls back to it when `JAVA_HOME` is unset.

> ⚠️ **`dropAll` is destructive.** The `Test` profile wipes every object in the target database, including `DATABASECHANGELOG`. Point `db.url` only at a database you own. Use `"-Dliquibase.skip=true"` when you want the tests without the rebuild.

## Security best practices not applied here

> **Scope note:** the core scope of this project is demonstrating a **Liquibase CI/CD workflow** for MariaDB Cloud. The practices below are recommended production hardening measures that were **intentionally left out** to keep the demo focused; adopt them when promoting this pattern to a real environment.

- **Secrets management**
  - Link the variable group to **Azure Key Vault** instead of storing secrets directly in the ADO Library, so secrets are centrally rotated/audited.
  - Automate **database-password rotation**.
  - Enable **GitHub Advanced Security for Azure DevOps** — secret scanning and push protection on the repo.
- **Transport security**
  - Replace `sslMode=trust` with **`sslMode=verify-full`** plus the SkySQL CA chain (`serverSslCert=…`) so the driver validates the server certificate instead of blindly trusting it.
- **Identity & least privilege**
  - Use **separate database users and variable groups per environment** (dev/test/prod) so a leaked dev credential cannot touch prod, and grant each user only the privileges it needs.
- **Pipeline & resource permissions**
  - Restrict the variable group and the environment to **this specific pipeline** (avoid "open access" / grant-to-all-pipelines when permitting resources).
  - Limit **job authorization scope** to the current project; disable creation of classic (non-YAML) pipelines.
- **Branch & code governance**
  - **Branch policies on `main`**: required PR reviewers, build-validation run of the Validate stage, no direct pushes — today anyone pushing to `main` triggers a deployment path.
- **Deployment checks beyond a single approval**
  - **Branch control** check on the environment (only runs from `main` may deploy), **exclusive lock** (no concurrent deployments), optionally business-hours gates.
- **Network**
  - A wide SkySQL allowlist (or `0.0.0.0/0`) admits traffic from anywhere. Production: **self-hosted agents** with a stable, allowlisted egress IP, or SkySQL private connectivity where available.
- **Supply chain**
  - Pin the Docker image **by digest** (`liquibase/liquibase@sha256:…`) rather than a mutable tag.
  - Route Maven resolution through an **Azure Artifacts upstream feed** (proxy/quarantine of Maven Central) and add dependency/vulnerability scanning of `pom.xml`.
- **Audit**
  - Enable **Azure DevOps audit log streaming** to a SIEM (who approved, who changed variable groups, who edited the pipeline).

## Verification Process

- `sed` substitution smoke-tested against a copy of the tokenized properties: reproduces the intended working URL **byte-for-byte**, and the guard (`#\{[A-Z_]+\}#`) correctly detects unreplaced tokens in the raw file and passes on the substituted file.
- `azure-pipelines.yml` parses cleanly as YAML; all changelog XML and `rootChangeLog.xml`/`pom.xml` parse cleanly as XML.
- The pinned Maven artifact (`mariadb-java-client:3.5.9`) confirmed present on Maven Central.
- Dependency documentation checked against reality, not memory: `mvn -PTest dependency:list` resolves 9 jars (1 `compile` + 8 `test`) and `-DincludeScope=runtime` resolves exactly 1, matching the tables in [Dependencies the Test profile adds](#dependencies-the-test-profile-adds).
- `pom.xml` parses as a valid Maven model (`mvn` resolves the project as `local.pipeline:liquibase-jdbc-drivers:1.0.0`), and every `<include>` in `rootMvnChangeLog.xml` resolves to a file on disk.
- The `Test` profile was exercised against the live SkySQL database (with `"-Dliquibase.skip=true"`, so read-only — `dropAll`/`update` were not run):
  - **Independence** — with `expected.department.count=4` against a database holding 5 departments: `DepartmentCountTest` FAILED, `EmployeeCountTest` PASSED, `Tests run: 2, Failures: 1, Errors: 0`.
  - **Graceful failure** — with a deliberately wrong password, the driver's `SQLException` surfaced as `Failures: 2, Errors: 0` with the access-denied message as the reason; no exception propagated.
- Regression checks: a bare `mvn test` (no profile) reports `Tests are skipped` and runs no Liquibase goal; `mvn dependency:copy-dependencies -DincludeScope=runtime` — the exact pipeline step — still resolves `mariadb-java-client-3.5.9.jar` into `drivers/`.
- The **full destructive path** was executed end-to-end against the live database, exactly as Stage 3 invokes it (`mvn -B -PTest clean test -Ddb.url=...`): `clean` → `liquibase:dropAll` → `liquibase:update` (*Update has been successful. Rows affected: 15*) → `Tests run: 2, Failures: 0, Errors: 0` → `BUILD SUCCESS`.
- **Pipeline stage graph** parsed from `azure-pipelines.yml`: 4 stages chaining `Validate → DeployTest → UnitTests → DeployProd`, zero dangling `dependsOn` references after the Stage 2 rename, environments bound as `mariadb-database` (Stage 2) and `mariadb-production` (Stage 4).
- **`-Ddb.url` override** confirmed to beat the pom's profile value for *both* consumers — pointed at a bogus host, the Liquibase plugin and the JUnit tests each reported that host.
- **Stage 4 guard** dry-run against three inputs: all stubs → blocked, partially replaced → blocked, fully replaced → allowed.
- **Stage 4 smoke check** validated against real Liquibase 5.0.3 output captured from this database: `…is up to date` → pass; `2 changesets have not been applied to …` → fail; unrecognised wording → fail.
- **Surefire glob** `**/surefire-reports/TEST-*.xml` matches the produced reports, and `clean` was confirmed to remove a stale report from a deleted test class.
- **Not yet executed**: Stage 4 itself, which cannot run until the stub values are replaced and the `mariadb-production` environment exists.
