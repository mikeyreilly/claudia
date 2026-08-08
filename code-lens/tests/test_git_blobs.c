/* Tests for the git blob source: indexing reads clean tracked files from
 * hand-built .git fixtures (loose objects, packs with non-delta, ofs-delta
 * and ref-delta entries) and falls back to the working tree for anything
 * uncertain. Counters exported from code_lens.c distinguish the paths. */

#include "code_lens.h"

#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>
#include <zlib.h>

int test_git_blobs(void);

#define FIXED_MTIME 1000000000U /* well in the past: never racy */

/* --- minimal SHA-1 (needed to build object ids and pack indexes) --- */

typedef struct {
    uint32_t h[5];
    uint64_t total_len;
    uint8_t buf[64];
    size_t buf_len;
} Sha1;

static uint32_t sha1_rotl(uint32_t value, unsigned int bits)
{
    return (value << bits) | (value >> (32U - bits));
}

static void sha1_init(Sha1 *s)
{
    (void)memset(s, 0, sizeof(*s));
    s->h[0] = 0x67452301U;
    s->h[1] = 0xEFCDAB89U;
    s->h[2] = 0x98BADCFEU;
    s->h[3] = 0x10325476U;
    s->h[4] = 0xC3D2E1F0U;
}

static void sha1_block(Sha1 *s, const uint8_t *block)
{
    uint32_t w[80];
    uint32_t a = s->h[0];
    uint32_t b = s->h[1];
    uint32_t c = s->h[2];
    uint32_t d = s->h[3];
    uint32_t e = s->h[4];

    for (size_t i = 0U; i < 16U; i++) {
        w[i] = ((uint32_t)block[i * 4U] << 24) | ((uint32_t)block[i * 4U + 1U] << 16) |
               ((uint32_t)block[i * 4U + 2U] << 8) | (uint32_t)block[i * 4U + 3U];
    }
    for (size_t i = 16U; i < 80U; i++) {
        w[i] = sha1_rotl(w[i - 3U] ^ w[i - 8U] ^ w[i - 14U] ^ w[i - 16U], 1U);
    }
    for (size_t i = 0U; i < 80U; i++) {
        uint32_t f;
        uint32_t k;

        if (i < 20U) {
            f = (b & c) | ((~b) & d);
            k = 0x5A827999U;
        } else if (i < 40U) {
            f = b ^ c ^ d;
            k = 0x6ED9EBA1U;
        } else if (i < 60U) {
            f = (b & c) | (b & d) | (c & d);
            k = 0x8F1BBCDCU;
        } else {
            f = b ^ c ^ d;
            k = 0xCA62C1D6U;
        }
        uint32_t temp = sha1_rotl(a, 5U) + f + e + k + w[i];
        e = d;
        d = c;
        c = sha1_rotl(b, 30U);
        b = a;
        a = temp;
    }
    s->h[0] += a;
    s->h[1] += b;
    s->h[2] += c;
    s->h[3] += d;
    s->h[4] += e;
}

static void sha1_update(Sha1 *s, const void *data, size_t len)
{
    const uint8_t *p = data;

    s->total_len += len;
    while (len > 0U) {
        size_t take = 64U - s->buf_len;

        if (take > len) {
            take = len;
        }
        (void)memcpy(s->buf + s->buf_len, p, take);
        s->buf_len += take;
        p += take;
        len -= take;
        if (s->buf_len == 64U) {
            sha1_block(s, s->buf);
            s->buf_len = 0U;
        }
    }
}

