# Agentic

A provider-independent Java 17 agent-loop library extracted from Claudia.
Both Claudia and Workflow use `com.quaxt.agentic.AgentLoop.run`. Hosts supply
model calls, conversations, tools, cancellation and lifecycle events. The loop
owns sequential model/tool iteration, tool-error recovery and optional budgets.
It has no dependencies and installs no tools or credentials.

From the repository root, `mvn install` builds both modules in dependency order.
To build just this library and its parent: `mvn -pl agentic -am install`.
Java consumers depend on `com.quaxt:agentic:0.1.1`.

Workflow consumes the library's Maven artifact. Install the development version
with `mvn -pl agentic -am install` from Claudia's root before starting Workflow.
The optional tools.deps manifest also supports source consumers: to compile
through Clojure, run `clojure -X:build build/compile-java` in this module.
Maven and Clojure both compile into this module's `target/classes`.

Provider transports, retries, persistence and application permissions stay in
the host. Claudia retains its streaming and session adapters; Workflow stages
booking tools and commits them in one Datomic transaction after the loop finishes.

## Authentication and Responses

`ChatGptAuth` owns the Codex device flow and token renewal extracted from
ClaudiaOperations. Hosts implement `Transport`, `Codec` and `Store`.
`begin` returns a device handle; web apps use nonblocking `poll`, while CLI
apps use `complete`. `resolve` refreshes an expired access token under the
store's lock. Device and OAuth carriers redact their string representations.

Claudia adapts its existing credential store. Workflow supplies a separate,
explicit-path `CredentialFile`, which writes atomically with owner permissions.
The library never selects another application's credential location. Hosts
must rate-limit polls and invalidate cancelled device handles before persisting
credentials. Workflow's sign-in and cancellation checks do this server-side.

`Responses.envelope` supplies the common request fields used by both hosts.
`Responses.completed` handles bounded SSE responses for hosts that consume
whole turns; an incomplete or failed stream is never a completed result.
Claudia retains its incremental streaming adapter.
