# Agent-friendly Java debugger: interface design

## Purpose

Give a coding agent a precise, low-noise way to reproduce a Java failure, stop at the relevant code, inspect evidence, test a hypothesis, and return control to the user. The interface is a set of structured tool calls and structured results, not a terminal-oriented debugger transcript. It should work equally well for a short-lived test and a long-running service, including code running on virtual threads.

The debugger never starts, attaches to, modifies, or terminates a process without an explicit request. Observations are read-only by default; evaluating code with side effects and changing program state require explicit authorization. The agent can always identify *which process, thread, frame, source location, and stop* an observation belongs to.

## Shared contract

- Every call identifies a `session_id`. A running or suspended target is still a live session; a completed target remains available for its final status and captured output until the session is closed.
- Results have a stable `status` (`ok`, `running`, `stopped`, `completed`, or `error`), a concise human-readable `summary`, and structured data. Errors have a machine-readable `code`, actionable `message`, and, where useful, suggested valid choices. Never report a timeout or an unsupported capability as an empty result.
- A `stop_id` identifies one suspension. Stop-specific reads and execution controls take that ID and reject stale calls after execution resumes or a newer stop occurs. Frame, variable, and object references are scoped to the stop and cannot silently refer to a different state.
- Locations carry the best available `source_path`, 1-based `line`, declaring class, method signature, and bytecode location when source is unavailable. Ambiguous files, overloaded methods, and multiple loaded classes are reported as choices rather than guessed.
- All collections are bounded, searchable/filterable where useful, and paginated with `limit` and `cursor`. Cursors are nonnegative JSON integers (0 through 2147483647), not strings; omitted or zero starts at the beginning. Responses say whether results were truncated and how to fetch more. Strings and object graphs are size/depth limited, with explicit truncation markers. The default response is a focused summary, not an entire heap or stack dump.
- Calls that wait accept `wait_ms`; a wait expiring returns the current state and a way to continue waiting, without implicitly resuming, cancelling, or ending the debug session. Target output and debugger events are separate streams with ordered cursors so the agent can retrieve missed information without repeated giant responses.
- Read operations are safe to retry. State-changing operations accept an idempotency key and return the resulting state, so a retried call cannot accidentally step twice, resume twice, or launch another target.

## Start and end a session

| Operation | Agent-facing request | Result |
| --- | --- | --- |
| `debug_launch` | Explicit Java entry point **or** test selector; working directory; arguments, JVM options, environment overrides; optional classpath/module-path configuration; optional `stop_on_entry`, output capture, and launch timeout. | `session_id`, target identity, capabilities, state, output cursor, and initial stop if requested. A test selector can name one class, method, or unique test identifier; an ambiguous match is an error. |
| `debug_attach` | Target PID or unambiguous target selector; optional source roots and path mappings; explicit consent to attach. | `session_id`, target identity, capabilities, current state, and any limitations (for example, missing source or symbols). |
| `debug_sessions` / `debug_status` | List sessions or inspect one. | Ownership, target identity, running/stopped/exited state, latest stop, configured breakpoints, output cursor, exit code or failure reason where available. |
| `debug_detach` | Session and whether to leave the process running or terminate a process launched by this session. | Final session state and confirmation of what happened to the target. Attaching does not confer an implicit right to terminate it. |

Launch and attach are distinct: an agent can reproduce a test under the debugger or investigate an already running JVM without guessing how it was started. Launch requests expose their effective target and arguments for review, and never silently run a broader test suite than selected. The interface describes any prerequisite permission or target capability before attempting an unavailable operation.

## Choose where to stop

`debug_breakpoints` lists, adds, updates, enables/disables, or removes breakpoints by stable `breakpoint_id`. A request can set multiple breakpoints atomically and gets per-breakpoint verification status, resolved location(s), and pending reasons. Pending breakpoints (such as a class not yet loaded) become active when their location becomes available and generate an event. Each breakpoint may have a condition, hit count/policy, thread filter, one-shot setting, suspension policy (`all_threads` or `event_thread`), and optional log expression instead of suspension.

Supported stop specifications:

- Source location: path and line, optionally class/method to disambiguate.
- Method entry or exit: declaring class, method name, and signature for overloads.
- Exception: caught, uncaught, or both; class/type filter and optional location/thread filter. The stop identifies the throw site and the exception object, not just a console trace.
- Field access or modification: declaring class and field, with old/new values when available; clearly signal unsupported targets.
- Explicit `stop_on_entry` and manual `debug_pause`.

Breakpoints explain if an exact line cannot be bound, show the actual executable location(s), and never silently move to another line. Pending source diagnostics distinguish an unloaded class from missing source/line-number debug information, a source or method/signature mismatch, and a loaded source line with no executable location. Updated diagnostics generate `breakpoint_pending` events; unrelated class loads do not replace evidence from the matching class. Conditions and log expressions follow the evaluation safety rules below; an unsafe expression cannot run merely because it was put in a breakpoint.

## Drive execution

`debug_continue`, `debug_step`, `debug_run_to`, and `debug_pause` control execution. `debug_step` supports `into`, `over`, and `out`, a selected thread, optional class/package skip filters, and a maximum wait. `debug_run_to` accepts a precise location and is one-shot. Continue/step/run-to require the current `stop_id` and state exactly which threads will resume; pause returns a stop when it succeeds. A stop can suspend all threads (the default, yielding a coherent inspection point) or just the event thread. Per-thread stops explicitly mark other threads as still running; data from them is not presented as a coherent snapshot.

