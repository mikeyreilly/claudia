package com.quaxt.codingagent.cli;

/**
 * Parsed command-line arguments and the application's identity constants.
 * Argument parsing, mode dispatch, and every command implementation live in
 * CodingAgentOperations.
 */
public final class Cli {
	public static final String APP_NAME = "codingagent";
	public static final String VERSION = "0.1.0-java";

	public boolean help;
	public boolean version;
	public boolean listModels;
	public boolean print;
	public String modelSearch;
	public String provider;
	public String model;
	public String apiKey;
	public String systemPrompt = "";
	public String message = "";
	public boolean noSession;
	public String mode = "print";

	public Cli() {}
}
