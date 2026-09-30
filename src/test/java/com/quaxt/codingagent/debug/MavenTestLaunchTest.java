package com.quaxt.codingagent.debug;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class MavenTestLaunchTest {
    @TempDir Path project;

    @Test
    void rejectsMalformedSelectorsAndPorts() throws Exception {
        prepare();
        for (String selector : List.of("[engine:junit-jupiter]/[class:example.Fixture]", "Fixture",
                "example.*", "example.Fixture,other.Other", "example.Fixture#single,other",
                "example.Fixture#*", "example.Fixture#single()", "example.Fixture#single -Dtest=Other",
                "example.Fixture -Dtest=Other", "example.Fixture;touch /tmp/evil", "example.Fixture$Nested")) {
            assertThrows(IllegalArgumentException.class, () -> MavenTestLaunch.start(project, selector, 5005), selector);
        }
        assertThrows(IllegalArgumentException.class, () -> MavenTestLaunch.start(project, "example.Fixture#single", 0));
        assertThrows(IllegalArgumentException.class, () -> MavenTestLaunch.start(project, "example.Fixture#single", 65536));
    }

    @Test
    void rejectsMissingNonTestAndOverloadedMethodsWithoutLaunchingMaven() throws Exception {
        prepare();
        assertThrows(IllegalArgumentException.class, () -> MavenTestLaunch.start(project, "example.Fixture#missing", 5005));
        copy(SelectorFixture.class.getName(), SelectorFixture.class);
        String fixture = SelectorFixture.class.getName();
        assertThrows(IllegalArgumentException.class, () -> MavenTestLaunch.start(project, fixture + "#notATest", 5005));
        var overload = assertThrows(IllegalArgumentException.class,
                () -> MavenTestLaunch.start(project, fixture + "#overloaded", 5005));
        assertTrue(overload.getMessage().contains("found 2"), overload.getMessage());
        assertThrows(IllegalArgumentException.class, () -> MavenTestLaunch.start(project, fixture + "#parameterized", 5005));
        assertThrows(IllegalArgumentException.class, () -> MavenTestLaunch.start(project, fixture + "#factory", 5005));
    }

    @Test
    void classSelectorRejectsMissingAmbiguousAndUnsupportedTests() throws Exception {
        prepare();
        assertThrows(IllegalArgumentException.class, () -> MavenTestLaunch.start(project, "example.Missing", 5005));
        copy(SelectorFixture.class.getName(), SelectorFixture.class);
        assertThrows(IllegalArgumentException.class,
                () -> MavenTestLaunch.start(project, SelectorFixture.class.getName(), 5005));
        copy(EmptyFixture.class.getName(), EmptyFixture.class);
        assertThrows(IllegalArgumentException.class,
                () -> MavenTestLaunch.start(project, EmptyFixture.class.getName(), 5005));
        copy(OverloadedFixture.class.getName(), OverloadedFixture.class);
        assertThrows(IllegalArgumentException.class,
                () -> MavenTestLaunch.start(project, OverloadedFixture.class.getName(), 5005));
        copy(NestedFixture.class.getName(), NestedFixture.class);
        assertThrows(IllegalArgumentException.class,
                () -> MavenTestLaunch.start(project, NestedFixture.class.getName(), 5005));
    }

    @Test
    void rejectsNonJupiterRunnerAndMismatchedClassFile() throws Exception {
        prepare();
        copy(SelectorFixture.class.getName(), SelectorFixture.class);
        assertThrows(IllegalArgumentException.class, () -> MavenTestLaunch.start(project, "example.Other#single", 5005));
        copy("example.Other", SelectorFixture.class);
        assertThrows(IllegalArgumentException.class, () -> MavenTestLaunch.start(project, "example.Other#single", 5005));
        assertThrows(IllegalArgumentException.class, () -> MavenTestLaunch.start(project, "example.Other", 5005));
        copy(LegacyFixture.class.getName(), LegacyFixture.class);
        assertThrows(IllegalArgumentException.class, () -> MavenTestLaunch.start(project, LegacyFixture.class.getName() + "#single", 5005));
        assertThrows(IllegalArgumentException.class, () -> MavenTestLaunch.start(project, LegacyFixture.class.getName(), 5005));
        copy(InterfaceFixture.class.getName(), InterfaceFixture.class);
        assertThrows(IllegalArgumentException.class, () -> MavenTestLaunch.start(project, InterfaceFixture.class.getName() + "#single", 5005));
        assertThrows(IllegalArgumentException.class, () -> MavenTestLaunch.start(project, InterfaceFixture.class.getName(), 5005));
    }

    @Test
    void startsSuspendedSurefireForkWithExactSelectorAndCapturableOutput() throws Exception {
        // This project already contains the compiled fixture and Surefire/Jupiter configuration.
        Path cwd = Path.of("").toAbsolutePath();
        try (ServerSocket reserved = new ServerSocket(0)) {
            int port = reserved.getLocalPort();
            reserved.close();
            Process process = MavenTestLaunch.start(cwd, SelectorFixture.class.getName() + "#single", port);
            try {
                assertFalse(process.getInputStream() == null);
                assertFalse(process.getErrorStream() == null);
                // The Maven command is running until a debugger attaches; never allow the test to
                // execute the nested Maven run to completion inside this test suite.
                assertTrue(process.isAlive() || process.exitValue() != 0);
            } finally {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void startsSuspendedSurefireForkForOneClass() throws Exception {
        Path cwd = Path.of("").toAbsolutePath();
        try (ServerSocket reserved = new ServerSocket(0)) {
            int port = reserved.getLocalPort();
            reserved.close();
            Process process = MavenTestLaunch.start(cwd, ClassFixture.class.getName(), port);
            try {
                assertNotNull(process.getInputStream());
                assertNotNull(process.getErrorStream());
                assertTrue(process.isAlive() || process.exitValue() != 0);
            } finally {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private void prepare() throws IOException {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
    }

    private void copy(String name, Class<?> source) throws IOException {
        Path target = project.resolve("target/test-classes/" + name.replace('.', '/') + ".class");
        Files.createDirectories(target.getParent());
        try (var input = source.getResourceAsStream(source.getSimpleName() + ".class")) {
            Files.copy(input, target);
        }
    }
}

class SelectorFixture {
    @Test void single() {}
    @Test void overloaded() {}
    void overloaded(int value) {}
    void notATest() {}
    @org.junit.jupiter.params.ParameterizedTest void parameterized(String input) {}
    @org.junit.jupiter.api.TestFactory java.util.List<String> factory() { return List.of(); }
}

class ClassFixture {
    @Test void first() {}
    @Test void second() {}
}

class EmptyFixture { void helper() {} }

class OverloadedFixture {
    @Test void single() {}
    void single(int value) {}
}

class NestedFixture {
    @Test void single() {}
    @org.junit.jupiter.api.Nested class Inner { @Test void nested() {} }
}

class LegacyFixture extends LegacyBase {}

class LegacyBase {
    @Test void single() {}
}

class InterfaceFixture implements TestInterface {
    @Test void single() {}
}

interface TestInterface {
    @Test default void inherited() {}
}
