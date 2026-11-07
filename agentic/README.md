# Agentic

A provider-independent Java 17 agent-loop library extracted from Claudia.
Both Claudia and Workflow use `com.quaxt.agentic.AgentLoop.run`. Hosts supply
model calls, conversations, tools, cancellation and lifecycle events. The loop
owns sequential model/tool iteration, tool-error recovery and optional budgets.
It has no dependencies and installs no tools or credentials.

From the repository root, `mvn install` builds both modules in dependency order.
To build just this library and its parent: `mvn -pl agentic -am install`.
Java consumers depend on `com.quaxt:agentic:0.1.0-SNAPSHOT`.

Workflow's tools.deps dependency points at `../claudia/agentic` relative to its
checkout. Run `clojure -X:deps prep` in Workflow on a fresh checkout. To rebuild
Java sources through Clojure, run `clojure -X:build build/compile-java` here.
Maven and Clojure both compile into this module's `target/classes`.

Provider transports, retries, persistence and application permissions stay in
the host. Claudia retains its streaming and session adapters; Workflow stages
booking tools and commits them in one Datomic transaction after the loop finishes.
