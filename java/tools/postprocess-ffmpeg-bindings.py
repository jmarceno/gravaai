#!/usr/bin/env python3
"""Make freshly extracted jextract bindings compile under NullAway/JSpecify.

`extract-ffmpeg-bindings.sh` runs jextract, whose output knows nothing about JSpecify. The
bindings live in a `@NullMarked` package, so the raw sources either do not compile or force
callers into null hacks:

* `Linker.nativeLinker().canonicalLayouts().get(...)` returns `@Nullable` (JDK signature), but
  the canonical layouts of the native linker are guaranteed present. The generated `$shared`
  constants are mapped with an `Objects.requireNonNull` helper so they are genuinely non-null and
  class initialisation fails loudly if a layout is ever missing.
* `traceDowncall` formats boxed native arguments, and a downcall may pass a null pointer as a
  Java `null` reference; the varargs elements are annotated `@Nullable` and formatted as `null`
  explicitly instead of dereferencing them.
* `reinterpret(addr, arena, cleanup)` forwards `cleanup` to `MemorySegment.reinterpret`, whose
  JDK contract documents "can be null". The parameter is annotated `@Nullable` so callers can
  pass `null` without a dummy no-op cleanup action.
* The package gets the `@NullMarked` `package-info.java` jextract does not emit.

Usage: postprocess-ffmpeg-bindings.py GEN_DIR

The transforms are idempotent and deterministic; each one asserts it matched, so a jextract
upgrade that changes its output fails here instead of silently producing nullable-unsafe code.
"""

import pathlib
import re
import sys

PACKAGE = "dev.gravaai.media.ffmpeg.gen"
NULLABLE_IMPORT = "import org.jspecify.annotations.Nullable;"
PACKAGE_DECL = f"package {PACKAGE};\n"

PACKAGE_INFO = f"""@NullMarked
package {PACKAGE};

import org.jspecify.annotations.NullMarked;
"""

CANONICAL_LAYOUT_HELPER = '''    /**
     * Returns the canonical layout registered for {@code name} by the native linker.
     *
     * @throws NullPointerException when the native linker has no canonical layout for {@code name}
     */
    private static MemoryLayout canonicalLayout(String name) {
        return Objects.requireNonNull(Linker.nativeLinker().canonicalLayouts().get(name),
                () -> "native linker has no canonical layout named " + name);
    }

'''


def fail(path, message):
    raise SystemExit(f"postprocess-ffmpeg-bindings.py: {path.name}: {message}")


def add_nullable_import(source, path):
    """Insert the JSpecify import directly before the first `java.*` import block."""
    if NULLABLE_IMPORT + "\n" in source:
        return source
    marker = "import java.lang.invoke.*;"
    if marker not in source:
        fail(path, "cannot find the jextract import block")
    return source.replace(marker, f"{NULLABLE_IMPORT}\n\n{marker}", 1)


def annotate_reinterpret_cleanup(source, path):
    """`cleanup` is forwarded to MemorySegment.reinterpret, which accepts null."""
    matches = len(re.findall(r"Consumer<MemorySegment> cleanup\)", source))
    if matches == 0:
        return source
    source = source.replace(
        "MemorySegment addr, Arena arena, Consumer<MemorySegment> cleanup)",
        "MemorySegment addr, Arena arena, @Nullable Consumer<MemorySegment> cleanup)")
    source = source.replace(
        "MemorySegment addr, long elementCount, Arena arena, Consumer<MemorySegment> cleanup)",
        "MemorySegment addr, long elementCount, Arena arena, "
        "@Nullable Consumer<MemorySegment> cleanup)")
    if ", Consumer<MemorySegment> cleanup)" in source:
        fail(path, "unexpected reinterpret overload shape")
    return source


def document_cleanup_param(source):
    """Add a @param line to the reinterpret javadoc blocks that precede the nullable param."""
    pattern = re.compile(
        r"(?P<indent> +)\* Reinterprets \{@code addr\}[^\n]*\n"
        r"(?P=indent)\* The returned segment has size[^\n]*\n"
        r"(?!\s*\* @param cleanup)")

    def add_param(match):
        indent = match.group("indent")
        return (match.group(0)
                + f"{indent}* @param cleanup the cleanup action to run when the arena closes,\n"
                + f"{indent}*        or {{@code null}} for none\n")

    # The negative lookahead makes a re-run idempotent: a block that already documents the
    # parameter is left alone.
    source = pattern.sub(add_param, source)
    return source


def harden_shared(source, path):
    """Make the canonical layout constants non-null and the trace formatter null-safe."""
    if "private static MemoryLayout canonicalLayout(String name)" in source:
        return source  # already post-processed (idempotent re-run)
    if "canonicalLayout(" not in source:
        marker = "    public static final ValueLayout.OfBoolean C_BOOL ="
        if marker not in source:
            fail(path, "cannot find the shared constants")
        source = source.replace(marker, CANONICAL_LAYOUT_HELPER + marker, 1)
    layouts = re.findall(r'Linker\.nativeLinker\(\)\.canonicalLayouts\(\)\.get\("[^"]+"\)', source)
    if not layouts:
        fail(path, "no canonicalLayouts().get(...) call in the shared constants")
    source = re.sub(r'Linker\.nativeLinker\(\)\.canonicalLayouts\(\)\.get\(("[^"]+")\)',
                    r"canonicalLayout(\1)", source)
    if "Object... args" in source:
        source = source.replace("Object... args", "@Nullable Object... args")
        source = source.replace(".map(Object::toString)",
                                '.map(arg -> arg == null ? "null" : arg.toString())')
    if "@Nullable Object... args" not in source:
        fail(path, "cannot find traceDowncall varargs")
    return source


def main(argv):
    if len(argv) != 2:
        print("usage: postprocess-ffmpeg-bindings.py GEN_DIR", file=sys.stderr)
        return 2
    gen_dir = pathlib.Path(argv[1])
    if not gen_dir.is_dir():
        print(f"postprocess-ffmpeg-bindings.py: {gen_dir} is not a directory", file=sys.stderr)
        return 2

    sources = sorted(gen_dir.glob("*.java"))
    if not sources:
        print(f"postprocess-ffmpeg-bindings.py: no sources in {gen_dir}", file=sys.stderr)
        return 2

    for path in sources:
        source = path.read_text(encoding="utf-8")
        source = annotate_reinterpret_cleanup(source, path)
        source = document_cleanup_param(source)
        if "Consumer<MemorySegment> cleanup" in source or path.name == "ffmpeg_h$shared.java":
            source = add_nullable_import(source, path)
        if path.name == "ffmpeg_h$shared.java":
            source = harden_shared(source, path)
        path.write_text(source, encoding="utf-8")

    (gen_dir / "package-info.java").write_text(PACKAGE_INFO, encoding="utf-8")
    print(f"postprocess-ffmpeg-bindings.py: post-processed {len(sources)} files in {gen_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
