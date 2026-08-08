#include "code_lens.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void set_parser_backend(const char *backend)
{
    if (backend == nullptr) {
        (void)unsetenv("CODE_LENS_PARSER");
    } else {
        (void)setenv("CODE_LENS_PARSER", backend, 1);
    }
}

static int assert_true(int condition, const char *message)
{
    if (!condition) {
        (void)fprintf(stderr, "parser test failed: %s\n", message);
        return 1;
    }
    return 0;
}

static const CodeLensSymbol *find_symbol(const CodeLensClojureFile *file, const char *name)
{
    for (size_t i = 0U; i < file->symbol_count; i++) {
        if (strcmp(file->symbols[i].name, name) == 0) {
            return &file->symbols[i];
        }
    }
    return nullptr;
}

static const CodeLensReference *find_reference(const CodeLensClojureFile *file,
                                                const char *symbol)
{
    for (size_t i = 0U; i < file->reference_count; i++) {
        if (strcmp(file->references[i].symbol, symbol) == 0) {
            return &file->references[i];
        }
    }
    return nullptr;
}

static const CodeLensKeyword *find_keyword(const CodeLensClojureFile *file, const char *keyword)
{
    for (size_t i = 0U; i < file->keyword_count; i++) {
        if (strcmp(file->keywords[i].keyword, keyword) == 0) {
            return &file->keywords[i];
        }
    }
    return nullptr;
}

