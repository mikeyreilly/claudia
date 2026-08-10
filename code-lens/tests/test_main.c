#include "code_lens.h"

#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

int test_clojure_parser(void);
int test_java_support(void);
int test_c_support(void);
int test_git_blobs(void);
char *code_lens_test_mcp_call_tool(const char *tool_name, const char *args);
const char *code_lens_test_json_object_value(const char *object, const char *key);
char *code_lens_test_json_get_string(const char *object, const char *key);
char *code_lens_test_json_get_id_raw(const char *object);
char *code_lens_test_json_get_arguments(const char *params);
int code_lens_test_repo_write_lock_acquire(const char *repo_name);
void code_lens_test_repo_write_lock_release(int fd);

/* Bulk write + read-back regression test.
 *
 * The fixture is sized well past single-batch comfort: the Symbol table gets
 * more than 2048 rows and the Ref table more than 131072 rows, so the write
 * path exercises sustained prepared-statement streaming inside one
 * transaction and the read path scans multi-page tables. */
#define READBACK_FILE_COUNT 12U
#define READBACK_DEFNS_PER_FILE 180U
#define READBACK_TOKENS_PER_DEFN 72U
#define READBACK_MIN_SYMBOLS 2048ULL
#define READBACK_MIN_REFERENCES 131072ULL
#define READBACK_MIN_KEYWORDS 2048ULL

static int readback_assert(int condition, const char *message)
{
    if (!condition) {
        (void)fprintf(stderr, "readback test failed: %s\n", message);
        return 1;
    }
    return 0;
}

/* Repo ids are canonical repository paths, so tests must build fixtures
 * under the realpath of the temp root (macOS symlinks /var to
 * /private/var) for output comparisons to hold. */
static char *canonical_temp_root(char *template_buffer)
{
    char resolved[PATH_MAX];
    char *root = mkdtemp(template_buffer);
    char *copy;
    size_t len;

    if ((root == nullptr) || (realpath(root, resolved) == nullptr)) {
        return nullptr;
    }
    len = strlen(resolved);
    copy = code_lens_alloc(len + 1U);
    if (copy == nullptr) {
        return nullptr;
    }
    (void)memcpy(copy, resolved, len + 1U);
    return copy;
}

static int write_text_file(const char *src_dir, const char *name, const char *content);
static int commit_git_fixture(const char *repo_dir);

static int write_fixture_file(const char *src_dir, size_t file_index)
{
    char name[64];
    char *path;
    FILE *out;
    int rc = 0;

    (void)snprintf(name, sizeof(name), "gen_ns_%zu.clj", file_index);
    path = code_lens_join_path(src_dir, name);
    if (path == nullptr) {
        return -1;
    }

    out = fopen(path, "w");
    if (out == nullptr) {
        (void)fprintf(stderr, "readback test failed: cannot create %s\n", path);
        return -1;
    }

    (void)fprintf(out,
                  "(ns gen.ns-%zu\n"
                  "  (:require [clojure.string :as str%zu]\n"
                  "            [gen.helper :as helper%zu]))\n\n",
                  file_index,
                  file_index,
                  file_index);

    for (size_t defn = 0U; defn < READBACK_DEFNS_PER_FILE; defn++) {
        (void)fprintf(out,
                      "(defn gen-fn-%zu-%zu [alpha-%zu beta-%zu]\n  (combine-values",
                      file_index,
                      defn,
                      defn,
                      defn);
        for (size_t token = 0U; token < READBACK_TOKENS_PER_DEFN; token++) {
            (void)fprintf(out, " token-%zu-%zu-%zu", file_index, defn, token);
        }
        (void)fprintf(out, "))\n  (:report!-fn {:report!-fn token-%zu-%zu}))\n\n", file_index, defn);
    }

    if (fclose(out) != 0) {
        rc = -1;
    }
    return rc;
}

/* Parses the single value from a one-row COUNT query result: the first line
 * is the column header and the second line is the count. */
static long long parse_single_count(const char *text)
{
    const char *newline;

    if (text == nullptr) {
        return -1LL;
    }
    newline = strchr(text, '\n');
    if (newline == nullptr) {
        return -1LL;
    }
    return strtoll(newline + 1, nullptr, 10);
}

/* Copies the single value from a one-row query result: the first line is the
 * column header and the second line is the value. */
static char *parse_single_value(const char *text)
{
    const char *start;
    const char *end;
    size_t len;
    char *value;

    if (text == nullptr) {
        return nullptr;
    }
    start = strchr(text, '\n');
    if (start == nullptr) {
        return nullptr;
    }
    start++;
    end = strchr(start, '\n');
    end = end == nullptr ? start + strlen(start) : end;
    len = (size_t)(end - start);
    if (len == 0U) {
        return nullptr;
    }
    value = code_lens_alloc(len + 1U);
    if (value == nullptr) {
        return nullptr;
    }
    (void)memcpy(value, start, len);
    value[len] = '\0';
    return value;
}

/* The repo id (canonical fixture path); set once the fixture is indexed. */
static const char *readback_repo_id = "";

static int expect_count(const char *label, const char *sql, long long expected)
{
    char *rows = code_lens_run_sql(readback_repo_id, sql);
    long long actual = parse_single_count(rows);

    if (actual != expected) {
        (void)fprintf(stderr,
                      "readback test failed: %s: expected %lld, got %lld (raw: %s)\n",
                      label,
                      expected,
                      actual,
                      rows == nullptr ? "(null)" : rows);
        return 1;
    }
    return 0;
}