static void sha1_final(Sha1 *s, uint8_t out[20])
{
    uint64_t bit_len = s->total_len * 8U;
    uint8_t pad = 0x80U;
    uint8_t zero = 0U;
    uint8_t len_bytes[8];

    sha1_update(s, &pad, 1U);
    while (s->buf_len != 56U) {
        sha1_update(s, &zero, 1U);
    }
    for (size_t i = 0U; i < 8U; i++) {
        len_bytes[i] = (uint8_t)(bit_len >> (56U - i * 8U));
    }
    sha1_update(s, len_bytes, 8U);
    for (size_t i = 0U; i < 5U; i++) {
        out[i * 4U] = (uint8_t)(s->h[i] >> 24);
        out[i * 4U + 1U] = (uint8_t)(s->h[i] >> 16);
        out[i * 4U + 2U] = (uint8_t)(s->h[i] >> 8);
        out[i * 4U + 3U] = (uint8_t)s->h[i];
    }
}

static void blob_oid(uint8_t out[20], const char *content, size_t len)
{
    char header[40];
    int header_len = snprintf(header, sizeof(header), "blob %zu", len);
    Sha1 s;

    sha1_init(&s);
    sha1_update(&s, header, (size_t)header_len + 1U); /* includes the NUL */
    sha1_update(&s, content, len);
    sha1_final(&s, out);
}

/* --- fixture writing helpers --- */

static int write_bytes_file(const char *path, const void *data, size_t len)
{
    FILE *f = fopen(path, "wb");

    if (f == nullptr) {
        return -1;
    }
    if ((len > 0U) && (fwrite(data, 1U, len, f) != len)) {
        (void)fclose(f);
        return -1;
    }
    return fclose(f) == 0 ? 0 : -1;
}

static int set_file_mtime(const char *path, uint32_t seconds)
{
    struct timeval times[2];

    times[0].tv_sec = (time_t)seconds;
    times[0].tv_usec = 0;
    times[1] = times[0];
    return utimes(path, times);
}

/* Writes a working file with a fixed old mtime so it is clean and
 * non-racy relative to a freshly written index. */
static int write_worktree_file(const char *repo, const char *rel, const char *content)
{
    char *path = code_lens_join_path(repo, rel);

    if (path == nullptr) {
        return -1;
    }
    if (write_bytes_file(path, content, strlen(content)) != 0) {
        return -1;
    }
    return set_file_mtime(path, FIXED_MTIME);
}

typedef struct {
    const char *rel_path; /* entries must be supplied in sorted order */
    const char *content;
    uint32_t mtime;
} TestIndexEntry;

static void put_be32(uint8_t *p, uint32_t value)
{
    p[0] = (uint8_t)(value >> 24);
    p[1] = (uint8_t)(value >> 16);
    p[2] = (uint8_t)(value >> 8);
    p[3] = (uint8_t)value;
}

/* Writes a v2 .git/index. Stat fields the parser ignores are zero. */
static int write_git_index(const char *gitdir, const TestIndexEntry *entries, size_t count)
{
    uint8_t buf[8192];
    size_t off = 12U;
    char *path = code_lens_join_path(gitdir, "index");

    if (path == nullptr) {
        return -1;
    }
    (void)memcpy(buf, "DIRC", 4U);
    put_be32(buf + 4, 2U);
    put_be32(buf + 8, (uint32_t)count);
    for (size_t i = 0U; i < count; i++) {
        size_t name_len = strlen(entries[i].rel_path);
        size_t content_len = strlen(entries[i].content);
        size_t entry_len = (62U + name_len + 8U) & ~(size_t)7U;
        uint8_t *e = buf + off;

        if ((off + entry_len + 20U) > sizeof(buf)) {
            return -1;
        }
        (void)memset(e, 0, entry_len);
        put_be32(e + 8, entries[i].mtime); /* mtime seconds; nsec stays 0 */
        put_be32(e + 24, 0100644U);
        put_be32(e + 36, (uint32_t)content_len);
        blob_oid(e + 40, entries[i].content, content_len);
        e[60] = (uint8_t)(name_len >> 8);
        e[61] = (uint8_t)(name_len & 0xFFU);
        (void)memcpy(e + 62, entries[i].rel_path, name_len);
        off += entry_len;
    }
    (void)memset(buf + off, 0, 20U); /* checksum trailer (unverified) */
    return write_bytes_file(path, buf, off + 20U);
}

