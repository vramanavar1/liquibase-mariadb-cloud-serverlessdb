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
8. **Create environment `mariadb-database` with an approval gate** — Pipelines → Environments → **New environment** (name: `mariadb-database`, resource: *None*). Then open it → **Approvals and checks** → **+** → **Approvals** → add the approver(s) → Create. This approval is what gates the Deploy stage; without it the Deploy stage runs unattended.

### C. Pipeline creation & first run

9. **Create the pipeline** — Pipelines → **New pipeline** → select this repo → **Existing Azure Pipelines YAML file** → branch `main`, path `/azure-pipelines.yml` → Save (or Run).
10. **Authorize resources on first run** — the first run pauses with "This pipeline needs permission to access a resource"; click **Permit** for both the variable group and the environment.
11. **Follow the run** — Validate stage runs `validate`/`status` and publishes the `sql-preview` artifact; review it, then approve the pending Deploy approval; Deploy stage runs `liquibase update`.

### D. Post-deployment sanity check

12. In the target database confirm the `DATABASECHANGELOG` table records changeset `1::VivekRamanavar` and the `Employee` table exists:

    ```sql
    SELECT ID, AUTHOR, FILENAME, DATEEXECUTED FROM DATABASECHANGELOG ORDER BY ORDEREXECUTED;
    SHOW TABLES;
    ```

## Changes

**[liquibase.properties](liquibase.properties)** — connection details replaced with simple tokens the pipeline substitutes:
- `url: jdbc:mariadb://#{DB_HOST}#:#{DB_PORT}#/#{DB_NAME}#?sslMode=trust`
- Added a comment block documenting the tokens ↔ variable-group mapping and how to run locally; kept the SkySQL SSL/credentials troubleshooting history (`LIQUIBASE_COMMAND_*` env vars).
- No `defaultSchemaName` — MariaDB treats the schema as the database.

**[azure-pipelines.yml](azure-pipelines.yml)** — new two-stage pipeline:
- **Validate stage**: checks out sources, substitutes tokens via inline `sed` (with a guard that fails the build if any `#{TOKEN}#` remains), then runs `liquibase validate`, `status --verbose`, and `update-sql`, publishing the generated SQL as a `sql-preview` artifact you can inspect before approving.
- **Deploy stage**: a `deployment` job bound to the `mariadb-database` ADO Environment (add an Approvals check there to gate it), which re-substitutes tokens and runs `liquibase update`.
- Liquibase runs via the pinned `liquibase/liquibase:5.0` Docker image (matching the locally-verified 5.0.3); `-w /liquibase/changelog` makes the relative `changeLogFile`/`changelog/` includes resolve correctly.
- Secrets `LIQUIBASE_COMMAND_USERNAME`/`LIQUIBASE_COMMAND_PASSWORD` are mapped per-step via `env:` (ADO never auto-exports secrets) and forwarded into the container with `-e` flags — credentials never touch the properties file or disk.
- Triggers on `main` for changes to `changelog/**`, `rootChangeLog.xml`, or `liquibase.properties`.

**[drivers-pom.xml](drivers-pom.xml)** — pinned manifest for the MariaDB JDBC driver, resolved at pipeline runtime and mounted into the Liquibase container (see the dedicated [driver management section](#jdbc-driver-management--drivers-pomxml-and-how-its-wired-into-the-pipeline) below).

**[changelog/](changelog/)** — ordered changesets, included by [rootChangeLog.xml](rootChangeLog.xml) (uncomment each `<include>` when ready to deploy it):
- `0001.xml` — `Employee` table.
- `0002.xml` — `Department` table + `DepartmentId` foreign key on `Employee`.
- `0003.xml` — seed data (5 departments, 10 employees — **fictional sample data**).
- `0004.xml` — `usp_GetEmployee` stored procedure (`runOnChange="true"`).

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

## JDBC driver management — drivers-pom.xml and how it's wired into the pipeline

### The problem it solves

The `liquibase/liquibase` Docker image ships **without** the MariaDB JDBC driver — a bare pipeline run fails with:

```
Unexpected error running Liquibase: Cannot find database driver: org.mariadb.jdbc.Driver
```

Committing the driver `.jar` to the repo was rejected deliberately: binaries bloat git history and can't be code-reviewed.

### What drivers-pom.xml is

[drivers-pom.xml](drivers-pom.xml) is a **text manifest, not an application build** — a minimal `pom`-packaging Maven file that pins exactly one coordinate:

| Artifact | Version | Why |
|---|---|---|
| `org.mariadb.jdbc:mariadb-java-client` | `3.5.9` | The MariaDB Connector/J JDBC driver |

SkySQL's plain username/password auth needs no companion library, so — unlike the Entra service-principal variant which additionally requires `msal4j` — this manifest has a single dependency. Maven still resolves its full transitive closure, so any supporting jars arrive automatically and version-consistently.

### How it's wired into the pipeline

```
drivers-pom.xml (checked in, text)
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
   mvn -B -q -f drivers-pom.xml dependency:copy-dependencies \
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

**To upgrade the driver**: bump the version in `drivers-pom.xml`. Nothing else changes.

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
  - Route Maven resolution through an **Azure Artifacts upstream feed** (proxy/quarantine of Maven Central) and add dependency/vulnerability scanning of `drivers-pom.xml`.
- **Audit**
  - Enable **Azure DevOps audit log streaming** to a SIEM (who approved, who changed variable groups, who edited the pipeline).

## Verification Process

- `sed` substitution smoke-tested against a copy of the tokenized properties: reproduces the intended working URL **byte-for-byte**, and the guard (`#\{[A-Z_]+\}#`) correctly detects unreplaced tokens in the raw file and passes on the substituted file.
- `azure-pipelines.yml` parses cleanly as YAML; all changelog XML and `rootChangeLog.xml`/`drivers-pom.xml` parse cleanly as XML.
- The pinned Maven artifact (`mariadb-java-client:3.5.9`) confirmed present on Maven Central.
