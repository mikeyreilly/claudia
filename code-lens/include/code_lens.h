#ifndef CODE_LENS_H
#define CODE_LENS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include <sqlite3.h>

#define CODE_LENS_VERSION "0.1.0-dev"
#define CODE_LENS_C_STANDARD "ISO C23"

typedef struct {
    void *block;
    size_t used;
} CodeLensArenaMark;

void *code_lens_alloc(size_t size);
void *code_lens_alloc_zeroed(size_t count, size_t size);
void *code_lens_resize(void *ptr, size_t size);
CodeLensArenaMark code_lens_arena_mark(void);
void code_lens_arena_reset(CodeLensArenaMark mark);

typedef struct {
    char *namespace_name;
    char *alias;
} CodeLensNamespaceAlias;

/* One symbol made visible unqualified by a Clojure :refer/:use or Java
 * static import; ":all" records whole-namespace/type wildcard visibility. */
typedef struct {
    char *namespace_name;
    char *symbol;
} CodeLensReferred;

/* For Clojure, base and target_namespace are filled by a post-parse pass:
 * base is the text after the last '/', and target_namespace is the resolved
 * require alias (or verbatim qualifier). The Java extractor fills the same
 * fields directly with the final type/member name and syntax-resolved fully
 * qualified target type. C uses the final identifier plus a file-local or
 * aggregate-type target when known. An empty target means global,
 * unresolved, or unqualified. All pointers outlive the parsed file. The _hash fields carry the indexer's
 * term-interner hash of each text, precomputed on parse workers. */
typedef struct {
    char *symbol;
    size_t symbol_len;
    char *base;
    size_t base_len;
    char *target_namespace;
    size_t target_namespace_len;
    uint64_t symbol_hash;
    uint64_t base_hash;
    uint64_t target_namespace_hash;
    uint32_t line;
    uint32_t column;
    uint32_t start_byte;
    uint32_t end_byte;
} CodeLensReference;

typedef struct {
    char *keyword;
    size_t keyword_len;
    char *base;
    size_t base_len;
    char *qualifier;
    size_t qualifier_len;
    char *target_namespace;
    size_t target_namespace_len;
    uint64_t keyword_hash;
    uint64_t base_hash;
    uint64_t qualifier_hash;
    uint64_t target_namespace_hash;
    uint32_t line;
    uint32_t column;
} CodeLensKeyword;

typedef struct {
    char *kind;
    char *name;
    /* Clojure namespace, Java enclosing type, or C linkage/type scope. */
    char *namespace_name;
    char *doc;
    char *source;
    uint32_t start_line;
    uint32_t end_line;
} CodeLensSymbol;

/* Parsed source-file data shared by the Clojure, Java, and C extractors.
 * namespace_name is a Clojure namespace, Java package, or empty for C. For
 * Java, Alias rows model normal imports and Referred rows model static imports. */
typedef struct {
    char *file_path;
    char *namespace_name;
    CodeLensNamespaceAlias *aliases;
    size_t alias_count;
    size_t alias_capacity;
    CodeLensReferred *referred;
    size_t referred_count;
    size_t referred_capacity;
    CodeLensSymbol *symbols;
    size_t symbol_count;
    size_t symbol_capacity;
    CodeLensReference *references;
    size_t reference_count;
    size_t reference_capacity;
    CodeLensKeyword *keywords;
    size_t keyword_count;
    size_t keyword_capacity;
} CodeLensSourceFile;

/* Language-specific aliases keep the original Clojure parser API source
 * compatible while exposing the shared representation explicitly. */
typedef CodeLensSourceFile CodeLensClojureFile;
typedef CodeLensSourceFile CodeLensJavaFile;
typedef CodeLensSourceFile CodeLensCFile;

typedef struct CodeLensClojureParser CodeLensClojureParser;
typedef struct CodeLensJavaParser CodeLensJavaParser;
typedef struct CodeLensCParser CodeLensCParser;

CodeLensClojureParser *code_lens_clojure_parser_new(void);
void code_lens_clojure_parser_delete(CodeLensClojureParser *parser);
int code_lens_parse_clojure_source_with_parser(CodeLensClojureParser *parser,
                                                const char *file_path,
                                                const char *source,
                                                size_t source_len,
                                                CodeLensClojureFile *out_file);
int code_lens_parse_clojure_source(const char *file_path,
                                    const char *source,
                                    size_t source_len,
                                    CodeLensClojureFile *out_file);
/* Process-wide count of files the custom Clojure reader declined,
 * forcing a tree-sitter fallback parse. */
uint64_t code_lens_clojure_reader_fallbacks(void);

CodeLensJavaParser *code_lens_java_parser_new(void);
void code_lens_java_parser_delete(CodeLensJavaParser *parser);
int code_lens_parse_java_source_with_parser(CodeLensJavaParser *parser,
                                             const char *file_path,
                                             const char *source,
                                             size_t source_len,
                                             CodeLensJavaFile *out_file);