static int write_loose_object(const char *gitdir, const char *content)
{
    static const char hex[] = "0123456789abcdef";
    uint8_t oid[20];
    char header[40];
    size_t content_len = strlen(content);
    int header_len = snprintf(header, sizeof(header), "blob %zu", content_len);
    uint8_t raw[4096];
    uint8_t packed[4096];
    uLongf packed_len = sizeof(packed);
    size_t raw_len = (size_t)header_len + 1U + content_len;
    char rel[64];
    char *dir_path;
    char *obj_path;

    if (raw_len > sizeof(raw)) {
        return -1;
    }
    (void)memcpy(raw, header, (size_t)header_len + 1U);
    (void)memcpy(raw + header_len + 1, content, content_len);
    if (compress2(packed, &packed_len, raw, (uLong)raw_len, 6) != Z_OK) {
        return -1;
    }
    blob_oid(oid, content, content_len);
    (void)snprintf(rel, sizeof(rel), "objects/%c%c", hex[oid[0] >> 4], hex[oid[0] & 15U]);
    dir_path = code_lens_join_path(gitdir, rel);
    if ((dir_path == nullptr) || (code_lens_mkdir_p(dir_path) != 0)) {
        return -1;
    }
    rel[10] = '/';
    for (size_t i = 1U; i < 20U; i++) {
        rel[9U + i * 2U] = hex[oid[i] >> 4];
        rel[10U + i * 2U] = hex[oid[i] & 15U];
    }
    rel[9U + 40U] = '\0';
    obj_path = code_lens_join_path(gitdir, rel);
    if (obj_path == nullptr) {
        return -1;
    }
    return write_bytes_file(obj_path, packed, packed_len);
}

/* --- pack construction: one full blob + one ofs-delta + one ref-delta --- */

static size_t append_pack_header(uint8_t *out, int type, size_t size)
{
    size_t n = 0U;
    uint8_t byte = (uint8_t)((type << 4) | (size & 15U));

    size >>= 4;
    while (size > 0U) {
        out[n++] = byte | 0x80U;
        byte = (uint8_t)(size & 0x7FU);
        size >>= 7;
    }
    out[n++] = byte;
    return n;
}

static size_t append_zlib(uint8_t *out, const void *data, size_t len)
{
    uLongf packed_len = 4096U;

    if (compress2(out, &packed_len, data, (uLong)len, 6) != Z_OK) {
        return 0U;
    }
    return packed_len;
}

/* Delta producing `target` from `base`: copy the shared prefix, then
 * insert the rest as a literal. Returns delta length. */
static size_t build_delta(uint8_t *out, const char *base, const char *target)
{
    size_t base_len = strlen(base);
    size_t target_len = strlen(target);
    size_t prefix = 0U;
    size_t n = 0U;
    size_t rest;

    while ((prefix < base_len) && (prefix < target_len) && (base[prefix] == target[prefix])) {
        prefix++;
    }
    if (prefix > 100U) {
        prefix = 100U;
    }
    out[n++] = (uint8_t)base_len; /* varint, sizes < 128 in these fixtures */
    out[n++] = (uint8_t)target_len;
    if (prefix > 0U) {
        out[n++] = 0x80U | 0x10U; /* copy: offset 0, one length byte */
        out[n++] = (uint8_t)prefix;
    }
    rest = target_len - prefix;
    out[n++] = (uint8_t)rest; /* literal insert */
    (void)memcpy(out + n, target + prefix, rest);
    return n + rest;
}

typedef struct {
    const char *base;    /* full blob stored as-is */
    const char *ofs;     /* stored as ofs-delta against base */
    const char *ref;     /* stored as ref-delta against base */
} PackTrio;

