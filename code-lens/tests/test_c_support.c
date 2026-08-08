#include "code_lens.h"

#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int c_assert(bool condition, const char *message)
{
    if (!condition) {
        (void)fprintf(stderr, "C support test failed: %s\n", message);
        return 1;
    }
    return 0;
}

static const CodeLensSymbol *c_find_symbol(const CodeLensCFile *file,
                                            const char *name,
                                            const char *kind,
                                            const char *namespace_name)
{
    for (size_t i = 0U; i < file->symbol_count; i++) {
        const CodeLensSymbol *symbol = &file->symbols[i];

        if ((strcmp(symbol->name, name) == 0) &&
            ((kind == nullptr) || (strcmp(symbol->kind, kind) == 0)) &&
            ((namespace_name == nullptr) ||
             (strcmp(symbol->namespace_name, namespace_name) == 0))) {
            return symbol;
        }
    }
    return nullptr;
}

static const CodeLensReference *c_find_reference(const CodeLensCFile *file,
                                                  const char *base,
                                                  const char *target)
{
    for (size_t i = 0U; i < file->reference_count; i++) {
        const CodeLensReference *reference = &file->references[i];

        if ((reference->base_len == strlen(base)) &&
            (memcmp(reference->base, base, reference->base_len) == 0) &&
            (strcmp(reference->target_namespace, target) == 0)) {
            return reference;
        }
    }
    return nullptr;
}

static bool c_has_reference_text(const CodeLensCFile *file, const char *text)
{
    for (size_t i = 0U; i < file->reference_count; i++) {
        if (strcmp(file->references[i].symbol, text) == 0) {
            return true;
        }
    }
    return false;
}

