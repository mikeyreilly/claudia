#include "code_lens.h"

#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
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

static void java_zip_write_u16(FILE *file, uint16_t value)
{
    unsigned char bytes[2] = {(unsigned char)(value & 0xffU),
                              (unsigned char)((value >> 8U) & 0xffU)};
    (void)fwrite(bytes, 1U, sizeof(bytes), file);
}

static void java_zip_write_u32(FILE *file, uint32_t value)
{
    unsigned char bytes[4] = {(unsigned char)(value & 0xffU),
                              (unsigned char)((value >> 8U) & 0xffU),
                              (unsigned char)((value >> 16U) & 0xffU),
                              (unsigned char)((value >> 24U) & 0xffU)};
    (void)fwrite(bytes, 1U, sizeof(bytes), file);
}

static uint32_t java_zip_crc32(const char *data, size_t len)
{
    uint32_t crc = UINT32_MAX;

    for (size_t i = 0U; i < len; i++) {
        crc ^= (uint32_t)(unsigned char)data[i];
        for (unsigned int bit = 0U; bit < 8U; bit++) {
            crc = (crc >> 1U) ^ ((crc & 1U) != 0U ? 0xedb88320U : 0U);
        }
    }
    return ~crc;
}

static int java_write_source_jar(const char *path)
{
    /* Dependency archives are not workspace trees: package paths that happen
     * to use an ignored workspace directory name must still be indexed. */
    static const char entry_name[] = "vendor/org/example/lib/ExternalService.java";
    static const char source[] =
        "package org.example.lib;\n"
        "/** Dependency service. */\n"
        "public class ExternalService {\n"
        "  /** Executes dependency work. */\n"
        "  public String execute(int value) { return String.valueOf(value); }\n"
        "}\n";
    FILE *file = fopen(path, "wb");
    uint32_t crc = java_zip_crc32(source, sizeof(source) - 1U);
    long central_offset;
    long end_offset;

    if (file == nullptr) {
        return -1;
    }
    java_zip_write_u32(file, 0x04034b50U);
    java_zip_write_u16(file, 20U);
    java_zip_write_u16(file, 0U);
    java_zip_write_u16(file, 0U); /* stored */
    java_zip_write_u16(file, 0U);
    java_zip_write_u16(file, 0U);
    java_zip_write_u32(file, crc);
    java_zip_write_u32(file, (uint32_t)(sizeof(source) - 1U));
    java_zip_write_u32(file, (uint32_t)(sizeof(source) - 1U));
    java_zip_write_u16(file, (uint16_t)(sizeof(entry_name) - 1U));
    java_zip_write_u16(file, 0U);
    (void)fwrite(entry_name, 1U, sizeof(entry_name) - 1U, file);
    (void)fwrite(source, 1U, sizeof(source) - 1U, file);

    central_offset = ftell(file);
    if (central_offset < 0L) {
        (void)fclose(file);
        return -1;
    }
    java_zip_write_u32(file, 0x02014b50U);
    java_zip_write_u16(file, 20U);
    java_zip_write_u16(file, 20U);
    java_zip_write_u16(file, 0U);
    java_zip_write_u16(file, 0U);
    java_zip_write_u16(file, 0U);
    java_zip_write_u16(file, 0U);
    java_zip_write_u32(file, crc);
    java_zip_write_u32(file, (uint32_t)(sizeof(source) - 1U));
    java_zip_write_u32(file, (uint32_t)(sizeof(source) - 1U));
    java_zip_write_u16(file, (uint16_t)(sizeof(entry_name) - 1U));
    java_zip_write_u16(file, 0U);
    java_zip_write_u16(file, 0U);
    java_zip_write_u16(file, 0U);
    java_zip_write_u16(file, 0U);
    java_zip_write_u32(file, 0U);
    java_zip_write_u32(file, 0U);
    (void)fwrite(entry_name, 1U, sizeof(entry_name) - 1U, file);

    end_offset = ftell(file);
    if (end_offset < central_offset) {
        (void)fclose(file);
        return -1;
    }
    java_zip_write_u32(file, 0x06054b50U);
    java_zip_write_u16(file, 0U);
    java_zip_write_u16(file, 0U);
    java_zip_write_u16(file, 1U);
    java_zip_write_u16(file, 1U);
    java_zip_write_u32(file, (uint32_t)(end_offset - central_offset));
    java_zip_write_u32(file, (uint32_t)central_offset);
    java_zip_write_u16(file, 0U);
    {
        int io_error = ferror(file);
        int close_error = fclose(file);

        return (io_error == 0) && (close_error == 0) ? 0 : -1;
    }
}