static int write_git_pack(const char *gitdir, const PackTrio *trio)
{
    uint8_t pack[16384];
    uint8_t delta[4096];
    size_t off = 12U;
    uint8_t oids[3][20];
    uint64_t offsets[3];
    size_t z;
    size_t delta_len;
    int order[3] = {0, 1, 2};
    uint8_t idx[8192];
    size_t idx_off;
    char *pack_dir = code_lens_join_path(gitdir, "objects/pack");
    char *pack_path;
    char *idx_path;

    if ((pack_dir == nullptr) || (code_lens_mkdir_p(pack_dir) != 0)) {
        return -1;
    }
    blob_oid(oids[0], trio->base, strlen(trio->base));
    blob_oid(oids[1], trio->ofs, strlen(trio->ofs));
    blob_oid(oids[2], trio->ref, strlen(trio->ref));

    (void)memcpy(pack, "PACK", 4U);
    put_be32(pack + 4, 2U);
    put_be32(pack + 8, 3U);

    /* object 0: plain blob */
    offsets[0] = off;
    off += append_pack_header(pack + off, 3, strlen(trio->base));
    z = append_zlib(pack + off, trio->base, strlen(trio->base));
    if (z == 0U) {
        return -1;
    }
    off += z;

    /* object 1: ofs-delta against object 0 (distance fits one byte) */
    offsets[1] = off;
    delta_len = build_delta(delta, trio->base, trio->ofs);
    off += append_pack_header(pack + off, 6, delta_len);
    if ((offsets[1] - offsets[0]) >= 128U) {
        return -1;
    }
    pack[off++] = (uint8_t)(offsets[1] - offsets[0]);
    z = append_zlib(pack + off, delta, delta_len);
    if (z == 0U) {
        return -1;
    }
    off += z;

    /* object 2: ref-delta against object 0 */
    offsets[2] = off;
    delta_len = build_delta(delta, trio->base, trio->ref);
    off += append_pack_header(pack + off, 7, delta_len);
    (void)memcpy(pack + off, oids[0], 20U);
    off += 20U;
    z = append_zlib(pack + off, delta, delta_len);
    if (z == 0U) {
        return -1;
    }
    off += z;

    (void)memset(pack + off, 0, 20U); /* trailer checksum (unverified) */
    pack_path = code_lens_join_path(pack_dir, "pack-test.pack");
    if ((pack_path == nullptr) || (write_bytes_file(pack_path, pack, off + 20U) != 0)) {
        return -1;
    }

    /* sort oid order for the .idx sha table */
    for (int i = 0; i < 2; i++) {
        for (int j = i + 1; j < 3; j++) {
            if (memcmp(oids[order[i]], oids[order[j]], 20U) > 0) {
                int swap = order[i];

                order[i] = order[j];
                order[j] = swap;
            }
        }
    }

    put_be32(idx, 0xff744f63U);
    put_be32(idx + 4, 2U);
    idx_off = 8U;
    for (unsigned int b = 0U; b < 256U; b++) {
        uint32_t cumulative = 0U;

        for (int i = 0; i < 3; i++) {
            if (oids[i][0] <= b) {
                cumulative++;
            }
        }
        put_be32(idx + idx_off, cumulative);
        idx_off += 4U;
    }
    for (int i = 0; i < 3; i++) {
        (void)memcpy(idx + idx_off, oids[order[i]], 20U);
        idx_off += 20U;
    }
    (void)memset(idx + idx_off, 0, 3U * 4U); /* crc32 table (unverified) */
    idx_off += 3U * 4U;
    for (int i = 0; i < 3; i++) {
        put_be32(idx + idx_off, (uint32_t)offsets[order[i]]);
        idx_off += 4U;
    }
    (void)memset(idx + idx_off, 0, 40U); /* trailing checksums (unverified) */
    idx_off += 40U;
    idx_path = code_lens_join_path(pack_dir, "pack-test.idx");
    if (idx_path == nullptr) {
        return -1;
    }
    return write_bytes_file(idx_path, idx, idx_off);
}

/* --- scenario helpers --- */

