CC ?= cc
AR ?= ar
RM ?= rm -f
CP ?= cp

CSTD_FLAG := $(shell sh scripts/detect-c23-flag.sh "$(CC)")
WARNFLAGS := -Wall -Wextra -Wpedantic -Werror -pedantic-errors
CPPFLAGS := -Iinclude -Ivendor/sqlite -Ivendor/tree-sitter/lib/include -Ivendor/libdeflate
BUILD ?= release
RELEASE_OPTFLAGS ?= -O3 -DNDEBUG -march=native
RELEASE_LTOFLAGS ?= -flto
DEBUG_OPTFLAGS ?= -O0 -g
DEBUG_LTOFLAGS ?=

ifeq ($(BUILD),release)
DEFAULT_OPTFLAGS := $(RELEASE_OPTFLAGS)
DEFAULT_LTOFLAGS := $(RELEASE_LTOFLAGS)
else ifeq ($(BUILD),debug)
DEFAULT_OPTFLAGS := $(DEBUG_OPTFLAGS)
DEFAULT_LTOFLAGS := $(DEBUG_LTOFLAGS)
else ifeq ($(BUILD),pgo-gen)
DEFAULT_OPTFLAGS := $(RELEASE_OPTFLAGS) -fprofile-generate=$(CURDIR)/build/pgo/profraw
DEFAULT_LTOFLAGS := $(RELEASE_LTOFLAGS)
else ifeq ($(BUILD),pgo-use)
DEFAULT_OPTFLAGS := $(RELEASE_OPTFLAGS) -fprofile-use=$(CURDIR)/build/pgo/code-lens.profdata
DEFAULT_LTOFLAGS := $(RELEASE_LTOFLAGS)
else
$(error Unknown BUILD '$(BUILD)'; use release or debug)
endif

OPTFLAGS ?= $(DEFAULT_OPTFLAGS)
LTOFLAGS ?= $(DEFAULT_LTOFLAGS)
PTHREADFLAGS ?= -pthread
CFLAGS ?=
PROJECT_CFLAGS := $(CSTD_FLAG) $(WARNFLAGS) $(OPTFLAGS) $(LTOFLAGS) $(PTHREADFLAGS) $(CFLAGS)
VENDOR_CFLAGS ?= $(OPTFLAGS) $(LTOFLAGS)
LDFLAGS ?=
PROJECT_LDFLAGS := $(filter-out -D%,$(OPTFLAGS)) $(LTOFLAGS) $(PTHREADFLAGS) $(LDFLAGS)
# System zlib still inflates loose objects (streaming, unknown output size);
# vendored libdeflate inflates pack objects (whole-buffer, known size).
PROJECT_LDLIBS ?= -lz

BUILD_DIR := build
BUILD_ROOT := $(BUILD_DIR)/$(BUILD)
OBJ_DIR := $(BUILD_ROOT)/obj
TEST_DIR := $(BUILD_ROOT)/tests
BIN := $(BUILD_DIR)/code-lens
TEST_BIN := $(TEST_DIR)/test-main
CANONICAL_TEST_BIN := $(BUILD_DIR)/tests/test-main

SQLITE_OBJ := $(OBJ_DIR)/vendor/sqlite/sqlite3.o
SQLITE_DEFINES := -DSQLITE_ENABLE_FTS5 -DSQLITE_THREADSAFE=2 -DSQLITE_OMIT_LOAD_EXTENSION \
	-DSQLITE_DQS=0 -DSQLITE_DEFAULT_MEMSTATUS=0 -DSQLITE_LIKE_DOESNT_MATCH_BLOBS \
	-DSQLITE_MAX_EXPR_DEPTH=0 -DSQLITE_OMIT_DEPRECATED -DSQLITE_USE_ALLOCA
TREE_SITTER_OBJ := $(OBJ_DIR)/vendor/tree-sitter/lib.o
TREE_SITTER_CLOJURE_OBJ := $(OBJ_DIR)/vendor/tree-sitter-clojure/parser.o
TREE_SITTER_JAVA_OBJ := $(OBJ_DIR)/vendor/tree-sitter-java/parser.o
TREE_SITTER_C_OBJ := $(OBJ_DIR)/vendor/tree-sitter-c/parser.o
# Decompression-only slice of libdeflate; the cpu_features files are
# internally guarded per architecture, so compiling both is safe anywhere.
LIBDEFLATE_SRCS := vendor/libdeflate/lib/deflate_decompress.c \
	vendor/libdeflate/lib/zlib_decompress.c \
	vendor/libdeflate/lib/adler32.c \
	vendor/libdeflate/lib/utils.c \
	vendor/libdeflate/lib/arm/cpu_features.c \
	vendor/libdeflate/lib/x86/cpu_features.c
LIBDEFLATE_OBJS := $(patsubst vendor/libdeflate/lib/%.c,$(OBJ_DIR)/vendor/libdeflate/%.o,$(LIBDEFLATE_SRCS))
VENDOR_OBJS := $(SQLITE_OBJ) $(TREE_SITTER_OBJ) $(TREE_SITTER_CLOJURE_OBJ) $(TREE_SITTER_JAVA_OBJ) $(TREE_SITTER_C_OBJ) $(LIBDEFLATE_OBJS)

SRC := src/code_lens.c
OBJ := $(patsubst src/%.c,$(OBJ_DIR)/%.o,$(SRC))
TEST_SRC := tests/test_main.c tests/test_clojure_parser.c tests/test_java_support.c tests/test_c_support.c tests/test_git_blobs.c
TEST_OBJ := $(patsubst tests/%.c,$(OBJ_DIR)/tests/%.o,$(TEST_SRC))
TEST_APP_OBJ := $(OBJ_DIR)/code_lens_test.o