static int java_count_lines(const char *path)
{
    FILE *file = fopen(path, "rb");
    int count = 0;
    int ch;

    if (file == nullptr) {
        return -1;
    }
    while ((ch = fgetc(file)) != EOF) {
        if (ch == '\n') {
            count++;
        }
    }
    (void)fclose(file);
    return count;
}

static char *java_first_row_value(const char *rows)
{
    const char *start = rows == nullptr ? nullptr : strchr(rows, '\n');
    const char *end;
    char *value;
    size_t len;

    if ((start == nullptr) || (start[1] == '\0')) {
        return nullptr;
    }
    start++;
    end = strchr(start, '\n');
    len = end == nullptr ? strlen(start) : (size_t)(end - start);
    value = code_lens_alloc(len + 1U);
    if (value == nullptr) {
        return nullptr;
    }
    (void)memcpy(value, start, len);
    value[len] = '\0';
    return value;
}

static int test_maven_dependency_sources(void)
{
    static const char wrapper[] =
        "#!/bin/sh\n"
        "set -eu\n"
        "echo run >> maven-invocations.txt\n"
        "if [ -f fail-maven ]; then exit 42; fi\n"
        "out=\n"
        "for arg in \"$@\"; do\n"
        "  case \"$arg\" in -DoutputDirectory=*) out=${arg#*=} ;; esac\n"
        "done\n"
        "test -n \"$out\"\n"
        "dest=\"$out/org/example/demo-lib/1.0\"\n"
        "mkdir -p \"$dest\"\n"
        "cp fixture-sources.jar \"$dest/demo-lib-1.0-sources.jar\"\n";
    static const char app_source[] =
        "package demo.app;\n"
        "import org.example.lib.ExternalService;\n"
        "public class App {\n"
        "  private final ExternalService service;\n"
        "  public App(ExternalService service) { this.service = service; }\n"
        "  public String run(int value) { return service.execute(value); }\n"
        "}\n";
    char template_buffer[PATH_MAX];
    char resolved[PATH_MAX];
    const char *tmp = getenv("TMPDIR");
    const char *old_home = getenv("CODE_LENS_HOME");
    const char *old_incremental = getenv("CODE_LENS_INCREMENTAL");
    const char *old_maven = getenv("CODE_LENS_MAVEN");
    const char *old_command = getenv("CODE_LENS_MAVEN_COMMAND");
    char saved_home[PATH_MAX] = {0};
    char saved_incremental[PATH_MAX] = {0};
    char saved_maven[PATH_MAX] = {0};
    char saved_command[PATH_MAX] = {0};
    bool had_home = old_home != nullptr;
    bool had_incremental = old_incremental != nullptr;
    bool had_maven = old_maven != nullptr;
    bool had_command = old_command != nullptr;
    char *root;
    char *repo;
    char *home;
    char *src;
    char *wrapper_path;
    char *jar_path;
    char *pom_path;
    char *count_path;
    char *fail_path;
    CodeLensIndexStats stats = {0};
    CodeLensQueryOptions dependency_options = {
        .limit = 10,
        .kind = "method",
        .scope = "dependencies",
        .dependency = "org.example:*",
    };
    CodeLensQueryOptions wrong_dependency_options = {
        .limit = 10,
        .scope = "dependencies",
        .dependency = "org.other:*",
    };
    char *workspace_query;
    char *dependency_query;
    char *wrong_dependency_query;
    char *context;
    char *rows;
    int failed = 0;

    if (had_home) (void)snprintf(saved_home, sizeof(saved_home), "%s", old_home);
    if (had_incremental) {
        (void)snprintf(saved_incremental, sizeof(saved_incremental), "%s", old_incremental);
    }
    if (had_maven) (void)snprintf(saved_maven, sizeof(saved_maven), "%s", old_maven);
    if (had_command) (void)snprintf(saved_command, sizeof(saved_command), "%s", old_command);
    if ((tmp == nullptr) || (tmp[0] == '\0')) tmp = "/tmp";
    (void)snprintf(template_buffer,
                   sizeof(template_buffer),
                   "%s/code-lens-maven-java-XXXXXX",
                   tmp);
    root = mkdtemp(template_buffer);
    if ((root == nullptr) || (realpath(root, resolved) == nullptr)) {
        return 1;
    }
    root = resolved;
    repo = code_lens_join_path(root, "repo");
    home = code_lens_join_path(root, "home");
    src = repo == nullptr ? nullptr : code_lens_join_path(repo, "src/demo/app");
    wrapper_path = repo == nullptr ? nullptr : code_lens_join_path(repo, "mvnw");
    jar_path = repo == nullptr ? nullptr : code_lens_join_path(repo, "fixture-sources.jar");
    pom_path = repo == nullptr ? nullptr : code_lens_join_path(repo, "pom.xml");
    count_path = repo == nullptr ? nullptr : code_lens_join_path(repo, "maven-invocations.txt");
    fail_path = repo == nullptr ? nullptr : code_lens_join_path(repo, "fail-maven");
    if ((repo == nullptr) || (home == nullptr) || (src == nullptr) ||
        (wrapper_path == nullptr) || (jar_path == nullptr) || (pom_path == nullptr) ||
        (count_path == nullptr) || (fail_path == nullptr) ||
        (code_lens_mkdir_p(src) != 0) ||
        (code_lens_mkdir_p(home) != 0) ||
        (java_write_file(src, "App.java", app_source) != 0) ||
        (java_write_file(repo, "pom.xml", "<project/>\n") != 0) ||
        (java_write_file(repo, "mvnw", wrapper) != 0) || (chmod(wrapper_path, 0755) != 0) ||
        (java_write_file(repo, "fail-maven", "transient failure\n") != 0) ||
        (java_write_source_jar(jar_path) != 0) ||
        (setenv("CODE_LENS_HOME", home, 1) != 0) ||
        (setenv("CODE_LENS_MAVEN", "1", 1) != 0) ||
        (unsetenv("CODE_LENS_MAVEN_COMMAND") != 0) ||
        (unsetenv("CODE_LENS_INCREMENTAL") != 0)) {
        failed = 1;
        goto done;
    }

    if (code_lens_index_repository(repo, &stats) != 0) {
        failed = 1;
        goto done;
    }
    failed |= java_assert(stats.file_count == 1U,
                          "first Maven failure publishes a workspace-only partial index");
    failed |= java_assert(java_count_lines(count_path) == 1,
                          "Maven wrapper invoked on first index");
    rows = code_lens_run_sql(repo,
                             "SELECT status FROM MavenProject WHERE repo = "
                             "(SELECT path FROM Repo LIMIT 1)");
    failed |= java_assert((rows != nullptr) && (strstr(rows, "\nfailed\n") != nullptr),
                          "first Maven failure records retryable status metadata");

    if ((unlink(fail_path) != 0) || (code_lens_index_repository(repo, &stats) != 0)) {
        failed = 1;
        goto done;
    }
    failed |= java_assert(stats.file_count == 2U,
                          "failed Maven status retries without an input edit");
    failed |= java_assert(java_count_lines(count_path) == 2,
                          "retry invokes Maven and indexes dependency sources");

    workspace_query = code_lens_query_symbols(repo, "execute", 10);
    failed |= java_assert((workspace_query != nullptr) &&
                              (strstr(workspace_query, "ExternalService") == nullptr),
                          "dependency symbols stay out of default workspace query");
    dependency_query =
        code_lens_query_symbols_ex(repo, "execute", &dependency_options);
    failed |= java_assert((dependency_query != nullptr) &&
                              (strstr(dependency_query, "execute|method|") != nullptr) &&
                              (strstr(dependency_query, "org.example:demo-lib:1.0") != nullptr) &&
                              (strstr(dependency_query, "|dependency|") != nullptr),
                          "dependency-scoped query returns coordinate and origin");
    wrong_dependency_query =
        code_lens_query_symbols_ex(repo, "execute", &wrong_dependency_options);
    failed |= java_assert((wrong_dependency_query != nullptr) &&
                              (strstr(wrong_dependency_query, "execute|method|") == nullptr),
                          "dependency coordinate glob filters results");

    {
        CodeLensContextOptions context_options = {.path = src};

        context = code_lens_context_symbol_ex(
            repo, "ExternalService#execute", &context_options);
    }
    failed |= java_assert((context != nullptr) &&
                              (strstr(context, "showing Maven dependency definitions") != nullptr) &&
                              (strstr(context, "org.example.lib.ExternalService") != nullptr) &&
                              (strstr(context, "org.example:demo-lib:1.0") != nullptr) &&
                              (strstr(context, "service.execute") != nullptr) &&
                              (strstr(context, "|workspace|") != nullptr) &&
                              (strstr(context, "Executes dependency work.") != nullptr),
                          "context falls back to dependency definition and ranks workspace call");
    rows = code_lens_run_sql(repo,
                             "SELECT COUNT(*) FROM DependencyArtifact WHERE coordinate = "
                             "'org.example:demo-lib:1.0'");
    failed |= java_assert((rows != nullptr) && (strstr(rows, "\n1\n") != nullptr),
                          "dependency metadata is exposed through SQL");

    {
        char *dependency_file;

        rows = code_lens_run_sql(repo, "SELECT filePath FROM DependencyFile LIMIT 1");
        dependency_file = java_first_row_value(rows);
        if ((dependency_file == nullptr) || (unlink(dependency_file) != 0) ||
            (code_lens_index_repository(repo, &stats) != 0)) {
            failed = 1;
            goto done;
        }
        failed |= java_assert(java_count_lines(count_path) == 3,
                              "missing dependency cache file reruns Maven");
        dependency_query =
            code_lens_query_symbols_ex(repo, "execute", &dependency_options);
        failed |= java_assert((dependency_query != nullptr) &&
                                  (strstr(dependency_query, "execute|method|") != nullptr),
                              "Maven refresh repairs a missing materialized source");
    }

    if ((java_write_file(src,
                         "Extra.java",
                         "package demo.app; public class Extra {}\n") != 0) ||
        (setenv("CODE_LENS_INCREMENTAL", "force", 1) != 0) ||
        (code_lens_index_repository(repo, &stats) != 0)) {
        failed = 1;
        goto done;
    }
    failed |= java_assert(java_count_lines(count_path) == 3,
                          "ordinary Java refresh reuses dependency sources without Maven");

    if ((java_write_file(repo, "fail-maven", "refresh failure\n") != 0) ||
        (java_write_file(repo, "pom.xml", "<project><!-- changed --></project>\n") != 0)) {
        failed = 1;
        goto done;
    }
    if (code_lens_index_repository(repo, &stats) == 0) {
        failed = 1;
        goto done;
    }
    failed |= java_assert(java_count_lines(count_path) == 4,
                          "failed Maven refresh invokes the wrapper once");
    dependency_query =
        code_lens_query_symbols_ex(repo, "execute", &dependency_options);
    failed |= java_assert((dependency_query != nullptr) &&
                              (strstr(dependency_query, "org.example:demo-lib:1.0") != nullptr),
                          "failed Maven refresh leaves the published dependency index readable");

    if ((unlink(fail_path) != 0) || (code_lens_index_repository(repo, &stats) != 0)) {
        failed = 1;
        goto done;
    }
    failed |= java_assert(java_count_lines(count_path) == 5,
                          "Maven input change refreshes dependency sources after retry");
    dependency_query =
        code_lens_query_symbols_ex(repo, "execute", &dependency_options);
    failed |= java_assert((dependency_query != nullptr) &&
                              (strstr(dependency_query, "org.example:demo-lib:1.0") != nullptr),
                          "dependency remains queryable after Maven refresh");

done:
    if (had_command) (void)setenv("CODE_LENS_MAVEN_COMMAND", saved_command, 1);
    else (void)unsetenv("CODE_LENS_MAVEN_COMMAND");
    if (had_maven) (void)setenv("CODE_LENS_MAVEN", saved_maven, 1);
    else (void)unsetenv("CODE_LENS_MAVEN");
    if (had_incremental) (void)setenv("CODE_LENS_INCREMENTAL", saved_incremental, 1);
    else (void)unsetenv("CODE_LENS_INCREMENTAL");
    if (had_home) (void)setenv("CODE_LENS_HOME", saved_home, 1);
    else (void)unsetenv("CODE_LENS_HOME");
    if ((root != nullptr) && (code_lens_remove_tree(root) != 0)) {
        (void)fprintf(stderr, "Maven dependency test warning: failed to remove %s\n", root);
    }
    return failed == 0 ? 0 : 1;
}

int test_java_support(void)
{
    int failed = 0;

    failed |= test_java_parser();
    failed |= test_java_index();
    failed |= test_maven_dependency_sources();
    if (failed == 0) {
        (void)fprintf(stderr, "Java parser and indexing tests passed\n");
    }
    return failed == 0 ? 0 : 1;
}