static int test_c_parser(void)
{
    static const char source[] =
        "#include <stddef.h>\n"
        "#define MAX_SIZE 16\n"
        "#define APPLY(x) helper(x)\n"
        "typedef struct Point { int x, y; } Point;\n"
        "typedef union Value { int integer; void *pointer; } Value;\n"
        "typedef struct RawPoint { int z; } PointAlias;\n"
        "struct Forward;\n"
        "enum Mode { MODE_FAST, MODE_SAFE = 2 };\n"
        "typedef int (*Transform)(int);\n"
        "static int (*callback)(int);\n"
        "static int helper(int value);\n"
        "extern int external_value;\n"
        "static int counter = 0;\n"
        "/** Normalize one value. */\n"
        "static int helper(int value) {\n"
        "  Point point = {.x = value, .y = counter};\n"
        "  counter++;\n"
        "  return point.x;\n"
        "}\n"
        "/// Run one point.\n"
        "int run(Point *point) {\n"
        "  return helper(point->y) + external_call(point->x);\n"
        "}\n"
        "int alias_run(PointAlias *point) { return point->z; }\n";
    CodeLensCFile file;
    const CodeLensSymbol *helper;
    const CodeLensReference *field_ref;
    int failed = 0;

    if (code_lens_parse_c_source("fixture.c", source, sizeof(source) - 1U, &file) != 0) {
        (void)fprintf(stderr, "C support test failed: parser returned an error\n");
        return 1;
    }

    failed |= c_assert(strcmp(file.namespace_name, "") == 0,
                       "C file namespace is global");
    failed |= c_assert(file.alias_count == 0U && file.referred_count == 0U,
                       "C parse does not invent import mappings");
    failed |= c_assert(c_find_symbol(&file, "MAX_SIZE", "macro", "") != nullptr,
                       "object-like macro extracted");
    failed |= c_assert(c_find_symbol(&file, "APPLY", "macro", "") != nullptr,
                       "function-like macro extracted");
    failed |= c_assert(c_find_symbol(&file, "Point", "struct", "") != nullptr,
                       "struct definition extracted");
    failed |= c_assert(c_find_symbol(&file, "Point", "typedef", "") != nullptr,
                       "typedef definition extracted");
    failed |= c_assert(c_find_symbol(&file, "Value", "union", "") != nullptr,
                       "union definition extracted");
    failed |= c_assert(c_find_symbol(&file, "Mode", "enum", "") != nullptr,
                       "enum definition extracted");
    failed |= c_assert(c_find_symbol(&file, "Forward", "struct", "") != nullptr,
                       "forward struct declaration extracted");
    failed |= c_assert(c_find_symbol(&file, "MODE_FAST", "enum_constant", "") != nullptr,
                       "enumerator extracted");
    failed |= c_assert(c_find_symbol(&file, "x", "field", "Point") != nullptr &&
                           c_find_symbol(&file, "y", "field", "Point") != nullptr,
                       "struct fields use aggregate namespace");
    failed |= c_assert(c_find_symbol(&file, "integer", "field", "Value") != nullptr,
                       "union field extracted");
    failed |= c_assert(c_find_symbol(&file, "PointAlias", "typedef", "") != nullptr &&
                           c_find_symbol(&file, "z", "field", "RawPoint") != nullptr &&
                           c_find_symbol(&file, "z", "field", "PointAlias") != nullptr,
                       "fields are indexed under canonical tags and typedef aliases");
    failed |= c_assert(c_find_symbol(&file, "Transform", "typedef", "") != nullptr,
                       "function-pointer typedef extracted");
    failed |= c_assert(c_find_symbol(&file, "callback", "variable", "fixture.c") != nullptr,
                       "function pointer classified as static variable");
    failed |= c_assert(c_find_symbol(&file, "external_value", "variable", "") != nullptr,
                       "extern variable extracted");
    failed |= c_assert(c_find_symbol(&file, "counter", "variable", "fixture.c") != nullptr,
                       "static variable gets file namespace");
    {
        const CodeLensSymbol *run = c_find_symbol(&file, "run", "function", "");

        failed |= c_assert(run != nullptr, "external function definition extracted");
        failed |= c_assert((run != nullptr) && (run->doc != nullptr) &&
                               (strcmp(run->doc, "Run one point.") == 0),
                           "triple-slash C documentation extracted");
    }

    helper = nullptr;
    for (size_t i = 0U; i < file.symbol_count; i++) {
        const CodeLensSymbol *candidate = &file.symbols[i];

        if ((strcmp(candidate->name, "helper") == 0) &&
            (strcmp(candidate->kind, "function") == 0) &&
            (strcmp(candidate->namespace_name, "fixture.c") == 0) &&
            (candidate->doc != nullptr)) {
            helper = candidate;
            break;
        }
    }
    failed |= c_assert(helper != nullptr, "static function gets file namespace and docs");
    if (helper != nullptr) {
        failed |= c_assert(strcmp(helper->doc, "Normalize one value.") == 0,
                           "C documentation comment extracted");
    }

    failed |= c_assert(c_find_reference(&file, "helper", "fixture.c") != nullptr,
                       "static function call resolves to same file");
    failed |= c_assert(c_find_reference(&file, "counter", "fixture.c") != nullptr,
                       "static variable reference resolves to same file");
    failed |= c_assert(c_find_reference(&file, "external_call", "") != nullptr,
                       "unknown external call remains global");
    field_ref = c_find_reference(&file, "y", "Point");
    failed |= c_assert(field_ref != nullptr, "pointer member access resolves aggregate type");
    failed |= c_assert(c_find_reference(&file, "x", "Point") != nullptr,
                       "direct member access resolves aggregate type");
    failed |= c_assert(c_find_reference(&file, "z", "RawPoint") != nullptr,
                       "typedef alias resolves member to canonical struct tag");
    failed |= c_assert(!c_has_reference_text(&file, "value"),
                       "parameter identifiers are not global references");
    if (field_ref != nullptr) {
        failed |= c_assert(strcmp(field_ref->symbol, "point->y") == 0,
                           "member expression retained as reference text");
        failed |= c_assert(strncmp(source + field_ref->start_byte,
                                   "y",
                                   strlen("y")) == 0,
                           "member byte span points to field name");
    }

    return failed == 0 ? 0 : 1;
}

