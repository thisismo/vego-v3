# SQLDelight plugin for the Kotlin Toolchain

Runs the [SQLDelight](https://sqldelight.github.io/sqldelight/) compiler as a
[Kotlin Toolchain](https://github.com/JetBrains/kotlin-toolchain) (Amper) plugin. Write your SQL in
`.sq` files, get type-safe Kotlin APIs generated into your module — no Gradle involved.

Works for Kotlin Multiplatform modules (Android/iOS) as well as plain JVM modules.

```yaml
# module.yaml
plugins:
  sqldelight: enabled
```

```sql
-- src/sqldelight/User.sq
CREATE TABLE user (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL
);

selectAll:
SELECT *
FROM user;
```

```kotlin
val database = Database(driver)
val users = database.userQueries.selectAll().executeAsList()
```

## Status

The Kotlin Toolchain's [plugin API](https://amper.org/latest/user-guide/plugins/overview/) is
experimental and plugins are currently project-local — there is no plugin publishing yet. This
plugin is used by adding its module to your project. It is built against Kotlin Toolchain 0.11.x
and SQLDelight 2.3.2.

## Setup

1. Add this directory to your project, for example under `plugins/sqldelight`, either by copying it
   or as a git submodule:

   ```
   git submodule add https://github.com/<you>/<this-repo> plugins/sqldelight
   ```

2. Register it in `project.yaml`:

   ```yaml
   modules:
     - ...
     - plugins/sqldelight

   plugins:
     - ./plugins/sqldelight
   ```

   The plugin ID is pinned to `sqldelight` in `module.yaml`, so the directory name does not matter.

3. Enable it in the `module.yaml` of every module that owns a database, and add the SQLDelight
   runtime and a [driver](https://sqldelight.github.io/sqldelight/2.3.2/multiplatform_sqlite/) for
   each platform. The runtime version should match the compiler version pinned in this plugin's
   `module.yaml`:

   ```yaml
   product:
     type: kmp/lib
     platforms: [ android, iosArm64, iosSimulatorArm64 ]

   plugins:
     sqldelight: enabled

   dependencies:
     - app.cash.sqldelight:runtime:2.3.2

   dependencies@android:
     - app.cash.sqldelight:android-driver:2.3.2

   dependencies@ios:
     - app.cash.sqldelight:native-driver:2.3.2
   ```

4. Put your `.sq` files under `src/sqldelight/` and build. The generated sources are compiled into
   the module automatically; there is no separate task to invoke.

## Source layout

The module's source roots (`src`, and platform-qualified variants like `src@android`) act as
SQLDelight source folders. The package of the generated code for a `.sq` file is derived from its
directory path relative to the source root — the same rule SQLDelight applies to
`src/main/sqldelight` in Gradle projects:

| File                              | Generated queries class       |
|-----------------------------------|-------------------------------|
| `src/sqldelight/User.sq`          | `sqldelight.UserQueries`      |
| `src/com/example/db/User.sq`      | `com.example.db.UserQueries`  |
| `src@android/sqldelight/Sync.sq`  | `sqldelight.SyncQueries`      |

`.sq` files cannot sit directly in a source root; SQLDelight requires them to live in a package
directory. The default settings assume the `src/sqldelight/` convention — if you prefer proper
package directories, set `packageName` accordingly (see below).

## Configuration

All settings are optional. Configure them under the plugin entry in `module.yaml`:

```yaml
plugins:
  sqldelight:
    enabled: true
    packageName: com.example.db
    databaseClassName: AppDatabase
    dialect: sqlite-3-38
```

| Setting                         | Default      | Description                                                                                                                             |
|---------------------------------|--------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| `packageName`                   | `sqldelight` | Package of the generated database interface and its implementation. Independent of the query packages, which follow the directory layout. |
| `databaseClassName`             | `Database`   | Name of the generated database interface.                                                                                                 |
| `dialect`                       | `sqlite-3-18`| SQL dialect used to parse and type-check the files. See below.                                                                            |
| `generateAsync`                 | `false`      | Generate suspending query APIs for asynchronous drivers.                                                                                  |
| `deriveSchemaFromMigrations`    | `false`      | Build the schema from `.sqm` migration files instead of `CREATE` statements in `.sq` files.                                               |
| `verifyMigrations`              | `false`      | Type-check `.sqm` files during code generation and fail the build on errors.                                                              |
| `treatNullAsUnknownForEquality` | `false`      | Keep SQL semantics for `= NULL` instead of rewriting it to `IS NULL`.                                                                     |
| `expandSelectStar`              | `true`       | Expand `SELECT *` into explicit column lists at compile time.                                                                             |
| `additionalSourceDirectories`   | `[]`         | Extra directories to compile SQL from, resolved against the module root. See [Sharing SQL between modules](#sharing-sql-between-modules). |

### Dialects

`sqlite-3-18`, `sqlite-3-24`, `sqlite-3-25`, `sqlite-3-30`, `sqlite-3-33`, `sqlite-3-35`,
`sqlite-3-38`, `postgresql`, `hsql`.

The SQLite dialects are cumulative; pick the version matching the oldest SQLite you ship on. For
Android, the OS-bundled SQLite version depends on the API level — `sqlite-3-18` is safe on
API 26+.

The MySQL dialect is not bundled: it depends on `mysql:mysql-connector-java`, which only exists as
a relocation POM that the Kotlin Toolchain's dependency resolver cannot follow yet.

### Migrations

`.sqm` files are picked up from the same source folders (for example
`src/sqldelight/migrations/1.sqm`). By default they are only carried along; enable
`verifyMigrations` to have them type-checked at build time, or `deriveSchemaFromMigrations` to
treat them as the source of truth for the schema.

### Sharing SQL between modules

A module can compile `.sq`/`.sqm` files that live in another module by listing extra source
directories. Relative paths are resolved against the module root:

```yaml
plugins:
  sqldelight:
    enabled: true
    additionalSourceDirectories: [ ../common/sqldelight ]
```

With `../common/sqldelight/shared/User.sq`, this generates `shared.UserQueries` into the module
above, together with its own SQL. Changes to the shared files retrigger code generation — the
directories are tracked as task inputs.

Each entry acts as a SQLDelight source folder, so the package of the generated code is derived
from a file's directory path below the entry — the files need at least one directory level, same
as in `src`.

Two things to keep in mind:

- The owning module should treat the shared directory as data-only and not compile it itself
  (don't place it under that module's `src`). Every module listing the directory generates its own
  copy of the classes, so two such modules must not end up on the same compile classpath, or the
  duplicates will clash.
- The toolchain rejects task inputs that nest, so the directory must lie outside the consuming
  module's own root. Inside the module, `src` already covers everything.

## How it works

SQLDelight's compiler is not tied to Gradle — the Gradle plugin merely assembles a project model
and hands it to a headless compilation environment (`SqlDelightEnvironment`). This plugin builds
that model directly from the toolchain module: one compilation unit spanning all source roots,
compiled with the configured dialect into the task's output directory, which is registered as a
generated-sources root via `plugin.yaml`.

Code generation is incremental at the task level: the toolchain's execution avoidance reruns the
task when files in the module change. Each run replaces the output directory wholesale, so renamed
or deleted `.sq` files never leave stale generated code behind.

## Limitations

- One database per module. Multiple databases require multiple modules.
- No schema dependencies between modules (SQLDelight's `dependency()` mechanism is not wired up).
  Sharing SQL sources via `additionalSourceDirectories` covers the common cases.
- No `.db` schema file output and no migration squashing — the Gradle-only auxiliary tasks are not
  replicated. Migration *verification* against a real driver is likewise out of scope; what
  `verifyMigrations` gives you is compiler-level type checking.
- Only the toolchain's default file layout is supported (source roots named `src` or `src@...`).

## Development

The module ships with a JVM test suite that runs the real compiler against fixture projects. From
the root of a project that includes the plugin:

```
./kotlin test -m sqldelight
```

(The module name is the plugin's directory name in your project.)

Version bumps: update the SQLDelight artifacts in `module.yaml` (all of them share one version) and
keep `app.cash.sql-psi:environment` at the version the matching
`app.cash.sqldelight:gradle-plugin` POM declares.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