static int test_index_readback(void)
{
    char root_template[1024];
    const char *tmp_dir = getenv("TMPDIR");
    const char *old_home = getenv("CODE_LENS_HOME");
    char saved_home[1024] = {0};
    bool had_home = false;
    char *root;
    char *repo_dir = nullptr;
    char *src_dir = nullptr;
    char *home_dir = nullptr;
    char *second_repo_dir = nullptr;
    char *second_src_dir = nullptr;
    CodeLensIndexStats stats = {0};
    CodeLensIndexStats second_stats = {0};
    CodeLensIndexStats reindex_stats = {0};
    char *context_text;
    char *fts_exact_text;
    char *subdir_query_text;
    char *fts_prefix_text;
    char *no_match_text;
    char *or_fallback_text;
    char *all_bogus_text;
    char *unknown_repo_text;
    char *sql_error_text;
    char *authz_text;
    char *capped_text;
    char *pk_id_rows;
    char *pk_symbol_id;
    char pk_query[2048];
    char *list_text;
    char *remove_text;
    char *removed_query_text;
    char *remove_again_text;
    char *stale_file_path;
    char *stale_query_text;
    char *new_file_query_text;
    char *emacs_lock_path;
    int failed = 0;

    if ((old_home != nullptr) && (strlen(old_home) < sizeof(saved_home))) {
        (void)strcpy(saved_home, old_home);
        had_home = true;
    }

    if ((tmp_dir == nullptr) || (tmp_dir[0] == '\0')) {
        tmp_dir = "/tmp";
    }
    (void)snprintf(root_template, sizeof(root_template), "%s/code-lens-readback-XXXXXX", tmp_dir);
    root = canonical_temp_root(root_template);
    if (root == nullptr) {
        (void)fprintf(stderr, "readback test failed: mkdtemp failed\n");
        return 1;
    }

    repo_dir = code_lens_join_path(root, "repo");
    home_dir = code_lens_join_path(root, "home");
    src_dir = repo_dir == nullptr ? nullptr : code_lens_join_path(repo_dir, "src");
    if ((repo_dir == nullptr) || (home_dir == nullptr) || (src_dir == nullptr) ||
        (code_lens_mkdir_p(src_dir) != 0) || (code_lens_mkdir_p(home_dir) != 0)) {
        (void)fprintf(stderr, "readback test failed: fixture directory setup failed\n");
        failed = 1;
        goto done;
    }

    for (size_t file_index = 0U; file_index < READBACK_FILE_COUNT; file_index++) {
        if (write_fixture_file(src_dir, file_index) != 0) {
            (void)fprintf(stderr, "readback test failed: fixture generation failed\n");
            failed = 1;
            goto done;
        }
    }

    emacs_lock_path = code_lens_join_path(src_dir, ".#gen_ns_0.clj");
    if ((emacs_lock_path == nullptr) ||
        (symlink("missing-emacs-lock-target", emacs_lock_path) != 0)) {
        (void)fprintf(stderr, "readback test failed: editor lock fixture setup failed\n");
        failed = 1;
        goto done;
    }

    if (setenv("CODE_LENS_HOME", home_dir, 1) != 0) {
        (void)fprintf(stderr, "readback test failed: setenv failed\n");
        failed = 1;
        goto done;
    }

    if (code_lens_index_repository(repo_dir, &stats) != 0) {
        (void)fprintf(stderr, "readback test failed: indexing failed\n");
        failed = 1;
        goto done;
    }
    readback_repo_id = repo_dir;

    /* The fixture must actually be large enough to exercise sustained bulk
     * writes and multi-page scans, otherwise this test silently loses its
     * regression value. */
    failed |= readback_assert(stats.file_count == READBACK_FILE_COUNT, "fixture file count");
    failed |= readback_assert(stats.symbol_count > READBACK_MIN_SYMBOLS,
                              "fixture must exceed 2048 symbols");
    failed |= readback_assert(stats.reference_count > READBACK_MIN_REFERENCES,
                              "fixture must exceed 131072 references");
    failed |= readback_assert(stats.keyword_count > READBACK_MIN_KEYWORDS,
                              "fixture must exceed 2048 keywords");
    failed |= readback_assert(stats.alias_count == READBACK_FILE_COUNT * 2U,
                              "fixture alias count");
    if (failed != 0) {
        goto done;
    }

    failed |= expect_count("Repo count", "SELECT COUNT(*) FROM Repo", 1LL);
    failed |= expect_count("File count",
                           "SELECT COUNT(*) FROM File",
                           (long long)stats.file_count);
    failed |= expect_count("Symbol count",
                           "SELECT COUNT(*) FROM Symbol",
                           (long long)stats.symbol_count);
    failed |= expect_count("Ref count",
                           "SELECT COUNT(*) FROM Ref",
                           (long long)stats.reference_count);
    failed |= expect_count("Keyword count",
                           "SELECT COUNT(*) FROM Keyword",
                           (long long)stats.keyword_count);
    failed |= expect_count("Alias count",
                           "SELECT COUNT(*) FROM Alias",
                           (long long)stats.alias_count);
    /* DISTINCT over the string primary key catches scans that emit a row
     * twice while skipping another; plain COUNT(*) stays correct in that
     * failure mode. */
    failed |= expect_count("Symbol distinct ids",
                           "SELECT COUNT(DISTINCT id) FROM Symbol",
                           (long long)stats.symbol_count);
    failed |= expect_count("Ref distinct ids",
                           "SELECT COUNT(DISTINCT id) FROM Ref",
                           (long long)stats.reference_count);
    failed |= expect_count("Keyword distinct ids",
                           "SELECT COUNT(DISTINCT id) FROM Keyword",
                           (long long)stats.keyword_count);
    failed |= expect_count("Keyword report-fn rows",
                           "SELECT COUNT(*) FROM Keyword WHERE keyword = ':report!-fn'"
                           " AND keywordBase = 'report!-fn'",
                           (long long)(READBACK_FILE_COUNT * READBACK_DEFNS_PER_FILE * 2U));
    /* The views expose the file path directly: the natural-looking join on
     * File.id (a TEXT key unrelated to fileId) silently matched nothing in
     * field use. Every row must resolve a real path. */
    failed |= expect_count("Keyword filePath rows",
                           "SELECT COUNT(*) FROM Keyword WHERE filePath LIKE '%.clj'",
                           (long long)stats.keyword_count);
    failed |= expect_count("Ref filePath rows",
                           "SELECT COUNT(*) FROM Ref WHERE filePath LIKE '%.clj'",
                           (long long)stats.reference_count);

    /* Spot-check that a definition from the tail of the fixture is
     * reachable through the context path. */
    context_text = code_lens_context_symbol(repo_dir, "gen-fn-11-179");
    failed |= readback_assert((context_text != nullptr) &&
                                  (strstr(context_text, "staleness check passed") != nullptr) &&
                                  (strstr(context_text, "gen-fn-11-179") != nullptr),
                              "context finds a late symbol definition");

    /* FTS search: a full-token query must surface the symbol it names. */
    fts_exact_text = code_lens_query_symbols(repo_dir, "gen-fn-11-179", 5);
    failed |= readback_assert((fts_exact_text != nullptr) &&
                                  (strstr(fts_exact_text, "staleness check passed") != nullptr) &&
                                  (strstr(fts_exact_text, "Symbols") != nullptr) &&
                                  (strstr(fts_exact_text, "gen-fn-11-179|") != nullptr),
                              "query finds a symbol by full token match");

    /* Any path inside an indexed repository must resolve to its repo id,
     * so an agent can pass its working directory or a source subdirectory
     * verbatim. */
    subdir_query_text = code_lens_query_symbols(src_dir, "gen-fn-11-179", 5);
    failed |= readback_assert((subdir_query_text != nullptr) &&
                                  (strstr(subdir_query_text, "gen-fn-11-179|") != nullptr),
                              "query resolves a subdirectory to the enclosing repo");

    /* FTS search: a trailing partial term acts as a prefix query. The limit
     * comfortably exceeds the match set so ranking is not pinned; both the
     * exact-name symbol and a longer-name sibling must be present. */
    fts_prefix_text = code_lens_query_symbols(repo_dir, "gen-fn-11-17", 25);
    failed |= readback_assert((fts_prefix_text != nullptr) &&
                                  (strstr(fts_prefix_text, "gen-fn-11-17|") != nullptr) &&
                                  (strstr(fts_prefix_text, "gen-fn-11-179|") != nullptr),
                              "query prefix-matches partial trailing terms");

    /* Empty results on a known repo must say so instead of returning a bare
     * header row. */
    no_match_text = code_lens_query_symbols(repo_dir, "zzz-no-such-symbol", 5);
    failed |= readback_assert((no_match_text != nullptr) &&
                                  (strstr(no_match_text,
                                          "no symbols or keywords matching") != nullptr),
                              "query reports no matches on a known repo");

    /* Keyword search: a Clojure keyword query must surface keyword usage rows,
     * not only incidental symbol-definition matches. */
    {
        char *keyword_query_text = code_lens_query_symbols(repo_dir, ":report!-fn", 5);
        failed |= readback_assert((keyword_query_text != nullptr) &&
                                      (strstr(keyword_query_text, "Keywords") != nullptr) &&
                                      (strstr(keyword_query_text, ":report!-fn|") != nullptr),
                                  "query finds keyword usages by keyword literal");
    }

    /* A multi-term query where only one term matches must relax to any-term
     * matches, flag the relaxation with the notice line, and still surface
     * the matching symbol. */
    or_fallback_text =
        code_lens_query_symbols(repo_dir, "gen-fn-11-179 zzzznope", 5);
    failed |= readback_assert(
        (or_fallback_text != nullptr) &&
            (strstr(or_fallback_text,
                    "note: no symbols or keywords match all terms; showing any-term matches") !=
             nullptr) &&
            (strstr(or_fallback_text, "gen-fn-11-179|") != nullptr),
        "query relaxes an over-narrow multi-term search to any-term matches");

    /* When no term matches anything, the relaxed pass finds nothing either
     * and the ordinary no-match message must come back unchanged. */
    all_bogus_text =
        code_lens_query_symbols(repo_dir, "zzz-no-such-symbol qqq-bogus", 5);
    failed |= readback_assert((all_bogus_text != nullptr) &&
                                  (strstr(all_bogus_text,
                                          "no symbols or keywords matching") != nullptr),
                              "query reports no matches when every term is bogus");

    /* Unknown repos must be called out with the list of indexed repos. */
    unknown_repo_text = code_lens_run_sql("no-such-repo", "SELECT COUNT(*) FROM Symbol");
    failed |= readback_assert((unknown_repo_text != nullptr) &&
                                  (strstr(unknown_repo_text, "unknown repo") != nullptr) &&
                                  (strstr(unknown_repo_text, repo_dir) != nullptr),
                              "sql rejects unknown repo names");

    /* A broken statement must surface the real SQLite error, not the
     * re-index advice: telling an agent to re-index over a typo'd table
     * name sends it down a dead end. */
    sql_error_text =
        code_lens_run_sql(repo_dir, "SELECT COUNT(*) FROM keywords_typo");
    failed |= readback_assert((sql_error_text != nullptr) &&
                                  (strstr(sql_error_text, "SQL error:") != nullptr) &&
                                  (strstr(sql_error_text, "no such table") != nullptr) &&
                                  (strstr(sql_error_text, "missing, unreadable") == nullptr),
                              "sql surfaces SQLite error text for a bad statement");

    /* The authorizer must deny anything but reads of the repo database:
     * ATTACH would let a query read any SQLite file on disk, and PRAGMA
     * can change connection state. */
    authz_text = code_lens_run_sql(repo_dir,
                                    "ATTACH DATABASE '/tmp/no-such.sqlite' AS other");
    failed |= readback_assert((authz_text != nullptr) &&
                                  (strstr(authz_text, "SQL error:") != nullptr) &&
                                  (strstr(authz_text, "not authorized") != nullptr),
                              "sql denies ATTACH");
    authz_text = code_lens_run_sql(repo_dir, "PRAGMA user_version");
    failed |= readback_assert((authz_text != nullptr) &&
                                  (strstr(authz_text, "SQL error:") != nullptr) &&
                                  (strstr(authz_text, "not authorized") != nullptr),
                              "sql denies PRAGMA");

    /* The row cap lives in the render loop, so a trailing semicolon (which
     * defeated the old textual LIMIT append) changes nothing. */
    capped_text = code_lens_run_sql(repo_dir, "SELECT fileId FROM Ref;");
    failed |= readback_assert(
        (capped_text != nullptr) &&
            (strstr(capped_text, "note: output capped at 100 rows") != nullptr),
        "sql caps row output despite a trailing semicolon");
    if (capped_text != nullptr) {
        size_t line_count = 0U;

        for (const char *q = capped_text; *q != '\0'; q++) {
            line_count += (*q == '\n') ? 1U : 0U;
        }
        /* header + 100 rows + cap note */
        failed |= readback_assert(line_count == 102U, "capped output holds exactly 100 rows");
    }

    /* Primary-key equality must return the matching row. */
    {
        char repo_pk_query[2048];

        (void)snprintf(repo_pk_query,
                       sizeof(repo_pk_query),
                       "SELECT COUNT(*) FROM Repo WHERE path = '%s'",
                       repo_dir);
        failed |= expect_count("Repo PK equality", repo_pk_query, 1LL);
    }

    /* Round-trip a Symbol primary key: resolve the id of a known definition,
     * then look the row up again by id. Fixture ids never contain quotes, so
     * embedding the value directly is safe. */
    pk_id_rows = code_lens_run_sql(repo_dir,
                                    "SELECT id FROM Symbol WHERE name = 'gen-fn-11-179'");
    pk_symbol_id = parse_single_value(pk_id_rows);
    if (pk_symbol_id == nullptr) {
        (void)fprintf(stderr, "readback test failed: could not resolve a Symbol id\n");
        failed = 1;
    } else {
        (void)snprintf(pk_query,
                       sizeof(pk_query),
                       "SELECT COUNT(*) FROM Symbol WHERE id = '%s'",
                       pk_symbol_id);
        failed |= expect_count("Symbol PK equality", pk_query, 1LL);
    }

    /* Indexing a second repo into the same home must not clobber the first
     * repo's index. */
    second_repo_dir = code_lens_join_path(root, "second-repo");
    second_src_dir =
        second_repo_dir == nullptr ? nullptr : code_lens_join_path(second_repo_dir, "src");
    if ((second_src_dir == nullptr) || (code_lens_mkdir_p(second_src_dir) != 0) ||
        (write_fixture_file(second_src_dir, 0U) != 0)) {
        (void)fprintf(stderr, "readback test failed: second fixture setup failed\n");
        failed = 1;
        goto done;
    }
    if (code_lens_index_repository(second_repo_dir, &second_stats) != 0) {
        (void)fprintf(stderr, "readback test failed: indexing second repo failed\n");
        failed = 1;
        goto done;
    }
    failed |= readback_assert(second_stats.file_count == 1U, "second fixture file count");

    list_text = code_lens_list_repos();
    failed |= readback_assert((list_text != nullptr) &&
                                  (strstr(list_text, repo_dir) != nullptr) &&
                                  (strstr(list_text, second_repo_dir) != nullptr) &&
                                  (strstr(list_text, "staleness check passed") != nullptr),
                              "list shows both repos after indexing a second repo");

    failed |= expect_count("Symbol count after second repo",
                           "SELECT COUNT(*) FROM Symbol",
                           (long long)stats.symbol_count);
    failed |= expect_count("Ref count after second repo",
                           "SELECT COUNT(*) FROM Ref",
                           (long long)stats.reference_count);

    /* Re-indexing the first repo must replace only that repo. */
    if (code_lens_index_repository(repo_dir, &reindex_stats) != 0) {
        (void)fprintf(stderr, "readback test failed: re-indexing failed\n");
        failed = 1;
        goto done;
    }
    failed |= readback_assert(reindex_stats.symbol_count == stats.symbol_count,
                              "re-index reproduces the symbol count");
    failed |= expect_count("Symbol count after re-index",
                           "SELECT COUNT(*) FROM Symbol",
                           (long long)stats.symbol_count);
    failed |= expect_count("Ref count after re-index",
                           "SELECT COUNT(*) FROM Ref",
                           (long long)stats.reference_count);
    list_text = code_lens_list_repos();
    failed |= readback_assert((list_text != nullptr) &&
                                  (strstr(list_text, repo_dir) != nullptr) &&
                                  (strstr(list_text, second_repo_dir) != nullptr),
                              "list shows both repos after re-indexing");

    stale_file_path = code_lens_join_path(src_dir, "gen_ns_0.clj");
    if (stale_file_path == nullptr) {
        failed = 1;
        goto done;
    }
    {
        FILE *out = fopen(stale_file_path, "a");

        if (out == nullptr) {
            failed = 1;
            goto done;
        }
        (void)fprintf(out, "\n(defn added-after-index [] :later)\n");
        if (fclose(out) != 0) {
            failed = 1;
            goto done;
        }
    }
    stale_query_text = code_lens_query_symbols(repo_dir, "gen-fn-11-179", 5);
    failed |= readback_assert((stale_query_text != nullptr) &&
                                  (strstr(stale_query_text, "index is stale") != nullptr) &&
                                  (strstr(stale_query_text, "1 changed") != nullptr),
                              "query reports changed indexed files");
    if (write_text_file(src_dir, "new_after_index.clj", "(ns new.after-index)\n") != 0) {
        failed = 1;
        goto done;
    }
    new_file_query_text = code_lens_query_symbols(repo_dir, "gen-fn-11-179", 5);
    failed |= readback_assert((new_file_query_text != nullptr) &&
                                  (strstr(new_file_query_text, "index is stale") != nullptr) &&
                                  (strstr(new_file_query_text, "1 changed") != nullptr) &&
                                  (strstr(new_file_query_text, "1 new") != nullptr),
                              "query reports new source files");

    /* Removing a repo deletes only that repo and later queries against it
     * report it as unknown. */
    remove_text = code_lens_remove_repo(second_repo_dir);
    failed |= readback_assert((remove_text != nullptr) &&
                                  (strstr(remove_text, "removed repo") != nullptr),
                              "remove reports the removed repo");
    list_text = code_lens_list_repos();
    failed |= readback_assert((list_text != nullptr) &&
                                  (strstr(list_text, repo_dir) != nullptr) &&
                                  (strstr(list_text, second_repo_dir) == nullptr),
                              "list drops the removed repo");
    removed_query_text =
        code_lens_run_sql(second_repo_dir, "SELECT COUNT(*) FROM Symbol");
    failed |= readback_assert((removed_query_text != nullptr) &&
                                  (strstr(removed_query_text, "unknown repo") != nullptr) &&
                                  (strstr(removed_query_text, repo_dir) != nullptr),
                              "sql rejects a removed repo and lists the remaining one");
    remove_again_text = code_lens_remove_repo(second_repo_dir);
    failed |= readback_assert((remove_again_text != nullptr) &&
                                  (strstr(remove_again_text, "unknown repo") != nullptr),
                              "removing a removed repo reports it as unknown");

done:
    if (had_home) {
        (void)setenv("CODE_LENS_HOME", saved_home, 1);
    } else {
        (void)unsetenv("CODE_LENS_HOME");
    }
    if (code_lens_remove_tree(root) != 0) {
        (void)fprintf(stderr, "readback test warning: failed to remove %s\n", root);
    }
    return failed == 0 ? 0 : 1;
}

/* Alias-resolution regression test for the context command.
 *
 * File A defines target-fn in a.ns; file B references it through a require
 * alias, file C through the fully-qualified namespace, and file D defines an
 * unrelated target-fn in d.ns plus a reference whose alias resolves to a
 * namespace that defines no target-fn at all. Context must surface the alias
 * and fully-qualified call sites and exclude the unresolvable-qualifier one. */

static int context_assert(int condition, const char *message)
{
    if (!condition) {
        (void)fprintf(stderr, "context alias test failed: %s\n", message);
        return 1;
    }
    return 0;
}

static int write_text_file(const char *src_dir, const char *name, const char *content)
{
    char *path = code_lens_join_path(src_dir, name);
    FILE *out;
    int rc = 0;

    if (path == nullptr) {
        return -1;
    }
    out = fopen(path, "w");
    if (out == nullptr) {
        (void)fprintf(stderr, "context alias test failed: cannot create %s\n", path);
        return -1;
    }
    if (fputs(content, out) == EOF) {
        rc = -1;
    }
    if (fclose(out) != 0) {
        rc = -1;
    }
    return rc;
}

/* The repo id (canonical fixture path); set once the fixture is indexed. */
static const char *context_repo_id = "";

