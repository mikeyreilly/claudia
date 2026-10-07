# Java debugger tools: shared concepts

The `debug_*` tools are thin wrappers around
[`DebugManager`](../../src/main/java/com/quaxt/claudia/debug/DebugManager.java),
which uses JDI (the Java Debug Interface) to control Java programs. The
model-facing definitions live in
[`DebugTools`](../../src/main/java/com/quaxt/claudia/debug/DebugTools.java).
Each tool sends its raw JSON arguments to `DebugManager.call(name, …)` and
returns the JSON response. A response with `"status": "error"` is marked as a
tool error.

## Availability

The tools work only when Claudia runs on a JVM (`java -jar claudia.jar`). In the
GraalVM native executable, every call returns `unsupported`.

## Sessions

- `debug_launch` or `debug_attach` creates a **session** and returns its
  `session_id`. Every other tool except `debug_sessions` requires a
  `session_id`.
- A session's `ownership` is either `launched` or `attached`. Only a launched
  target can be terminated by `debug_detach`.
- At most 8 sessions can be active at once, and at most 64 can be retained in
  total. Use `debug_detach` with `close_session: true` to free one.
- Calls are serialised: each agent's `DebugManager` handles one call at a time.

## Stops and stop-scoped IDs

- When the target is suspended, the session has a current **stop** with a
  `stop_id`. You can get it from `debug_status`, `debug_wait`, or the result of
  an execution tool.
- The inspection tools (`debug_threads`, `debug_stack`, `debug_variables`,
  `debug_object`, `debug_source`, `debug_exception`, `debug_evaluate`) and the
  execution tools (`debug_continue`, `debug_step`, `debug_run_to`) need the
  current `stop_id`. An old one is rejected with `stale_stop`.
- `frame_id` and object `reference` values belong to one stop and expire when
  the target resumes.

## Execution and waiting

`debug_continue`, `debug_step` and `debug_run_to` resume the target. They then
wait up to `wait_ms` (0–30000, default 1000) for the next stop or exit.

- If nothing happens in time, the result is `running` with
  `wait_expired: true`. The target keeps running; the tool does not pause or
  terminate it.
- Cancelling the wait also leaves the target running.
- Use [`debug_wait`](debug_wait.md) to wait again without changing execution.

## Results

The execution, pause and wait tools return a compact state:

- `status` and `summary`
- `session_id` and `pid`
- `output_cursor`, `output_complete` and `event_cursor`
- `stop`, when the target is stopped
- `exit_code`, `failure` and `completion_reason`, when they apply

[`debug_status`](debug_status.md) adds the launch configuration, breakpoints
and capabilities.

## Paging

List results take `limit` (1–100, default 20) and `cursor` (default 0). When
there is more, the result has `truncated: true` and a `next_cursor` to pass in
the next call.

## Idempotency

The tools that change state accept an optional `idempotency_key`. Repeating a
call with the same key and the same arguments returns the first result without
running the operation again. Reusing a key with different arguments returns
`idempotency_conflict`.

## Sensitive data

- Variables, fields and arguments whose names look like secrets (`password`,
  `secret`, `token`, `api_key`, `credential` …) are shown as `[REDACTED]` unless
  `include_sensitive_fields` is set.
- Captured output redacts the values of sensitive environment variables.
- Arbitrary values in the target's memory can still contain secrets.
