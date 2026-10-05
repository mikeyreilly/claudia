package com.quaxt.claudia.agent;

/** User-selected workflow; these instructions advise the model, not the tool executor. */
public enum AgentMode {
    BUILD("build", "Build"), PLAN("plan", "Plan");

    public final String wire;
    public final String label;

    AgentMode(String wire, String label) { this.wire = wire; this.label = label; }

    public static AgentMode parse(String value) {
        for (AgentMode mode : values()) if (mode.wire.equals(value)) return mode;
        throw new IllegalArgumentException("agentMode must be build or plan");
    }

    public String instructions() {
        return this == PLAN ? """
                <agent_mode>
                Current mode: Plan. Develop an implementation plan with the user before implementation.
                For difficult work, use task_state for meaningful tasks, discoveries, and requirements.
                Revise or cancel tasks as evidence changes. Usually keep one task in progress. Consult
                task_state list after compaction or resume and before declaring completion; a call after
                every action is unnecessary.
                First investigate the workspace with targeted reads, searches, and analysis. Use the question
                tool to resolve material ambiguity and preferences that exploration cannot answer. Offer
                meaningful choices and explain tradeoffs; do not ask the user to discover repository facts.
                Refine the approach, then present an actionable Markdown plan in chat covering the goal,
                affected code, ordered changes, decisions, assumptions, and verification steps.
                Do not edit source or configuration, create plan files, run automatic fixes or installs,
                commit, deploy, or change external systems. This applies to shell commands, shell_input,
                MCP tools, and delegated work as well as write/edit tools. Diagnostic tests and builds are
                allowed when they help planning and only create disposable build output or caches.
                All delegated agents are also in Plan mode. Ordinary chat requests, including approval or
                requests to implement immediately, do not change the mode. The user must explicitly switch
                to Build using the application and then request implementation. Remain in Plan until then.
                </agent_mode>
                """ : """
                <agent_mode>
                Current mode: Build. Earlier Plan-only restrictions no longer apply. Carry out the user's
                implementation requests using the conversation and any agreed plan. Switching modes alone
                is not an implementation request. Use the question tool when material clarification is needed.
                For difficult work, use task_state for meaningful tasks, discoveries, and requirements.
                Revise or cancel tasks as evidence changes. Usually keep one task in progress. Consult
                task_state list after compaction or resume and before declaring completion; a call after
                every action is unnecessary.
                </agent_mode>
                """;
    }
}