static int context_expect_count(const char *label, const char *sql, long long expected)
{
    char *rows = code_lens_run_sql(context_repo_id, sql);
    long long actual = parse_single_count(rows);

    if (actual != expected) {
        (void)fprintf(stderr,
                      "context alias test failed: %s: expected %lld, got %lld (raw: %s)\n",
                      label,
                      expected,
                      actual,
                      rows == nullptr ? "(null)" : rows);
        return 1;
    }
    return 0;
}

static int test_context_alias_resolution(void)
{
    char root_template[1024];
    const char *tmp_dir = getenv("TMPDIR");
    const char *old_home = getenv("CODE_LENS_HOME");
    char saved_home[1024] = {0};
    bool had_home = false;
    char *root;
    char *repo_dir = nullptr;
    char *src_dir = nullptr;
    char *test_dir = nullptr;
    char *home_dir = nullptr;
    CodeLensIndexStats stats = {0};
    CodeLensContextOptions context_options = {0};
    char *context_text;
    char *namespace_context_text;
    char *path_context_text;
    char *exclude_context_text;
    char *mcp_exclude_text;
    char *mcp_filter_text;
    char *qualified_context_text;
    char *substring_context_text;
    int failed = 0;

    if ((old_home != nullptr) && (strlen(old_home) < sizeof(saved_home))) {
        (void)strcpy(saved_home, old_home);
        had_home = true;
    }

    if ((tmp_dir == nullptr) || (tmp_dir[0] == '\0')) {
        tmp_dir = "/tmp";
    }
    (void)snprintf(root_template, sizeof(root_template), "%s/code-lens-context-XXXXXX", tmp_dir);
    root = canonical_temp_root(root_template);
    if (root == nullptr) {
        (void)fprintf(stderr, "context alias test failed: mkdtemp failed\n");
        return 1;
    }

    repo_dir = code_lens_join_path(root, "repo");
    home_dir = code_lens_join_path(root, "home");
    src_dir = repo_dir == nullptr ? nullptr : code_lens_join_path(repo_dir, "src");
    test_dir = repo_dir == nullptr ? nullptr : code_lens_join_path(repo_dir, "test");
    if ((repo_dir == nullptr) || (home_dir == nullptr) || (src_dir == nullptr) ||
        (test_dir == nullptr) || (code_lens_mkdir_p(src_dir) != 0) ||
        (code_lens_mkdir_p(test_dir) != 0) || (code_lens_mkdir_p(home_dir) != 0)) {
        (void)fprintf(stderr, "context alias test failed: fixture directory setup failed\n");
        failed = 1;
        goto done;
    }

    if ((write_text_file(src_dir,
                         "file_a.clj",
                         "(ns a.ns)\n\n(defn target-fn [] 1)\n") != 0) ||
        (write_text_file(src_dir,
                         "file_b.clj",
                         "(ns b.ns\n"
                         "  (:require [a.ns :as al]))\n\n"
                         "(defn use-b [] (al/target-fn))\n") != 0) ||
        (write_text_file(src_dir,
                         "file_c.clj",
                         "(ns c.ns)\n\n(defn use-c [] (a.ns/target-fn))\n") != 0) ||
        (write_text_file(src_dir,
                         "file_d.clj",
                          "(ns d.ns\n"
                          "  (:require [x.empty :as xe]))\n\n"
                          "(defn target-fn [] 2)\n\n"
                          "(defn unrelated-use-d [] (xe/target-fn))\n") != 0) ||
        (write_text_file(test_dir,
                         "target_test.clj",
                         "(ns t.ns\n"
                         "  (:require [a.ns :as al]))\n\n"
                         "(deftest target-fn-check (al/target-fn))\n") != 0) ||
        (write_text_file(src_dir,
                         "file_f.clj",
                         "(ns f.ns\n"
                         "  (:require [a.ns :refer [target-fn]]))\n\n"
                         "(defn use-f [] (target-fn))\n") != 0) ||
        (write_text_file(src_dir,
                         "file_g.clj",
                         "(ns g.ns)\n\n(defn use-g [] (target-fn))\n") != 0) ||
        (write_text_file(src_dir,
                         "file_h.clj",
                         "(ns h.ns\n"
                         "  (:use [a.ns]))\n\n"
                         "(defn use-h [] (target-fn))\n") != 0)) {
        failed = 1;
        goto done;
    }
    if (commit_git_fixture(repo_dir) != 0) {
        (void)fprintf(stderr, "context alias test failed: git fixture setup failed\n");
        failed = 1;
        goto done;
    }

    if (setenv("CODE_LENS_HOME", home_dir, 1) != 0) {
        (void)fprintf(stderr, "context alias test failed: setenv failed\n");
        failed = 1;
        goto done;
    }

    if (code_lens_index_repository(repo_dir, &stats) != 0) {
        (void)fprintf(stderr, "context alias test failed: indexing failed\n");
        failed = 1;
        goto done;
    }
    context_repo_id = repo_dir;
    failed |= context_assert(stats.file_count == 8U, "fixture file count");

    /* Ref rows must carry the split base name and the alias-resolved (or
     * verbatim) target namespace. */
    failed |= context_expect_count("alias-qualified ref columns",
                                   "SELECT COUNT(*) FROM Ref WHERE symbol = 'al/target-fn'"
                                   " AND symbolBase = 'target-fn'"
                                   " AND targetNamespace = 'a.ns'",
                                   2LL);
    failed |= context_expect_count("fully-qualified ref columns",
                                   "SELECT COUNT(*) FROM Ref WHERE symbol = 'a.ns/target-fn'"
                                   " AND symbolBase = 'target-fn'"
                                   " AND targetNamespace = 'a.ns'",
                                   1LL);
    failed |= context_expect_count("unresolvable-qualifier ref columns",
                                   "SELECT COUNT(*) FROM Ref WHERE symbol = 'xe/target-fn'"
                                   " AND symbolBase = 'target-fn'"
                                   " AND targetNamespace = 'x.empty'",
                                   1LL);
    failed |= context_expect_count("unqualified ref columns",
                                   "SELECT COUNT(*) FROM Ref WHERE symbol = 'target-fn'"
                                   " AND symbolBase = 'target-fn'"
                                   " AND targetNamespace = ''",
                                   6LL);
    failed |= context_expect_count("reference byte spans",
                                   "SELECT COUNT(*) FROM Ref WHERE symbol = 'al/target-fn'"
                                   " AND startByte < endByte",
                                   2LL);

    /* :refer and :use land in the Referred table; :refer :all and :use use
     * the ':all' sentinel. */
    failed |= context_expect_count("referred rows",
                                   "SELECT COUNT(*) FROM Referred",
                                   2LL);
    failed |= context_expect_count("referred symbol row",
                                   "SELECT COUNT(*) FROM Referred WHERE namespace = 'a.ns'"
                                   " AND symbol = 'target-fn'"
                                   " AND filePath LIKE '%file_f.clj'",
                                   1LL);
    failed |= context_expect_count("use sentinel row",
                                   "SELECT COUNT(*) FROM Referred WHERE namespace = 'a.ns'"
                                   " AND symbol = ':all'"
                                   " AND filePath LIKE '%file_h.clj'",
                                   1LL);

    context_text = code_lens_context_symbol(repo_dir, "target-fn");
    failed |= context_assert(context_text != nullptr, "context returns output");
    if (context_text != nullptr) {
        failed |= context_assert(strstr(context_text, "al/target-fn|a.ns") != nullptr,
                                 "context lists the alias-qualified call site");
        failed |= context_assert(strstr(context_text, "a.ns/target-fn|a.ns") != nullptr,
                                 "context lists the fully-qualified call site");
        failed |= context_assert(strstr(context_text, "file_b.clj") != nullptr,
                                 "context lists file B");
        failed |= context_assert(strstr(context_text, "file_c.clj") != nullptr,
                                 "context lists file C");
        failed |= context_assert(strstr(context_text, "r.startByte|r.endByte") != nullptr,
                                 "context reference table includes byte offsets");
        failed |= context_assert(strstr(context_text, "Reference snippets") != nullptr,
                                 "context includes reference snippets");
        failed |= context_assert(strstr(context_text,
                                        "| (defn use-b [] (al/target-fn))") != nullptr,
                                 "context snippet highlights alias-qualified reference");
        failed |= context_assert(strstr(context_text,
                                        "| (defn use-c [] (a.ns/target-fn))") != nullptr,
                                 "context snippet highlights fully-qualified reference");
        /* d.ns defines its own target-fn, so d.ns-qualified references would
         * be legitimate; the reference whose qualifier resolves to x.empty
         * (which defines no target-fn) must be excluded. */
        failed |= context_assert(strstr(context_text, "xe/target-fn|x.empty") == nullptr,
                                 "context excludes qualifiers resolving to a namespace"
                                 " without the definition");
        failed |= context_assert(strstr(context_text, "target-fn|function|a.ns") != nullptr,
                                 "context lists the a.ns definition for a bare name");
        failed |= context_assert(strstr(context_text, "(defn target-fn [] 1)") != nullptr,
                                 "context exact match includes the definition content");
        failed |= context_assert(strstr(context_text, "target_test.clj") != nullptr,
                                 "context includes test-file references by default");
        /* Unqualified references count only where they can resolve: via a
         * :refer of the symbol, a :use / :refer :all of a defining
         * namespace, or the file's own namespace defining it. */
        failed |= context_assert(strstr(context_text, "file_f.clj") != nullptr,
                                 "context counts unqualified references behind :refer");
        failed |= context_assert(strstr(context_text, "file_h.clj") != nullptr,
                                 "context counts unqualified references behind :use");
        failed |= context_assert(strstr(context_text,
                                        "| (defn use-f [] (target-fn))") != nullptr,
                                 "context snippet renders the referred call site");
        failed |= context_assert(strstr(context_text, "file_g.clj") == nullptr,
                                 "context drops unqualified references without any referral");
        failed |= context_assert(strstr(context_text, "file_a.clj") != nullptr,
                                 "context keeps definition-site references in the defining ns");
    }

    /* Namespace and definition-path filters disambiguate common names. They
     * constrain the candidate definition set, so matching references can
     * still originate in other files. */
    context_options = (CodeLensContextOptions){.namespace_name = "a.ns"};
    namespace_context_text =
        code_lens_context_symbol_ex(repo_dir, "target-fn", &context_options);
    failed |= context_assert((namespace_context_text != nullptr) &&
                                 (strstr(namespace_context_text,
                                         "target-fn|function|a.ns") != nullptr) &&
                                 (strstr(namespace_context_text,
                                         "target-fn|function|d.ns") == nullptr) &&
                                 (strstr(namespace_context_text, "file_b.clj") != nullptr),
                             "namespace filter narrows definitions and retains resolved callers");

    context_options = (CodeLensContextOptions){.path = "file_a.clj"};
    path_context_text = code_lens_context_symbol_ex(repo_dir, "target-fn", &context_options);
    failed |= context_assert((path_context_text != nullptr) &&
                                 (strstr(path_context_text, "target-fn|function|a.ns") != nullptr) &&
                                 (strstr(path_context_text, "target-fn|function|d.ns") == nullptr) &&
                                 (strstr(path_context_text, "file_c.clj") != nullptr),
                             "path filter narrows definition files and retains resolved callers");

    /* excludeTests drops test-path definitions, references, and snippets
     * (and test-kind definitions), leaving production rows intact. */
    context_options = (CodeLensContextOptions){.exclude_tests = true};
    exclude_context_text =
        code_lens_context_symbol_ex(repo_dir, "target-fn", &context_options);
    failed |= context_assert(exclude_context_text != nullptr,
                             "exclude-tests context returns output");
    if (exclude_context_text != nullptr) {
        failed |= context_assert(strstr(exclude_context_text, "target_test.clj") == nullptr,
                                 "exclude-tests context omits test-file rows");
        failed |= context_assert(strstr(exclude_context_text, "target-fn-check") == nullptr,
                                 "exclude-tests context omits test-kind definitions");
        failed |= context_assert(strstr(exclude_context_text, "file_b.clj") != nullptr,
                                 "exclude-tests context keeps production references");
        failed |= context_assert(strstr(exclude_context_text,
                                        "(defn target-fn [] 1)") != nullptr,
                                 "exclude-tests context keeps the exact definition");
    }

    /* Same behavior through the MCP tool argument. */
    {
        char mcp_context_args[2048];

        (void)snprintf(mcp_context_args,
                       sizeof(mcp_context_args),
                       "{\"repo\":\"%s\",\"name\":\"target-fn\",\"excludeTests\":true}",
                       repo_dir);
        mcp_exclude_text = code_lens_test_mcp_call_tool("context", mcp_context_args);
    }
    failed |= context_assert((mcp_exclude_text != nullptr) &&
                                 (strstr(mcp_exclude_text, "target_test.clj") == nullptr) &&
                                 (strstr(mcp_exclude_text, "file_b.clj") != nullptr),
                             "MCP context excludeTests filters test files");

    {
        char mcp_context_args[2048];

        (void)snprintf(mcp_context_args,
                       sizeof(mcp_context_args),
                       "{\"repo\":\"%s\",\"name\":\"target-fn\",\"namespace\":\"a.ns\","
                       "\"path\":\"file_a.clj\"}",
                       repo_dir);
        mcp_filter_text = code_lens_test_mcp_call_tool("context", mcp_context_args);
    }
    failed |= context_assert((mcp_filter_text != nullptr) &&
                                 (strstr(mcp_filter_text, "target-fn|function|a.ns") != nullptr) &&
                                 (strstr(mcp_filter_text, "target-fn|function|d.ns") == nullptr) &&
                                 (strstr(mcp_filter_text, "file_h.clj") != nullptr),
                             "MCP context namespace and path filter resolved callers");

    /* A qualifier in the user's input is stripped before the base lookup. */
    qualified_context_text = code_lens_context_symbol(repo_dir, "al/target-fn");
    failed |= context_assert((qualified_context_text != nullptr) &&
                                 (strstr(qualified_context_text, "al/target-fn|a.ns") != nullptr),
                             "context strips a user-typed qualifier for the reference lookup");
    failed |= context_assert((qualified_context_text != nullptr) &&
                                 (strstr(qualified_context_text, "target-fn|function|a.ns") != nullptr),
                             "context strips a user-typed qualifier for the definition lookup");

    /* Substring fallback rows keep name/kind/location but drop content:
     * whole near-miss definition bodies used to bury the exact answer. */
    substring_context_text = code_lens_context_symbol(repo_dir, "arget-f");
    failed |= context_assert((substring_context_text != nullptr) &&
                                 (strstr(substring_context_text, "target-fn|function|a.ns") !=
                                  nullptr),
                             "context substring fallback lists the definition row");
    failed |= context_assert((substring_context_text != nullptr) &&
                                 (strstr(substring_context_text, "(defn target-fn") == nullptr),
                             "context substring fallback omits definition content");

done:
    if (had_home) {
        (void)setenv("CODE_LENS_HOME", saved_home, 1);
    } else {
        (void)unsetenv("CODE_LENS_HOME");
    }
    if (code_lens_remove_tree(root) != 0) {
        (void)fprintf(stderr, "context alias test warning: failed to remove %s\n", root);
    }
    return failed == 0 ? 0 : 1;
}



