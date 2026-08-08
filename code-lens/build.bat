@echo off
setlocal EnableExtensions DisableDelayedExpansion
pushd "%~dp0" || exit /b 1

set "MODE=%~1"
if not defined MODE set "MODE=release"
if /i "%MODE%"=="all" set "MODE=release"
if /i "%MODE%"=="release" goto configure
if /i "%MODE%"=="debug" goto configure
if /i "%MODE%"=="clean" goto clean
if /i "%MODE%"=="vendor-deps" goto vendor_deps
if /i "%MODE%"=="help" goto usage
if /i "%MODE%"=="--help" goto usage
echo code-lens: unknown build mode "%MODE%" 1>&2
goto usage_error

:configure
where clang.exe >nul 2>nul || (echo code-lens: clang.exe was not found on PATH 1>&2 & goto fail)
for %%D in (sqlite tree-sitter tree-sitter-clojure tree-sitter-java tree-sitter-c libdeflate zlib) do (
  if not exist "vendor\%%D" (
    echo code-lens: vendor\%%D is missing. Run scripts\vendor-deps.bat first. 1>&2
    goto fail
  )
)

set "BUILD_ROOT=build\windows-%MODE%"
set "OBJ_DIR=%BUILD_ROOT%\obj"
if not exist "%OBJ_DIR%" mkdir "%OBJ_DIR%" || goto fail
if not exist "build" mkdir "build" || goto fail

set "INCLUDES=-Iinclude -Isrc -Ivendor\sqlite -Ivendor\tree-sitter\lib\include -Ivendor\libdeflate -Ivendor\zlib"
set "COMMON=-std=c23 -D_CRT_SECURE_NO_WARNINGS -DWIN32_LEAN_AND_MEAN -DNOMINMAX"
rem SQLite's public Windows ABI uses MSVC's __int64 spelling.
set "WARNINGS=-Wall -Wextra -Wpedantic -Werror -pedantic-errors -Wno-language-extension-token"
if /i "%MODE%"=="debug" (
  set "OPT=-O0 -g"
) else (
  set "OPT=-O3 -DNDEBUG -march=native"
)
set "VENDOR_FLAGS=%COMMON% %OPT%"
set "PROJECT_FLAGS=%COMMON% %OPT% %WARNINGS%"

echo Building code-lens for Windows with:
clang --version | findstr /b /c:"clang version" /c:"Target:"

call :compile_project src\code_lens.c code_lens.obj || goto fail
call :compile_vendor src\windows_compat.c windows_compat.obj || goto fail
call :compile_sqlite vendor\sqlite\sqlite3.c sqlite3.obj || goto fail
call :compile_tree vendor\tree-sitter\lib\src\lib.c tree_sitter.obj -Ivendor\tree-sitter\lib\src || goto fail
call :compile_tree vendor\tree-sitter-clojure\src\parser.c tree_sitter_clojure.obj -Ivendor\tree-sitter-clojure\src || goto fail
call :compile_tree vendor\tree-sitter-java\src\parser.c tree_sitter_java.obj -Ivendor\tree-sitter-java\src || goto fail
call :compile_tree vendor\tree-sitter-c\src\parser.c tree_sitter_c.obj -Ivendor\tree-sitter-c\src || goto fail

call :compile_vendor vendor\libdeflate\lib\deflate_decompress.c libdeflate_deflate_decompress.obj || goto fail
call :compile_vendor vendor\libdeflate\lib\zlib_decompress.c libdeflate_zlib_decompress.obj || goto fail
call :compile_vendor vendor\libdeflate\lib\adler32.c libdeflate_adler32.obj || goto fail
call :compile_vendor vendor\libdeflate\lib\utils.c libdeflate_utils.obj || goto fail
call :compile_vendor vendor\libdeflate\lib\arm\cpu_features.c libdeflate_arm_cpu_features.obj || goto fail
call :compile_vendor vendor\libdeflate\lib\x86\cpu_features.c libdeflate_x86_cpu_features.obj || goto fail

call :compile_vendor vendor\zlib\adler32.c zlib_adler32.obj || goto fail
call :compile_vendor vendor\zlib\crc32.c zlib_crc32.obj || goto fail
call :compile_vendor vendor\zlib\inffast.c zlib_inffast.obj || goto fail
call :compile_vendor vendor\zlib\inflate.c zlib_inflate.obj || goto fail
call :compile_vendor vendor\zlib\inftrees.c zlib_inftrees.obj || goto fail
call :compile_vendor vendor\zlib\zutil.c zlib_zutil.obj || goto fail

echo Linking build\code-lens.exe
clang %OPT% "%OBJ_DIR%\*.obj" -o "%BUILD_ROOT%\code-lens.exe" || goto fail
copy /y "%BUILD_ROOT%\code-lens.exe" "build\code-lens.exe" >nul || goto fail
echo Built %CD%\build\code-lens.exe
popd
exit /b 0

:compile_project
echo [CC] %~1
clang %INCLUDES% %PROJECT_FLAGS% -c "%~1" -o "%OBJ_DIR%\%~2"
exit /b %ERRORLEVEL%

:compile_vendor
echo [CC] %~1
clang %INCLUDES% %VENDOR_FLAGS% -c "%~1" -o "%OBJ_DIR%\%~2"
exit /b %ERRORLEVEL%

:compile_sqlite
echo [CC] %~1
clang %INCLUDES% %VENDOR_FLAGS% -DSQLITE_ENABLE_FTS5 -DSQLITE_THREADSAFE=2 -DSQLITE_OMIT_LOAD_EXTENSION -DSQLITE_DQS=0 -DSQLITE_DEFAULT_MEMSTATUS=0 -DSQLITE_LIKE_DOESNT_MATCH_BLOBS -DSQLITE_MAX_EXPR_DEPTH=0 -DSQLITE_OMIT_DEPRECATED -DSQLITE_USE_ALLOCA -c "%~1" -o "%OBJ_DIR%\%~2"
exit /b %ERRORLEVEL%

:compile_tree
echo [CC] %~1
clang %INCLUDES% %VENDOR_FLAGS% %~3 -c "%~1" -o "%OBJ_DIR%\%~2"
exit /b %ERRORLEVEL%

:clean
if exist "build" rmdir /s /q "build"
echo Cleaned build directory.
popd
exit /b 0

:vendor_deps
call scripts\vendor-deps.bat
set "RC=%ERRORLEVEL%"
popd
exit /b %RC%

:usage
echo Usage: build.bat [release^|debug^|clean^|vendor-deps]
popd
exit /b 0

:usage_error
echo Usage: build.bat [release^|debug^|clean^|vendor-deps] 1>&2
:fail
popd
exit /b 1