int code_lens_parse_java_source(const char *file_path,
                                 const char *source,
                                 size_t source_len,
                                 CodeLensJavaFile *out_file);

CodeLensCParser *code_lens_c_parser_new(void);
void code_lens_c_parser_delete(CodeLensCParser *parser);
int code_lens_parse_c_source_with_parser(CodeLensCParser *parser,
                                          const char *file_path,
                                          const char *source,
                                          size_t source_len,
                                          CodeLensCFile *out_file);
int code_lens_parse_c_source(const char *file_path,
                              const char *source,
                              size_t source_len,
                              CodeLensCFile *out_file);

/* Process-wide counts of files served from the git object store versus
 * read from the working tree while a git blob source was active. */
uint64_t code_lens_git_blob_read_count(void);
uint64_t code_lens_git_file_read_count(void);

const char *code_lens_sqlite_version(void);
uint32_t code_lens_clojure_language_abi_version(void);
uint32_t code_lens_clojure_language_symbol_count(void);
uint32_t code_lens_java_language_abi_version(void);
uint32_t code_lens_java_language_symbol_count(void);
uint32_t code_lens_c_language_abi_version(void);
uint32_t code_lens_c_language_symbol_count(void);

typedef struct {
    char *path;
    sqlite3 *handle;
    double deadline_seconds;
    bool is_open;
} CodeLensDb;

int code_lens_db_open_read(CodeLensDb *db, const char *path);
int code_lens_db_open_build(CodeLensDb *db, const char *path);
void code_lens_db_close(CodeLensDb *db);
int code_lens_db_exec(CodeLensDb *db, const char *query);
char *code_lens_db_query_to_string(CodeLensDb *db, const char *query);

typedef struct {
    size_t file_count;
    size_t symbol_count;
    size_t reference_count;
    size_t keyword_count;
    size_t alias_count;
    /* wall time of the whole indexing run, including any classic-emitter
     * retry; reported by the explicit CLI index command */
    double elapsed_seconds;
    /* files served from the git object store during this run; the stats
     * line reports it as "(git <blob_reads>/<file_count>)" so an inactive
     * or missing git source is immediately visible */
    uint64_t git_blob_reads;
} CodeLensIndexStats;

int code_lens_index_repository(const char *repo_path, CodeLensIndexStats *out_stats);
char *code_lens_remove_repo(const char *repo_name);

/* The callback path is valid only for the duration of the callback. */
typedef int (*CodeLensFileCallback)(const char *path, void *ctx);

typedef struct {
    char **paths;
    size_t count;
    size_t capacity;
} CodeLensPathList;

typedef struct {
    const char *data;
    size_t len;
    void *mapping;
    size_t mapping_len;
    void *heap;
    /* Filled from the read's own stat when the source path was a regular
     * file: the same size/mtime values stat() would report at read time,
     * captured so the indexer needs no second stat on the emit thread. */
    bool has_stat;
    int64_t stat_size;
    int64_t stat_mtime_sec;
    int64_t stat_mtime_nsec;
} CodeLensMappedFile;

char *code_lens_default_home(void);
char *code_lens_join_path(const char *left, const char *right);
bool code_lens_path_exists(const char *path);
bool code_lens_is_directory(const char *path);
int code_lens_mkdir_p(const char *path);
int code_lens_remove_tree(const char *path);
int code_lens_map_file(const char *path, CodeLensMappedFile *out_file);
void code_lens_mapped_file_free(CodeLensMappedFile *file);
int code_lens_walk_clojure_files(const char *root, CodeLensFileCallback callback, void *ctx);
int code_lens_collect_clojure_files(const char *root, CodeLensPathList *out_files);
int code_lens_walk_source_files(const char *root, CodeLensFileCallback callback, void *ctx);
int code_lens_collect_source_files(const char *root, CodeLensPathList *out_files);

char *code_lens_list_repos(void);
typedef struct {
    int limit;
    bool exclude_tests;
    const char *kind;
    /* Case-insensitive substring of result file paths. */
    const char *path;
    /* workspace (default), dependencies, or all; dependency is an optional
     * Maven GAV glob. */
    const char *scope;
    const char *dependency;
} CodeLensQueryOptions;

char *code_lens_query_symbols_ex(const char *repo_name,
                                  const char *query_text,
                                  const CodeLensQueryOptions *options);
char *code_lens_query_symbols(const char *repo_name, const char *query_text, int limit);
typedef struct {
    bool exclude_tests;
    /* Narrow candidate definitions before collecting references. namespace_name
     * is exact; path is a case-insensitive definition-file substring. */
    const char *namespace_name;
    const char *path;
} CodeLensContextOptions;

char *code_lens_context_symbol_ex(const char *repo_name,
                                   const char *symbol_name,
                                   const CodeLensContextOptions *options);
char *code_lens_context_symbol(const char *repo_name, const char *symbol_name);
char *code_lens_run_sql(const char *repo_name, const char *sql);

int code_lens_mcp_main(void);
int code_lens_cli_main(int argc, char **argv);

#endif