static int test_query_filters(void)
{
    char root_template[1024];
    const char *tmp_dir = getenv("TMPDIR");
    const char *old_home = getenv("CODE_LENS_HOME");
    char saved_home[1024] = {0};
    bool had_home = false;
    char *root;
    char *repo_dir = nullptr;
    char *src_dir = nullptr;
    char *test_dir = nullptr;
    char *home_dir = nullptr;
    CodeLensIndexStats stats = {0};
    CodeLensQueryOptions options = {0};
    char *default_text;
    char *exclude_text;
    char *function_text;
    char *test_text;
    char *keyword_text;
    char *unknown_kind_text;
    char *path_text;
    int failed = 0;

    if ((old_home != nullptr) && (strlen(old_home) < sizeof(saved_home))) {
        (void)strcpy(saved_home, old_home);
        had_home = true;
    }

    if ((tmp_dir == nullptr) || (tmp_dir[0] == '\0')) {
        tmp_dir = "/tmp";
    }
    (void)snprintf(root_template, sizeof(root_template), "%s/code-lens-filters-XXXXXX", tmp_dir);
    root = canonical_temp_root(root_template);
    if (root == nullptr) {
        (void)fprintf(stderr, "query filter test failed: mkdtemp failed\n");
        return 1;
    }

    repo_dir = code_lens_join_path(root, "repo");
    home_dir = code_lens_join_path(root, "home");
    src_dir = repo_dir == nullptr ? nullptr : code_lens_join_path(repo_dir, "src");
    test_dir = repo_dir == nullptr ? nullptr : code_lens_join_path(repo_dir, "test");
    if ((repo_dir == nullptr) || (home_dir == nullptr) || (src_dir == nullptr) ||
        (test_dir == nullptr) || (code_lens_mkdir_p(src_dir) != 0) ||
        (code_lens_mkdir_p(test_dir) != 0) || (code_lens_mkdir_p(home_dir) != 0)) {
        (void)fprintf(stderr, "query filter test failed: fixture directory setup failed\n");
        failed = 1;
        goto done;
    }

    if ((write_text_file(src_dir,
                         "payment_report.clj",
                         "(ns filter.prod)\n\n"
                         "(defn report-target [] :report-key)\n") != 0) ||
        (write_text_file(test_dir,
                         "payment_report_test.clj",
                         "(ns filter.prod-test)\n\n"
                         "(defn report-target-test-helper [] :report-key)\n"
                         "(deftest report-target-test (report-target-test-helper))\n") != 0)) {
        failed = 1;
        goto done;
    }

    if (setenv("CODE_LENS_HOME", home_dir, 1) != 0) {
        failed = 1;
        goto done;
    }
    if (code_lens_index_repository(repo_dir, &stats) != 0) {
        (void)fprintf(stderr, "query filter test failed: indexing failed\n");
        failed = 1;
        goto done;
    }
    failed |= readback_assert(stats.file_count == 2U, "query filter fixture file count");

    default_text = code_lens_query_symbols(repo_dir, "report target", 20);
    failed |= readback_assert((default_text != nullptr) &&
                                  (strstr(default_text, "payment_report.clj") != nullptr) &&
                                  (strstr(default_text, "payment_report_test.clj") != nullptr),
                              "default query returns prod and test hits");

    options = (CodeLensQueryOptions){.limit = 20, .exclude_tests = true};
    exclude_text = code_lens_query_symbols_ex(repo_dir, "report target", &options);
    failed |= readback_assert((exclude_text != nullptr) &&
                                  (strstr(exclude_text, "payment_report.clj") != nullptr) &&
                                  (strstr(exclude_text, "payment_report_test.clj") == nullptr) &&
                                  (strstr(exclude_text, "report-target-test") == nullptr),
                              "exclude-tests removes test path and test kind hits");

    options = (CodeLensQueryOptions){.limit = 20, .kind = "function"};
    function_text = code_lens_query_symbols_ex(repo_dir, "report target", &options);
    failed |= readback_assert((function_text != nullptr) &&
                                  (strstr(function_text, "report-target|function") != nullptr) &&
                                  (strstr(function_text, "report-target-test|test") == nullptr) &&
                                  (strstr(function_text, ":report-key") == nullptr),
                              "kind=function keeps functions and suppresses keywords");

    options = (CodeLensQueryOptions){.limit = 20, .kind = "test"};
    test_text = code_lens_query_symbols_ex(repo_dir, "report target", &options);
    failed |= readback_assert((test_text != nullptr) &&
                                  (strstr(test_text, "report-target-test|test") != nullptr) &&
                                  (strstr(test_text, "report-target|function") == nullptr) &&
                                  (strstr(test_text, ":report-key") == nullptr),
                              "kind=test returns only test symbols and suppresses keywords");

    options = (CodeLensQueryOptions){.limit = 20, .kind = "keyword"};
    keyword_text = code_lens_query_symbols_ex(repo_dir, "report key", &options);
    failed |= readback_assert((keyword_text != nullptr) &&
                                  (strstr(keyword_text, ":report-key") != nullptr) &&
                                  (strstr(keyword_text, "report-target|function") == nullptr) &&
                                  (strstr(keyword_text, "unknown kind") == nullptr),
                              "kind=keyword returns keyword rows and no symbol rows");

    options = (CodeLensQueryOptions){.limit = 20, .kind = "fn"};
    unknown_kind_text = code_lens_query_symbols_ex(repo_dir, "report target", &options);
    failed |= readback_assert((unknown_kind_text != nullptr) &&
                                  (strstr(unknown_kind_text, "note: unknown kind \"fn\"") !=
                                   nullptr) &&
                                  (strstr(unknown_kind_text, "valid kinds:") != nullptr) &&
                                  (strstr(unknown_kind_text, "keyword") != nullptr),
                              "unknown kind produces a note naming the valid kinds");

    options = (CodeLensQueryOptions){.limit = 20, .path = "/test/"};
    path_text = code_lens_query_symbols_ex(repo_dir, "report target", &options);
    failed |= readback_assert((path_text != nullptr) &&
                                  (strstr(path_text, "payment_report_test.clj") != nullptr) &&
                                  (strstr(path_text, "/src/payment_report.clj") == nullptr),
                              "path filter narrows results by file path");

done:
    if (had_home) {
        (void)setenv("CODE_LENS_HOME", saved_home, 1);
    } else {
        (void)unsetenv("CODE_LENS_HOME");
    }
    if (code_lens_remove_tree(root) != 0) {
        (void)fprintf(stderr, "query filter test warning: failed to remove %s\n", root);
    }
    return failed == 0 ? 0 : 1;
}

/* Two same-name defs on one line -- the .cljc reader-conditional pattern
 * #?(:clj (def x 1) :cljs (def x 2)) -- must produce two Symbol rows with
 * distinct ids and a database that passes integrity_check; the ids used to
 * collide, corrupting the raw emitter's unique index. */
static int json_assert(int condition, const char *message)
{
    if (!condition) {
        (void)fprintf(stderr, "json test failed: %s\n", message);
        return 1;
    }
    return 0;
}

/* The MCP JSON layer must scope key lookups to one object level: a client
 * that serializes params.arguments before params.name (legal JSON) or sends
 * "name" inside a string value must not confuse tool dispatch. */
static int test_mcp_json_parsing(void)
{
    int failed = 0;
    char *text;
    const char *value;

    /* arguments-before-name: name lookup on params must find params.name,
     * not arguments.name. */
    {
        static const char message[] =
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
            "\"params\":{\"arguments\":{\"name\":\"evil\",\"repo\":\"r\"},"
            "\"name\":\"list_repos\"}}";
        const char *params = code_lens_test_json_object_value(message, "params");

        failed |= json_assert(params != nullptr, "params found");
        text = code_lens_test_json_get_string(params, "name");
        failed |= json_assert((text != nullptr) && (strcmp(text, "list_repos") == 0),
                              "params.name wins over arguments.name");
        text = code_lens_test_json_get_arguments(params);
        failed |= json_assert((text != nullptr) &&
                                  (strcmp(text, "{\"name\":\"evil\",\"repo\":\"r\"}") == 0),
                              "arguments object extracted");
        text = code_lens_test_json_get_string(text, "name");
        failed |= json_assert((text != nullptr) && (strcmp(text, "evil") == 0),
                              "arguments.name scoped inside arguments");
        text = code_lens_test_json_get_id_raw(message);
        failed |= json_assert((text != nullptr) && (strcmp(text, "7") == 0), "numeric id");
    }

    /* Key names inside string VALUES must not match. */
    {
        static const char params[] =
            "{\"arguments\":{\"query\":\"\\\"name\\\": tricky, \\\"id\\\": 9\"},"
            "\"name\":\"query\"}";

        text = code_lens_test_json_get_string(params, "name");
        failed |= json_assert((text != nullptr) && (strcmp(text, "query") == 0),
                              "quoted key text inside a string value ignored");
        failed |= json_assert(code_lens_test_json_get_id_raw(params) == nullptr,
                              "id inside a string value ignored");
    }

    /* id forms: string ids with escaped quotes; ids in nested objects must
     * not shadow the top-level id. */
    {
        static const char message[] =
            "{\"params\":{\"id\":99},\"id\":\"a\\\"b\",\"method\":\"x\"}";

        text = code_lens_test_json_get_id_raw(message);
        failed |= json_assert((text != nullptr) && (strcmp(text, "\"a\\\"b\"") == 0),
                              "escaped-quote string id raw span");
    }
    failed |= json_assert(code_lens_test_json_get_id_raw("{\"params\":{\"id\":99}}") == nullptr,
                          "nested id is not the message id");

    /* \uXXXX decoding: BMP, surrogate pair, malformed. */
    text = code_lens_test_json_get_string("{\"k\":\"\\u0041\\u00e9\\u2192\"}", "k");
    failed |= json_assert((text != nullptr) && (strcmp(text, "A\xc3\xa9\xe2\x86\x92") == 0),
                          "\\u BMP escapes decode to UTF-8");
    text = code_lens_test_json_get_string("{\"k\":\"\\ud83d\\ude00\"}", "k");
    failed |= json_assert((text != nullptr) && (strcmp(text, "\xf0\x9f\x98\x80") == 0),
                          "surrogate pair decodes to 4-byte UTF-8");
    failed |= json_assert(code_lens_test_json_get_string("{\"k\":\"\\ud83d oops\"}", "k") ==
                              nullptr,
                          "lone high surrogate rejected");
    failed |= json_assert(code_lens_test_json_get_string("{\"k\":\"\\uZZZZ\"}", "k") == nullptr,
                          "malformed hex rejected");

    /* Structural edge cases. */
    value = code_lens_test_json_object_value("{\"a\":[{\"b\":1},2],\"c\":true}", "c");
    failed |= json_assert((value != nullptr) && (strncmp(value, "true", 4U) == 0),
                          "arrays of objects skipped wholesale");
    failed |= json_assert(code_lens_test_json_object_value("{\"a\":{\"b\":1}}", "b") == nullptr,
                          "nested object keys invisible at outer level");
    failed |= json_assert(code_lens_test_json_object_value("[1,2]", "a") == nullptr,
                          "non-object input rejected");
    failed |= json_assert(code_lens_test_json_get_arguments("{\"arguments\":\"str\"}") == nullptr,
                          "non-object arguments rejected");

    return failed;
}

