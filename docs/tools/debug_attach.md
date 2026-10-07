# `debug_attach`

Attaches the debugger to a JVM that is already running with JDWP enabled. See
[debugger concepts](debugger.md).

## Parameters

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| `pid` | integer | required | Process ID of the target JVM. |
| `consent` | boolean | `false` | Must be `true`. It confirms that the user has authorised attaching to this process. |
| `target_selector` | string | — | Not supported yet; use `pid`. |
| `source_roots` | string[] | — | Not implemented; giving it returns an error. |
| `path_mappings` | map | — | Not implemented; giving it returns an error. |
| `idempotency_key` | string | none | See [concepts](debugger.md#idempotency). |

## Behaviour

- Attaches with JDI's `ProcessAttach` connector, using the workspace as the
  session's source root.
- The session's `ownership` is `attached`:
  - `debug_detach` never terminates an attached JVM.
  - JDI cannot capture an attached process's stdout or stderr, so
    [`debug_output`](debug_output.md) returns `unsupported`. Use the target's
    own logs.
- Returns the session's full [`debug_status`](debug_status.md).

## Errors

- `permission_required`: `consent` is missing or not `true`.
- `attach_unavailable`: the process isn't a JDWP-enabled JVM you are permitted
  to attach to.
- `limit`: too many sessions.
