#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
lock_file="${root}/vendor/deps.lock"
vendor_dir="${root}/vendor"

sha256_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        sha256sum "$1" | awk '{print $1}'
    fi
}

clone_dep() {
    name=$1
    url=$2
    ref=$3
    dest="${vendor_dir}/${name}"

    if [ -d "${dest}/.git" ]; then
        printf '%s already exists; verifying pinned ref\n' "$name"
    else
        printf 'cloning %s\n' "$name"
        git clone --depth 1 "$url" "$dest"
    fi

    git -C "$dest" fetch --depth 1 origin "$ref"
    git -C "$dest" checkout --detach "$ref"
}

# Downloads a pinned .zip dependency, verifies its SHA-256, and extracts it
# flattened into the destination so files like sqlite3.c sit directly under
# the vendor directory. A stamp file records the extracted archive's hash so
# re-runs are no-ops until the pin changes.
download_dep() {
    name=$1
    url=$2
    sha256=$3
    dest=$4
    stamp="${dest}/.sha256"

    if [ -f "$stamp" ] && [ "$(cat "$stamp")" = "$sha256" ]; then
        printf '%s already exists; pinned archive hash matches\n' "$name"
        return
    fi

    printf 'downloading %s\n' "$name"
    tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/code-lens-vendor-XXXXXX")
    trap 'rm -rf "$tmp_dir"' EXIT
    archive="${tmp_dir}/archive.zip"
    curl -fsSL "$url" -o "$archive"

    actual=$(sha256_of "$archive")
    if [ "$actual" != "$sha256" ]; then
        printf '%s archive hash mismatch: expected %s, got %s\n' "$name" "$sha256" "$actual" >&2
        exit 1
    fi

    unzip -q -o "$archive" -d "${tmp_dir}/extract"
    rm -rf "$dest"
    mkdir -p "$dest"
    # Flatten a single wrapping directory if the archive has one.
    entries=$(find "${tmp_dir}/extract" -mindepth 1 -maxdepth 1)
    if [ "$(printf '%s\n' "$entries" | wc -l)" -eq 1 ] && [ -d "$entries" ]; then
        mv "$entries"/* "$dest"/
    else
        mv "${tmp_dir}/extract"/* "$dest"/
    fi
    printf '%s\n' "$sha256" > "$stamp"
    rm -rf "$tmp_dir"
    trap - EXIT
}

mkdir -p "$vendor_dir"

while IFS=' ' read -r name url ref license; do
    case "$name" in
        ''|'#'*) continue ;;
    esac

    if [ -z "${url:-}" ] || [ -z "${ref:-}" ] || [ -z "${license:-}" ]; then
        printf 'invalid dependency line for %s\n' "$name" >&2
        exit 1
    fi

    case "$name" in
        sqlite-amalgamation)
            download_dep "$name" "$url" "$ref" "${vendor_dir}/sqlite"
            ;;
        *)
            clone_dep "$name" "$url" "$ref"
            ;;
    esac
done < "$lock_file"
