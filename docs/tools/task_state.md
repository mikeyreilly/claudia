# `task_state`

Keeps a per-agent list of tasks, findings and constraints outside the
conversation. Because it is not part of the conversation, compaction does not
summarise it away, and it is restored when a session is resumed.

Defined in [`LocalTools.taskState()`](../../src/main/java/com/quaxt/claudia/cli/tools/LocalTools.java);
state is held in
[`TaskState`](../../src/main/java/com/quaxt/claudia/cli/tools/TaskState.java).

## Parameters

| Name | Type | Used by | Description |
| --- | --- | --- | --- |
| `action` | enum | all | One of `add_task`, `update_task`, `remove_task`, `list`, `add_finding`, `remove_finding`, `add_constraint`, `remove_constraint`. |
| `id` | string | update/remove | `#N` for a task, `FN` for a finding, `CN` for a constraint. Leave it out for add and list actions. |
| `description` | string | `add_task` (required), `update_task` | Task text. |
| `status` | enum | `add_task`, `update_task` | `todo` (the default for new tasks), `in_progress`, `done` or `cancelled`. |
| `note` | string | `add_task`, `update_task` | Free-form note. An empty string clears it. |
| `depends_on` | string[] | `add_task`, `update_task` | IDs of tasks this one depends on. An empty list clears them. |
| `text` | string | `add_finding`, `add_constraint` | Text of the finding or constraint. |

The schema sent to the model has a `oneOf` with one branch per action. Each
branch lists only the fields that action accepts, and checks ID patterns
(`^#[1-9][0-9]*$` and so on).

## Behaviour

- IDs are assigned automatically and never reused within a session.
- `update_task` must change at least one field. Dependencies must refer to
  existing tasks, and a change that would create a dependency cycle is rejected.
- `remove_task` also removes the task from other tasks' `depends_on` lists.
- `list` shows every task, finding and constraint with its ID and details.
- Each change is validated against a copy of the state before it replaces the
  real state, so an invalid call changes nothing.
- Each valid change is saved to the session journal.
- Each agent, including each child, has its own state.
- `/fork` copies the state into the new session.

The Build and Plan mode instructions both tell the model to use `task_state` for
substantial work. They say to check `list` after compaction or resume and before
declaring the work complete, but not to call it after every action.

## UI summary

`Task state: <action>`