static int c_write_file(const char *directory, const char *name, const char *content)
{
    char *path = code_lens_join_path(directory, name);
    FILE *file;

    if (path == nullptr) {
        return -1;
    }
    file = fopen(path, "w");
    if (file == nullptr) {
        return -1;
    }
    if (fputs(content, file) == EOF) {
        (void)fclose(file);
        return -1;
    }
    return fclose(file) == 0 ? 0 : -1;
}

static int test_c_index(void)
{
    char template_buffer[PATH_MAX];
    char resolved[PATH_MAX];
    const char *tmp = getenv("TMPDIR");
    const char *old_home = getenv("CODE_LENS_HOME");
    const char *old_incremental = getenv("CODE_LENS_INCREMENTAL");
    char saved_home[PATH_MAX] = {0};
    char saved_incremental[PATH_MAX] = {0};
    bool had_home = old_home != nullptr;
    bool had_incremental = old_incremental != nullptr;
    char *root;
    char *repo;
    char *home;
    char *include_dir;
    char *src_dir;
    char *vendor_dir;
    CodeLensIndexStats stats = {0};
    CodeLensPathList source_files = {0};
    CodeLensPathList clojure_files = {0};
    CodeLensQueryOptions struct_options = {.limit = 5, .kind = "struct"};
    char *query;
    char *context;
    char *static_context;
    char *field_context;
    char *stale;
    char *fresh;
    int failed = 0;

    if (had_home) {
        (void)snprintf(saved_home, sizeof(saved_home), "%s", old_home);
    }
    if (had_incremental) {
        (void)snprintf(saved_incremental, sizeof(saved_incremental), "%s", old_incremental);
    }
    if ((tmp == nullptr) || (tmp[0] == '\0')) {
        tmp = "/tmp";
    }
    (void)snprintf(template_buffer,
                   sizeof(template_buffer),
                   "%s/code-lens-c-XXXXXX",
                   tmp);
    root = mkdtemp(template_buffer);
    if ((root == nullptr) || (realpath(root, resolved) == nullptr)) {
        (void)fprintf(stderr, "C support test failed: temporary directory setup\n");
        return 1;
    }
    root = resolved;
    repo = code_lens_join_path(root, "repo");
    home = code_lens_join_path(root, "home");
    include_dir = repo == nullptr ? nullptr : code_lens_join_path(repo, "include");
    src_dir = repo == nullptr ? nullptr : code_lens_join_path(repo, "src");
    vendor_dir = repo == nullptr ? nullptr : code_lens_join_path(repo, "vendor");
    if ((repo == nullptr) || (home == nullptr) || (include_dir == nullptr) ||
        (src_dir == nullptr) || (vendor_dir == nullptr) ||
        (code_lens_mkdir_p(include_dir) != 0) || (code_lens_mkdir_p(src_dir) != 0) ||
        (code_lens_mkdir_p(vendor_dir) != 0) || (code_lens_mkdir_p(home) != 0)) {
        failed = 1;
        goto done;
    }

    if ((c_write_file(include_dir,
                      "widget.h",
                      "#ifndef WIDGET_H\n"
                      "#define WIDGET_H\n"
                      "#define WIDGET_LIMIT 32\n"
                      "/** A widget value. */\n"
                      "typedef struct WidgetImpl { int value; } Widget;\n"
                      "/** Read a widget. */\n"
                      "int widget_get(const Widget *widget);\n"
                      "#endif\n") != 0) ||
        (c_write_file(src_dir,
                      "widget.c",
                      "#include \"../include/widget.h\"\n"
                      "static int normalize(int value) { return value < 0 ? 0 : value; }\n"
                      "int widget_get(const Widget *widget) { return normalize(widget->value); }\n") != 0) ||
        (c_write_file(src_dir,
                      "app.c",
                      "#include \"../include/widget.h\"\n"
                      "int run_app(Widget *widget) { return widget_get(widget); }\n") != 0) ||
        (c_write_file(vendor_dir,
                      "ignored.c",
                      "int vendored_symbol(void) { return 0; }\n") != 0) ||
        (setenv("CODE_LENS_HOME", home, 1) != 0)) {
        failed = 1;
        goto done;
    }

    failed |= c_assert(code_lens_collect_source_files(repo, &source_files) == 0 &&
                           source_files.count == 3U,
                       "source walker includes C sources and headers");
    failed |= c_assert(code_lens_collect_clojure_files(repo, &clojure_files) == 0 &&
                           clojure_files.count == 0U,
                       "C files do not leak into Clojure compatibility walker");
    if (code_lens_index_repository(repo, &stats) != 0) {
        failed = 1;
        goto done;
    }
    failed |= c_assert(stats.file_count == 3U, "C files counted by indexer");
    failed |= c_assert(stats.symbol_count >= 9U, "C symbols indexed");
    failed |= c_assert(stats.reference_count > 0U, "C references indexed");

    query = code_lens_query_symbols_ex(repo, "Widget", &struct_options);
    failed |= c_assert((query != nullptr) &&
                           (strstr(query, "WidgetImpl|struct|") != nullptr),
                       "struct kind filter returns C tag");

    context = code_lens_context_symbol(repo, "widget_get");
    failed |= c_assert((context != nullptr) &&
                           (strstr(context, "widget_get|function|") != nullptr) &&
                           (strstr(context, "src/app.c") != nullptr),
                       "context links external C declaration, definition, and call");

    static_context = code_lens_context_symbol(repo, "normalize");
    failed |= c_assert((static_context != nullptr) &&
                           (strstr(static_context, "normalize|function|") != nullptr) &&
                           (strstr(static_context, "normalize -> ") != nullptr) &&
                           (strstr(static_context, "src/widget.c") != nullptr),
                       "context links file-local static C call");

    field_context = code_lens_context_symbol(repo, "widget->value");
    failed |= c_assert((field_context != nullptr) &&
                           (strstr(field_context, "value|field|Widget") != nullptr) &&
                           (strstr(field_context, "widget->value -> Widget") != nullptr),
                       "context links C aggregate member access");

    if (c_write_file(src_dir,
                     "extra.c",
                     "int c_extra(void) { return 1; }\n") != 0) {
        failed = 1;
        goto done;
    }
    stale = code_lens_query_symbols(repo, "c_extra", 5);
    failed |= c_assert((stale != nullptr) && (strstr(stale, "index is stale") != nullptr) &&
                           (strstr(stale, "1 new") != nullptr),
                       "staleness check detects new C source");

    if (setenv("CODE_LENS_INCREMENTAL", "force", 1) != 0) {
        failed = 1;
        goto done;
    }
    if (code_lens_index_repository(repo, &stats) != 0) {
        failed = 1;
    }
    if (had_incremental) {
        (void)setenv("CODE_LENS_INCREMENTAL", saved_incremental, 1);
    } else {
        (void)unsetenv("CODE_LENS_INCREMENTAL");
    }
    failed |= c_assert(stats.file_count == 4U, "incremental refresh adds C source");
    fresh = code_lens_query_symbols(repo, "c_extra", 5);
    failed |= c_assert((fresh != nullptr) && (strstr(fresh, "c_extra|function|") != nullptr),
                       "new C function queryable after refresh");

done:
    if (had_incremental) {
        (void)setenv("CODE_LENS_INCREMENTAL", saved_incremental, 1);
    } else {
        (void)unsetenv("CODE_LENS_INCREMENTAL");
    }
    if (had_home) {
        (void)setenv("CODE_LENS_HOME", saved_home, 1);
    } else {
        (void)unsetenv("CODE_LENS_HOME");
    }
    if ((root != nullptr) && (code_lens_remove_tree(root) != 0)) {
        (void)fprintf(stderr, "C support test warning: failed to remove %s\n", root);
    }
    return failed == 0 ? 0 : 1;
}

int test_c_support(void)
{
    int failed = 0;

    failed |= test_c_parser();
    failed |= test_c_index();
    if (failed == 0) {
        (void)fprintf(stderr, "C parser and indexing tests passed\n");
    }
    return failed == 0 ? 0 : 1;
}
