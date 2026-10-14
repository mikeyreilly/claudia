#include "code_lens.h"

#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int py_assert(bool condition, const char *message)
{
    if (!condition) {
        (void)fprintf(stderr, "Python support test failed: %s\n", message);
        return 1;
    }
    return 0;
}

static const CodeLensSymbol *py_find_symbol(const CodeLensPythonFile *file,
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

/* Finds a reference by its symbol text, base, and target namespace. */
static const CodeLensReference *py_find_reference(const CodeLensPythonFile *file,
                                                  const char *symbol,
                                                  const char *base,
                                                  const char *target)
{
    for (size_t i = 0U; i < file->reference_count; i++) {
        const CodeLensReference *reference = &file->references[i];

        if (((symbol == nullptr) || (strcmp(reference->symbol, symbol) == 0)) &&
            (reference->base_len == strlen(base)) &&
            (memcmp(reference->base, base, reference->base_len) == 0) &&
            ((target == nullptr) || (strcmp(reference->target_namespace, target) == 0))) {
            return reference;
        }
    }
    return nullptr;
}

static size_t py_count_base(const CodeLensPythonFile *file, const char *base)
{
    size_t count = 0U;

    for (size_t i = 0U; i < file->reference_count; i++) {
        const CodeLensReference *reference = &file->references[i];

        if ((reference->base_len == strlen(base)) &&
            (memcmp(reference->base, base, reference->base_len) == 0)) {
            count++;
        }
    }
    return count;
}

static bool py_has_alias(const CodeLensPythonFile *file, const char *namespace_name, const char *alias)
{
    for (size_t i = 0U; i < file->alias_count; i++) {
        if ((strcmp(file->aliases[i].namespace_name, namespace_name) == 0) &&
            (strcmp(file->aliases[i].alias, alias) == 0)) {
            return true;
        }
    }
    return false;
}

static bool py_has_referred(const CodeLensPythonFile *file, const char *namespace_name, const char *symbol)
{
    for (size_t i = 0U; i < file->referred_count; i++) {
        if ((strcmp(file->referred[i].namespace_name, namespace_name) == 0) &&
            (strcmp(file->referred[i].symbol, symbol) == 0)) {
            return true;
        }
    }
    return false;
}

static int py_write_file(const char *dir, const char *name, const char *content)
{
    char *path = code_lens_join_path(dir, name);
    FILE *file;

    if ((path == nullptr) || ((file = fopen(path, "w")) == nullptr)) {
        return -1;
    }
    if (fputs(content, file) == EOF) {
        (void)fclose(file);
        return -1;
    }
    return fclose(file) == 0 ? 0 : -1;
}

static char *py_make_temp_root(const char *label, char *buffer, size_t buffer_len, char *resolved)
{
    const char *tmp = getenv("TMPDIR");

    if ((tmp == nullptr) || (tmp[0] == '\0')) {
        tmp = "/tmp";
    }
    (void)snprintf(buffer, buffer_len, "%s/code-lens-py-%s-XXXXXX", tmp, label);
    if ((mkdtemp(buffer) == nullptr) || (realpath(buffer, resolved) == nullptr)) {
        return nullptr;
    }
    return resolved;
}

static bool py_module_is(const char *repo, const char *relative, const char *expected)
{
    char *path = code_lens_join_path(repo, relative);
    char *module = path == nullptr ? nullptr : code_lens_python_module_name(repo, path);

    if ((module == nullptr) || (strcmp(module, expected) != 0)) {
        (void)fprintf(stderr,
                      "  module for %s: expected %s, got %s\n",
                      relative,
                      expected,
                      module == nullptr ? "(null)" : module);
        return false;
    }
    return true;
}

static int test_python_module_names(void)
{
    char buffer[PATH_MAX];
    char resolved[PATH_MAX];
    char *root = py_make_temp_root("modules", buffer, sizeof(buffer), resolved);
    char *repo = root == nullptr ? nullptr : code_lens_join_path(root, "repo");
    char *app = repo == nullptr ? nullptr : code_lens_join_path(repo, "backend/app");
    char *sub = repo == nullptr ? nullptr : code_lens_join_path(repo, "backend/app/sub");
    char *rooted = nullptr;
    int failed = 0;

    if ((app == nullptr) || (sub == nullptr) || (code_lens_mkdir_p(sub) != 0) ||
        (py_write_file(app, "__init__.py", "") != 0) ||
        (py_write_file(sub, "__init__.py", "") != 0)) {
        failed = 1;
        goto done;
    }

    failed |= py_assert(py_module_is(repo, "backend/app/models.py", "app.models"),
                        "__init__.py chain names a regular package module");
    failed |= py_assert(py_module_is(repo, "backend/app/__init__.py", "app"),
                        "__init__.py names its package");
    failed |= py_assert(py_module_is(repo, "backend/app/sub/x.py", "app.sub.x"),
                        "nested package module");
    failed |= py_assert(py_module_is(repo, "src/ns/pkg/mod.py", "ns.pkg.mod"),
                        "namespace package under src/ strips the src root");
    failed |= py_assert(py_module_is(repo, "scripts/run.py", "scripts.run"),
                        "loose script uses its repository-relative path");
    failed |= py_assert(py_module_is(repo, "top.py", "top"), "top-level module");
    {
        char *outside = code_lens_python_module_name(repo, "/elsewhere/lib/z.py");
        char *no_root = code_lens_python_module_name(nullptr, "/elsewhere/lib/z.py");

        failed |= py_assert((outside != nullptr) && (strcmp(outside, "z") == 0),
                            "file outside the repository falls back to its stem");
        failed |= py_assert((no_root != nullptr) && (strcmp(no_root, "z") == 0),
                            "missing repository root falls back to the stem");
    }
    rooted = code_lens_join_path(repo, "");
    if (rooted != nullptr) {
        char *path = code_lens_join_path(repo, "scripts/run.py");
        char *module = path == nullptr ? nullptr : code_lens_python_module_name(rooted, path);

        failed |= py_assert((module != nullptr) && (strcmp(module, "scripts.run") == 0),
                            "trailing separator on the repository root is ignored");
    }

done:
    if ((root != nullptr) && (code_lens_remove_tree(root) != 0)) {
        (void)fprintf(stderr, "Python support test warning: failed to remove %s\n", root);
    }
    return failed;
}

static int test_python_parser(void)
{
    static const char source[] =
        "\"\"\"Service module.\"\"\"\n"
        "import os.path\n"
        "import shop.models.user as um\n"
        "from .models.user import User, DEFAULT_NAME as DN\n"
        "from . import models\n"
        "from typing import Optional\n"
        "from helpers import *\n"
        "\n"
        "LIMIT: int = 10\n"
        "\"\"\"Max items.\"\"\"\n"
        "\n"
        "def make_user(name=DN, *, tags=None):\n"
        "    \"\"\"Build a user.\n"
        "\n"
        "    Returns it.\n"
        "    \"\"\"\n"
        "    u = User(name)\n"
        "    u.save()\n"
        "    v: Optional[User] = None\n"
        "    v.greet(u)\n"
        "    c = Cart()\n"
        "    c.add(1)\n"
        "    c.items.append(2)\n"
        "    um.User.build(name)\n"
        "    os.path.join('a', 'b')\n"
        "    total = 0\n"
        "    for item in tags:\n"
        "        total += item\n"
        "    if (found := lookup(name)):\n"
        "        found.strip()\n"
        "    unknown_helper()\n"
        "    print(len(tags))\n"
        "    models.user.User.build('y')\n"
        "    return [x.name for x in (u,)] + [lambda q: q]\n"
        "\n"
        "@decorate(LIMIT)\n"
        "class Cart:\n"
        "    \"\"\"A shopping cart.\"\"\"\n"
        "    size = 0\n"
        "\n"
        "    def __init__(self, owner: User):\n"
        "        self.owner = owner\n"
        "        self.items = []\n"
        "        self.first = self.last = None\n"
        "\n"
        "    def add(self, item):\n"
        "        self.items.append(item)\n"
        "        self.total()\n"
        "\n"
        "    def total(self):\n"
        "        return len(self.items)\n"
        "\n"
        "    @staticmethod\n"
        "    def empty(other):\n"
        "        return other.size\n"
        "\n"
        "class Admin(User):\n"
        "    def promote(self):\n"
        "        self.greet(self)\n"
        "        super().save()\n"
        "        self.owner_name()\n"
        "\n"
        "class TestCart:\n"
        "    def test_add(self):\n"
        "        Cart(None).add(1)\n"
        "\n"
        "def test_make_user():\n"
        "    make_user('x')\n";
    CodeLensPythonFile file;
    const CodeLensSymbol *symbol;
    const CodeLensReference *reference;
    int failed = 0;

    if (code_lens_parse_python_source("/virtual/repo",
                                      "/virtual/repo/shop/service.py",
                                      source,
                                      sizeof(source) - 1U,
                                      &file) != 0) {
        return py_assert(false, "parse Python source");
    }

    failed |= py_assert(strcmp(file.namespace_name, "shop.service") == 0,
                        "module name derives from the repository-relative path");

    symbol = py_find_symbol(&file, "LIMIT", "variable", "shop.service");
    failed |= py_assert((symbol != nullptr) && (symbol->doc != nullptr) &&
                            (strcmp(symbol->doc, "Max items.") == 0),
                        "module variable with attribute docstring");
    symbol = py_find_symbol(&file, "make_user", "function", "shop.service");
    failed |= py_assert((symbol != nullptr) && (symbol->doc != nullptr) &&
                            (strcmp(symbol->doc, "Build a user.\n\nReturns it.") == 0) &&
                            (symbol->start_line == 12U),
                        "function with cleaned docstring");
    symbol = py_find_symbol(&file, "Cart", "class", "shop.service");
    failed |= py_assert((symbol != nullptr) && (symbol->start_line == 36U) &&
                            (strcmp(symbol->source, "@decorate(LIMIT)\nclass Cart: ...") == 0) &&
                            (symbol->doc != nullptr) &&
                            (strcmp(symbol->doc, "A shopping cart.") == 0),
                        "decorated class keeps decorators and a header-only source");
    failed |= py_assert(py_find_symbol(&file, "size", "field", "shop.service.Cart") != nullptr,
                        "class attribute is a field");
    failed |= py_assert(py_find_symbol(&file, "__init__", "constructor", "shop.service.Cart") != nullptr,
                        "__init__ is a constructor");
    failed |= py_assert(py_find_symbol(&file, "owner", "field", "shop.service.Cart") != nullptr,
                        "self attribute assignment is a field");
    failed |= py_assert((py_find_symbol(&file, "first", "field", "shop.service.Cart") != nullptr) &&
                            (py_find_symbol(&file, "last", "field", "shop.service.Cart") != nullptr),
                        "chained self attribute assignments are fields");
    failed |= py_assert((py_find_symbol(&file, "add", "method", "shop.service.Cart") != nullptr) &&
                            (py_find_symbol(&file, "empty", "method", "shop.service.Cart") != nullptr),
                        "methods use module.Class namespaces");
    failed |= py_assert(py_find_symbol(&file, "test_add", "test", "shop.service.TestCart") != nullptr,
                        "test methods in Test classes are tests");
    failed |= py_assert(py_find_symbol(&file, "test_make_user", "test", "shop.service") != nullptr,
                        "test_ functions are tests");
    failed |= py_assert(py_find_symbol(&file, "u", nullptr, nullptr) == nullptr,
                        "function locals are not symbols");

    failed |= py_assert(py_has_alias(&file, "os.path", "os.path") &&
                            py_has_alias(&file, "shop.models.user", "um"),
                        "import statements become Alias rows");
    failed |= py_assert(py_has_referred(&file, "shop.models.user", "User") &&
                            py_has_referred(&file, "shop.models.user", "DEFAULT_NAME") &&
                            py_has_referred(&file, "shop", "models") &&
                            py_has_referred(&file, "typing", "Optional") &&
                            py_has_referred(&file, "helpers", ":all"),
                        "from-imports (relative and wildcard) become Referred rows");

    failed |= py_assert(py_find_reference(&file, "DN", "DEFAULT_NAME", "shop.models.user") != nullptr,
                        "aliased from-import keeps the original name as base");
    failed |= py_assert(py_find_reference(&file, "User", "User", "shop.models.user") != nullptr,
                        "imported class reference targets its module");
    failed |= py_assert(py_find_reference(&file, "u.save", "save", "shop.models.user.User") != nullptr,
                        "constructor-assigned local resolves its receiver type");
    failed |= py_assert(py_find_reference(&file, "v.greet", "greet", "shop.models.user.User") != nullptr,
                        "Optional[...] annotation resolves its receiver type");
    failed |= py_assert(py_find_reference(&file, "c.add", "add", "shop.service.Cart") != nullptr,
                        "later-defined local class resolves as a receiver");
    failed |= py_assert(py_find_reference(&file, "c.items.append", "append", "") != nullptr,
                        "untyped member receivers stay unresolved");
    failed |= py_assert(py_find_reference(&file, "um.User.build", "build", "shop.models.user.User") != nullptr,
                        "module alias chains resolve through import aliases");
    failed |= py_assert(py_find_reference(&file, "models.user.User.build", "build", "shop.models.user.User") != nullptr,
                        "relative submodule import chains resolve");
    failed |= py_assert((py_find_reference(&file, "os.path.join", "join", "os.path") != nullptr) &&
                            (py_find_reference(&file, "os.path", "path", nullptr) == nullptr),
                        "imported module components are not references");
    failed |= py_assert(py_find_reference(&file, "lookup", "lookup", "") != nullptr,
                        "wildcard import keeps unresolved names unqualified");
    failed |= py_assert((py_count_base(&file, "print") == 0U) && (py_count_base(&file, "len") == 0U),
                        "builtins are not references");
    failed |= py_assert((py_count_base(&file, "item") == 0U) && (py_count_base(&file, "q") == 0U) &&
                            (py_find_reference(&file, "total", "total", nullptr) == nullptr),
                        "locals, lambda parameters, and loop targets are not references");
    failed |= py_assert(py_find_reference(&file, "found.strip", "strip", "") != nullptr,
                        "walrus targets bind as locals");
    failed |= py_assert(py_find_reference(&file, "self.owner", "owner", "shop.service.Cart") != nullptr,
                        "self receivers target the enclosing class");
    failed |= py_assert(py_find_reference(&file, "self.total", "total", "shop.service.Cart") != nullptr,
                        "self method calls target the enclosing class");
    failed |= py_assert(py_find_reference(&file, "other.size", "size", "") != nullptr,
                        "staticmethod first parameters are not receivers");
    failed |= py_assert((py_find_reference(&file, "self.greet", "greet", "shop.models.user.User") != nullptr) &&
                            (py_find_reference(&file, "self.owner_name", "owner_name", "shop.models.user.User") != nullptr),
                        "inherited members target the external base class");
    failed |= py_assert(py_find_reference(&file, "save", "save", "shop.models.user.User") != nullptr,
                        "super() calls target the base class");
    failed |= py_assert(py_find_reference(&file, "add", "add", "shop.service.Cart") != nullptr,
                        "constructor-call receivers resolve");
    failed |= py_assert(py_find_reference(&file, "make_user", "make_user", "shop.service") != nullptr,
                        "module function calls target the module");
    failed |= py_assert(py_find_reference(&file, "LIMIT", "LIMIT", "shop.service") != nullptr,
                        "decorator arguments are references");
    reference = py_find_reference(&file, "User", "User", "shop.models.user");
    failed |= py_assert((reference != nullptr) && (reference->line > 0U) &&
                            (reference->end_byte > reference->start_byte),
                        "references carry positions");
    return failed;
}

static int test_python_parser_robustness(void)
{
    static const char broken[] =
        "def broken(:\n"
        "    pass\n"
        "class Ok:\n"
        "    def fine(self):\n"
        "        return self.fine()\n";
    CodeLensPythonFile file;
    char *deep;
    size_t depth = 3000U;
    size_t len = 4U + depth * 2U + 2U;
    int failed = 0;

    failed |= py_assert(code_lens_parse_python_source(nullptr,
                                                      "/virtual/broken.py",
                                                      broken,
                                                      sizeof(broken) - 1U,
                                                      &file) == 0,
                        "syntax errors still parse");
    failed |= py_assert((strcmp(file.namespace_name, "broken") == 0) &&
                            (py_find_symbol(&file, "fine", "method", "broken.Ok") != nullptr),
                        "definitions after a syntax error are extracted");

    deep = malloc(len);
    if (deep == nullptr) {
        return 1;
    }
    (void)memcpy(deep, "x = ", 4U);
    (void)memset(deep + 4U, '(', depth);
    deep[4U + depth] = '1';
    (void)memset(deep + 5U + depth, ')', depth);
    deep[len - 1U] = '\n';
    failed |= py_assert(code_lens_parse_python_source(nullptr, "/virtual/deep.py", deep, len, &file) == 0,
                        "deeply nested expressions are bounded");
    free(deep);
    return failed;
}

static int test_python_index(void)
{
    char buffer[PATH_MAX];
    char resolved[PATH_MAX];
    const char *old_home = getenv("CODE_LENS_HOME");
    const char *old_incremental = getenv("CODE_LENS_INCREMENTAL");
    char saved_home[PATH_MAX] = {0};
    char saved_incremental[PATH_MAX] = {0};
    bool had_home = old_home != nullptr;
    bool had_incremental = old_incremental != nullptr;
    char *root;
    char *repo = nullptr;
    char *home = nullptr;
    char *backend = nullptr;
    char *app = nullptr;
    char *venv = nullptr;
    char *cache = nullptr;
    CodeLensIndexStats stats = {0};
    CodeLensQueryOptions class_options = {.limit = 5, .kind = "class"};
    char *text;
    int failed = 0;

    if (had_home) {
        (void)snprintf(saved_home, sizeof(saved_home), "%s", old_home);
    }
    if (had_incremental) {
        (void)snprintf(saved_incremental, sizeof(saved_incremental), "%s", old_incremental);
    }
    root = py_make_temp_root("index", buffer, sizeof(buffer), resolved);
    if (root == nullptr) {
        return py_assert(false, "temporary directory setup");
    }
    repo = code_lens_join_path(root, "repo");
    home = code_lens_join_path(root, "home");
    backend = repo == nullptr ? nullptr : code_lens_join_path(repo, "backend");
    app = backend == nullptr ? nullptr : code_lens_join_path(backend, "app");
    venv = repo == nullptr ? nullptr : code_lens_join_path(repo, ".venv/lib");
    cache = app == nullptr ? nullptr : code_lens_join_path(app, "__pycache__");
    if ((home == nullptr) || (app == nullptr) || (venv == nullptr) || (cache == nullptr) ||
        (code_lens_mkdir_p(app) != 0) || (code_lens_mkdir_p(venv) != 0) ||
        (code_lens_mkdir_p(cache) != 0) || (code_lens_mkdir_p(home) != 0) ||
        (py_write_file(app, "__init__.py", "") != 0) ||
        (py_write_file(app,
                       "models.py",
                       "class User:\n"
                       "    \"\"\"An account.\"\"\"\n"
                       "    def greet(self):\n"
                       "        return 'hi'\n"
                       "\n"
                       "def make():\n"
                       "    return User()\n") != 0) ||
        (py_write_file(app,
                       "views.py",
                       "from .models import User\n"
                       "from . import models\n"
                       "\n"
                       "def show():\n"
                       "    u = User()\n"
                       "    u.greet()\n"
                       "    return models.make()\n") != 0) ||
        (py_write_file(venv, "site.py", "def vendored():\n    pass\n") != 0) ||
        (py_write_file(cache, "stale.py", "def cached():\n    pass\n") != 0) ||
        (setenv("CODE_LENS_HOME", home, 1) != 0)) {
        failed = 1;
        goto done;
    }

    if (code_lens_index_repository(repo, &stats) != 0) {
        failed = 1;
        goto done;
    }
    failed |= py_assert(stats.file_count == 3U, "virtualenv and __pycache__ directories are skipped");

    text = code_lens_query_symbols_ex(repo, "User", &class_options);
    failed |= py_assert((text != nullptr) && (strstr(text, "User|class|app.models") != nullptr),
                        "class query returns the Python class");
    text = code_lens_context_symbol(repo, "greet");
    failed |= py_assert((text != nullptr) && (strstr(text, "greet|method|app.models.User") != nullptr) &&
                            (strstr(text, "u.greet -> app.models.User") != nullptr) &&
                            (strstr(text, "views.py") != nullptr),
                        "context links a method to a typed receiver call");
    text = code_lens_context_symbol(repo, "models.make");
    failed |= py_assert((text != nullptr) && (strstr(text, "make|function|app.models") != nullptr) &&
                            (strstr(text, "models.make -> app.models") != nullptr),
                        "context links a function through a submodule import");
    text = code_lens_run_sql(repo, "SELECT namespace FROM File WHERE path LIKE '%views.py'");
    failed |= py_assert((text != nullptr) && (strstr(text, "app.views") != nullptr),
                        "File.namespace stores the module name");

    /* A new __init__.py renames every module below it, so even a forced
     * incremental refresh must rebuild in full. */
    if ((py_write_file(backend, "__init__.py", "") != 0) ||
        (setenv("CODE_LENS_INCREMENTAL", "force", 1) != 0) ||
        (code_lens_index_repository(repo, &stats) != 0)) {
        failed = 1;
        goto done;
    }
    text = code_lens_run_sql(repo,
                             "SELECT path, namespace FROM File WHERE path LIKE '%models.py' "
                             "OR path LIKE '%views.py' ORDER BY path");
    failed |= py_assert((text != nullptr) && (strstr(text, "|backend.app.models") != nullptr) &&
                            (strstr(text, "|backend.app.views") != nullptr),
                        "adding __init__.py renames unchanged modules");
    text = code_lens_context_symbol(repo, "greet");
    failed |= py_assert((text != nullptr) && (strstr(text, "u.greet -> backend.app.models.User") != nullptr),
                        "references follow the renamed package");

    if ((py_write_file(app,
                       "views.py",
                       "from .models import User\n"
                       "\n"
                       "def show():\n"
                       "    return User().greet()\n"
                       "\n"
                       "def py_extra():\n"
                       "    return show()\n") != 0) ||
        (code_lens_index_repository(repo, &stats) != 0)) {
        failed = 1;
        goto done;
    }
    text = code_lens_query_symbols(repo, "py_extra", 5);
    failed |= py_assert((text != nullptr) && (strstr(text, "py_extra|function|backend.app.views") != nullptr),
                        "incremental refresh re-parses an edited Python file");

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
    if (code_lens_remove_tree(root) != 0) {
        (void)fprintf(stderr, "Python support test warning: failed to remove %s\n", root);
    }
    return failed;
}

int test_python_support(void)
{
    int failed = 0;

    failed |= test_python_module_names();
    failed |= test_python_parser();
    failed |= test_python_parser_robustness();
    failed |= test_python_index();
    if (failed == 0) {
        (void)fprintf(stderr, "Python parser and indexing tests passed\n");
    }
    return failed == 0 ? 0 : 1;
}
