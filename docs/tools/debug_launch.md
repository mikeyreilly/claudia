# `debug_launch`

Starts a Java main class, or a single JUnit test, under the debugger and returns
a new session. See [debugger concepts](debugger.md).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `main_class` | string | — | Fully qualified main class, for example `com.example.Main`. Give exactly one of `main_class` and `test_selector`. |
| `test_selector` | string | — | `com.example.FooTest` or `com.example.FooTest#method`. |
| `module` | string | none | Run `main_class` from this module (`--module module/main`). |
| `working_directory` | string | workspace | Directory to run the target in. |
| `arguments` / `args` | string[] | none | Program arguments. Give at most one of the two. |
| `jvm_options` | string[] | none | Extra JVM options (main class only). |
| `classpath` | string[] | `target/classes`, if it exists | Classpath entries (main class only). |
| `module_path` | string[] | none | Module path entries (main class only). |
| `environment` | map | none | Environment variables to set (main class only; at most 64). |
| `stop_on_entry` | boolean | `false` | Stop before any application code runs. |
| `capture_output` | boolean | `true` | Capture stdout/stderr so [`debug_output`](debug_output.md) can read them. |
| `launch_timeout_ms` | integer | `15000` | How long to wait for the JDWP connection, between 1,000 and 120,000 ms. |
| `source_roots` | string[] | — | Not implemented; giving it returns an error. |
| `idempotency_key` | string | none | See [concepts](debugger.md#idempotency). |

## Behaviour

### Main class

Claudia starts the JVM it is running on, with
`-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:<port>`,
then connects with JDI. Unless `stop_on_entry` is set, the target is resumed as
soon as the debugger is set up.

### Test

[`MavenTestLaunch`](../../src/main/java/com/quaxt/claudia/debug/MavenTestLaunch.java)
runs only the Maven Surefire goal for the selected test, with its forked JVM
debuggable. Claudia then identifies that fork's PID and attaches to it.

Test launches have these limits:

- one Maven project, using Surefire and JUnit Jupiter
- the top-level test class must already be compiled into `target/test-classes`
- `class#method` must name a single `@Test` method that takes no arguments
- `environment`, `jvm_options`, `classpath`, `module` and `module_path` are
  rejected

The exit code is reported with `exit_code_source: "maven_runner"`.

### Output and result

Output capture is turned off if a sensitive environment value is too long to
redact safely. The result is the full [`debug_status`](debug_status.md) of the
new session.

## Errors

- `invalid_argument`: both or neither of `main_class` and `test_selector`, or a
  bad name.
- `limit`: too many sessions.
- `timeout`: the JDWP connection was not ready in time.
- `ambiguous_target`: the Surefire fork could not be identified.