static int run_extraction_suite(void)
{
    static const char source[] =
        "(ns co.checkout.dashboard.pages.reports.report-form-components\n"
        "  (:require [clojure.string :as str]\n"
        "            [co.checkout.dashboard.common :refer [thing]]\n"
        "            [combined.ns :as cmb :refer [alpha beta]]\n"
        "            [walked.ns :refer :all]\n"
        "            [alias.only :as-alias ao]\n"
        "            #?(:clj [cond.clj-ns :as ccn]\n"
        "               :cljs [cond.cljs-ns :as csn])\n"
        "            #?@(:clj [[spliced.one :as sp1]\n"
        "                      [spliced.two :as sp2]]))\n"
        "  #?(:clj (:require [clause.level :as clv]))\n"
        "  (:use [used.ns] plain.used))\n"
        "\n"
        "(defn refund-date-type-enabled?\n"
        "  \"True when refund date type should be shown.\"\n"
        "  [marketplace]\n"
        "  (str/includes? (:settings marketplace) \"refund\"))\n"
        "\n"
        "(defmethod build-report :audit [_] :ok)\n"
        "\n"
        "(defn keyword-fixture []\n"
        "  {:report!-fn :foo/bar\n"
        "   ::local-key ::str/trimmed})\n"
        "\n"
        "(deftest refund-date-type-test\n"
        "  (refund-date-type-enabled? {}))\n"
        "\n"
        "(def ^:private secret-var 1)\n"
        "\n"
        "(defn ^{:merchant-auth [:x]}\n"
        "  annotated-fn [] nil)\n"
        "\n"
        "(def #^:legacy old-meta-var 2)\n"
        "\n"
        "(map ^:hinted str/upper-case [])\n";
    CodeLensClojureFile file;
    const CodeLensSymbol *function_symbol;
    const CodeLensSymbol *method_symbol;
    const CodeLensSymbol *test_symbol;
    const CodeLensKeyword *report_keyword;
    const CodeLensKeyword *qualified_keyword;
    const CodeLensKeyword *local_auto_keyword;
    const CodeLensKeyword *alias_auto_keyword;
    int failed = 0;

    if (code_lens_parse_clojure_source("fixture.clj", source, strlen(source), &file) != 0) {
        (void)fprintf(stderr, "parser test failed: parse returned error\n");
        return 1;
    }

    failed |= assert_true(strcmp(file.namespace_name,
                                 "co.checkout.dashboard.pages.reports.report-form-components") == 0,
                          "namespace extracted");
    failed |= assert_true(file.alias_count == 10U, "require specs extracted");
    failed |= assert_true(strcmp(file.aliases[0].namespace_name, "clojure.string") == 0,
                          "alias namespace extracted");
    failed |= assert_true(strcmp(file.aliases[0].alias, "str") == 0, "alias name extracted");
    failed |= assert_true(strcmp(file.aliases[2].namespace_name, "combined.ns") == 0 &&
                              strcmp(file.aliases[2].alias, "cmb") == 0,
                          ":as with :refer spec extracted");
    failed |= assert_true(strcmp(file.aliases[4].namespace_name, "alias.only") == 0 &&
                              strcmp(file.aliases[4].alias, "ao") == 0,
                          ":as-alias spec extracted");
    failed |= assert_true(strcmp(file.aliases[5].namespace_name, "cond.clj-ns") == 0 &&
                              strcmp(file.aliases[5].alias, "ccn") == 0,
                          "read-cond clj spec extracted");
    failed |= assert_true(strcmp(file.aliases[6].namespace_name, "cond.cljs-ns") == 0 &&
                              strcmp(file.aliases[6].alias, "csn") == 0,
                          "read-cond cljs spec extracted");
    failed |= assert_true(strcmp(file.aliases[7].namespace_name, "spliced.one") == 0 &&
                              strcmp(file.aliases[7].alias, "sp1") == 0,
                          "splicing read-cond first spec extracted");
    failed |= assert_true(strcmp(file.aliases[8].namespace_name, "spliced.two") == 0 &&
                              strcmp(file.aliases[8].alias, "sp2") == 0,
                          "splicing read-cond second spec extracted");
    failed |= assert_true(strcmp(file.aliases[9].namespace_name, "clause.level") == 0 &&
                              strcmp(file.aliases[9].alias, "clv") == 0,
                          "read-cond require clause extracted");

    failed |= assert_true(file.referred_count == 6U, "referred symbols extracted");
    if (file.referred_count == 6U) {
        failed |= assert_true(
            strcmp(file.referred[0].namespace_name, "co.checkout.dashboard.common") == 0 &&
                strcmp(file.referred[0].symbol, "thing") == 0,
            ":refer symbol extracted");
        failed |= assert_true(strcmp(file.referred[1].namespace_name, "combined.ns") == 0 &&
                                  strcmp(file.referred[1].symbol, "alpha") == 0,
                              ":as with :refer first symbol extracted");
        failed |= assert_true(strcmp(file.referred[2].namespace_name, "combined.ns") == 0 &&
                                  strcmp(file.referred[2].symbol, "beta") == 0,
                              ":as with :refer second symbol extracted");
        failed |= assert_true(strcmp(file.referred[3].namespace_name, "walked.ns") == 0 &&
                                  strcmp(file.referred[3].symbol, ":all") == 0,
                              ":refer :all recorded as sentinel");
        failed |= assert_true(strcmp(file.referred[4].namespace_name, "used.ns") == 0 &&
                                  strcmp(file.referred[4].symbol, ":all") == 0,
                              ":use vec spec recorded as sentinel");
        failed |= assert_true(strcmp(file.referred[5].namespace_name, "plain.used") == 0 &&
                                  strcmp(file.referred[5].symbol, ":all") == 0,
                              ":use bare symbol recorded as sentinel");
    }

    function_symbol = find_symbol(&file, "refund-date-type-enabled?");
    method_symbol = find_symbol(&file, "build-report");
    test_symbol = find_symbol(&file, "refund-date-type-test");

    failed |= assert_true(function_symbol != nullptr, "defn symbol extracted");
    failed |= assert_true(method_symbol != nullptr, "defmethod symbol extracted");
    failed |= assert_true(test_symbol != nullptr, "deftest symbol extracted");
    if (test_symbol != nullptr) {
        failed |= assert_true(strcmp(test_symbol->kind, "test") == 0, "deftest kind");
    }

    if (function_symbol != nullptr) {
        failed |= assert_true(strcmp(function_symbol->kind, "function") == 0, "defn kind");
        failed |= assert_true(function_symbol->doc != nullptr, "defn doc present");
        failed |= assert_true(strcmp(function_symbol->doc,
                                     "True when refund date type should be shown.") == 0,
                              "defn doc text");
        failed |= assert_true(function_symbol->start_line == 14U, "defn start line");
    }

    failed |= assert_true(file.reference_count > file.symbol_count, "symbol references collected");

    {
        const CodeLensSymbol *secret_symbol = find_symbol(&file, "secret-var");
        const CodeLensSymbol *annotated_symbol = find_symbol(&file, "annotated-fn");
        const CodeLensSymbol *old_meta_symbol = find_symbol(&file, "old-meta-var");

        failed |= assert_true(secret_symbol != nullptr, "meta-annotated def extracted");
        failed |= assert_true(annotated_symbol != nullptr, "map-meta defn extracted");
        failed |= assert_true(old_meta_symbol != nullptr, "old-meta def extracted");
        if (secret_symbol != nullptr) {
            failed |= assert_true(strcmp(secret_symbol->kind, "var") == 0,
                                  "meta-annotated def kind");
        }
        if (annotated_symbol != nullptr) {
            failed |= assert_true(strcmp(annotated_symbol->kind, "function") == 0,
                                  "map-meta defn kind");
        }
        if (old_meta_symbol != nullptr) {
            failed |= assert_true(strcmp(old_meta_symbol->kind, "var") == 0,
                                  "old-meta def kind");
        }
    }

    for (size_t i = 0U; i < file.symbol_count; i++) {
        failed |= assert_true((file.symbols[i].name[0] != '^') &&
                                  (strncmp(file.symbols[i].name, "#^", 2U) != 0),
                              "symbol name free of metadata markers");
    }
    for (size_t i = 0U; i < file.reference_count; i++) {
        failed |= assert_true((file.references[i].symbol[0] != '^') &&
                                  (strncmp(file.references[i].symbol, "#^", 2U) != 0),
                              "reference text free of metadata markers");
    }

    failed |= assert_true(find_reference(&file, "str/upper-case") != nullptr,
                          "meta-annotated qualified reference stored as clean alias/name");
    {
        const CodeLensReference *upper_ref = find_reference(&file, "str/upper-case");

        if (upper_ref != nullptr) {
            failed |= assert_true(upper_ref->start_byte < upper_ref->end_byte,
                                  "reference byte span is non-empty");
            failed |= assert_true((upper_ref->end_byte - upper_ref->start_byte) ==
                                      strlen("str/upper-case"),
                                  "reference byte span length matches symbol");
            failed |= assert_true(strncmp(source + upper_ref->start_byte,
                                          "str/upper-case",
                                          strlen("str/upper-case")) == 0,
                                  "reference byte span points at metadata-free symbol");
        }
    }

    report_keyword = find_keyword(&file, ":report!-fn");
    qualified_keyword = find_keyword(&file, ":foo/bar");
    local_auto_keyword = find_keyword(&file, "::local-key");
    alias_auto_keyword = find_keyword(&file, "::str/trimmed");
    failed |= assert_true(report_keyword != nullptr, "unqualified keyword extracted");
    failed |= assert_true(qualified_keyword != nullptr, "qualified keyword extracted");
    failed |= assert_true(local_auto_keyword != nullptr, "auto-resolved local keyword extracted");
    failed |= assert_true(alias_auto_keyword != nullptr, "auto-resolved alias keyword extracted");
    if (report_keyword != nullptr) {
        failed |= assert_true(strcmp(report_keyword->base, "report!-fn") == 0,
                              "unqualified keyword base extracted");
        failed |= assert_true(strcmp(report_keyword->target_namespace, "") == 0,
                              "unqualified keyword has no target namespace");
    }
    if (qualified_keyword != nullptr) {
        failed |= assert_true(strcmp(qualified_keyword->base, "bar") == 0,
                              "qualified keyword base extracted");
        failed |= assert_true(strcmp(qualified_keyword->qualifier, "foo") == 0,
                              "qualified keyword qualifier extracted");
        failed |= assert_true(strcmp(qualified_keyword->target_namespace, "foo") == 0,
                              "qualified keyword target namespace preserved");
    }
    if (local_auto_keyword != nullptr) {
        failed |= assert_true(strcmp(local_auto_keyword->base, "local-key") == 0,
                              "local auto-resolved keyword base extracted");
        failed |= assert_true(strcmp(local_auto_keyword->target_namespace,
                                    "co.checkout.dashboard.pages.reports.report-form-components") == 0,
                              "local auto-resolved keyword target namespace extracted");
    }
    if (alias_auto_keyword != nullptr) {
        failed |= assert_true(strcmp(alias_auto_keyword->base, "trimmed") == 0,
                              "alias auto-resolved keyword base extracted");
        failed |= assert_true(strcmp(alias_auto_keyword->qualifier, "str") == 0,
                              "alias auto-resolved keyword qualifier extracted");
        failed |= assert_true(strcmp(alias_auto_keyword->target_namespace, "clojure.string") == 0,
                              "alias auto-resolved keyword target namespace extracted");
    }

    return failed == 0 ? 0 : 1;
}

