# MongoDB Connector for Helical Insight

Round 2 technical task: add MongoDB connectivity to Helical Insight using the application's existing data source patterns.

## Summary

**Before:** MongoDB could only be used through Apache Drill (`MongoDrillLoader`, marked `@Deprecated`). Drill is disabled by default (`hi-repository/System/Admin/drillConfig.xml` → `<enabled>false</enabled>`), so MongoDB did not appear on the Data Sources screen at all.

**After:** MongoDB is available as a native connection type under **Data Sources → No SQL & Big Data → Mongodb**, without Drill. Users can configure, test, save and reopen a MongoDB connection. Connection failures produce clear messages that never contain credentials.

**Scope:** connection configuration, validation, testing and saving. Building metadata or reports on MongoDB collections still requires a SQL layer and is not part of this change (see [Known limitations](#known-limitations)).

## Implementation

### How it plugs in

The connector uses the NoSQL extension point that already exists in the code base, so the frontend needed no changes:

```
Data Sources UI (Mongodb card, provider = "noSql", driverName = "com.helicalinsight.nosql.mongodb")
  │
  ├─ Test Connection → GlobalConnectionsTester → DataSourceUtils.testNosqlDS
  │                     → NoSqlUtils.getNoSqlImplementation(driverName)   // Spring bean lookup by name
  │                     → MongoNativeLoader.testConnection
  │
  └─ Save / Update   → NoSqlDataSourcePropertiesDB → MongoNativeLoader.loadToMiddleWare (validation only)
                        → stored in ds_type_nosql (password encrypted by the existing CipherUtils)
```

### Files changed

| File | Change |
|---|---|
| `server/core/src/main/java/com/helicalinsight/datasource/nosql/MongoNativeLoader.java` | **New.** `NoSQLLoader` implementation registered as the Spring bean `com.helicalinsight.nosql.mongodb`. Builds client settings from the form, tests with `ping` plus `listCollectionNames`, and maps failures to clear messages. |
| `server/hi-repository/System/Admin/Static/DataSourcesList.groovy` | Registers the Mongodb card and driver entry **unconditionally** (the Drill-based entry is only added when Drill is enabled). |
| `server/core/src/test/java/com/helicalinsight/datasource/nosql/MongoNativeLoaderTest.java` | **New.** 6 JUnit 4 tests. |
| `MONGODB_CONNECTOR.md`, `docs/mongodb/*`, `README.md` | Documentation and evidence. |

**Not changed:** the React frontend, `pom.xml` files, `MongoDrillLoader` and the Drill route.

### Design decisions

1. **Reuse the existing NoSQL extension point.** The backend resolves the loader by `driverName` (`NoSqlUtils.getNoSqlImplementation`), so a new `@Component("com.helicalinsight.nosql.mongodb")` is picked up by the existing `component-scan` with no wiring changes.
2. **No new dependency.** `org.mongodb:mongo-java-driver:3.12.10` (Apache-2.0) is already declared in `server/pom.xml` for all modules. Adding `mongodb-driver-sync` 4.x/5.x would put a second copy of the `com.mongodb.*` classes on the classpath. The new code uses only the modern driver API (`MongoClients`, `MongoClientSettings`, `ConnectionString`), which is the same API in driver 4.x/5.x, so upgrading later is a version change plus updating the deprecated Drill loader.
3. **Real validation, fast failure.** `ping` checks the server is reachable; `listCollectionNames` checks the credentials can read the selected database. Server-selection and connect timeouts are 5 seconds, so a wrong host does not hang the UI.
4. **Safe error messages.** Failures become the existing `DatabaseConnectionFailedException`, which the UI already displays. Messages never echo the connection URL (it may contain a password), and logging records only the exception class.
5. **No regressions by design.** The Drill route, frontend and other drivers are untouched.

## Prerequisites

| Requirement | Version tested |
|---|---|
| Docker Desktop (WSL 2 backend) | Docker Engine 29.7.2 on Windows 11 |
| MongoDB server | 7.0 (`mongo:7.0` image) |
| JDK / Maven | Not needed locally: the build runs inside Docker (`eclipse-temurin:25`, Maven). For native development use JDK 25 and Maven 3.8+ as described in `server/README.md`. |

Tested on a machine with 8 GB RAM, with `%USERPROFILE%\.wslconfig` set to `memory=4GB` and `swap=8GB`.

## Build and run

```bash
# Windows: both settings are required (long paths; LF endings for server/docker/entrypoint.sh)
git clone -c core.longpaths=true -c core.autocrlf=false https://github.com/ShreyaBatra6/helicalinsight.git
cd helicalinsight
git checkout feature/mongodb-connector

docker compose -f docker-compose.dev.yml up --build -d postgres hiee
docker compose -f docker-compose.dev.yml ps        # wait until hiee is "(healthy)"
```

Open http://localhost:8080/hi-ee/ and log in as `hiadmin` / `hiadmin`.

- The first build takes 15–40 minutes (Maven dependency download).
- `instantbi` is not needed for this feature and is left out to save memory.
- The container entrypoint rewrites `setting.xml` and `globalConnections.xml` in the mounted `hi-repository`. These changes are environment-specific and should not be committed.

## MongoDB setup (local test instance)

```bash
docker run -d --name hi-mongo -p 27018:27017 \
  -e MONGO_INITDB_ROOT_USERNAME=<username> -e MONGO_INITDB_ROOT_PASSWORD=<password> mongo:7.0

mongosh "mongodb://<username>:<password>@localhost:27018/hi_demo?authSource=admin" \
  --eval "db.orders.insertMany([{orderId:1,customer:'Asha',amount:250},{orderId:2,customer:'Ravi',amount:120}])"
```

## Creating a MongoDB connection

**Data Sources → No SQL & Big Data → Mongodb**, then click **Advanced** to show all fields.

| Field | Example | Notes |
|---|---|---|
| Host | `host.docker.internal` | When Helical Insight runs in Docker, `localhost` means the app container itself. |
| Port | `27018` | MongoDB default is `27017`. |
| Database Name | `hi_demo` | Required. |
| Datasource Name | `mongo_demo` | |
| User Name / Password | `<username>` / `<password>` | Leave empty if the server has no authentication. |
| Other Options (Advanced) | `?authSource=admin` | Appended **as typed** to the URL, so it must start with `?`. Join more options with `&`. |
| Datasource (Advanced) | NoSql Datasource | Selected automatically. |

The example above produces `mongodb://host.docker.internal:27018/hi_demo?authSource=admin`. Click **Test Connection**, then **Save Datasource**.

### Configuration notes

- **authSource:** MongoDB authenticates against the database in the URL unless `authSource` is given. Users created in the `admin` database (including the root user from `MONGO_INITDB_ROOT_USERNAME`) need `?authSource=admin`; without it the test reports "Authentication failed". This was found during testing (scenario 7 below).
- **TLS:** add `tls=true`, for example `?authSource=admin&tls=true`. Use `tlsAllowInvalidHostnames=true` only with test certificates.
- **Credentials in the URL** take precedence over the User Name and Password fields.

## Testing performed

### Build and dependency check

| Check | Result |
|---|---|
| Backend Docker build (`docker compose -f docker-compose.dev.yml up --build -d hiee`) | ✅ Built; `MongoNativeLoader` is present in `WEB-INF/lib/hi-core-7.0.0.jar`; container healthy |
| `mvn -pl core dependency:tree -Dincludes=org.mongodb` | ✅ A single driver: `org.mongodb:mongo-java-driver:jar:3.12.10:compile`, BUILD SUCCESS |

### Unit tests

```
mvn -pl core test -Dtest=MongoNativeLoaderTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in com.helicalinsight.datasource.nosql.MongoNativeLoaderTest
BUILD SUCCESS
```

| Test | Verifies |
|---|---|
| `rejectsNonMongoUrl` | A non-MongoDB URL is rejected with a helpful message |
| `malformedUrlDoesNotLeakPassword` | Errors for a malformed URL never contain the password |
| `requiresDatabaseName` | A missing database name is reported |
| `credentialsUseAuthSourceOption` | `?authSource=admin` is used as the credential source |
| `credentialsDefaultToConnectionDatabase` | Without `authSource`, the connection database is used (MongoDB's own default) |
| `unreachableServerFailsWithClearMessage` | An unreachable server fails within about 5 s with a clear message |

Without a local JDK, the same tests run in Docker (PowerShell, from the repository root):

```powershell
docker run --rm -v "${PWD}\server:/build" -v hi-m2:/root/.m2 -w /build maven:3.9-eclipse-temurin-25 mvn -B -pl core test "-Dtest=MongoNativeLoaderTest" "-Dsurefire.failIfNoSpecifiedTests=false" -Denv=docker -Dedition=hice
```

### Connection scenarios against the running application

Each scenario was sent to the same endpoint the UI's **Test Connection** button uses (`POST /hi-ee/services`, `type=core`, `serviceType=dataSource`, `service=test`). Scenarios 1 and 7 were also reproduced in the UI (screenshots 04 and 07).

| # | Scenario | Response message | Time |
|---|---|---|---|
| 1 | Valid connection | ✅ The connection test is successful. | 0.9 s |
| 2 | Wrong password | ✅ Authentication failed. Check the username, password and authSource option. | 0.3 s |
| 3 | Wrong port (27999) | ✅ Could not reach MongoDB at host.docker.internal:27999 within 5 seconds. Check the host and port, and that the server is running. | 5.2 s |
| 4 | Server stopped (`docker stop hi-mongo`) | ✅ Could not reach MongoDB at host.docker.internal:27018 within 5 seconds. … | 5.0 s |
| 5 | Missing database name | ✅ Database name is required, e.g. mongodb://host:27017/database | < 0.1 s |
| 6 | Malformed URL (`mongodb://bad::host:27018/…`) | ✅ The MongoDB connection URL is not valid. Expected format: mongodb://host:27017/database?authSource=admin | < 0.1 s |
| 7 | Root user without `authSource` | ✅ Authentication failed. … (expected; see Configuration notes) | 3.8 s |
| 8 | **Regression:** existing PostgreSQL (Tomcat JDBC) data source | ✅ The connection test is successful. | 0.3 s |

None of the failures crashed or blocked the application.

### Saved connection

The saved `mongo_demo` connection is stored in `ds_type_nosql` with `sub_type = com.helicalinsight.nosql.mongodb`, `data_source_pool_id = nosql_<id>` and URL `mongodb://host.docker.internal:27018/hi_demo?authSource=admin`. The password is stored encrypted, not in plain text. Reopening the saved connection shows all its settings, including Other Options (screenshot 06).

### Security checks

- Searching the Docker container log and the application log files for the test password: **0 matches**.
- Error messages never include the connection URL or the password (also covered by a unit test).

### Screenshots

| # | Screenshot |
|---|---|
| 01 | [Before: no MongoDB data source](docs/mongodb/01-before-no-mongodb.png) |
| 02 | [Regression baseline: PostgreSQL data source created](docs/mongodb/02-postgres-baseline-saved.png) |
| 03 | [After: Mongodb card](docs/mongodb/03-mongodb-card.png) |
| 04 | [Successful MongoDB connection test](docs/mongodb/04-mongodb-test-success.png) |
| 05 | [MongoDB data source saved](docs/mongodb/05-mongodb-saved.jpeg) |
| 06 | [Saved connection reopened](docs/mongodb/06-mongodb-saved-reopened.png) |
| 07 | [Authentication failure message](docs/mongodb/07-mongodb-auth-failed.png) |

## Known limitations

1. **Reporting on MongoDB data is not included.** This change covers configuring, testing, saving and reopening connections. Creating metadata and reports on MongoDB collections needs a SQL layer (for example the existing Drill route) and was not implemented.
2. **Driver version.** The bundled `mongo-java-driver` 3.12.10 officially supports MongoDB servers up to 4.4. The connection path was verified against MongoDB 7.0. Moving to driver 5.x is recommended as a follow-up (see design decision 2).
3. **`mongodb+srv://` (Atlas) is untested.** SRV lookups need the `jdk.naming.dns` module, which the jlink-built runtime in `server/Dockerfile` does not include. Adding `jdk.naming.dns` to the `jlink --add-modules` list should enable it.
4. **Two Mongodb cards when Drill is enabled.** The Drill-based entry was intentionally left unchanged.
5. **Warnings are hidden by default.** Failures are logged at WARN, but `log4j2.properties` sets `rootLogger.level = error`. To see them, add a logger for `com.helicalinsight.datasource.nosql` at level `warn`.
6. **The timeout is fixed at 5 seconds** and overrides `serverSelectionTimeoutMS` in the URL.
7. **Only targeted tests were run.** The full backend test suite was not run because of memory limits on the 8 GB test machine.
8. Tested on Windows 11 with Docker Desktop only.