static int test_cljc_symbol_collision(void)
{
    char root_template[1024];
    const char *tmp_dir = getenv("TMPDIR");
    const char *old_home = getenv("CODE_LENS_HOME");
    char saved_home[1024] = {0};
    bool had_home = false;
    char *root;
    char *repo_dir = nullptr;
    char *src_dir = nullptr;
    char *home_dir = nullptr;
    char *repos_dir = nullptr;
    char *db_path = nullptr;
    CodeLensIndexStats stats = {0};
    char *rows;
    int failed = 0;

    if ((old_home != nullptr) && (strlen(old_home) < sizeof(saved_home))) {
        (void)strcpy(saved_home, old_home);
        had_home = true;
    }
    if ((tmp_dir == nullptr) || (tmp_dir[0] == '\0')) {
        tmp_dir = "/tmp";
    }
    (void)snprintf(root_template, sizeof(root_template), "%s/code-lens-cljc-XXXXXX", tmp_dir);
    root = canonical_temp_root(root_template);
    if (root == nullptr) {
        (void)fprintf(stderr, "cljc collision test failed: mkdtemp failed\n");
        return 1;
    }

    repo_dir = code_lens_join_path(root, "repo");
    home_dir = code_lens_join_path(root, "home");
    src_dir = repo_dir == nullptr ? nullptr : code_lens_join_path(repo_dir, "src");
    if ((repo_dir == nullptr) || (home_dir == nullptr) || (src_dir == nullptr) ||
        (code_lens_mkdir_p(src_dir) != 0) || (code_lens_mkdir_p(home_dir) != 0) ||
        (setenv("CODE_LENS_HOME", home_dir, 1) != 0)) {
        (void)fprintf(stderr, "cljc collision test failed: fixture setup failed\n");
        failed = 1;
        goto done;
    }

    if (write_text_file(src_dir,
                        "dup.cljc",
                        "(ns dup.core\n"
                        "  (:require #?(:clj [dup.clj-only :as dco]\n"
                        "               :cljs [dup.cljs-only :as-alias dcs])))\n"
                        "#?(:clj (def dup-def 1) :cljs (def dup-def 2))\n") != 0) {
        failed = 1;
        goto done;
    }

    if (code_lens_index_repository(repo_dir, &stats) != 0) {
        (void)fprintf(stderr,
                      "cljc collision test failed: indexing a same-line duplicate def failed\n");
        failed = 1;
        goto done;
    }
    failed |= readback_assert(stats.symbol_count == 2U, "cljc duplicate def symbol count");

    rows = code_lens_run_sql(repo_dir,
                              "SELECT COUNT(*), COUNT(DISTINCT id) FROM Symbol"
                              " WHERE name = 'dup-def'");
    failed |= readback_assert((rows != nullptr) && (strstr(rows, "2|2") != nullptr),
                              "both duplicate defs stored under distinct ids");

    rows = code_lens_run_sql(repo_dir,
                              "SELECT namespace, alias FROM Alias ORDER BY alias");
    failed |= readback_assert((rows != nullptr) &&
                                  (strstr(rows, "dup.clj-only|dco") != nullptr) &&
                                  (strstr(rows, "dup.cljs-only|dcs") != nullptr),
                              "reader-conditional requires indexed as aliases");

    /* integrity_check needs a direct handle: locate the one repo database
     * under <home>/repos/<dir>/index.sqlite. */
    repos_dir = code_lens_join_path(home_dir, "repos");
    if (repos_dir != nullptr) {
        DIR *dir = opendir(repos_dir);

        if (dir != nullptr) {
            struct dirent *entry;

            while ((entry = readdir(dir)) != nullptr) {
                if (entry->d_name[0] == '.') {
                    continue;
                }
                db_path = code_lens_join_path(repos_dir, entry->d_name);
                db_path = db_path == nullptr ? nullptr
                                             : code_lens_join_path(db_path, "index.sqlite");
                break;
            }
            (void)closedir(dir);
        }
    }
    if ((db_path == nullptr) || !code_lens_path_exists(db_path)) {
        (void)fprintf(stderr, "cljc collision test failed: repo database not found\n");
        failed = 1;
        goto done;
    }
    {
        CodeLensDb db;

        if (code_lens_db_open_read(&db, db_path) != 0) {
            (void)fprintf(stderr, "cljc collision test failed: cannot open repo database\n");
            failed = 1;
            goto done;
        }
        rows = code_lens_db_query_to_string(&db, "PRAGMA integrity_check");
        code_lens_db_close(&db);
        failed |= readback_assert((rows != nullptr) && (strstr(rows, "ok") != nullptr) &&
                                      (strstr(rows, "row missing") == nullptr) &&
                                      (strstr(rows, "non-unique") == nullptr),
                                  "published database passes integrity_check");
    }

done:
    if (had_home) {
        (void)setenv("CODE_LENS_HOME", saved_home, 1);
    } else {
        (void)unsetenv("CODE_LENS_HOME");
    }
    if (code_lens_remove_tree(root) != 0) {
        (void)fprintf(stderr, "cljc collision test warning: failed to remove %s\n", root);
    }
    return failed == 0 ? 0 : 1;
}

/* --- incremental re-index test --------------------------------------- */

#define INCREMENTAL_GEN_FILES 20U

static int incremental_assert(int condition, const char *message)
{
    if (!condition) {
        (void)fprintf(stderr, "incremental test failed: %s\n", message);
        return 1;
    }
    return 0;
}

/* Copies text minus note:/warning: lines (the staleness note embeds elapsed
 * time, which legitimately differs between otherwise identical outputs). */
static char *strip_note_lines(const char *text)
{
    size_t len;
    char *out;
    size_t pos = 0U;

    if (text == nullptr) {
        return nullptr;
    }
    len = strlen(text);
    out = code_lens_alloc(len + 1U);
    if (out == nullptr) {
        return nullptr;
    }
    for (size_t i = 0U; i < len;) {
        size_t line_end = i;

        while ((line_end < len) && (text[line_end] != '\n')) {
            line_end++;
        }
        if ((strncmp(text + i, "note:", 5U) != 0) && (strncmp(text + i, "warning:", 8U) != 0)) {
            size_t n = line_end - i + ((line_end < len) ? 1U : 0U);

            (void)memcpy(out + pos, text + i, n);
            pos += n;
        }
        i = line_end + 1U;
    }
    out[pos] = '\0';
    return out;
}

static int write_incremental_gen_file(const char *src_dir, size_t index, bool extended)
{
    char name[64];
    char content[512];

    (void)snprintf(name, sizeof(name), "inc_ns_%zu.clj", index);
    (void)snprintf(content,
                   sizeof(content),
                   "(ns inc.ns-%zu\n"
                   "  (:require [inc.util :as u]))\n\n"
                   "(defn consumer-%zu [x]\n"
                   "  (u/shared x ::local-%zu :plain/kw-%zu))\n%s",
                   index,
                   index,
                   index,
                   index,
                   extended ? "\n(defn consumer-extra [y]\n  (u/shared y ::extra))\n" : "");
    return write_text_file(src_dir, name, content);
}

/* RefData ids from a full build are contiguous 0-based ordinals, so
 * MAX(id) - COUNT(*) + 1 is 0; an incremental refresh vacates the deleted
 * files' id ranges and appends past the maximum, leaving holes. This is the
 * observable marker for which path produced the database. */
static long long refdata_id_gap(const char *repo_id)
{
    char *rows = code_lens_run_sql(repo_id,
                                    "SELECT (SELECT MAX(id) FROM RefData)"
                                    " - (SELECT COUNT(*) FROM RefData) + 1");

    return parse_single_count(rows);
}

/* Gathers the incremental-vs-full comparison bundle: query, context, and
 * deterministically ordered SQL over the three public views. */
static char *gather_comparison_bundle(const char *repo_id)
{
    static const char *const sql[] = {
        "SELECT symbol, symbolBase, targetNamespace, filePath, lineNumber, columnNumber,"
        " startByte, endByte FROM Ref ORDER BY filePath, lineNumber, columnNumber, symbol",
        "SELECT keyword, keywordBase, qualifier, targetNamespace, filePath, lineNumber,"
        " columnNumber FROM Keyword ORDER BY filePath, lineNumber, columnNumber, keyword",
        "SELECT repo, name, kind, namespace, filePath, startLine, endLine, content, doc"
        " FROM Symbol ORDER BY filePath, startLine, name",
        "SELECT path, namespace, size FROM File ORDER BY path",
        "SELECT filePath, namespace, alias FROM Alias ORDER BY filePath, namespace, alias",
        "SELECT filePath, namespace, symbol FROM Referred ORDER BY filePath, namespace, symbol",
    };
    char *parts[8] = {nullptr};
    size_t total = 0U;
    char *bundle;
    size_t pos = 0U;

    parts[0] = strip_note_lines(code_lens_query_symbols(repo_id, "shared", 50));
    parts[1] = strip_note_lines(code_lens_context_symbol(repo_id, "shared"));
    for (size_t i = 0U; i < (sizeof(sql) / sizeof(sql[0])); i++) {
        parts[2U + i] = code_lens_run_sql(repo_id, sql[i]);
    }
    for (size_t i = 0U; i < 8U; i++) {
        if (parts[i] == nullptr) {
            return nullptr;
        }
        total += strlen(parts[i]) + 1U;
    }
    bundle = code_lens_alloc(total + 1U);
    if (bundle == nullptr) {
        return nullptr;
    }
    for (size_t i = 0U; i < 8U; i++) {
        size_t n = strlen(parts[i]);

        (void)memcpy(bundle + pos, parts[i], n);
        pos += n;
        bundle[pos++] = '\x1f';
    }
    bundle[pos] = '\0';
    return bundle;
}

