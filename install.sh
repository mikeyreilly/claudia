#!/bin/sh
# Builds Claudia and its built-in code-lens MCP server in this checkout and
# writes a `claudia` launcher that runs them in place.
#
# code-lens is optional: when it cannot be built (no suitable C toolchain, or
# its vendored sources cannot be downloaded) a WARNING is printed and
# Claudia is still installed, just without the code-lens integration.
set -eu

usage() {
    cat <<'EOF'
Usage: ./install.sh [options]

Builds Claudia (target/claudia.jar) and code-lens
(code-lens/build/code-lens) in this checkout, then writes a `claudia`
launcher that runs this checkout's build. Re-run it to update after pulling
changes, or after moving the checkout.

Options:
  --bin-dir DIR   Directory for the launcher (default: ~/.local/bin)
  --native        Build the GraalVM native executable and launch it instead
                  of the jar (requires native-image)
  --run-tests     Run the Claudia and code-lens test suites
  -h, --help      Show this help

Requirements: a JDK 25 or newer (JAVA_HOME or java on PATH) and Maven.
code-lens additionally needs git, curl, unzip, make, a C compiler with ISO C23
support, and zlib headers; without them Claudia is installed without it.
EOF
}

say() { printf '==> %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

warnings=''
warn() {
    printf 'WARNING: %s\n' "$*" >&2
    warnings="${warnings}WARNING: $*
"
}

# Single-quotes a value for safe inclusion in a generated shell script.
quote() {
    printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

root=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
code_lens_dir="${root}/code-lens"
code_lens_bin="${code_lens_dir}/build/code-lens"
bin_dir="${HOME}/.local/bin"
native=0
run_tests=0

while [ $# -gt 0 ]; do
    case "$1" in
        --bin-dir)
            [ $# -ge 2 ] || die '--bin-dir requires a directory'
            bin_dir=$2
            shift 2
            ;;
        --bin-dir=*) bin_dir=${1#--bin-dir=}; shift ;;
        --native) native=1; shift ;;
        --run-tests) run_tests=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) printf 'install.sh: unknown option: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
done

# --------------------------------------------------------------- prerequisites

if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    java_cmd="${JAVA_HOME}/bin/java"
elif command -v java >/dev/null 2>&1; then
    java_cmd=$(command -v java)
else
    die 'Java was not found. Install a JDK 25 or newer, then set JAVA_HOME or put java on PATH.'
fi
java_version=$("$java_cmd" -version 2>&1 | sed -n 's/.* version "\([^"]*\)".*/\1/p' | head -n 1)
java_major=${java_version%%[.+-]*}
case "$java_major" in
    ''|*[!0-9]*) die "Could not determine the version of ${java_cmd}." ;;
esac
[ "$java_major" -ge 25 ] || die "Claudia needs a JDK 25 or newer, but ${java_cmd} is version ${java_version}."

command -v mvn >/dev/null 2>&1 || die 'Maven (mvn) was not found on PATH. Install Apache Maven 3.9 or newer.'

if [ "$native" -eq 1 ]; then
    if [ -x "$(dirname -- "$java_cmd")/native-image" ] || command -v native-image >/dev/null 2>&1; then
        :
    else
        die '--native needs GraalVM native-image; use a GraalVM JDK 25 as JAVA_HOME.'
    fi
    if [ "$(uname -s)" = Darwin ] && [ "$(uname -m)" != arm64 ]; then
        warn 'native executables do not support interactive mode on Intel Macs; consider installing without --native'
    fi
fi

# ------------------------------------------------------------------ code-lens

code_lens_skipped() {
    warn "code-lens was not built: $1"
    if [ -x "$code_lens_bin" ]; then
        warn "a code-lens binary from an earlier build remains in code-lens/build and will still be used"
    else
        warn 'Claudia will be installed without its built-in code-lens MCP server'
    fi
}

# True when every pinned dependency in vendor/deps.lock is already in place, so
# a rebuild needs no network access.
code_lens_vendor_current() {
    while IFS=' ' read -r name _url ref _license; do
        case "$name" in
            ''|'#'*) continue ;;
            sqlite-amalgamation)
                stamp="${code_lens_dir}/vendor/sqlite/.sha256"
                [ -f "$stamp" ] && [ "$(cat "$stamp")" = "$ref" ] || return 1
                ;;
            *)
                [ -d "${code_lens_dir}/vendor/${name}/.git" ] || return 1
                [ "$(git -C "${code_lens_dir}/vendor/${name}" rev-parse HEAD 2>/dev/null)" = "$ref" ] || return 1
                ;;
        esac
    done < "${code_lens_dir}/vendor/deps.lock"
}

