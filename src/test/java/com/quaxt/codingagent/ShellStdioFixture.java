package com.quaxt.codingagent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

/** Real subprocess used to exercise prompts, literal stdin, EOF, and process cleanup. */
public final class ShellStdioFixture {
    public static void main(String[] args) throws Exception {
        var output = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        switch (args[0]) {
            case "prompt" -> {
                output.print("Paste a link: ");
                output.flush();
                output.println("Received: " + input.readLine());
                output.print("Label: ");
                output.flush();
                output.println("Label received: " + input.readLine());
            }
            case "eof" -> {
                output.print("Send text, then EOF: ");
                output.flush();
                String line;
                while ((line = input.readLine()) != null) output.println("Received: " + line);
                output.println("EOF received");
            }
            case "wait" -> {
                output.println("PID=" + ProcessHandle.current().pid());
                Thread.sleep(120_000);
            }
            case "flood" -> {
                output.print("x".repeat(200_000));
                output.print("\nlog\n".repeat(3000));
                output.print("Paste a link after the log: ");
                output.flush();
                input.readLine();
                output.println("Done");
            }
            case "fail" -> {
                output.println("Script failed");
                System.exit(7);
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    static String command(String mode) throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
        String classes = Path.of(ShellStdioFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return (windows ? "& " : "") + quote(java, windows) + " -cp " + quote(classes, windows)
                + " com.quaxt.codingagent.ShellStdioFixture " + mode;
    }

    private static String quote(String value, boolean windows) {
        return "'" + value.replace("'", windows ? "''" : "'\"'\"'") + "'";
    }
}