static int git_assert(int condition, const char *message)
{
    if (!condition) {
        (void)fprintf(stderr, "git blob test failed: %s\n", message);
        return 1;
    }
    return 0;
}

static char *make_repo(const char *root, const char *name)
{
    char *repo = code_lens_join_path(root, name);
    char *gitdir = repo == nullptr ? nullptr : code_lens_join_path(repo, ".git");

    if ((gitdir == nullptr) || (code_lens_mkdir_p(gitdir) != 0)) {
        return nullptr;
    }
    return repo;
}

static int index_repo_counting(const char *repo,
                               CodeLensIndexStats *stats,
                               uint64_t *blob_delta,
                               uint64_t *file_delta)
{
    uint64_t blobs_before = code_lens_git_blob_read_count();
    uint64_t files_before = code_lens_git_file_read_count();
    int rc = code_lens_index_repository(repo, stats);

    *blob_delta = code_lens_git_blob_read_count() - blobs_before;
    *file_delta = code_lens_git_file_read_count() - files_before;
    return rc;
}

static int query_contains(const char *repo_name, const char *term, const char *expected)
{
    char *text = code_lens_query_symbols(repo_name, term, 10);
    int found = (text != nullptr) && (strstr(text, expected) != nullptr);

    return found ? 0 : 1;
}