Execution calls return promptly with either a stop or `running` plus an event cursor. `debug_wait` waits for the next relevant event/stop with a bounded `wait_ms` and optional thread or event filters. Each stop reports a `reason` (`breakpoint`, `exception`, `step`, `pause`, `entry`, etc.), matching breakpoint ID if any, `stop_id`, thread identity, location, and short surrounding source excerpt when available. Skipped locations, target exit, disconnect, and wait timeout are distinguishable outcomes. `debug_events` retrieves ordered events since a cursor: stops, resumes, breakpoint resolution, exceptions, target exit, and lost-event notices. A gap is explicit rather than silently dropping evidence.

## Inspect a stop

| Operation | Questions it answers |
| --- | --- |
| `debug_threads` | Which platform or virtual threads exist? Which are stopped, running, blocked, waiting, or terminated? What are their names, IDs, top frames, lock owners, and relevant contention/deadlock relationships? Supports filters and bounded results. |
| `debug_stack` | What frames are on this thread's stack? Returns frame IDs, locations, source context, and optional compact arguments; supports paging and frame filters (including library-frame suppression). |
| `debug_variables` | What arguments, locals, `this`, and captured values are visible in a frame? Returns name, declared/runtime type, value preview, availability status, and expandable reference, with paging. Optimized-away or unavailable values are distinguished from `null`. |
| `debug_object` | What is behind a value reference? Inspect fields (including inherited/static on request), array slices, collection entries, and string ranges with bounded depth and paging. Preserve object identity and cycles without expanding them indefinitely. |
| `debug_source` | What source corresponds to this frame/location? Omitted `line` or `line: 0` uses the stopped frame's line; a positive value requests that 1-based line. Returns a small requested line window with path and line numbers, or explains why source/line information is unavailable or mismatched. |
| `debug_exception` | What exception caused this stop? Returns type, message, throw site, stack, cause/suppressed chain, and expandable exception reference with bounds. |
| `debug_evaluate` | What does an expression evaluate to in a chosen frame? Returns typed value/reference or a precise evaluation error, with a stated safety level and time limit. |
| `debug_output` | What did the target write to stdout/stderr since a cursor? Returns separately identified streams, timestamps/order where known, byte/line limits, and truncation information. |

Stop-specific inspection requests name a `stop_id` and, where relevant, `thread_id` and `frame_id`; defaults are the stop's event thread and top frame, and the response repeats the selected context. `debug_output` and session status remain available while the target runs or after it exits. Values are presented with types and concise previews; an agent opts in to expansion. Evaluations default to read-only expressions without method invocation or mutation. The supported inspection grammar includes local/`this` field paths, chained array subscripts, array `.length`, decimal numeric/boolean/null literals, escaped double-quoted string literals, and one simple comparison. Examples: `items[index].name`, `matrix[indices[0]][1]`, and `model.provider == "openai"`. Subscripts accept byte/short/char/int values and report null/type/bounds errors. Strings compare by content; other object references compare by identity. Literal strings remain debugger-side and do not allocate target objects. Syntax is fully validated before target reads, capped at 256 characters and 16 nested subscript operands, and shared by breakpoint conditions and log expressions. Calls, assignment, increment/decrement, arithmetic, and arbitrary Java evaluation are rejected. Potentially side-effecting evaluation, assignment, or other program-state changes require an explicit `allow_side_effects` choice, a visible warning, a timeout, and a result that records what was requested. If a target cannot safely provide an operation, it reports `unsupported` rather than quietly executing something riskier. Object views make no promise of a stable snapshot for threads still running.

## Example agent workflow

1. `debug_launch` one failing test, with `stop_on_entry: true`; inspect the target identity and capabilities.
2. `debug_breakpoints` adds a source breakpoint near the suspected branch and an uncaught-exception breakpoint. Check which locations actually resolved.
3. `debug_continue` using the entry `stop_id`. If it returns `running`, call `debug_wait` with the returned cursor; do not infer a stop from silence.
4. On a stop, read the reason, source excerpt, stack, selected frame's locals, and only the relevant object fields. Compare an expression in `debug_evaluate` using the read-only default.
5. `debug_step` over one statement using the current `stop_id`, inspect the new stop, or continue to exit. Retrieve final target output and exit status. `debug_detach` with an explicit target disposition.

## Safety and usability requirements


- Do not expose secrets by default: redact known sensitive environment
  values and allow callers to suppress or redact fields and output.
  Warn that arbitrary target memory and evaluation can contain
  sensitive data; never send an unbounded dump as a tool result.

- Keep launch, attach, pause, resume, mutation, terminate, and detach
  auditable as distinct actions with target identity and outcome.
  Attach permissions and mutation consent must be explicit, not
  inferred from a read request.

- Handle target death, redefinition/reload, disappearing threads,
  missing debug information, disconnected sessions, and stale
  references with specific errors and recovery guidance. No silent
  fallback to another process, frame, class, or source file.

- Make modern Java diagnosable: virtual threads and their
  carrier/blocking context when available, modules and class loaders
  to disambiguate same-named classes, and structured exception chains.
  Report capability differences rather than pretending all targets
  support every feature.

- Preserve agent control: finite waits, cancellation of a pending wait
  without affecting the target, bounded output, explicit session
  cleanup, and no automatic resume after an error. Present enough
  identifiers and provenance in each result that a later tool call can
  refer to the exact evidence it used.
