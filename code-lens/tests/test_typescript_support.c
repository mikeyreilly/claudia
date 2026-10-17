#include "code_lens.h"

#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int ts_assert(bool condition, const char *message)
{
    if (!condition) {
        (void)fprintf(stderr, "TypeScript support test failed: %s\n", message);
        return 1;
    }
    return 0;
}

static const CodeLensSymbol *ts_symbol(const CodeLensTypeScriptFile *file,
                                       const char *name, const char *kind, const char *ns)
{
    for (size_t i = 0U; i < file->symbol_count; i++) {
        const CodeLensSymbol *symbol = &file->symbols[i];
        if ((strcmp(symbol->name, name) == 0) && (strcmp(symbol->kind, kind) == 0) &&
            (strcmp(symbol->namespace_name, ns) == 0)) {
            return symbol;
        }
    }
    return nullptr;
}

static bool ts_reference(const CodeLensTypeScriptFile *file, const char *symbol,
                         const char *base, const char *target)
{
    for (size_t i = 0U; i < file->reference_count; i++) {
        const CodeLensReference *ref = &file->references[i];
        if ((strcmp(ref->symbol, symbol) == 0) && (strcmp(ref->base, base) == 0) &&
            (strcmp(ref->target_namespace, target) == 0)) {
            return true;
        }
    }
    return false;
}

static int test_typescript_parser(void)
{
    static const char source[] =
        "/** Greeter documentation. */\n"
        "export default class Greeter {\n"
        "  value: string;\n"
        "  constructor(value: string) { this.value = value; }\n"
        "  hello(): string { return this.value; }\n"
        "}\n"
        "export interface Named { name: string; }\n"
        "export type ID = string | number;\n"
        "export enum Role { Admin, Guest = 2 }\n"
        "export namespace Tools { export function help() { return 1; } }\n"
        "export const arrow = (value: string): string => value;\n"
        "export function use<T extends Named>(arg: T) {\n"
        "  const local = new Greeter(arg.name); local.hello();\n"
        "}\n";
    CodeLensTypeScriptFile file = {0};
    CodeLensTypeScriptParser *parser = code_lens_typescript_parser_new();
    const CodeLensSymbol *symbol;
    char *module;
    int failed = 0;

    if ((parser == nullptr) ||
        (code_lens_typescript_parser_set_repo_root(parser, "/project") != 0) ||
        (code_lens_parse_typescript_source_with_parser(parser, "/project/src/model.ts",
                                                       source, sizeof(source) - 1U, &file) != 0)) {
        code_lens_typescript_parser_delete(parser);
        return ts_assert(false, "parser creation and source parse");
    }
    failed |= ts_assert(strcmp(file.namespace_name, "src/model.ts") == 0,
                         "module identity keeps relative path and extension");
    symbol = ts_symbol(&file, "Greeter", "class", "src/model.ts");
    failed |= ts_assert((symbol != nullptr) &&
                         (strstr(symbol->doc, "Greeter documentation.") != nullptr) &&
                         (symbol->start_line == 2U) && (symbol->end_line == 6U),
                         "class documentation and source lines");
    failed |= ts_assert(ts_symbol(&file, "default", "class", "src/model.ts") != nullptr,
                         "named default export has an exported-name alias");
    failed |= ts_assert(ts_symbol(&file, "hello", "method", "src/model.ts.Greeter") != nullptr,
                         "default-exported class retains its named member namespace");
    failed |= ts_assert(ts_symbol(&file, "constructor", "constructor", "src/model.ts.Greeter") != nullptr,
                         "constructor extraction");
    failed |= ts_assert(ts_symbol(&file, "Named", "interface", "src/model.ts") != nullptr &&
                         ts_symbol(&file, "ID", "typedef", "src/model.ts") != nullptr &&
                         ts_symbol(&file, "Admin", "enum_constant", "src/model.ts.Role") != nullptr &&
                         ts_symbol(&file, "help", "function", "src/model.ts.Tools") != nullptr &&
                         ts_symbol(&file, "arrow", "function", "src/model.ts") != nullptr,
                         "interface, type alias, enum, namespace and arrow extraction");
    failed |= ts_assert(ts_symbol(&file, "local", "variable", "src/model.ts") == nullptr,
                         "function locals are not module symbols");
    failed |= ts_assert(ts_reference(&file, "local.hello", "hello", "src/model.ts.Greeter") &&
                         ts_reference(&file, "this.value", "value", "src/model.ts.Greeter"),
                         "constructor-inferred and this receivers");
    for (size_t i = 0U; i < file.reference_count; i++) {
        const CodeLensReference *ref = &file.references[i];
        failed |= ts_assert((ref->start_byte <= ref->end_byte) &&
                             (ref->end_byte <= sizeof(source) - 1U) &&
                             (ref->symbol_len == strlen(ref->symbol)) &&
                             (ref->base_len == strlen(ref->base)) &&
                             (ref->target_namespace_len == strlen(ref->target_namespace)),
                             "reference spans and cached lengths are valid");
    }
    failed |= ts_assert(code_lens_parse_typescript_source_with_parser(parser,
                          "/project/types.d.mts", "export declare function ambient(): void;",
                          sizeof("export declare function ambient(): void;") - 1U,
                          &file) == 0 &&
                         ts_symbol(&file, "ambient", "function", "types.d.mts") != nullptr,
                         "parser reuse and ambient declaration files");
    module = code_lens_typescript_module_name("C:\\repo", "C:\\repo\\src\\model.cts");
    failed |= ts_assert((module != nullptr) && (strcmp(module, "src/model.cts") == 0),
                         "Windows paths normalize to POSIX module names");
    module = code_lens_typescript_module_name("/project", "/project-other/model.ts");
    failed |= ts_assert((module != nullptr) && (strcmp(module, "model.ts") == 0),
                         "non-enclosing root uses basename");
    code_lens_typescript_parser_delete(parser);
    return failed;
}