build_code_lens() {
    missing=''
    for tool in git curl unzip make; do
        command -v "$tool" >/dev/null 2>&1 || missing="${missing} ${tool}"
    done
    if ! command -v shasum >/dev/null 2>&1 && ! command -v sha256sum >/dev/null 2>&1; then
        missing="${missing} shasum/sha256sum"
    fi
    if [ -n "$missing" ]; then
        code_lens_skipped "missing required tools:${missing}"
        return 1
    fi

    if [ -n "${CC:-}" ]; then candidates=$CC; else candidates='cc gcc clang'; fi
    cc=''
    for candidate in $candidates; do
        if command -v "$candidate" >/dev/null 2>&1 \
            && sh "${code_lens_dir}/scripts/detect-c23-flag.sh" "$candidate" >/dev/null 2>&1; then
            cc=$candidate
            break
        fi
    done
    if [ -z "$cc" ]; then
        code_lens_skipped "no C compiler with ISO C23 support was found (tried: ${candidates}); install a recent clang or gcc, or set CC"
        return 1
    fi

    probe=$(mktemp -d "${TMPDIR:-/tmp}/claudia-install-XXXXXX")
    printf '#include <zlib.h>\nint main(void) { return zlibVersion() == 0; }\n' > "${probe}/zlib.c"
    if ! "$cc" "${probe}/zlib.c" -o "${probe}/zlib" -lz >/dev/null 2>&1; then
        rm -rf "$probe"
        code_lens_skipped 'zlib development files were not found (for example: apt install zlib1g-dev, or dnf install zlib-devel)'
        return 1
    fi
    rm -rf "$probe"

    if code_lens_vendor_current; then
        say 'code-lens vendored dependencies are up to date'
    else
        say 'Downloading code-lens vendored dependencies'
        if ! sh "${code_lens_dir}/scripts/vendor-deps.sh"; then
            code_lens_skipped 'its vendored dependencies could not be downloaded (see the output above; check access to github.com and sqlite.org)'
            return 1
        fi
    fi

    jobs=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)
    say "Building code-lens with ${cc}"
    if ! make -C "$code_lens_dir" -j"$jobs" CC="$cc" release; then
        code_lens_skipped 'the build failed (see the output above)'
        return 1
    fi
    if [ ! -x "$code_lens_bin" ]; then
        code_lens_skipped "the build did not produce ${code_lens_bin}"
        return 1
    fi

    if [ "$run_tests" -eq 1 ]; then
        say 'Running the code-lens tests'
        make -C "$code_lens_dir" -j"$jobs" CC="$cc" test \
            || warn 'code-lens tests failed (see the output above); the code-lens binary that was built will still be used'
    fi
}

build_code_lens || true

# ---------------------------------------------------------------- Claudia

set -- -B -f "${root}/pom.xml"
[ "$run_tests" -eq 1 ] || set -- "$@" -DskipTests
[ "$native" -eq 0 ] || set -- "$@" -Pnative
say "Building Claudia with Maven (mvn $* package)"
mvn "$@" package || die 'The Maven build failed; see the output above.'

if [ "$native" -eq 1 ]; then
    program="${root}/target/claudia"
    [ -x "$program" ] || die "The native build did not produce ${program}."
    launch="exec $(quote "$program") \"\$@\""
else
    program="${root}/target/claudia.jar"
    [ -f "$program" ] || die "The Maven build did not produce ${program}."
    launch="java=$(quote "$java_cmd")
[ -x \"\$java\" ] || java=java
exec \"\$java\" -jar $(quote "$program") \"\$@\""
fi

# ------------------------------------------------------------------- launcher

mkdir -p "$bin_dir" || die "Could not create ${bin_dir}."
bin_dir=$(CDPATH='' cd -- "$bin_dir" && pwd)
launcher="${bin_dir}/claudia"
content="#!/bin/sh
# Generated by install.sh in the Claudia checkout; re-run it after moving
# the checkout. Runs that checkout's build, which finds its code-lens build.
${launch}"

if [ -f "$launcher" ] && [ "$(cat "$launcher")" = "$content" ]; then
    say "Launcher ${launcher} is up to date"
else
    tmp="${launcher}.tmp.$$"
    if printf '%s\n' "$content" > "$tmp" && chmod 755 "$tmp" && mv -f "$tmp" "$launcher"; then
        say "Wrote launcher ${launcher}"
    else
        rm -f "$tmp"
        die "Could not write ${launcher}."
    fi
fi

# -------------------------------------------------------------------- summary

printf '\n'
say 'Claudia is installed'
printf '    launcher:  %s\n' "$launcher"
printf '    runs:      %s\n' "$program"
if [ -x "$code_lens_bin" ]; then
    printf '    code-lens: %s (built-in MCP server)\n' "$code_lens_bin"
else
    printf '    code-lens: not available\n'
fi
if [ -n "$warnings" ]; then
    printf '\n%s' "$warnings" >&2
fi
case ":${PATH}:" in
    *":${bin_dir}:"*) ;;
    *)
        printf '\nNOTE: %s is not on your PATH. Add it in your shell startup file\n' "$bin_dir"
        printf '      (for example ~/.zshrc, ~/.bashrc, or ~/.profile):\n'
        printf '          export PATH=%s:"$PATH"\n' "$(quote "$bin_dir")"
        ;;
esac