int test_git_blobs(void)
{
    char root_template[1024];
    const char *tmp_dir = getenv("TMPDIR");
    const char *old_home = getenv("CODE_LENS_HOME");
    char saved_home[1024] = {0};
    bool had_home = false;
    char *root;
    char *home_dir;
    CodeLensIndexStats stats;
    uint64_t blob_delta;
    uint64_t file_delta;
    int failed = 0;

    if ((old_home != nullptr) && (strlen(old_home) < sizeof(saved_home))) {
        (void)strcpy(saved_home, old_home);
        had_home = true;
    }
    if ((tmp_dir == nullptr) || (tmp_dir[0] == '\0')) {
        tmp_dir = "/tmp";
    }
    (void)snprintf(root_template, sizeof(root_template), "%s/code-lens-git-XXXXXX", tmp_dir);
    root = mkdtemp(root_template);
    if (root == nullptr) {
        (void)fprintf(stderr, "git blob test failed: mkdtemp failed\n");
        return 1;
    }
    home_dir = code_lens_join_path(root, "home");
    if ((home_dir == nullptr) || (code_lens_mkdir_p(home_dir) != 0) ||
        (setenv("CODE_LENS_HOME", home_dir, 1) != 0)) {
        (void)fprintf(stderr, "git blob test failed: home setup failed\n");
        return 1;
    }

    /* 1: loose object serves a clean tracked file; empty tracked file is
     * served without touching the object store; sequential path (1 thread). */
    {
        char *repo = make_repo(root, "loose");
        char *gitdir = repo == nullptr ? nullptr : code_lens_join_path(repo, ".git");
        const char *content = "(ns loose.core)\n(defn loose-blob-fn [] 1)\n";
        const TestIndexEntry entries[] = {
            {"a.clj", content, FIXED_MTIME},
            {"empty.clj", "", FIXED_MTIME},
        };

        failed |= git_assert(repo != nullptr, "loose repo setup");
        failed |= git_assert(write_worktree_file(repo, "a.clj", content) == 0, "loose write a");
        failed |= git_assert(write_worktree_file(repo, "empty.clj", "") == 0, "loose write empty");
        failed |= git_assert(write_loose_object(gitdir, content) == 0, "loose object write");
        failed |= git_assert(write_git_index(gitdir, entries, 2U) == 0, "loose index write");
        failed |= git_assert(setenv("CODE_LENS_INDEX_THREADS", "1", 1) == 0, "set threads");
        failed |= git_assert(index_repo_counting(repo, &stats, &blob_delta,
                                                 &file_delta) == 0,
                             "loose index run");
        (void)unsetenv("CODE_LENS_INDEX_THREADS");
        failed |= git_assert(stats.file_count == 2U, "loose file count");
        failed |= git_assert(stats.git_blob_reads == 2U, "loose stats blob reads");
        failed |= git_assert(blob_delta == 2U, "loose blob reads");
        failed |= git_assert(file_delta == 0U, "loose file reads");
        failed |= git_assert(query_contains(repo, "loose-blob-fn", "loose-blob-fn") == 0,
                             "loose symbol query");
    }

    /* 2: pack with a plain blob, an ofs-delta and a ref-delta. */
    {
        char *repo = make_repo(root, "packed");
        char *gitdir = repo == nullptr ? nullptr : code_lens_join_path(repo, ".git");
        PackTrio trio = {
            "(ns packed.base)\n(defn packed-base-fn [] 1)\n",
            "(ns packed.base)\n(defn packed-ofs-fn [] 22)\n",
            "(ns packed.base)\n(defn packed-ref-fn [] 333)\n",
        };
        const TestIndexEntry entries[] = {
            {"base.clj", trio.base, FIXED_MTIME},
            {"ofs.clj", trio.ofs, FIXED_MTIME},
            {"ref.clj", trio.ref, FIXED_MTIME},
        };

        failed |= git_assert(repo != nullptr, "pack repo setup");
        failed |= git_assert(write_worktree_file(repo, "base.clj", trio.base) == 0, "pack write base");
        failed |= git_assert(write_worktree_file(repo, "ofs.clj", trio.ofs) == 0, "pack write ofs");
        failed |= git_assert(write_worktree_file(repo, "ref.clj", trio.ref) == 0, "pack write ref");
        failed |= git_assert(write_git_pack(gitdir, &trio) == 0, "pack write");
        failed |= git_assert(write_git_index(gitdir, entries, 3U) == 0, "pack index write");
        failed |= git_assert(index_repo_counting(repo, &stats, &blob_delta,
                                                 &file_delta) == 0,
                             "pack index run");
        failed |= git_assert(stats.file_count == 3U, "pack file count");
        failed |= git_assert(blob_delta == 3U, "pack blob reads");
        failed |= git_assert(file_delta == 0U, "pack file reads");
        failed |= git_assert(query_contains(repo, "packed-ofs-fn", "packed-ofs-fn") == 0,
                             "pack ofs symbol");
        failed |= git_assert(query_contains(repo, "packed-ref-fn", "packed-ref-fn") == 0,
                             "pack ref symbol");
    }

    /* 3: modified tracked file (stat mismatch), untracked file and a racy
     * entry all fall back to the working tree; the clean file still comes
     * from the object store and disk content wins for the rest. */
    {
        char *repo = make_repo(root, "dirty");
        char *gitdir = repo == nullptr ? nullptr : code_lens_join_path(repo, ".git");
        char *index_path = gitdir == nullptr ? nullptr : code_lens_join_path(gitdir, "index");
        char *racy_path = repo == nullptr ? nullptr : code_lens_join_path(repo, "racy.clj");
        const char *clean = "(ns dirty.clean)\n(defn dirty-clean-fn [] 1)\n";
        const char *stale = "(ns dirty.mod)\n(defn dirty-old-fn [] 1)\n";
        const char *fresh = "(ns dirty.mod)\n(defn dirty-brand-new-fn [] 2)\n";
        const char *untracked = "(ns dirty.free)\n(defn dirty-untracked-fn [] 3)\n";
        const char *racy = "(ns dirty.racy)\n(defn dirty-racy-fn [] 4)\n";
        const TestIndexEntry entries[] = {
            {"clean.clj", clean, FIXED_MTIME},
            {"mod.clj", stale, FIXED_MTIME},
            {"racy.clj", racy, FIXED_MTIME + 100U},
        };

        failed |= git_assert(repo != nullptr, "dirty repo setup");
        failed |= git_assert(write_worktree_file(repo, "clean.clj", clean) == 0, "dirty write clean");
        failed |= git_assert(write_worktree_file(repo, "mod.clj", fresh) == 0, "dirty write mod");
        failed |= git_assert(write_worktree_file(repo, "free.clj", untracked) == 0,
                             "dirty write untracked");
        failed |= git_assert(write_worktree_file(repo, "racy.clj", racy) == 0, "dirty write racy");
        failed |= git_assert((racy_path != nullptr) &&
                                 (set_file_mtime(racy_path, FIXED_MTIME + 100U) == 0),
                             "dirty racy file mtime");
        failed |= git_assert(write_loose_object(gitdir, clean) == 0, "dirty loose clean");
        failed |= git_assert(write_loose_object(gitdir, stale) == 0, "dirty loose stale");
        failed |= git_assert(write_loose_object(gitdir, racy) == 0, "dirty loose racy");
        failed |= git_assert(write_git_index(gitdir, entries, 3U) == 0, "dirty index write");
        /* index dated FIXED_MTIME+100: the racy entry's mtime equals it,
         * the other entries are safely older */
        failed |= git_assert((index_path != nullptr) &&
                                 (set_file_mtime(index_path, FIXED_MTIME + 100U) == 0),
                             "dirty index mtime");
        failed |= git_assert(index_repo_counting(repo, &stats, &blob_delta,
                                                 &file_delta) == 0,
                             "dirty index run");
        failed |= git_assert(stats.file_count == 4U, "dirty file count");
        failed |= git_assert(stats.git_blob_reads == 1U, "dirty stats blob reads");
        failed |= git_assert(blob_delta == 1U, "dirty blob reads");
        failed |= git_assert(file_delta == 3U, "dirty file reads");
        failed |= git_assert(query_contains(repo, "dirty-brand-new-fn",
                                            "dirty-brand-new-fn") == 0,
                             "dirty modified symbol from disk");
        failed |= git_assert(query_contains(repo, "dirty-untracked-fn",
                                            "dirty-untracked-fn") == 0,
                             "dirty untracked symbol");
    }

    /* 4: pruned directories tracked in git stay invisible, and stat
     * mismatches (size) fall back per file while clean files use blobs. */
    {
        char *repo = make_repo(root, "mixed");
        char *gitdir = repo == nullptr ? nullptr : code_lens_join_path(repo, ".git");
        char *target_dir = repo == nullptr ? nullptr : code_lens_join_path(repo, "target");
        const char *kept = "(ns mixed.kept)\n(defn mixed-kept-fn [] 1)\n";
        const char *pruned = "(ns mixed.gone)\n(defn mixed-pruned-fn [] 2)\n";
        const char *resized = "(ns mixed.size)\n(defn mixed-size-fn [] 3)\n";
        const char *resized_now = "(ns mixed.size)\n(defn mixed-size-fn-changed [] 33)\n";
        const TestIndexEntry entries[] = {
            {"kept.clj", kept, FIXED_MTIME},
            {"size.clj", resized, FIXED_MTIME},
            {"target/gone.clj", pruned, FIXED_MTIME},
        };

        failed |= git_assert(repo != nullptr, "mixed repo setup");
        failed |= git_assert((target_dir != nullptr) && (code_lens_mkdir_p(target_dir) == 0),
                             "mixed target dir");
        failed |= git_assert(write_worktree_file(repo, "kept.clj", kept) == 0, "mixed write kept");
        failed |= git_assert(write_worktree_file(repo, "size.clj", resized_now) == 0,
                             "mixed write size");
        failed |= git_assert(write_worktree_file(repo, "target/gone.clj", pruned) == 0,
                             "mixed write pruned");
        failed |= git_assert(write_loose_object(gitdir, kept) == 0, "mixed loose kept");
        failed |= git_assert(write_loose_object(gitdir, resized) == 0, "mixed loose size");
        failed |= git_assert(write_loose_object(gitdir, pruned) == 0, "mixed loose pruned");
        failed |= git_assert(write_git_index(gitdir, entries, 3U) == 0, "mixed index write");
        failed |= git_assert(index_repo_counting(repo, &stats, &blob_delta,
                                                 &file_delta) == 0,
                             "mixed index run");
        failed |= git_assert(stats.file_count == 2U, "mixed file count excludes pruned dir");
        failed |= git_assert(blob_delta == 1U, "mixed blob reads");
        failed |= git_assert(file_delta == 1U, "mixed file reads");
        failed |= git_assert(query_contains(repo, "mixed-size-fn-changed",
                                            "mixed-size-fn-changed") == 0,
                             "mixed resized symbol from disk");
    }

    /* 5: CODE_LENS_GIT_BLOBS=0 disables the source entirely. */
    {
        char *repo = make_repo(root, "disabled");
        char *gitdir = repo == nullptr ? nullptr : code_lens_join_path(repo, ".git");
        const char *content = "(ns disabled.core)\n(defn disabled-fn [] 1)\n";
        const TestIndexEntry entries[] = {{"a.clj", content, FIXED_MTIME}};

        failed |= git_assert(repo != nullptr, "disabled repo setup");
        failed |= git_assert(write_worktree_file(repo, "a.clj", content) == 0, "disabled write");
        failed |= git_assert(write_loose_object(gitdir, content) == 0, "disabled loose");
        failed |= git_assert(write_git_index(gitdir, entries, 1U) == 0, "disabled index write");
        failed |= git_assert(setenv("CODE_LENS_GIT_BLOBS", "0", 1) == 0, "disable env");
        failed |= git_assert(index_repo_counting(repo, &stats, &blob_delta,
                                                 &file_delta) == 0,
                             "disabled index run");
        (void)unsetenv("CODE_LENS_GIT_BLOBS");
        failed |= git_assert(stats.file_count == 1U, "disabled file count");
        failed |= git_assert(blob_delta == 0U, "disabled blob reads");
        failed |= git_assert(file_delta == 0U, "disabled file reads counter untouched");
        failed |= git_assert(query_contains(repo, "disabled-fn", "disabled-fn") == 0,
                             "disabled symbol");
    }

    /* 6: corrupt index leaves the source inactive; indexing still works. */
    {
        char *repo = make_repo(root, "corrupt");
        char *gitdir = repo == nullptr ? nullptr : code_lens_join_path(repo, ".git");
        char *index_path = gitdir == nullptr ? nullptr : code_lens_join_path(gitdir, "index");
        const char *content = "(ns corrupt.core)\n(defn corrupt-survivor-fn [] 1)\n";

        failed |= git_assert(repo != nullptr, "corrupt repo setup");
        failed |= git_assert(write_worktree_file(repo, "a.clj", content) == 0, "corrupt write");
        failed |= git_assert((index_path != nullptr) &&
                                 (write_bytes_file(index_path, "NOTDIRCJUNKJUNKJUNKJUNKJUNKJUNK",
                                                   31U) == 0),
                             "corrupt index write");
        failed |= git_assert(index_repo_counting(repo, &stats, &blob_delta,
                                                 &file_delta) == 0,
                             "corrupt index run");
        failed |= git_assert(stats.file_count == 1U, "corrupt file count");
        failed |= git_assert(blob_delta == 0U, "corrupt blob reads");
        failed |= git_assert(file_delta == 0U, "corrupt file reads counter untouched");
        failed |= git_assert(query_contains(repo, "corrupt-survivor-fn",
                                            "corrupt-survivor-fn") == 0,
                             "corrupt symbol");
    }

    if (had_home) {
        (void)setenv("CODE_LENS_HOME", saved_home, 1);
    } else {
        (void)unsetenv("CODE_LENS_HOME");
    }
    if (failed == 0) {
        (void)fprintf(stderr, "git blob source tests passed\n");
    }
    return failed;
}
