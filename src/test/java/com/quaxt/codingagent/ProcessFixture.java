package com.quaxt.codingagent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

/** Small external process used to verify exact arguments, streams, and process cleanup. */
public final class ProcessFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "argv" -> {
                for (int i = 1; i < args.length; i++) {
                    System.out.println(Base64.getEncoder().encodeToString(args[i].getBytes(StandardCharsets.UTF_8)));
                }
            }
            case "stderr-zero" -> System.err.println("normal stderr output");
            case "false-success" -> {
                System.out.println("BUILD SUCCESSFUL");
                System.err.println("link failed");
                System.exit(7);
            }
            case "flood" -> {
                System.out.println("O".repeat(120_000));
                System.err.println("E".repeat(120_000));
            }
            case "binary" -> System.out.write(new byte[] {(byte) 0xff, 0, 1, 2});
            case "environment" -> {
                System.out.println("SET=" + System.getenv("PROCESS_TEST_SET"));
                System.out.println("REMOVED=" + System.getenv("PROCESS_TEST_REMOVED"));
                System.out.println("INHERITED=" + System.getenv("PROCESS_TEST_INHERITED"));
            }
            case "wait" -> Thread.sleep(120_000);
            case "child" -> {
                String java = Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
                Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                        ProcessFixture.class.getName(), "wait").start();
                System.out.println("CHILD=" + child.pid());
                System.out.flush();
                Thread.sleep(120_000);
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
}