.PHONY: all release debug test test-release test-debug clean check-c23 vendor-deps pgo

all: $(BUILD_ROOT)/code-lens
	@mkdir -p $(dir $(BIN))
	$(RM) $(BIN)
	$(CP) $< $(BIN)

release:
	$(MAKE) BUILD=release all

debug:
	$(MAKE) BUILD=debug all

$(BUILD_ROOT)/code-lens: $(OBJ) $(VENDOR_OBJS)
	@mkdir -p $(dir $@)
	$(CC) $(PROJECT_LDFLAGS) $(OBJ) $(VENDOR_OBJS) $(PROJECT_LDLIBS) -o $@

$(OBJ_DIR)/%.o: src/%.c include/code_lens.h
	@mkdir -p $(dir $@)
	$(CC) $(CPPFLAGS) $(PROJECT_CFLAGS) -c $< -o $@

$(TREE_SITTER_OBJ): vendor/tree-sitter/lib/src/lib.c
	@mkdir -p $(dir $@)
	$(CC) -Ivendor/tree-sitter/lib/include -Ivendor/tree-sitter/lib/src $(VENDOR_CFLAGS) -c $< -o $@

$(SQLITE_OBJ): vendor/sqlite/sqlite3.c
	@mkdir -p $(dir $@)
	$(CC) -Ivendor/sqlite $(VENDOR_CFLAGS) $(PTHREADFLAGS) $(SQLITE_DEFINES) -c $< -o $@

$(TREE_SITTER_CLOJURE_OBJ): vendor/tree-sitter-clojure/src/parser.c
	@mkdir -p $(dir $@)
	$(CC) -Ivendor/tree-sitter/lib/include -Ivendor/tree-sitter-clojure/src $(VENDOR_CFLAGS) -c $< -o $@

$(TREE_SITTER_JAVA_OBJ): vendor/tree-sitter-java/src/parser.c
	@mkdir -p $(dir $@)
	$(CC) -Ivendor/tree-sitter/lib/include -Ivendor/tree-sitter-java/src $(VENDOR_CFLAGS) -c $< -o $@

$(TREE_SITTER_C_OBJ): vendor/tree-sitter-c/src/parser.c
	@mkdir -p $(dir $@)
	$(CC) -Ivendor/tree-sitter/lib/include -Ivendor/tree-sitter-c/src $(VENDOR_CFLAGS) -c $< -o $@

$(OBJ_DIR)/vendor/libdeflate/%.o: vendor/libdeflate/lib/%.c
	@mkdir -p $(dir $@)
	$(CC) -Ivendor/libdeflate $(VENDOR_CFLAGS) -c $< -o $@

$(OBJ_DIR)/tests/%.o: tests/%.c include/code_lens.h
	@mkdir -p $(dir $@)
	$(CC) $(CPPFLAGS) $(PROJECT_CFLAGS) -c $< -o $@

$(TEST_APP_OBJ): src/code_lens.c include/code_lens.h
	@mkdir -p $(dir $@)
	$(CC) $(CPPFLAGS) $(PROJECT_CFLAGS) -DCODE_LENS_NO_MAIN -c $< -o $@

$(TEST_BIN): $(TEST_OBJ) $(TEST_APP_OBJ) $(VENDOR_OBJS)
	@mkdir -p $(dir $@)
	$(CC) $(PROJECT_LDFLAGS) $(TEST_OBJ) $(TEST_APP_OBJ) $(VENDOR_OBJS) $(PROJECT_LDLIBS) -o $@

test: $(TEST_BIN)
	@mkdir -p $(dir $(CANONICAL_TEST_BIN))
	$(RM) $(CANONICAL_TEST_BIN)
	$(CP) $(TEST_BIN) $(CANONICAL_TEST_BIN)
	$(CANONICAL_TEST_BIN)

test-release:
	$(MAKE) BUILD=release test

test-debug:
	$(MAKE) BUILD=debug test

check-c23:
	@printf 'Using %s for ISO C23 mode\n' "$(CSTD_FLAG)"

# Opt-in profile-guided build; the default release path stays PGO-free so
# clean builds need no profile data. Trains on PGO_TRAIN_PATH (defaults to
# the mcp-smoke fixture; point it at a real repo for a representative
# profile), then emits build/code-lens-pgo next to the plain binary.
LLVM_PROFDATA ?= $(shell command -v llvm-profdata 2>/dev/null || echo xcrun llvm-profdata)
PGO_TRAIN_PATH ?= tests/fixtures/mcp-smoke

pgo:
	$(RM) -r $(BUILD_DIR)/pgo $(BUILD_DIR)/pgo-gen $(BUILD_DIR)/pgo-use
	$(MAKE) BUILD=pgo-gen $(BUILD_DIR)/pgo-gen/code-lens
	scripts/pgo-train.sh $(BUILD_DIR)/pgo-gen/code-lens "$(PGO_TRAIN_PATH)"
	$(LLVM_PROFDATA) merge -output=$(BUILD_DIR)/pgo/code-lens.profdata $(BUILD_DIR)/pgo/profraw
	$(MAKE) BUILD=pgo-use $(BUILD_DIR)/pgo-use/code-lens
	$(RM) $(BUILD_DIR)/code-lens-pgo
	$(CP) $(BUILD_DIR)/pgo-use/code-lens $(BUILD_DIR)/code-lens-pgo

vendor-deps:
	scripts/vendor-deps.sh

clean:
	$(RM) -r $(BUILD_DIR)
