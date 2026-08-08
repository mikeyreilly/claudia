package com.quaxt.codingagent.cli;

/** CLI entry point. Mirrors packages/coding-agent/src/cli.ts. */
public final class Main {
	private Main() {}

	public static void main(String[] args) {
		System.exit(Cli.run(args));
	}
}