static int test_incremental_reindex(void)
{
    char root_template[1024];
    char *root;
    const char *old_home = getenv("CODE_LENS_HOME");
    char saved_home[1024] = {0};
    bool had_home = old_home != nullptr;
    const char *old_incremental = getenv("CODE_LENS_INCREMENTAL");
    char saved_incremental[64] = {0};
    bool had_incremental = old_incremental != nullptr;
    char *repo_dir;
    char *src_dir;
    char *home1;
    char *home2;
    char *removed_path = nullptr;
    CodeLensIndexStats stats_full = {0};
    CodeLensIndexStats stats_inc = {0};
    CodeLensIndexStats stats_fresh = {0};
    char *bundle_inc = nullptr;
    char *bundle_fresh = nullptr;
    char incremental_query_args[2048];
    int failed = 0;

    if (had_home) {
        (void)snprintf(saved_home, sizeof(saved_home), "%s", old_home);
    }
    if (had_incremental) {
        (void)snprintf(saved_incremental, sizeof(saved_incremental), "%s", old_incremental);
    }
    (void)unsetenv("CODE_LENS_INCREMENTAL");

    (void)snprintf(root_template, sizeof(root_template), "/tmp/code-lens-incremental-XXXXXX");
    root = canonical_temp_root(root_template);
    if (root == nullptr) {
        (void)fprintf(stderr, "incremental test failed: mkdtemp failed\n");
        return 1;
    }

    repo_dir = code_lens_join_path(root, "repo");
    home1 = code_lens_join_path(root, "home1");
    home2 = code_lens_join_path(root, "home2");
    src_dir = repo_dir == nullptr ? nullptr : code_lens_join_path(repo_dir, "src");
    if ((repo_dir == nullptr) || (home1 == nullptr) || (home2 == nullptr) ||
        (src_dir == nullptr) || (code_lens_mkdir_p(src_dir) != 0) ||
        (code_lens_mkdir_p(home1) != 0) || (code_lens_mkdir_p(home2) != 0)) {
        (void)fprintf(stderr, "incremental test failed: fixture setup failed\n");
        failed = 1;
        goto done;
    }

    failed |= incremental_assert(write_text_file(src_dir,
                                                 "util.clj",
                                                 "(ns inc.util)\n\n(defn shared [x]\n  x)\n") == 0,
                                 "write util.clj");
    for (size_t i = 0U; i < (size_t)INCREMENTAL_GEN_FILES; i++) {
        failed |= incremental_assert(write_incremental_gen_file(src_dir, i, false) == 0,
                                     "write generated fixture file");
    }
    if (failed) {
        goto done;
    }
    failed |= incremental_assert(commit_git_fixture(repo_dir) == 0,
                                 "commit Git fixture for MCP refresh");
    if (failed) {
        goto done;
    }

    /* Full build into home1. */
    if (setenv("CODE_LENS_HOME", home1, 1) != 0) {
        failed = 1;
        goto done;
    }
    failed |= incremental_assert(code_lens_index_repository(repo_dir, &stats_full) == 0,
                                 "initial full index succeeds");
    failed |= incremental_assert(refdata_id_gap(repo_dir) == 0LL,
                                 "full build has contiguous RefData ids");
    if (failed) {
        goto done;
    }

    /* Modify one file, remove one, add one: 3 of 21 files is under the 20%
     * budget, so the default configuration takes the incremental path. */
    failed |= incremental_assert(write_incremental_gen_file(src_dir, 2U, true) == 0,
                                 "modify fixture file");
    removed_path = code_lens_join_path(src_dir, "inc_ns_4.clj");
    failed |= incremental_assert((removed_path != nullptr) && (unlink(removed_path) == 0),
                                 "remove fixture file");
    failed |= incremental_assert(
        write_text_file(src_dir,
                        "inc_extra.clj",
                        "(ns inc.extra\n  (:require [inc.util :as u]))\n\n"
                        "(defn extra-consumer [z]\n  (u/shared z ::brand-new))\n") == 0,
        "add fixture file");
    if (failed) {
        goto done;
    }

    /* MCP query detects the stale index and drives the same incremental path
     * before searching. The changed file's new symbol must be visible in this
     * first read, and the id holes prove this was not a full rebuild. */
    {
        int args_len = snprintf(incremental_query_args,
                                sizeof(incremental_query_args),
                                "{\"repo\":\"%s\",\"query\":\"consumer-extra\"}",
                                repo_dir);
        char *refresh_text =
            (args_len < 0) || ((size_t)args_len >= sizeof(incremental_query_args))
                ? nullptr
                : code_lens_test_mcp_call_tool("query", incremental_query_args);

        failed |= incremental_assert(
            (refresh_text != nullptr) && (strstr(refresh_text, "auto-refreshed") != nullptr) &&
                (strstr(refresh_text, "consumer-extra|") != nullptr),
            "MCP query incrementally refreshes before searching");
    }
    failed |= incremental_assert(refdata_id_gap(repo_dir) > 0LL,
                                  "MCP incremental refresh leaves RefData id holes");

    /* A subsequent explicit no-op index must not rebuild (the id holes
     * survive) and must report the auto-refreshed index's stored totals. */
    failed |= incremental_assert(code_lens_index_repository(repo_dir, &stats_inc) == 0,
                                  "up-to-date index succeeds");
    failed |= incremental_assert(stats_inc.file_count == stats_full.file_count,
                                  "incremental file count matches (one added, one removed)");
    failed |= incremental_assert(refdata_id_gap(repo_dir) > 0LL,
                                  "up-to-date path does not rebuild");
    if (failed) {
        goto done;
    }
    bundle_inc = gather_comparison_bundle(repo_dir);

    /* Fresh full build of the same tree into home2 must be observably
     * identical: same stats, same query/context output, same ordered rows. */
    if (setenv("CODE_LENS_HOME", home2, 1) != 0) {
        failed = 1;
        goto done;
    }
    failed |= incremental_assert(code_lens_index_repository(repo_dir, &stats_fresh) == 0,
                                 "fresh full index succeeds");
    failed |= incremental_assert((stats_fresh.file_count == stats_inc.file_count) &&
                                     (stats_fresh.symbol_count == stats_inc.symbol_count) &&
                                     (stats_fresh.reference_count == stats_inc.reference_count) &&
                                     (stats_fresh.keyword_count == stats_inc.keyword_count) &&
                                     (stats_fresh.alias_count == stats_inc.alias_count),
                                 "incremental totals match a fresh full build");
    bundle_fresh = gather_comparison_bundle(repo_dir);
    failed |= incremental_assert((bundle_inc != nullptr) && (bundle_fresh != nullptr),
                                 "comparison bundles gathered");
    if ((bundle_inc != nullptr) && (bundle_fresh != nullptr)) {
        failed |= incremental_assert(strcmp(bundle_inc, bundle_fresh) == 0,
                                     "incremental results equal a fresh full build");
    }

    /* Kill switch: with CODE_LENS_INCREMENTAL=0 a change triggers a full
     * rebuild, restoring contiguous ids. */
    if (setenv("CODE_LENS_HOME", home1, 1) != 0) {
        failed = 1;
        goto done;
    }
    failed |= incremental_assert(write_incremental_gen_file(src_dir, 6U, true) == 0,
                                 "modify fixture file for kill-switch check");
    if (setenv("CODE_LENS_INCREMENTAL", "0", 1) != 0) {
        failed = 1;
        goto done;
    }
    failed |= incremental_assert(code_lens_index_repository(repo_dir, nullptr) == 0,
                                 "kill-switch index succeeds");
    failed |= incremental_assert(refdata_id_gap(repo_dir) == 0LL,
                                 "kill switch forces a full rebuild");
    (void)unsetenv("CODE_LENS_INCREMENTAL");

    /* Over-budget diff (7 of 21 files ~ 33%) falls back to a full rebuild:
     * ids stay contiguous even though incremental mode is enabled. */
    for (size_t i = 10U; i < 17U; i++) {
        failed |= incremental_assert(write_incremental_gen_file(src_dir, i, true) == 0,
                                     "modify fixture files for budget check");
    }
    failed |= incremental_assert(code_lens_index_repository(repo_dir, nullptr) == 0,
                                 "over-budget index succeeds");
    failed |= incremental_assert(refdata_id_gap(repo_dir) == 0LL,
                                 "over-budget diff falls back to a full rebuild");

done:
    if (had_home) {
        (void)setenv("CODE_LENS_HOME", saved_home, 1);
    } else {
        (void)unsetenv("CODE_LENS_HOME");
    }
    if (had_incremental) {
        (void)setenv("CODE_LENS_INCREMENTAL", saved_incremental, 1);
    } else {
        (void)unsetenv("CODE_LENS_INCREMENTAL");
    }
    if ((root != nullptr) && (code_lens_remove_tree(root) != 0)) {
        (void)fprintf(stderr, "incremental test warning: failed to remove %s\n", root);
    }
    if (failed == 0) {
        (void)fprintf(stderr, "incremental re-index tests passed\n");
    }
    return failed == 0 ? 0 : 1;
}

/* --- MCP on-demand indexing ------------------------------------------ */

static int run_test_command(char *const arguments[])
{
    pid_t child = fork();
    pid_t waited;
    int status;

    if (child == 0) {
        execvp(arguments[0], arguments);
        _exit(127);
    }
    if (child < 0) {
        return -1;
    }
    do {
        waited = waitpid(child, &status, 0);
    } while ((waited < 0) && (errno == EINTR));
    return (waited == child) && WIFEXITED(status) && (WEXITSTATUS(status) == 0) ? 0 : -1;
}

static int commit_git_fixture(const char *repo_dir)
{
    char *const init_arguments[] = {"git", "init", "--quiet", (char *)repo_dir, nullptr};
    char *const add_arguments[] = {"git", "-C", (char *)repo_dir, "add", "--all", nullptr};
    char *const commit_arguments[] = {
        "git",
        "-c",
        "user.name=CodeLens Test",
        "-c",
        "user.email=code-lens-test@example.invalid",
        "-C",
        (char *)repo_dir,
        "commit",
        "--quiet",
        "-m",
        "fixture",
        nullptr,
    };

    return (run_test_command(init_arguments) == 0) &&
                   (run_test_command(add_arguments) == 0) &&
                   (run_test_command(commit_arguments) == 0)
               ? 0
               : -1;
}

static int init_git_fixture(const char *repo_dir, const char *name, const char *content)
{
    return (code_lens_mkdir_p(repo_dir) == 0) &&
                   (write_text_file(repo_dir, name, content) == 0) &&
                   (commit_git_fixture(repo_dir) == 0)
               ? 0
               : -1;
}

static int add_linked_worktree(const char *repo_dir, const char *worktree_dir)
{
    char *const arguments[] = {
        "git",
        "-C",
        (char *)repo_dir,
        "worktree",
        "add",
        "--quiet",
        "--detach",
        (char *)worktree_dir,
        "HEAD",
        nullptr,
    };

    return run_test_command(arguments);
}

/* Every cache used here has at most one published index. This lets the
 * on-demand tests inspect the generated database without duplicating the
 * production private path-to-cache-key mapping. */
static char *mcp_single_index_db_path(const char *home_dir)
{
    char *repos_dir = code_lens_join_path(home_dir, "repos");
    DIR *dir;
    struct dirent *entry;
    char *result = nullptr;

    if (repos_dir == nullptr) {
        return nullptr;
    }
    dir = opendir(repos_dir);
    if (dir == nullptr) {
        return nullptr;
    }
    while ((entry = readdir(dir)) != nullptr) {
        char *repo_dir;
        char *db_path;

        if (entry->d_name[0] == '.') {
            continue;
        }
        repo_dir = code_lens_join_path(repos_dir, entry->d_name);
        db_path = repo_dir == nullptr ? nullptr : code_lens_join_path(repo_dir, "index.sqlite");
        if ((db_path == nullptr) || !code_lens_path_exists(db_path)) {
            continue;
        }
        if (result != nullptr) {
            (void)closedir(dir);
            return nullptr;
        }
        result = db_path;
    }
    (void)closedir(dir);
    return result;
}

static int mcp_index_is_valid(const char *db_path)
{
    CodeLensDb db;
    char *integrity;
    char *repo_count;
    int valid;

    if ((db_path == nullptr) || (code_lens_db_open_read(&db, db_path) != 0)) {
        return 0;
    }
    integrity = code_lens_db_query_to_string(&db, "PRAGMA integrity_check");
    repo_count = code_lens_db_query_to_string(&db, "SELECT COUNT(*) FROM Repo");
    valid = (integrity != nullptr) && (strstr(integrity, "ok") != nullptr) &&
            (parse_single_count(repo_count) == 1LL);
    code_lens_db_close(&db);
    return valid;
}

static int mcp_on_demand_assert(int condition, const char *message)
{
    if (!condition) {
        (void)fprintf(stderr, "MCP on-demand test failed: %s\n", message);
        return 1;
    }
    return 0;
}

static int mcp_rejects_non_root(const char *home_dir,
                                const char *repo_dir,
                                const char *expected_symbol)
{
    char args[2048];
    int args_len;
    char *text;

    args_len = snprintf(args,
                        sizeof(args),
                        "{\"repo\":\"%s\",\"query\":\"%s\"}",
                        repo_dir,
                        expected_symbol);
    if ((args_len < 0) || ((size_t)args_len >= sizeof(args)) ||
        (setenv("CODE_LENS_HOME", home_dir, 1) != 0)) {
        return 0;
    }
    text = code_lens_test_mcp_call_tool("query", args);
    return (text != nullptr) && (strstr(text, expected_symbol) == nullptr) &&
           (mcp_single_index_db_path(home_dir) == nullptr);
}

