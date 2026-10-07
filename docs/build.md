# Build and run

Claudia is a single Maven project targeting Java 25. Use a JDK 25 or newer
and Maven 3.9 or newer; set `JAVA_HOME` and put `mvn` on `PATH`. A regular
JDK is sufficient for the JVM build; GraalVM is needed only for native images.

See also [getting started](../README.md#getting-started),
[providers and credentials](providers.md), [terminal usage](terminal.md), and
[the developer guide](developer.md).

## Install from a checkout

```sh
./install.sh                 # macOS / Linux
```

```powershell
.\install.cmd               # Windows; invokes install.ps1
```

The installers build `target/claudia.jar` (skipping tests by default), attempt
to build the optional [code-lens](../code-lens/README.md) MCP server, and write
a launcher pointing at this checkout. The default launcher is
`~/.local/bin/claudia`, or `%USERPROFILE%\.local\bin\claudia.cmd` on Windows.
Add that directory to `PATH` if the installer reports it is missing. Re-run
the installer after pulling changes or moving the checkout.

| Option (*nix / Windows) | Effect |
| --- | --- |
| `--bin-dir DIR` / `-BinDir DIR` | Choose the launcher directory |
| `--native` / `-Native` | Build and launch the GraalVM executable instead of the JAR |
| `--run-tests` / `-RunTests` | Run Claudia tests; on *nix also run code-lens tests when built |

code-lens needs additional tools:

- **macOS/Linux:** Git, curl, unzip, make, shasum or sha256sum, an ISO C23
  compiler (or set `CC`), and zlib development headers/libraries.
- **Windows:** clang (MinGW clang, or LLVM clang with Windows SDK/MSVC runtime),
  Git for Windows, `curl.exe`, and `tar.exe`. The native Windows installer
  skips the code-lens test suite even with `-RunTests`.

Missing tools, failed downloads, or a failed code-lens build produce a warning
but do not prevent Claudia's installation. An older code-lens binary, if
present, remains in use. Otherwise its tools are not offered. Built-in
code-lens discovery expects the Claudia JAR/executable in the checkout's
`target/` directory and the server in `code-lens/build/`.

## Build manually

From the repository root:

```sh
mvn -B test
mvn -B package
java -jar target/claudia.jar --list-models
java -jar target/claudia.jar
```

`target/claudia.jar` is the runnable uber JAR; `target/claudia-thin.jar`
contains only this project's classes. `package` runs tests unless you pass
`-DskipTests`. Maven does not build code-lens; use the installer or its
[build instructions](../code-lens/README.md).

To run without a terminal, choose a current model from `--list-models` and
replace `PROVIDER/MODEL_ID` below (it is a placeholder):

```sh
java -jar target/claudia.jar --model PROVIDER/MODEL_ID \
  --print "Summarize the README" --no-session
```

For a Clojure tools.deps project, `--aliases :dev:reporting` forces that alias
basis on all code-lens MCP query, context, and SQL reads during the run. It is
not saved to settings:

```sh
java -jar target/claudia.jar --aliases :dev:reporting
```

### Rebuilding while Claudia is running

Packaging shades to `target/claudia.next.jar`, then publishes it by renaming
to `target/claudia.jar`, rather than rewriting the running JVM's archive.
Windows can prevent publication while the old JAR is locked. To stage a new
build without replacing it:

```sh
mvn -Dmaven.antrun.skip=true -DskipTests package
java -jar target/claudia.next.jar
```

New code takes effect only in a newly started process; use `/resume` if needed.
A running code-lens process can also lock its `.exe` on Windows.

## Native image

Set `JAVA_HOME` to a GraalVM JDK 25 with `native-image` and install its
platform build prerequisites, then:

```sh
mvn -B -Pnative package
./target/claudia --list-models
```

On Windows the output is `target/claudia.exe`. Native interactive mode is
supported on Linux and Apple silicon Macs, not Windows or Intel Macs; use
the JVM build for interactive mode there. Native images do not support the
[JDI debugger tools](tools/debugger.md). See [terminal requirements](terminal.md#terminal-requirements)
for terminal and native-access details.

Build settings and installer behavior are defined in [pom.xml](../pom.xml),
[install.sh](../install.sh), and [install.ps1](../install.ps1).
