package com.quaxt.codingagent.debug;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Launches one precompiled, top-level JUnit Jupiter test class or {@code @Test} method under Maven Surefire.
 * The project must use a Surefire version supporting {@code -Dtest=Class#method}, fork its tests,
 * have JUnit Platform support, and have its test classes already compiled in
 * {@code target/test-classes}. Maven is invoked non-recursively ({@code -N}), so the selected
 * test must belong to the project at {@code cwd}, not a reactor child. The caller owns the
 * returned process and must drain both its stdout and stderr, wait for it, and terminate it (and
 * its forked JVM) if cancelling. The specified TCP port must be free; it is not reserved here.
 * A nonzero Maven exit code (including missing tests or a port conflict) is reported by the process.
 * Invokes only the Surefire goal (not the full Maven test lifecycle), so unrelated
 * test-phase plugins do not run. Custom Surefire providers/configuration are outside
 * this helper's control; use an ordinary single-project Surefire/Jupiter configuration.
 */
public final class MavenTestLaunch {
    private static final Pattern SELECTOR = Pattern.compile(
            "([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+)(?:#([A-Za-z_$][A-Za-z0-9_$]*))?");
    private static final String JUPITER_TEST = "Lorg/junit/jupiter/api/Test;";

    private MavenTestLaunch() {}

    /** Starts Maven without a shell, with JDWP on {@code 127.0.0.1:port} and {@code suspend=y}.
     * Only top-level classes with unambiguous, zero-argument Jupiter {@code @Test} methods are
     * accepted. A class selector runs all such methods in that class; other test types and nested
     * tests are unsupported for class selectors.
     * Output remains available via {@link Process#getInputStream()} and
     * {@link Process#getErrorStream()}; the caller must consume both to avoid blocking Maven.
     *
     * @throws IllegalArgumentException if the selector, port, or compiled test is unsupported
     * @throws IOException if the compiled class cannot be inspected or Maven cannot be started
     */
    public static Process start(Path cwd, String selector, int port) throws IOException {
        Objects.requireNonNull(cwd, "cwd");
        if (!Files.isRegularFile(cwd.resolve("pom.xml"))) {
            throw new IllegalArgumentException("Expected a Maven pom.xml in " + cwd);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("JDWP port must be between 1 and 65535");
        }
        Matcher match = SELECTOR.matcher(Objects.requireNonNull(selector, "selector"));
        if (!match.matches() || match.group(1).contains("$")
                || (match.group(2) != null && match.group(2).contains("$"))) {
            throw new IllegalArgumentException("Expected a top-level fully qualified class or class#method selector (not a JUnit unique ID): " + selector);
        }
        String className = match.group(1);
        String method = match.group(2);
        Path classes = cwd.resolve("target/test-classes").toAbsolutePath().normalize();
        Path file = classes.resolve(className.replace('.', '/') + ".class");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Compile the selected test first; missing " + file);
        }
        inspect(file, className, method);

        String debug = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:" + port;
        String maven = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
                ? "mvn.cmd" : "mvn";
        return new ProcessBuilder(maven, "-N", "-Dtest=" + selector,
                "-DfailIfNoTests=true", "-Dsurefire.failIfNoSpecifiedTests=true",
                "-DskipTests=false", "-Dmaven.surefire.debug=" + debug, "surefire:test")
                .directory(cwd.toFile()).start();
    }

    private static void inspect(Path file, String className, String method) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            if (in.readInt() != 0xCAFEBABE) throw new IOException("Not a Java class: " + file);
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            String[] pool = new String[in.readUnsignedShort()];
            int[] classRefs = new int[pool.length];
            for (int i = 1; i < pool.length; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> pool[i] = in.readUTF();
                    case 7 -> classRefs[i] = in.readUnsignedShort();
                    case 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                    case 5, 6 -> { in.skipNBytes(8); i++; }
                    case 15 -> in.skipNBytes(3);
                    default -> throw new IOException("Unknown class-file constant tag " + tag + " in " + file);
                }
            }
            int flags = in.readUnsignedShort();
            String actual = pool[classRefs[in.readUnsignedShort()]];
            String parent = pool[classRefs[in.readUnsignedShort()]];
            if (!className.replace('.', '/').equals(actual) || !"java/lang/Object".equals(parent)
                    || (flags & (0x0200 | 0x0400)) != 0) {
                throw new IllegalArgumentException("Unsupported test class (must be a concrete top-level Jupiter class): " + className);
            }
            int interfaces = in.readUnsignedShort();
            in.skipNBytes(2L * interfaces);
            if (interfaces != 0) {
                throw new IllegalArgumentException("Unsupported test class with inherited interface tests: " + className);
            }
            skipMembers(in); // fields
            int candidates = 0;
            int tests = 0;
            boolean junitTest = false;
            boolean validSignature = false;
            List<String> names = new ArrayList<>();
            for (int i = in.readUnsignedShort(); i > 0; i--) {
                int access = in.readUnsignedShort();
                String name = pool[in.readUnsignedShort()];
                String descriptor = pool[in.readUnsignedShort()];
                List<String> annotations = annotations(in, pool);
                if (method == null) {
                    if (annotations.stream().anyMatch(MavenTestLaunch::otherTestAnnotation)) {
                        throw new IllegalArgumentException("Unsupported test type in " + className + "#" + name);
                    }
                    if (annotations.contains(JUPITER_TEST)) {
                        tests++;
                        if (names.contains(name) || !"()V".equals(descriptor)
                                || (access & (0x0002 | 0x0008 | 0x0400)) != 0
                                || annotations.contains("Lorg/junit/jupiter/api/Disabled;")) {
                            throw new IllegalArgumentException("Ambiguous or unsupported @Test method: " + className + "#" + name);
                        }
                    }
                    names.add(name);
                } else if (method.equals(name)) {
                    candidates++;
                    junitTest = annotations.contains(JUPITER_TEST);
                    validSignature = "()V".equals(descriptor) && (access & (0x0002 | 0x0008 | 0x0400)) == 0;
                }
            }
            List<String> classAnnotations = annotations(in, pool, method == null);
            if (method == null && classAnnotations.stream().anyMatch(a -> a.equals("Lorg/junit/jupiter/api/Disabled;"))) {
                throw new IllegalArgumentException("Disabled test class: " + className);
            }
            if (classAnnotations.stream().anyMatch(a -> a.startsWith("Lorg/junit/runner/")
                    || a.startsWith("Lorg/junit/platform/suite/")
                    || a.equals("Lorg/junit/jupiter/api/Nested;"))) {
                throw new IllegalArgumentException("Unsupported JUnit runner or suite: " + className);
            }
            if (method == null) {
                if (tests == 0 || names.stream().anyMatch(n -> names.indexOf(n) != names.lastIndexOf(n))) {
                    throw new IllegalArgumentException("Expected unambiguous Jupiter @Test methods in " + className);
                }
                return;
            }
            if (candidates != 1 || !junitTest || !validSignature) {
                throw new IllegalArgumentException("Expected exactly one zero-argument Jupiter @Test method (no overloads): "
                        + className + "#" + method + " (found " + candidates + " matching methods)");
            }
        }
    }

    private static boolean otherTestAnnotation(String annotation) {
        return annotation.equals("Lorg/junit/Test;")
                || annotation.equals("Lorg/junit/jupiter/api/TestFactory;")
                || annotation.equals("Lorg/junit/jupiter/api/TestTemplate;")
                || annotation.equals("Lorg/junit/jupiter/api/RepeatedTest;")
                || annotation.equals("Lorg/junit/jupiter/params/ParameterizedTest;");
    }

    private static void skipMembers(DataInputStream in) throws IOException {
        for (int i = in.readUnsignedShort(); i > 0; i--) {
            in.skipNBytes(6);
            annotations(in, null);
        }
    }

    private static List<String> annotations(DataInputStream in, String[] pool) throws IOException {
        return annotations(in, pool, false);
    }

    private static List<String> annotations(DataInputStream in, String[] pool, boolean classSelector) throws IOException {
        List<String> result = new ArrayList<>();
        for (int i = in.readUnsignedShort(); i > 0; i--) {
            int nameIndex = in.readUnsignedShort();
            int length = in.readInt();
            if (length < 0) throw new IOException("Invalid class attribute length");
            byte[] data = in.readNBytes(length);
            if (data.length != length) throw new IOException("Truncated class attribute");
            if (classSelector && "NestMembers".equals(pool[nameIndex]) && length > 2) {
                throw new IllegalArgumentException("Nested classes are unsupported for a class selector");
            }
            if (pool != null && ("RuntimeVisibleAnnotations".equals(pool[nameIndex])
                    || "RuntimeInvisibleAnnotations".equals(pool[nameIndex]))) {
                try (DataInputStream attr = new DataInputStream(new java.io.ByteArrayInputStream(data))) {
                    for (int n = attr.readUnsignedShort(); n > 0; n--) readAnnotation(attr, pool, result);
                }
            }
        }
        return result;
    }

    private static void readAnnotation(DataInputStream in, String[] pool, List<String> result) throws IOException {
        result.add(pool[in.readUnsignedShort()]);
        for (int n = in.readUnsignedShort(); n > 0; n--) {
            in.readUnsignedShort();
            skipValue(in, pool);
        }
    }

    private static void skipValue(DataInputStream in, String[] pool) throws IOException {
        switch (in.readUnsignedByte()) {
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> in.readUnsignedShort();
            case 'e' -> in.skipNBytes(4);
            case '@' -> readAnnotation(in, pool, new ArrayList<>());
            case '[' -> { for (int n = in.readUnsignedShort(); n > 0; n--) skipValue(in, pool); }
            default -> throw new IOException("Invalid annotation element in class file");
        }
    }
}
