#include "code_lens.h"

#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int java_assert(bool condition, const char *message)
{
    if (!condition) {
        (void)fprintf(stderr, "Java support test failed: %s\n", message);
        return 1;
    }
    return 0;
}

static const CodeLensSymbol *java_find_symbol(const CodeLensJavaFile *file,
                                               const char *name,
                                               const char *kind)
{
    for (size_t i = 0U; i < file->symbol_count; i++) {
        const CodeLensSymbol *symbol = &file->symbols[i];

        if ((strcmp(symbol->name, name) == 0) &&
            ((kind == nullptr) || (strcmp(symbol->kind, kind) == 0))) {
            return symbol;
        }
    }
    return nullptr;
}

static const CodeLensReference *java_find_reference(const CodeLensJavaFile *file,
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

static int test_java_parser(void)
{
    static const char source[] =
        "package demo.app;\n"
        "import java.util.List;\n"
        "import demo.lib.Service;\n"
        "import static demo.util.Helpers.make;\n"
        "import static demo.util.Constants.*;\n"
        "/** Main application. */\n"
        "public class App extends BaseApp {\n"
        "  private final Service service;\n"
        "  private int attempts;\n"
        "  public App(Service service) { this.service = service; }\n"
        "  /** Executes one request. */\n"
        "  @Test public List<String> run(int value) {\n"
        "    attempts++;\n"
        "    Service.create();\n"
        "    make();\n"
        "    this.helper();\n"
        "    return List.of(service.execute(value));\n"
        "  }\n"
        "  void helper() {}\n"
        "  enum Mode { FAST, SAFE }\n"
        "  record Result(String value) {}\n"
        "}\n";
    CodeLensJavaFile file;
    const CodeLensSymbol *app;
    const CodeLensSymbol *run;
    const CodeLensReference *execute;
    int failed = 0;

    if (code_lens_parse_java_source("App.java", source, sizeof(source) - 1U, &file) != 0) {
        (void)fprintf(stderr, "Java support test failed: parser returned an error\n");
        return 1;
    }

    failed |= java_assert(strcmp(file.namespace_name, "demo.app") == 0,
                          "package extracted");
    failed |= java_assert(file.alias_count == 2U, "normal imports extracted");
    if (file.alias_count == 2U) {
        failed |= java_assert(strcmp(file.aliases[0].namespace_name, "java.util.List") == 0 &&
                                  strcmp(file.aliases[0].alias, "List") == 0,
                              "java.util.List import mapping");
        failed |= java_assert(strcmp(file.aliases[1].namespace_name, "demo.lib.Service") == 0 &&
                                  strcmp(file.aliases[1].alias, "Service") == 0,
                              "Service import mapping");
    }
    failed |= java_assert(file.referred_count == 2U, "static imports extracted");
    if (file.referred_count == 2U) {
        failed |= java_assert(strcmp(file.referred[0].namespace_name,
                                     "demo.util.Helpers") == 0 &&
                                  strcmp(file.referred[0].symbol, "make") == 0,
                              "single static import mapping");
        failed |= java_assert(strcmp(file.referred[1].namespace_name,
                                     "demo.util.Constants") == 0 &&
                                  strcmp(file.referred[1].symbol, ":all") == 0,
                              "wildcard static import mapping");
    }

    app = java_find_symbol(&file, "App", "class");
    run = java_find_symbol(&file, "run", "test");
    failed |= java_assert(app != nullptr, "class definition extracted");
    failed |= java_assert(java_find_symbol(&file, "App", "constructor") != nullptr,
                          "constructor definition extracted");
    failed |= java_assert(java_find_symbol(&file, "service", "field") != nullptr,
                          "field definition extracted");
    failed |= java_assert(run != nullptr, "JUnit method classified as test");
    failed |= java_assert(java_find_symbol(&file, "helper", "method") != nullptr,
                          "method definition extracted");
    failed |= java_assert(java_find_symbol(&file, "Mode", "enum") != nullptr,
                          "nested enum definition extracted");
    failed |= java_assert(java_find_symbol(&file, "FAST", "enum_constant") != nullptr,
                          "enum constant extracted");
    failed |= java_assert(java_find_symbol(&file, "Result", "record") != nullptr,
                          "nested record definition extracted");
    failed |= java_assert(java_find_symbol(&file, "value", "field") != nullptr,
                          "record component extracted as a field");
    if (app != nullptr) {
        failed |= java_assert(strcmp(app->namespace_name, "demo.app.App") == 0,
                              "class fully-qualified namespace");
        failed |= java_assert((app->doc != nullptr) &&
                                  (strcmp(app->doc, "Main application.") == 0),
                              "class Javadoc extracted");
    }
    if (run != nullptr) {
        failed |= java_assert(strcmp(run->namespace_name, "demo.app.App") == 0,
                              "member namespace is enclosing type");
        failed |= java_assert((run->doc != nullptr) &&
                                  (strcmp(run->doc, "Executes one request.") == 0),
                              "method Javadoc extracted");
    }

    failed |= java_assert(java_find_reference(&file, "create", "demo.lib.Service") != nullptr,
                          "static call resolves imported type");
    failed |= java_assert(java_find_reference(&file, "make", "demo.util.Helpers") != nullptr,
                          "unqualified call resolves static import");
    failed |= java_assert(java_find_reference(&file, "helper", "demo.app.App") != nullptr,
                          "this call resolves enclosing type");
    failed |= java_assert(java_find_reference(&file, "attempts", "demo.app.App") != nullptr,
                          "unqualified field use resolves enclosing type");
    execute = java_find_reference(&file, "execute", "demo.lib.Service");
    failed |= java_assert(execute != nullptr, "receiver call resolves declared field type");
    failed |= java_assert(java_find_reference(&file, "List", "java.util.List") != nullptr,
                          "type reference resolves normal import");
    if (execute != nullptr) {
        failed |= java_assert(strcmp(execute->symbol, "service.execute") == 0,
                              "qualified call text retained");
        failed |= java_assert(execute->start_byte < execute->end_byte,
                              "reference byte span retained");
        failed |= java_assert(strncmp(source + execute->start_byte,
                                      "execute",
                                      strlen("execute")) == 0,
                              "reference byte span points to member name");
    }

    return failed == 0 ? 0 : 1;
}

static int java_write_file(const char *directory, const char *name, const char *content)
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

static int test_java_index(void)
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
    char *app_dir;
    char *lib_dir;
    char *util_dir;
    CodeLensIndexStats stats = {0};
    CodeLensPathList source_files = {0};
    CodeLensPathList clojure_files = {0};
    CodeLensQueryOptions class_options = {.limit = 5, .kind = "class"};
    char *query;
    char *context;
    char *static_context;
    char *rows;
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
                   "%s/code-lens-java-XXXXXX",
                   tmp);
    root = mkdtemp(template_buffer);
    if ((root == nullptr) || (realpath(root, resolved) == nullptr)) {
        (void)fprintf(stderr, "Java support test failed: temporary directory setup\n");
        return 1;
    }
    root = resolved;
    repo = code_lens_join_path(root, "repo");
    home = code_lens_join_path(root, "home");
    app_dir = repo == nullptr ? nullptr : code_lens_join_path(repo, "src/demo/app");
    lib_dir = repo == nullptr ? nullptr : code_lens_join_path(repo, "src/demo/lib");
    util_dir = repo == nullptr ? nullptr : code_lens_join_path(repo, "src/demo/util");
    if ((repo == nullptr) || (home == nullptr) || (app_dir == nullptr) ||
        (lib_dir == nullptr) || (util_dir == nullptr) ||
        (code_lens_mkdir_p(app_dir) != 0) || (code_lens_mkdir_p(lib_dir) != 0) ||
        (code_lens_mkdir_p(util_dir) != 0) || (code_lens_mkdir_p(home) != 0)) {
        (void)fprintf(stderr, "Java support test failed: fixture directories\n");
        failed = 1;
        goto done;
    }

    if ((java_write_file(lib_dir,
                         "Service.java",
                         "package demo.lib;\n"
                         "/** A service. */\n"
                         "public class Service {\n"
                         "  public static Service create() { return new Service(); }\n"
                         "  /** Executes work. */\n"
                         "  public String execute(int value) { return String.valueOf(value); }\n"
                         "}\n") != 0) ||
        (java_write_file(util_dir,
                         "Helpers.java",
                         "package demo.util;\n"
                         "public final class Helpers { public static void make() {} }\n") != 0) ||
        (java_write_file(app_dir,
                         "App.java",
                         "package demo.app;\n"
                         "import demo.lib.Service;\n"
                         "import static demo.util.Helpers.*;\n"
                         "public class App {\n"
                         "  private final Service service;\n"
                         "  public App(Service service) { this.service = service; }\n"
                         "  public String run(int value) {\n"
                         "    Service.create();\n"
                         "    make();\n"
                         "    return service.execute(value);\n"
                         "  }\n"
                         "}\n") != 0) ||
        (setenv("CODE_LENS_HOME", home, 1) != 0)) {
        (void)fprintf(stderr, "Java support test failed: fixture files\n");
        failed = 1;
        goto done;
    }

    failed |= java_assert(code_lens_collect_source_files(repo, &source_files) == 0 &&
                              source_files.count == 3U,
                          "source walker includes Java files");
    failed |= java_assert(code_lens_collect_clojure_files(repo, &clojure_files) == 0 &&
                              clojure_files.count == 0U,
                          "legacy Clojure walker remains language-specific");
    if (code_lens_index_repository(repo, &stats) != 0) {
        (void)fprintf(stderr, "Java support test failed: repository indexing\n");
        failed = 1;
        goto done;
    }
    failed |= java_assert(stats.file_count == 3U, "Java files counted by indexer");
    failed |= java_assert(stats.symbol_count >= 9U, "Java definitions indexed");
    failed |= java_assert(stats.reference_count > 0U, "Java references indexed");
    failed |= java_assert(stats.alias_count == 1U, "Java normal imports counted");

    query = code_lens_query_symbols_ex(repo, "Service", &class_options);
    failed |= java_assert((query != nullptr) && (strstr(query, "Service|class|") != nullptr) &&
                              (strstr(query, "demo.lib.Service") != nullptr),
                          "kind-filtered query returns Java class");

    /* The dotted spelling exercises the Java qualifier fallback in context;
     * the call site itself resolves through the declared field's imported type. */
    context = code_lens_context_symbol(repo, "Service.execute");
    failed |= java_assert((context != nullptr) &&
                              (strstr(context, "execute|method|demo.lib.Service") != nullptr) &&
                              (strstr(context, "service.execute|demo.lib.Service") != nullptr) &&
                              (strstr(context, "Executes work.") != nullptr),
                          "context links Java method definition and call site");

    static_context = code_lens_context_symbol(repo, "make");
    failed |= java_assert((static_context != nullptr) &&
                              (strstr(static_context, "make|method|demo.util.Helpers") != nullptr) &&
                              (strstr(static_context, "|make|") != nullptr),
                          "wildcard static import links an unqualified Java call");

    rows = code_lens_run_sql(
        repo,
        "SELECT COUNT(*) FROM Symbol WHERE kind = 'class' AND namespace = 'demo.lib.Service'");
    failed |= java_assert((rows != nullptr) && (strstr(rows, "\n1\n") != nullptr),
                          "SQL surface exposes Java symbols");

    if (java_write_file(app_dir,
                        "Extra.java",
                        "package demo.app;\npublic class Extra { public void added() {} }\n") != 0) {
        failed = 1;
        goto done;
    }
    stale = code_lens_query_symbols(repo, "Extra", 5);
    failed |= java_assert((stale != nullptr) &&
                              (strstr(stale, "index is stale") != nullptr) &&
                              (strstr(stale, "1 new") != nullptr),
                          "staleness check detects a newly added Java file");

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
    failed |= java_assert(stats.file_count == 4U,
                          "incremental refresh adds a Java file");
    fresh = code_lens_query_symbols(repo, "Extra", 5);
    failed |= java_assert((fresh != nullptr) && (strstr(fresh, "Extra|class|") != nullptr),
                          "new Java class is queryable after refresh");

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
        (void)fprintf(stderr, "Java support test warning: failed to remove %s\n", root);
    }
    return failed == 0 ? 0 : 1;
}

int test_java_support(void)
{
    int failed = 0;

    failed |= test_java_parser();
    failed |= test_java_index();
    if (failed == 0) {
        (void)fprintf(stderr, "Java parser and indexing tests passed\n");
    }
    return failed == 0 ? 0 : 1;
}