static int ts_write(const char *root, const char *name, const char *source)
{
    char *path = code_lens_join_path(root, name);
    FILE *out = path == nullptr ? nullptr : fopen(path, "w");
    int failed;
    if (out == nullptr) {
        return -1;
    }
    failed = fputs(source, out) == EOF;
    return fclose(out) == 0 && !failed ? 0 : -1;
}

static int test_typescript_index(void)
{
    static const char named[] =
        "export default class Greeter {\n"
        "  hello() { return 1; }\n"
        "  self() { return this.hello(); }\n"
        "}\n"
        "export { Greeter };\n";
    static const char usage[] =
        "import First from './named.js';\n"
        "import { Greeter as Named } from './named';\n"
        "import * as models from './named';\n"
        "import Other from './other';\n"
        "import Anonymous from './anonymous';\n"
        "import Actual from './collision';\n"
        "const collision = new Actual(); collision.hello();\n"
        "const first = new First(); first.hello();\n"
        "function typed(second: First) { second.hello(); }\n"
        "const third = new models.default(); third.hello();\n"
        "const fourth = new Named(); fourth.hello();\n"
        "const elsewhere = new Other(); elsewhere.hello();\n"
        "const anonymous = new Anonymous(); anonymous.hello();\n";
    static const char imports[] =
        "import { esm } from './esm.mjs';\n"
        "import { cjs } from './cjs.cjs';\n"
        "import { ambient } from './decl.js';\n"
        "import { added } from './added';\n"
        "esm; cjs; ambient(); added();\n";
    static const char refs_sql[] =
        "SELECT symbol, symbolBase, targetNamespace FROM Ref "
        "ORDER BY filePath, lineNumber, columnNumber, symbol";
    static const char *const env_names[] = {
        "CODE_LENS_HOME", "CODE_LENS_INCREMENTAL", "CODE_LENS_MAVEN"
    };
    char *saved[3] = {0};
    const char *tmp = getenv("TMPDIR");
    char buffer[PATH_MAX];
    char resolved[PATH_MAX];
    char *root = nullptr;
    char *repo;
    char *home;
    char *tests;
    char *text;
    char *before;
    char *after;
    CodeLensIndexStats stats = {0};
    CodeLensContextOptions options = {.namespace_name = "named.ts.Greeter"};
    int failed = 0;

    for (size_t i = 0U; i < 3U; i++) {
        const char *value = getenv(env_names[i]);
        if (value != nullptr) {
            size_t len = strlen(value) + 1U;
            saved[i] = code_lens_alloc(len);
            if (saved[i] == nullptr) {
                return 1;
            }
            (void)memcpy(saved[i], value, len);
        }
    }
    if ((tmp == nullptr) || (tmp[0] == '\0')) {
        tmp = "/tmp";
    }
    (void)snprintf(buffer, sizeof(buffer), "%s/code-lens-ts-XXXXXX", tmp);
    if ((mkdtemp(buffer) == nullptr) || (realpath(buffer, resolved) == nullptr)) {
        return ts_assert(false, "temporary repository creation");
    }
    root = resolved;
    repo = code_lens_join_path(root, "repo");
    home = code_lens_join_path(root, "home");
    tests = repo == nullptr ? nullptr : code_lens_join_path(repo, "tests");
    if ((home == nullptr) || (tests == nullptr) || (code_lens_mkdir_p(tests) != 0) ||
        (ts_write(repo, "named.ts", named) != 0) ||
        (ts_write(repo, "other.ts", "export default class Other { hello() { return 2; } }\n") != 0) ||
        (ts_write(repo, "anonymous.ts", "export default class { hello() { return 3; } }\n") != 0) ||
        (ts_write(repo, "collision.ts", "class Decoy { hello() { return 0; } } export default class Actual { hello() { return 1; } }\n") != 0) ||
        (ts_write(repo, "usage.ts", usage) != 0) ||
        (ts_write(repo, "imports.ts", imports) != 0) ||
        (ts_write(repo, "esm.mts", "export const esm = 1;\n") != 0) ||
        (ts_write(repo, "cjs.cts", "export const cjs = 1;\n") != 0) ||
        (ts_write(repo, "decl.d.ts", "export declare function ambient(): number;\n") != 0) ||
        (ts_write(repo, "ignored.tsx", "export const ExcludedTSX = <div/>;\n") != 0) ||
        (ts_write(repo, "ignored.js", "export const ExcludedJS = 1;\n") != 0) ||
        (ts_write(tests, "use.ts", "import First from '../named'; const testUser = new First(); testUser.hello();\n") != 0) ||
        (setenv("CODE_LENS_HOME", home, 1) != 0) ||
        (setenv("CODE_LENS_MAVEN", "0", 1) != 0) ||
        (code_lens_index_repository(repo, &stats) != 0)) {
        failed = ts_assert(false, "fixture indexing");
        goto done;
    }
    failed |= ts_assert(stats.file_count == 10U, "TypeScript extensions discovered; JS/TSX excluded");
    text = code_lens_query_symbols(repo, "Greeter", 10);
    failed |= ts_assert((text != nullptr) && (strstr(text, "Greeter|class|named.ts") != nullptr),
                         "class query read-back");
    text = code_lens_context_symbol_ex(repo, "hello", &options);
    failed |= ts_assert((text != nullptr) &&
                         (strstr(text, "hello|method|named.ts.Greeter") != nullptr) &&
                         (strstr(text, "|first.hello|named.ts.default|") != nullptr) &&
                         (strstr(text, "first.hello -> named.ts.default") != nullptr) &&
                         (strstr(text, "second.hello -> named.ts.default") != nullptr) &&
                         (strstr(text, "third.hello -> named.ts.default") != nullptr) &&
                         (strstr(text, "fourth.hello -> named.ts.Greeter") != nullptr) &&
                         (strstr(text, "this.hello -> named.ts.Greeter") != nullptr) &&
                         (strstr(text, "elsewhere.hello ->") == nullptr) &&
                         (strstr(text, "anonymous.hello ->") == nullptr),
                         "default/named imports and local calls link to selected class, not unrelated defaults");
    options.exclude_tests = true;
    text = code_lens_context_symbol_ex(repo, "hello", &options);
    failed |= ts_assert((text != nullptr) && (strstr(text, "first.hello -> named.ts.default") != nullptr) &&
                         (strstr(text, "testUser.hello") == nullptr),
                         "excludeTests also applies to default-import reference rows and snippets");
    options = (CodeLensContextOptions){.path = "named.ts"};
    text = code_lens_context_symbol_ex(repo, "hello", &options);
    failed |= ts_assert((text != nullptr) && (strstr(text, "first.hello -> named.ts.default") != nullptr) &&
                         (strstr(text, "elsewhere.hello ->") == nullptr),
                         "definition path filter preserves default-import references without cross-class leakage");
    options = (CodeLensContextOptions){.namespace_name = "anonymous.ts.default"};
    text = code_lens_context_symbol_ex(repo, "hello", &options);
    failed |= ts_assert((text != nullptr) && (strstr(text, "anonymous.hello -> anonymous.ts.default") != nullptr) &&
                         (strstr(text, "first.hello ->") == nullptr),
                         "anonymous default class members still resolve");
    options = (CodeLensContextOptions){.namespace_name = "collision.ts.Decoy"};
    text = code_lens_context_symbol_ex(repo, "hello", &options);
    failed |= ts_assert((text != nullptr) && (strstr(text, "hello|method|collision.ts.Decoy") != nullptr) &&
                         (strstr(text, "collision.hello ->") == nullptr),
                         "same-line declarations are not mistaken for default-export aliases");
    text = code_lens_run_sql(repo, "SELECT symbolBase, targetNamespace FROM Ref WHERE symbolBase IN "
                                  "('esm', 'cjs', 'ambient', 'added') ORDER BY symbolBase");
    failed |= ts_assert((text != nullptr) && (strstr(text, "esm|esm.mts") != nullptr) &&
                         (strstr(text, "cjs|cjs.cts") != nullptr) &&
                         (strstr(text, "ambient|decl.d.ts") != nullptr),
                         "JS import spellings resolve to TS and declaration sources");
    text = code_lens_run_sql(repo, "SELECT namespace, symbol, alias FROM Referred "
                                  "WHERE filePath LIKE '%usage.ts' ORDER BY alias");
    failed |= ts_assert((text != nullptr) && (strstr(text, "named.ts|default|First") != nullptr) &&
                         (strstr(text, "named.ts|Greeter|Named") != nullptr) &&
                         (strstr(text, "other.ts|default|Other") != nullptr),
                         "Referred rows record default and renamed named-import local aliases");
    if ((setenv("CODE_LENS_INCREMENTAL", "force", 1) != 0) ||
        (ts_write(repo, "usage.ts", "import First from './named'; const edited = new First(); edited.hello();\n") != 0) ||
        (code_lens_index_repository(repo, &stats) != 0)) {
        failed |= ts_assert(false, "incremental refresh");
        goto done;
    }
    options = (CodeLensContextOptions){.namespace_name = "named.ts.Greeter"};
    text = code_lens_context_symbol_ex(repo, "hello", &options);
    failed |= ts_assert((text != nullptr) && (strstr(text, "edited.hello -> named.ts.default") != nullptr) &&
                         (strstr(text, "first.hello ->") == nullptr),
                         "default member alias matching survives incremental refresh");
    before = code_lens_run_sql(repo, refs_sql);
    if ((setenv("CODE_LENS_INCREMENTAL", "0", 1) != 0) ||
        (code_lens_index_repository(repo, &stats) != 0)) {
        failed |= ts_assert(false, "forced full rebuild");
        goto done;
    }
    after = code_lens_run_sql(repo, refs_sql);
    failed |= ts_assert((before != nullptr) && (after != nullptr) && (strcmp(before, after) == 0),
                         "incremental and full reference read-back agree");
    if ((setenv("CODE_LENS_INCREMENTAL", "force", 1) != 0) ||
        (ts_write(repo, "added.ts", "export function added() {}\n") != 0) ||
        (code_lens_index_repository(repo, &stats) != 0)) {
        failed |= ts_assert(false, "adding missing import target");
        goto done;
    }
    text = code_lens_run_sql(repo, "SELECT targetNamespace FROM Ref WHERE symbolBase = 'added'");
    failed |= ts_assert((text != nullptr) && (strstr(text, "added.ts") != nullptr),
                         "new target re-resolves unchanged importers");
    /* The importer keeps module.default as its receiver. Renaming just the
     * exported class must not require re-parsing that unchanged importer. */
    if ((ts_write(repo, "named.ts", "export default class Renamed { hello() { return 4; } }\n") != 0) ||
        (code_lens_index_repository(repo, &stats) != 0)) {
        failed |= ts_assert(false, "renaming only the default-exported declaration");
        goto done;
    }
    options = (CodeLensContextOptions){.namespace_name = "named.ts.Renamed"};
    text = code_lens_context_symbol_ex(repo, "hello", &options);
    failed |= ts_assert((text != nullptr) &&
                         (strstr(text, "hello|method|named.ts.Renamed") != nullptr) &&
                         (strstr(text, "edited.hello -> named.ts.default") != nullptr),
                         "exporter-only rename preserves default-import member references");

done:
    for (size_t i = 0U; i < 3U; i++) {
        if (saved[i] != nullptr) {
            (void)setenv(env_names[i], saved[i], 1);
        } else {
            (void)unsetenv(env_names[i]);
        }
    }
    if ((root != nullptr) && (code_lens_remove_tree(root) != 0)) {
        (void)fprintf(stderr, "TypeScript support test warning: failed to remove %s\n", root);
    }
    return failed;
}

int test_typescript_support(void)
{
    int failed = test_typescript_parser();
    failed |= test_typescript_index();
    if (failed == 0) {
        (void)fprintf(stderr, "TypeScript parser and indexing tests passed\n");
    }
    return failed == 0 ? 0 : 1;
}