/* --- differential test: custom reader vs tree-sitter, field by field --- */

static bool cstr_eq(const char *a, const char *b)
{
    if ((a == nullptr) || (b == nullptr)) {
        return a == b;
    }
    return strcmp(a, b) == 0;
}

static int diff_fail(const char *what, size_t index)
{
    (void)fprintf(stderr, "parser differential failed: %s (item %zu)\n", what, index);
    return 1;
}

static int compare_parses(const CodeLensClojureFile *a, const CodeLensClojureFile *b)
{
    if (!cstr_eq(a->namespace_name, b->namespace_name)) {
        return diff_fail("namespace", 0U);
    }

    if (a->alias_count != b->alias_count) {
        return diff_fail("alias count", 0U);
    }
    for (size_t i = 0U; i < a->alias_count; i++) {
        if (!cstr_eq(a->aliases[i].namespace_name, b->aliases[i].namespace_name) ||
            !cstr_eq(a->aliases[i].alias, b->aliases[i].alias)) {
            return diff_fail("alias fields", i);
        }
    }

    if (a->referred_count != b->referred_count) {
        (void)fprintf(stderr, "  a: %zu referred, b: %zu referred\n",
                      a->referred_count, b->referred_count);
        return diff_fail("referred count", 0U);
    }
    for (size_t i = 0U; i < a->referred_count; i++) {
        if (!cstr_eq(a->referred[i].namespace_name, b->referred[i].namespace_name) ||
            !cstr_eq(a->referred[i].symbol, b->referred[i].symbol)) {
            return diff_fail("referred fields", i);
        }
    }

    if (a->symbol_count != b->symbol_count) {
        return diff_fail("symbol count", 0U);
    }
    for (size_t i = 0U; i < a->symbol_count; i++) {
        const CodeLensSymbol *sa = &a->symbols[i];
        const CodeLensSymbol *sb = &b->symbols[i];

        if (!cstr_eq(sa->kind, sb->kind) || !cstr_eq(sa->name, sb->name) ||
            !cstr_eq(sa->namespace_name, sb->namespace_name) ||
            !cstr_eq(sa->source, sb->source) || !cstr_eq(sa->doc, sb->doc) ||
            (sa->start_line != sb->start_line) || (sa->end_line != sb->end_line)) {
            (void)fprintf(stderr,
                          "  a: kind=%s name=%s lines=%u..%u\n  b: kind=%s name=%s lines=%u..%u\n",
                          sa->kind, sa->name, sa->start_line, sa->end_line,
                          sb->kind, sb->name, sb->start_line, sb->end_line);
            return diff_fail("symbol fields", i);
        }
    }

    if (a->reference_count != b->reference_count) {
        (void)fprintf(stderr, "  a: %zu references, b: %zu references\n",
                      a->reference_count, b->reference_count);
        for (size_t i = 0U; (i < a->reference_count) && (i < b->reference_count); i++) {
            if (!cstr_eq(a->references[i].symbol, b->references[i].symbol)) {
                (void)fprintf(stderr, "  first divergence at %zu: a=%s b=%s\n", i,
                              a->references[i].symbol, b->references[i].symbol);
                break;
            }
        }
        return diff_fail("reference count", 0U);
    }
    for (size_t i = 0U; i < a->reference_count; i++) {
        const CodeLensReference *ra = &a->references[i];
        const CodeLensReference *rb = &b->references[i];

        if (!cstr_eq(ra->symbol, rb->symbol) || (ra->symbol_len != rb->symbol_len) ||
            (ra->line != rb->line) || (ra->column != rb->column) ||
            (ra->start_byte != rb->start_byte) || (ra->end_byte != rb->end_byte)) {
            (void)fprintf(stderr,
                          "  a: %s @%u:%u bytes %u..%u\n  b: %s @%u:%u bytes %u..%u\n",
                          ra->symbol, ra->line, ra->column, ra->start_byte, ra->end_byte,
                          rb->symbol, rb->line, rb->column, rb->start_byte, rb->end_byte);
            return diff_fail("reference fields", i);
        }
        if ((ra->base_len != rb->base_len) ||
            (memcmp(ra->base, rb->base, ra->base_len) != 0) ||
            (ra->target_namespace_len != rb->target_namespace_len) ||
            (memcmp(ra->target_namespace, rb->target_namespace, ra->target_namespace_len) != 0)) {
            (void)fprintf(stderr,
                          "  a: base %.*s target %.*s\n  b: base %.*s target %.*s\n",
                          (int)ra->base_len, ra->base,
                          (int)ra->target_namespace_len, ra->target_namespace,
                          (int)rb->base_len, rb->base,
                          (int)rb->target_namespace_len, rb->target_namespace);
            return diff_fail("reference target fields", i);
        }
    }

    if (a->keyword_count != b->keyword_count) {
        (void)fprintf(stderr, "  a: %zu keywords, b: %zu keywords\n",
                      a->keyword_count, b->keyword_count);
        for (size_t i = 0U; (i < a->keyword_count) && (i < b->keyword_count); i++) {
            if (!cstr_eq(a->keywords[i].keyword, b->keywords[i].keyword)) {
                (void)fprintf(stderr, "  first divergence at %zu: a=%s b=%s\n", i,
                              a->keywords[i].keyword, b->keywords[i].keyword);
                break;
            }
        }
        return diff_fail("keyword count", 0U);
    }
    for (size_t i = 0U; i < a->keyword_count; i++) {
        const CodeLensKeyword *ka = &a->keywords[i];
        const CodeLensKeyword *kb = &b->keywords[i];

        if (!cstr_eq(ka->keyword, kb->keyword) || (ka->keyword_len != kb->keyword_len) ||
            !cstr_eq(ka->base, kb->base) || (ka->base_len != kb->base_len) ||
            !cstr_eq(ka->qualifier, kb->qualifier) || (ka->qualifier_len != kb->qualifier_len) ||
            !cstr_eq(ka->target_namespace, kb->target_namespace) ||
            (ka->target_namespace_len != kb->target_namespace_len) ||
            (ka->line != kb->line) || (ka->column != kb->column)) {
            (void)fprintf(stderr, "  a: %s @%u:%u\n  b: %s @%u:%u\n",
                          ka->keyword, ka->line, ka->column,
                          kb->keyword, kb->line, kb->column);
            return diff_fail("keyword fields", i);
        }
    }

    return 0;
}

