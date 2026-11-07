package com.quaxt.claudia;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Optional;

/**
 * Locates the checkout that built the running Claudia.
 *
 * <p>An in-place build (see {@code install.sh}/{@code install.ps1}) leaves Claudia's
 * jar or native executable in {@code <repo>/target/}, and its native helpers in
 * other directories of the same checkout, such as {@code <repo>/code-lens/build/}.
 */
public final class RunningBuild {
    private RunningBuild() {}

    /**
     * The jar or native executable Claudia is running from. Classes directories
     * (tests and IDE runs) are not a build, so they report none and stay hermetic.
     */
    public static Optional<Path> artifact() {
        try {
            Path location;
            if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
                Optional<String> command = ProcessHandle.current().info().command();
                if (command.isEmpty()) return Optional.empty();
                location = Path.of(command.get());
            } else {
                CodeSource source = RunningBuild.class.getProtectionDomain().getCodeSource();
                if (source == null || source.getLocation() == null) return Optional.empty();
                location = Path.of(source.getLocation().toURI());
            }
            if (!Files.isRegularFile(location)) return Optional.empty();
            try {
                // A launcher may reach the build through a symbolic link.
                return Optional.of(location.toRealPath());
            } catch (IOException error) {
                return Optional.of(location);
            }
        } catch (URISyntaxException | RuntimeException error) {
            return Optional.empty();
        }
    }

    /** The checkout {@code <repo>} of an artifact at {@code <repo>/target/<artifact>}. */
    public static Optional<Path> checkout(Path artifact) {
        Path directory = artifact.toAbsolutePath().normalize().getParent();
        return Optional.ofNullable(directory == null ? null : directory.getParent());
    }

    /** {@code relative} inside {@code artifact}'s checkout, when it is an executable file. */
    public static Optional<Path> executable(Path artifact, String... relative) {
        return checkout(artifact)
                .map(checkout -> {
                    Path file = checkout;
                    for (String name : relative) file = file.resolve(name);
                    return file;
                })
                .filter(file -> Files.isRegularFile(file) && Files.isExecutable(file));
    }
}