static int test_mcp_on_demand_indexing(void)
{
    char root_template[1024];
    const char *old_home = getenv("CODE_LENS_HOME");
    char saved_home[1024] = {0};
    bool had_home = old_home != nullptr;
    char *root;
    char *empty_home;
    char *query_home;
    char *context_home;
    char *sql_home;
    char *repair_home;
    char *validation_home;
    char *linked_home;
    char *invalid_home;
    char *remove_home;
    char *query_repo;
    char *context_repo;
    char *sql_repo;
    char *repair_repo;
    char *validation_repo;
    char *validation_child;
    char *linked_worktree;
    char *plain_repo;
    char *bare_repo;
    char *malformed_repo;
    char *dangling_repo;
    char *missing_repo;
    char *remove_repo;
    char query_args[2048];
    char context_args[2048];
    char sql_args[2048];
    char *text;
    char *db_path;
    int failed = 0;

    if (had_home) {
        (void)snprintf(saved_home, sizeof(saved_home), "%s", old_home);
    }
    (void)snprintf(root_template,
                   sizeof(root_template),
                   "build/code-lens-mcp-on-demand-XXXXXX");
    root = canonical_temp_root(root_template);
    if (root == nullptr) {
        (void)fprintf(stderr, "MCP on-demand test failed: mkdtemp failed\n");
        return 1;
    }

    empty_home = code_lens_join_path(root, "empty-home");
    query_home = code_lens_join_path(root, "query-home");
    context_home = code_lens_join_path(root, "context-home");
    sql_home = code_lens_join_path(root, "sql-home");
    repair_home = code_lens_join_path(root, "repair-home");
    validation_home = code_lens_join_path(root, "validation-home");
    linked_home = code_lens_join_path(root, "linked-home");
    invalid_home = code_lens_join_path(root, "invalid-home");
    remove_home = code_lens_join_path(root, "remove-home");
    query_repo = code_lens_join_path(root, "query-repo");
    context_repo = code_lens_join_path(root, "context-repo");
    sql_repo = code_lens_join_path(root, "sql-repo");
    repair_repo = code_lens_join_path(root, "repair-repo");
    validation_repo = code_lens_join_path(root, "validation-repo");
    linked_worktree = code_lens_join_path(root, "linked-worktree");
    plain_repo = code_lens_join_path(root, "plain-repo");
    bare_repo = code_lens_join_path(root, "bare-repo");
    malformed_repo = code_lens_join_path(root, "malformed-repo");
    dangling_repo = code_lens_join_path(root, "dangling-repo");
    missing_repo = code_lens_join_path(root, "missing-repo");
    remove_repo = code_lens_join_path(root, "remove-repo");
    validation_child =
        validation_repo == nullptr ? nullptr : code_lens_join_path(validation_repo, "child");
    if ((empty_home == nullptr) || (query_home == nullptr) || (context_home == nullptr) ||
        (sql_home == nullptr) || (repair_home == nullptr) || (validation_home == nullptr) ||
        (linked_home == nullptr) || (invalid_home == nullptr) || (remove_home == nullptr) ||
        (query_repo == nullptr) || (context_repo == nullptr) || (sql_repo == nullptr) ||
        (repair_repo == nullptr) || (validation_repo == nullptr) ||
        (validation_child == nullptr) || (linked_worktree == nullptr) || (plain_repo == nullptr) ||
        (bare_repo == nullptr) || (malformed_repo == nullptr) || (dangling_repo == nullptr) ||
        (missing_repo == nullptr) || (remove_repo == nullptr) ||
        (code_lens_mkdir_p(empty_home) != 0) || (code_lens_mkdir_p(query_home) != 0) ||
        (code_lens_mkdir_p(context_home) != 0) || (code_lens_mkdir_p(sql_home) != 0) ||
        (code_lens_mkdir_p(repair_home) != 0) || (code_lens_mkdir_p(validation_home) != 0) ||
        (code_lens_mkdir_p(linked_home) != 0) || (code_lens_mkdir_p(invalid_home) != 0) ||
        (code_lens_mkdir_p(remove_home) != 0) ||
        (init_git_fixture(query_repo,
                          "query.clj",
                          "(ns on.demand.query)\n(defn first-query [] 1)\n") != 0) ||
        (init_git_fixture(context_repo,
                          "context.clj",
                          "(ns on.demand.context)\n"
                          "(defn first-context [] 1)\n"
                          "(defn context-caller [] (first-context))\n") != 0) ||
        (init_git_fixture(sql_repo,
                          "sql.clj",
                          "(ns on.demand.sql)\n(defn sql-first [] 1)\n") != 0) ||
        (init_git_fixture(repair_repo,
                          "repair.clj",
                          "(ns on.demand.repair)\n(defn repair-symbol [] 1)\n") != 0) ||
        (init_git_fixture(validation_repo,
                          "validation.clj",
                          "(ns on.demand.validation)\n(defn exact-root [] 1)\n") != 0) ||
        (code_lens_mkdir_p(validation_child) != 0) ||
        (add_linked_worktree(validation_repo, linked_worktree) != 0) ||
        (init_git_fixture(remove_repo,
                          "remove.clj",
                          "(ns on.demand.remove)\n(defn remove-after-delete [] 1)\n") != 0) ||
        (code_lens_mkdir_p(plain_repo) != 0) ||
        (write_text_file(plain_repo,
                         "plain.clj",
                         "(ns on.demand.plain)\n(defn plain-root [] 1)\n") != 0) ||
        (code_lens_mkdir_p(malformed_repo) != 0) ||
        (write_text_file(malformed_repo,
                         "malformed.clj",
                         "(ns on.demand.malformed)\n(defn malformed-root [] 1)\n") != 0) ||
        (write_text_file(malformed_repo, ".git", "not a gitdir\n") != 0) ||
        (code_lens_mkdir_p(dangling_repo) != 0) ||
        (write_text_file(dangling_repo,
                         "dangling.clj",
                         "(ns on.demand.dangling)\n(defn dangling-root [] 1)\n") != 0) ||
        (write_text_file(dangling_repo, ".git", "gitdir: /does/not/exist\n") != 0)) {
        (void)fprintf(stderr, "MCP on-demand test failed: fixture setup failed\n");
        failed = 1;
        goto done;
    }
    {
        char *const bare_arguments[] = {"git", "init", "--bare", "--quiet", bare_repo, nullptr};

        if (run_test_command(bare_arguments) != 0) {
            (void)fprintf(stderr, "MCP on-demand test failed: bare repository setup failed\n");
            failed = 1;
            goto done;
        }
    }

    /* list_repos is observational: an empty cache remains empty after listing. */
    if (setenv("CODE_LENS_HOME", empty_home, 1) != 0) {
        failed = 1;
        goto done;
    }
    text = code_lens_test_mcp_call_tool("list_repos", "{}");
    failed |= mcp_on_demand_assert(
        (text != nullptr) &&
            (strcmp(text,
                    "No readable repositories are indexed yet. `list_repos` only reports "
                    "existing indexes. To create one automatically, call `query`, `context`, "
                    "or `sql` with `repo` set to the exact root of a non-bare Git worktree. "
                    "That call will build the index before returning results.\n") == 0) &&
            (mcp_single_index_db_path(empty_home) == nullptr),
        "MCP list_repos explains automatic indexing without building an index");

    /* First calls must build from a valid exact Git root and return their
     * requested result, rather than telling the client to make a second call. */
    (void)snprintf(query_args,
                   sizeof(query_args),
                   "{\"repo\":\"%s\",\"query\":\"first-query\"}",
                   query_repo);
    if (setenv("CODE_LENS_HOME", query_home, 1) != 0) {
        failed = 1;
        goto done;
    }
    text = code_lens_test_mcp_call_tool("query", query_args);
    failed |= mcp_on_demand_assert((text != nullptr) &&
                                       (strstr(text, "first-query|function|on.demand.query") != nullptr) &&
                                       (mcp_single_index_db_path(query_home) != nullptr),
                                   "MCP query builds an empty-cache Git root on its initiating call");

    (void)snprintf(context_args,
                   sizeof(context_args),
                   "{\"repo\":\"%s\",\"name\":\"first-context\"}",
                   context_repo);
    if (setenv("CODE_LENS_HOME", context_home, 1) != 0) {
        failed = 1;
        goto done;
    }
    text = code_lens_test_mcp_call_tool("context", context_args);
    failed |= mcp_on_demand_assert(
        (text != nullptr) &&
            (strstr(text, "first-context|function|on.demand.context") != nullptr) &&
            (strstr(text, "context-caller") != nullptr) &&
            (mcp_single_index_db_path(context_home) != nullptr),
        "MCP context builds an empty-cache Git root on its initiating call");

    (void)snprintf(sql_args,
                   sizeof(sql_args),
                   "{\"repo\":\"%s\",\"query\":\"SELECT name FROM Symbol WHERE name = 'sql-first'\"}",
                   sql_repo);
    if (setenv("CODE_LENS_HOME", sql_home, 1) != 0) {
        failed = 1;
        goto done;
    }
    text = code_lens_test_mcp_call_tool("sql", sql_args);
    failed |= mcp_on_demand_assert((text != nullptr) &&
                                       (strncmp(text, "name\nsql-first\n", 15U) == 0) &&
                                       (mcp_single_index_db_path(sql_home) != nullptr),
                                   "MCP sql builds an empty-cache Git root on its initiating call");

    if (write_text_file(sql_repo,
                        "sql.clj",
                        "(ns on.demand.sql)\n(defn sql-stale [] 2)\n") != 0) {
        failed = 1;
        goto done;
    }
    (void)snprintf(sql_args,
                   sizeof(sql_args),
                   "{\"repo\":\"%s\",\"query\":\"SELECT name FROM Symbol WHERE name = 'sql-stale'\"}",
                   sql_repo);
    text = code_lens_test_mcp_call_tool("sql", sql_args);
    failed |= mcp_on_demand_assert((text != nullptr) &&
                                       (strncmp(text, "name\nsql-stale\n", 15U) == 0) &&
                                       (strstr(text, "auto-refreshed") != nullptr),
                                   "MCP sql refreshes stale indexes without preceding its header");

    /* An unreadable/corrupt or obsolete database is recovered by the same
     * on-demand path, and the initiating query sees the rebuilt rows. */
    (void)snprintf(query_args,
                   sizeof(query_args),
                   "{\"repo\":\"%s\",\"query\":\"repair-symbol\"}",
                   repair_repo);
    if (setenv("CODE_LENS_HOME", repair_home, 1) != 0) {
        failed = 1;
        goto done;
    }
    text = code_lens_test_mcp_call_tool("query", query_args);
    db_path = mcp_single_index_db_path(repair_home);
    failed |= mcp_on_demand_assert((text != nullptr) &&
                                       (strstr(text, "repair-symbol|") != nullptr) &&
                                       mcp_index_is_valid(db_path),
                                   "MCP creates a repair fixture index");
    {
        FILE *corrupt = db_path == nullptr ? nullptr : fopen(db_path, "w");

        if ((corrupt == nullptr) || (fputs("not an SQLite database\n", corrupt) == EOF) ||
            (fclose(corrupt) != 0)) {
            failed = 1;
            goto done;
        }
    }
    text = code_lens_test_mcp_call_tool("query", query_args);
    db_path = mcp_single_index_db_path(repair_home);
    failed |= mcp_on_demand_assert((text != nullptr) &&
                                       (strstr(text, "repair-symbol|") != nullptr) &&
                                       mcp_index_is_valid(db_path),
                                   "MCP query rebuilds a corrupt index");
    {
        sqlite3 *db = nullptr;
        char *error = nullptr;
        int rc = db_path == nullptr
                     ? SQLITE_ERROR
                     : sqlite3_open_v2(db_path, &db, SQLITE_OPEN_READWRITE, nullptr);

        if ((rc != SQLITE_OK) ||
            (sqlite3_exec(db, "PRAGMA user_version = 0", nullptr, nullptr, &error) != SQLITE_OK)) {
            (void)fprintf(stderr,
                          "MCP on-demand test failed: cannot mark index obsolete: %s\n",
                          error == nullptr ? "open failed" : error);
            sqlite3_free(error);
            if (db != nullptr) {
                (void)sqlite3_close(db);
            }
            failed = 1;
            goto done;
        }
        sqlite3_free(error);
        (void)sqlite3_close(db);
    }
    text = code_lens_test_mcp_call_tool("query", query_args);
    db_path = mcp_single_index_db_path(repair_home);
    failed |= mcp_on_demand_assert((text != nullptr) &&
                                       (strstr(text, "repair-symbol|") != nullptr) &&
                                       mcp_index_is_valid(db_path),
                                   "MCP query rebuilds an obsolete index");
    if ((db_path == nullptr) || (chmod(db_path, 0000) != 0)) {
        (void)fprintf(stderr, "MCP on-demand test failed: cannot make index unreadable\n");
        failed = 1;
        goto done;
    }
    text = code_lens_test_mcp_call_tool("query", query_args);
    db_path = mcp_single_index_db_path(repair_home);
    failed |= mcp_on_demand_assert((text != nullptr) &&
                                       (strstr(text, "repair-symbol|") != nullptr) &&
                                       mcp_index_is_valid(db_path),
                                   "MCP query rebuilds an unreadable index");

    /* Only exact working-tree roots may trigger a build. A normal root and
     * Git's linked-worktree gitfile form are both accepted. */
    (void)snprintf(query_args,
                   sizeof(query_args),
                   "{\"repo\":\"%s\",\"query\":\"exact-root\"}",
                   validation_repo);
    if (setenv("CODE_LENS_HOME", validation_home, 1) != 0) {
        failed = 1;
        goto done;
    }
    text = code_lens_test_mcp_call_tool("query", query_args);
    failed |= mcp_on_demand_assert((text != nullptr) && (strstr(text, "exact-root|") != nullptr) &&
                                       mcp_index_is_valid(mcp_single_index_db_path(validation_home)),
                                   "MCP accepts a normal Git working-tree root");
    {
        char *gitfile_path = code_lens_join_path(linked_worktree, ".git");
        FILE *gitfile = gitfile_path == nullptr ? nullptr : fopen(gitfile_path, "r");
        char line[1024] = {0};

        failed |= mcp_on_demand_assert((gitfile != nullptr) &&
                                           (fgets(line, sizeof(line), gitfile) != nullptr) &&
                                           (strncmp(line, "gitdir: ", 8U) == 0),
                                       "linked-worktree fixture has a .git gitfile");
        if (gitfile != nullptr) {
            (void)fclose(gitfile);
        }
    }
    (void)snprintf(context_args,
                   sizeof(context_args),
                   "{\"repo\":\"%s\",\"name\":\"exact-root\"}",
                   linked_worktree);
    if (setenv("CODE_LENS_HOME", linked_home, 1) != 0) {
        failed = 1;
        goto done;
    }
    text = code_lens_test_mcp_call_tool("context", context_args);
    failed |= mcp_on_demand_assert((text != nullptr) && (strstr(text, "exact-root|") != nullptr) &&
                                       mcp_index_is_valid(mcp_single_index_db_path(linked_home)),
                                   "MCP accepts a linked-worktree .git gitfile root");

    failed |= mcp_on_demand_assert(
        mcp_rejects_non_root(invalid_home, validation_child, "exact-root"),
        "MCP rejects a descendant of a Git working-tree root");
    failed |= mcp_on_demand_assert(mcp_rejects_non_root(invalid_home, plain_repo, "plain-root"),
                                   "MCP rejects a plain non-Git directory");
    failed |= mcp_on_demand_assert(mcp_rejects_non_root(invalid_home, bare_repo, "bare-root"),
                                   "MCP rejects a bare Git repository");
    failed |= mcp_on_demand_assert(mcp_rejects_non_root(invalid_home, missing_repo, "missing-root"),
                                   "MCP rejects a missing repository path");
    failed |= mcp_on_demand_assert(
        mcp_rejects_non_root(invalid_home, malformed_repo, "malformed-root"),
        "MCP rejects a malformed .git gitfile");
    failed |= mcp_on_demand_assert(mcp_rejects_non_root(invalid_home, dangling_repo, "dangling-root"),
                                   "MCP rejects a dangling .git gitfile");

    /* Removal must resolve an already-indexed path even after its checkout
     * vanishes, so stale cache entries never become unremovable. */
    (void)snprintf(query_args,
                   sizeof(query_args),
                   "{\"repo\":\"%s\",\"query\":\"remove-after-delete\"}",
                   remove_repo);
    if (setenv("CODE_LENS_HOME", remove_home, 1) != 0) {
        failed = 1;
        goto done;
    }
    text = code_lens_test_mcp_call_tool("query", query_args);
    failed |= mcp_on_demand_assert((text != nullptr) &&
                                       (strstr(text, "remove-after-delete|") != nullptr) &&
                                       mcp_index_is_valid(mcp_single_index_db_path(remove_home)),
                                   "MCP creates the checkout-deletion fixture index");
    if (code_lens_remove_tree(remove_repo) != 0) {
        failed = 1;
        goto done;
    }
    (void)snprintf(query_args, sizeof(query_args), "{\"repo\":\"%s\"}", remove_repo);
    text = code_lens_test_mcp_call_tool("remove_repo", query_args);
    failed |= mcp_on_demand_assert((text != nullptr) &&
                                       (strstr(text, "removed repo") != nullptr) &&
                                       (mcp_single_index_db_path(remove_home) == nullptr),
                                   "MCP remove_repo works after checkout deletion");

done:
    if (had_home) {
        (void)setenv("CODE_LENS_HOME", saved_home, 1);
    } else {
        (void)unsetenv("CODE_LENS_HOME");
    }
    if (code_lens_remove_tree(root) != 0) {
        (void)fprintf(stderr, "MCP on-demand test warning: failed to remove %s\n", root);
    }
    return failed == 0 ? 0 : 1;
}