/*
 * Every grammar construct the custom reader models natively, in one source.
 * Both backends parse it and every extracted field must agree exactly; the
 * fallback counter must stay flat so the comparison actually exercises the
 * custom reader rather than tree-sitter twice.
 */
static int run_differential_test(void)
{
    static const char source[] =
        "(ns ^{:doc \"torture ns\"} torture.core\n"
        "  (:require [clojure.string :as str]\n"
        "            [other.ns :as o]\n"
        "            [plain.require]\n"
        "            [torture.alias :as-alias ta]\n"
        "            [torture.refer :refer [tr-one tr-two]]\n"
        "            [torture.both :as tb :refer [tb-fn]]\n"
        "            [torture.everything :refer :all]\n"
        "            #?(:clj [torture.cond :as tc] :cljs [torture.cond-s :as tcs])\n"
        "            #?@(:clj [[torture.spliced :as tsp]]))\n"
        "  #?(:clj (:require [torture.clause :as tcl]))\n"
        "  (:use [torture.used] torture.plain-used))\n"
        ";; plain comment\n"
        "#!shebang-style comment\n"
        "(defn f1 \"doc string\" [x] (str/join x))\n"
        "(s/def ::spec string?)\n"
        "(def ^:private ^{:a 1} multi-meta 1)\n"
        "(defn #^:old old-style [] nil)\n"
        "#_(def discarded 1)\n"
        "#_#_(def d1 1) (def d2 2)\n"
        "(def unicode-sym-\xe2\x86\x92 3)\n"
        "(def ^:kw ;; comment between metas\n"
        "  ^{:m 2} chained-meta 10)\n"
        "'(quoted (def quoted-def 5))\n"
        "`(syn ~unq ~@unq-splice)\n"
        "@(deref-me)\n"
        "#'var-quoted\n"
        "##Inf\n"
        "##-Inf\n"
        "#{1 2 3}\n"
        "#(inc %1)\n"
        "#\"regex[abc]+\\d\"\n"
        "#?(:clj (def clj-only 6) :cljs (def cljs-only 7))\n"
        "#?@(:clj [(def spliced 8)])\n"
        "#:prefix{:a 1 :b 2}\n"
        "#::{:auto 1}\n"
        "#::str{:aliased 2}\n"
        "#inst \"2024-01-01\"\n"
        "#uuid\"00000000-0000-0000-0000-000000000000\"\n"
        "# my.ns/tag-with-gap {:t 1}\n"
        "# ;; comment gap inside tagged\n"
        "  other-tag 9\n"
        "(def with-chars [\\a \\newline \\space \\u0041 \\o101 \\tab \\\\ \\\" \\) \\( \\\xe2\x86\x92])\n"
        "(def with-strings [\"s1\" \"s\\\"2\" \"s\\\\3\" \"multi\nline\"])\n"
        "(def with-numbers [1 -2 +3 1.5 -1.5e10 2e-3 0x1F 017 2r1010 3/4 42N 3.14M 1M 1.])\n"
        "(def num-then-sym [123abc])\n"
        "(def nil-bool [nil true false nily true/x])\n"
        "(def kw-forms [:a ::b :c/d ::str/e :/ :/lead/ing :5num :#weird :a:b :end: :trail])\n"
        "(def sym-forms [foo foo/bar foo.baz/qux-1 foo// / + - * +x -y a+b-c <=> a's !bang ?q %2 %& _])\n"
        "(let [m ^:kw-meta {:in-meta :yes}] m)\n"
        "(defn tail-fn [] {:pre [(pos? 1)]} 1)\n";
    CodeLensClojureFile custom_file;
    CodeLensClojureFile ts_file;
    uint64_t fallbacks_before;
    int failed = 0;

    fallbacks_before = code_lens_clojure_reader_fallbacks();
    set_parser_backend(nullptr);
    if (code_lens_parse_clojure_source("torture.clj", source, sizeof(source) - 1U,
                                        &custom_file) != 0) {
        (void)fprintf(stderr, "parser differential failed: custom parse returned error\n");
        return 1;
    }
    if (code_lens_clojure_reader_fallbacks() != fallbacks_before) {
        (void)fprintf(stderr,
                      "parser differential failed: custom reader fell back to tree-sitter\n");
        return 1;
    }

    set_parser_backend("treesitter");
    if (code_lens_parse_clojure_source("torture.clj", source, sizeof(source) - 1U,
                                        &ts_file) != 0) {
        (void)fprintf(stderr, "parser differential failed: tree-sitter parse returned error\n");
        set_parser_backend(nullptr);
        return 1;
    }
    set_parser_backend(nullptr);

    failed |= compare_parses(&custom_file, &ts_file);
    failed |= assert_true(custom_file.alias_count == 11U, "torture ns aliases extracted");
    failed |= assert_true(custom_file.referred_count == 6U, "torture ns referred extracted");
    failed |= assert_true(custom_file.reference_count > 40U, "torture source yields references");
    failed |= assert_true(custom_file.keyword_count > 15U, "torture source yields keywords");
    failed |= assert_true(custom_file.symbol_count > 10U, "torture source yields symbols");
    return failed;
}

int test_clojure_parser(void)
{
    int failed = 0;

    /* run the extraction assertions under both parser backends */
    set_parser_backend(nullptr);
    if (run_extraction_suite() != 0) {
        (void)fprintf(stderr, "parser test failed under custom reader backend\n");
        failed = 1;
    }
    set_parser_backend("treesitter");
    if (run_extraction_suite() != 0) {
        (void)fprintf(stderr, "parser test failed under tree-sitter backend\n");
        failed = 1;
    }
    set_parser_backend(nullptr);

    failed |= run_differential_test();

    return failed;
}
