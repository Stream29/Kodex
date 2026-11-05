#!/usr/bin/env bash
set -euo pipefail
target=$1
out=$2
mkdir -p "$out"
out=$(cd "$out" && pwd)
source_dir=$(cd "$(dirname "$0")" && pwd)
archive="$source_dir/sqlite-amalgamation-3540100.zip"
digest_tool=openssl
if [[ -x /opt/homebrew/opt/openssl@3/bin/openssl ]]; then
    digest_tool=/opt/homebrew/opt/openssl@3/bin/openssl
fi
test "$("$digest_tool" dgst -sha3-256 "$archive" | awk '{print $NF}')" = 92281c86e2957f9b9305890f9ac04204caa7b677cc868dc326d942689df28e30
unzip -oq "$archive" -d "$out"
deps="${KONAN_DATA_DIR:-$HOME/.konan}/dependencies"
flags=(-O2 -fPIC -DSQLITE_THREADSAFE=1 -DSQLITE_OMIT_LOAD_EXTENSION)
if [[ $(uname -s) == Darwin ]]; then
    llvm="$deps/llvm-21-aarch64-macos-essentials-97/bin"
else
    llvm="$deps/llvm-21-x86_64-linux-essentials-116/bin"
fi
case "$target" in
    linuxX64)
        flags+=(--target=x86_64-unknown-linux-gnu --sysroot="$deps/x86_64-unknown-linux-gnu-gcc-8.3.0-glibc-2.19-kernel-4.9-2/x86_64-unknown-linux-gnu/sysroot") ;;
    linuxArm64)
        flags+=(--target=aarch64-unknown-linux-gnu --sysroot="$deps/aarch64-unknown-linux-gnu-gcc-8.3.0-glibc-2.25-kernel-4.9-2/aarch64-unknown-linux-gnu/sysroot") ;;
    mingwX64)
        flags+=(--target=x86_64-w64-windows-gnu --sysroot="$deps/msys2-mingw-w64-x86_64-2" -I"$deps/msys2-mingw-w64-x86_64-2/x86_64-w64-mingw32/include") ;;
    macosArm64)
        flags+=(-target arm64-apple-macos13 -isysroot "$(xcrun --sdk macosx --show-sdk-path)") ;;
    *) exit 2 ;;
esac
"$llvm/clang" "${flags[@]}" -c "$out/sqlite-amalgamation-3540100/sqlite3.c" -o "$out/sqlite.o"
"$llvm/llvm-ar" rcs "$out/libkodexsqlite.a" "$out/sqlite.o"
cat > "$out/bundled.def" <<DEF
package = io.github.stream29.kodex.utils.sqlite.bundled
staticLibraries = libkodexsqlite.a
libraryPaths = $out
DEF