/* --- repository write-lock test -------------------------------------- */

static int read_pipe_bytes(int fd, char *out, size_t count, int timeout_ms)
{
    size_t total = 0U;

    while (total < count) {
        struct pollfd descriptor = {.fd = fd, .events = POLLIN};
        int ready;
        ssize_t got;

        do {
            ready = poll(&descriptor, 1, timeout_ms);
        } while ((ready < 0) && (errno == EINTR));
        if (ready <= 0) {
            return -1;
        }
        do {
            got = read(fd, out + total, count - total);
        } while ((got < 0) && (errno == EINTR));
        if (got <= 0) {
            return -1;
        }
        total += (size_t)got;
    }
    return 0;
}

static void concurrent_refresh_child(int inherited_lock_fd,
                                     int ready_fd,
                                     int result_fd,
                                     const char *query_args,
                                     const char *expected_symbol,
                                     bool require_auto_refresh)
{
    char signal = 'R';
    char *text;

    /* POSIX record locks are not inherited across fork, but close the copied
     * descriptor so the child owns only the descriptor opened by indexing. */
    code_lens_test_repo_write_lock_release(inherited_lock_fd);
    if (write(ready_fd, &signal, 1U) != 1) {
        _exit(2);
    }
    text = code_lens_test_mcp_call_tool("query", query_args);
    signal = ((text != nullptr) && (strstr(text, expected_symbol) != nullptr) &&
              (!require_auto_refresh || (strstr(text, "auto-refreshed") != nullptr)) &&
              (strstr(text, "automatic refresh failed") == nullptr))
                 ? '1'
                 : '0';
    (void)write(result_fd, &signal, 1U);
    _exit(signal == '1' ? 0 : 1);
}

static int assert_locked_mcp_pair(const char *repo_dir,
                                  const char *query_args,
                                  const char *expected_symbol,
                                  bool require_auto_refresh)
{
    int ready_pipe[2] = {-1, -1};
    int result_pipe[2] = {-1, -1};
    pid_t children[2] = {-1, -1};
    size_t child_count = 0U;
    int lock_fd = -1;
    int failed = 0;

    lock_fd = code_lens_test_repo_write_lock_acquire(repo_dir);
    if ((lock_fd < 0) || (pipe(ready_pipe) != 0) || (pipe(result_pipe) != 0)) {
        (void)fprintf(stderr, "repository lock test failed: synchronization setup failed\n");
        failed = 1;
        goto done;
    }
    for (size_t i = 0U; i < 2U; i++) {
        pid_t pid = fork();

        if (pid == 0) {
            (void)close(ready_pipe[0]);
            (void)close(result_pipe[0]);
            concurrent_refresh_child(lock_fd,
                                     ready_pipe[1],
                                     result_pipe[1],
                                     query_args,
                                     expected_symbol,
                                     require_auto_refresh);
        }
        if (pid < 0) {
            (void)fprintf(stderr, "repository lock test failed: fork failed\n");
            failed = 1;
            break;
        }
        children[child_count++] = pid;
    }
    (void)close(ready_pipe[1]);
    ready_pipe[1] = -1;
    (void)close(result_pipe[1]);
    result_pipe[1] = -1;

    if (child_count == 2U) {
        char ready[2];
        struct pollfd descriptor = {.fd = result_pipe[0], .events = POLLIN};
        int early;

        if (read_pipe_bytes(ready_pipe[0], ready, sizeof(ready), 30000) != 0) {
            (void)fprintf(stderr, "repository lock test failed: children did not start\n");
            failed = 1;
        }
        do {
            early = poll(&descriptor, 1, 250);
        } while ((early < 0) && (errno == EINTR));
        if (early != 0) {
            (void)fprintf(stderr, "repository lock test failed: MCP call did not block on lock\n");
            failed = 1;
        }
    }

    code_lens_test_repo_write_lock_release(lock_fd);
    lock_fd = -1;
    if (child_count == 2U) {
        char results[2];

        if (read_pipe_bytes(result_pipe[0], results, sizeof(results), 120000) != 0) {
            (void)fprintf(stderr, "repository lock test failed: MCP calls did not finish\n");
            failed = 1;
        } else if ((results[0] != '1') || (results[1] != '1')) {
            (void)fprintf(stderr, "repository lock test failed: a call returned stale data\n");
            failed = 1;
        }
    }
    for (size_t i = 0U; i < child_count; i++) {
        int status;

        if ((waitpid(children[i], &status, 0) != children[i]) || !WIFEXITED(status) ||
            (WEXITSTATUS(status) != 0)) {
            failed = 1;
        }
        children[i] = -1;
    }
done:
    if (lock_fd >= 0) {
        code_lens_test_repo_write_lock_release(lock_fd);
    }
    for (size_t i = 0U; i < child_count; i++) {
        if (children[i] > 0) {
            int status;

            (void)waitpid(children[i], &status, 0);
        }
    }
    for (size_t i = 0U; i < 2U; i++) {
        if (ready_pipe[i] >= 0) {
            (void)close(ready_pipe[i]);
        }
        if (result_pipe[i] >= 0) {
            (void)close(result_pipe[i]);
        }
    }
    return failed == 0 ? 0 : 1;
}

static int test_repository_write_lock(void)
{
    char root_template[1024];
    const char *old_home = getenv("CODE_LENS_HOME");
    char saved_home[1024] = {0};
    bool had_home = old_home != nullptr;
    char *root;
    char *repo_dir;
    char *home_dir;
    char first_args[2048];
    char stale_args[2048];
    char *db_path;
    char *fresh;
    int failed = 0;

    if (had_home) {
        (void)snprintf(saved_home, sizeof(saved_home), "%s", old_home);
    }
    (void)snprintf(root_template, sizeof(root_template), "build/code-lens-lock-XXXXXX");
    root = canonical_temp_root(root_template);
    if (root == nullptr) {
        (void)fprintf(stderr, "repository lock test failed: mkdtemp failed\n");
        return 1;
    }
    repo_dir = code_lens_join_path(root, "repo");
    home_dir = code_lens_join_path(root, "home");
    if ((repo_dir == nullptr) || (home_dir == nullptr) || (code_lens_mkdir_p(home_dir) != 0) ||
        (init_git_fixture(repo_dir,
                          "lock.clj",
                          "(ns lock.repo)\n(defn first-build [] 1)\n") != 0) ||
        (setenv("CODE_LENS_HOME", home_dir, 1) != 0)) {
        (void)fprintf(stderr, "repository lock test failed: fixture setup failed\n");
        failed = 1;
        goto done;
    }
    (void)snprintf(first_args,
                   sizeof(first_args),
                   "{\"repo\":\"%s\",\"query\":\"first-build\"}",
                   repo_dir);
    failed |= readback_assert(mcp_single_index_db_path(home_dir) == nullptr,
                              "fork lock test starts without an index");
    failed |= readback_assert(assert_locked_mcp_pair(repo_dir,
                                                      first_args,
                                                      "first-build|",
                                                      false) == 0,
                              "two first-use MCP calls wait and return indexed data");
    db_path = mcp_single_index_db_path(home_dir);
    failed |= readback_assert(mcp_index_is_valid(db_path),
                              "first-use concurrent calls publish one valid Repo row");

    /* Keep the stale-index race covered independently of first-use builds. */
    if (write_text_file(repo_dir,
                        "lock.clj",
                        "(ns lock.repo)\n(defn concurrent-fresh [] 2)\n") != 0) {
        failed = 1;
        goto done;
    }
    (void)snprintf(stale_args,
                   sizeof(stale_args),
                   "{\"repo\":\"%s\",\"query\":\"concurrent-fresh\"}",
                   repo_dir);
    failed |= readback_assert(assert_locked_mcp_pair(repo_dir,
                                                      stale_args,
                                                      "concurrent-fresh|",
                                                      true) == 0,
                              "two stale-refresh MCP calls wait and return current data");
    fresh = code_lens_query_symbols(repo_dir, "concurrent-fresh", 5);
    failed |= readback_assert((fresh != nullptr) &&
                                  (strstr(fresh, "staleness check passed") != nullptr) &&
                                  (strstr(fresh, "concurrent-fresh|") != nullptr) &&
                                  mcp_index_is_valid(mcp_single_index_db_path(home_dir)),
                              "stale concurrent refresh leaves one current valid index");

done:
    if (had_home) {
        (void)setenv("CODE_LENS_HOME", saved_home, 1);
    } else {
        (void)unsetenv("CODE_LENS_HOME");
    }
    if (code_lens_remove_tree(root) != 0) {
        (void)fprintf(stderr, "repository lock test warning: failed to remove %s\n", root);
    }
    if (failed == 0) {
        (void)fprintf(stderr, "repository write-lock tests passed\n");
    }
    return failed == 0 ? 0 : 1;
}

int main(void)
{
    if (strcmp(CODE_LENS_C_STANDARD, "ISO C23") != 0) {
        (void)fprintf(stderr, "unexpected C standard label: %s\n", CODE_LENS_C_STANDARD);
        return 1;
    }

    if (strlen(CODE_LENS_VERSION) == 0U) {
        (void)fprintf(stderr, "version must not be empty\n");
        return 1;
    }

    const char *sqlite_version = code_lens_sqlite_version();
    if ((sqlite_version == nullptr) || (strlen(sqlite_version) == 0U)) {
        (void)fprintf(stderr, "SQLite version must not be empty\n");
        return 1;
    }

    if (code_lens_clojure_language_abi_version() == 0U) {
        (void)fprintf(stderr, "Clojure language ABI version must not be zero\n");
        return 1;
    }

    if (code_lens_clojure_language_symbol_count() == 0U) {
        (void)fprintf(stderr, "Clojure language symbol count must not be zero\n");
        return 1;
    }

    if (code_lens_java_language_abi_version() == 0U) {
        (void)fprintf(stderr, "Java language ABI version must not be zero\n");
        return 1;
    }

    if (code_lens_java_language_symbol_count() == 0U) {
        (void)fprintf(stderr, "Java language symbol count must not be zero\n");
        return 1;
    }

    if (code_lens_c_language_abi_version() == 0U) {
        (void)fprintf(stderr, "C language ABI version must not be zero\n");
        return 1;
    }

    if (code_lens_c_language_symbol_count() == 0U) {
        (void)fprintf(stderr, "C language symbol count must not be zero\n");
        return 1;
    }

    if (test_clojure_parser() != 0) {
        return 1;
    }

    if (test_java_support() != 0) {
        return 1;
    }

    if (test_c_support() != 0) {
        return 1;
    }

    if (test_index_readback() != 0) {
        return 1;
    }

    if (test_context_alias_resolution() != 0) {
        return 1;
    }

    if (test_query_filters() != 0) {
        return 1;
    }

    if (test_mcp_json_parsing() != 0) {
        return 1;
    }

    if (test_cljc_symbol_collision() != 0) {
        return 1;
    }

    if (test_incremental_reindex() != 0) {
        return 1;
    }

    if (test_mcp_on_demand_indexing() != 0) {
        return 1;
    }

    if (test_repository_write_lock() != 0) {
        return 1;
    }

    if (test_git_blobs() != 0) {
        return 1;
    }

    return 0;
}
