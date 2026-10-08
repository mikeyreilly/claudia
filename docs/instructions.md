# Agent instructions

Claudia combines personal defaults, per-run instructions and repository files
into the model's instruction context. These are prompt text, not a permissions
system or filesystem sandbox.

## Personal defaults

Create `~/.claudia/AGENTS.md` (`%USERPROFILE%\.claudia\AGENTS.md` on Windows)
for preferences that should apply across projects. For example:

```markdown
- Prefer code-lens for semantic code discovery.
- Keep responses concise.
- Run relevant checks before finishing code changes.
```

The default location belongs to Claudia's configuration directory; embedders
can supply different [application paths](embedding.md).

## Order and repository scope

The resolved instruction context is assembled in this order:

1. Working-directory context (and repository root when different).
2. The personal `AGENTS.md` file.
3. Per-run `--system-prompt` text.
4. Repository instruction files, from the repository root down to the active
   instruction directory.

The nearest enclosing directory containing `.git` is treated as the repository
root, including Git worktrees where `.git` is a file. Outside a Git repository,
the workspace itself is the instruction root; Claudia does not walk arbitrary
parent directories looking for instructions.

In each repository directory, a readable `AGENTS.override.md` takes precedence
over `AGENTS.md`: Claudia uses one, not both. More deeply nested files follow
the broader instructions, allowing more specific guidance.

Missing or unreadable files are ignored. An empty override file still takes
precedence over the standard file in that directory.

## Discovery during work

The current instruction scope is refreshed at the start of each prompt, so
changes to applicable files can be picked up without restarting Claudia.

Workspace tools can discover additional instruction files as they operate in
deeper descendant directories. Newly applicable instructions are loaded before
the next model request. The terminal reports a source once when it first
applies, for example:

```text
Found /home/me/project/component/AGENTS.md
```

This is instruction discovery, not a change to the tool working directory:
relative tool paths still resolve against the configured workspace. Use
[`/cd`](sessions.md#workspace-changes) to change that workspace.

[Subagents](subagents.md) inherit the workspace and instruction configuration,
but have their own conversation and instruction-discovery state.

## Safety

Instruction files can guide the agent but cannot enforce access restrictions.
Only load instructions from sources you trust. Plan-mode constraints are also
model instructions, not tool permissions; see [planning](planning.md).

## Implementation pointers

- [AgentInstructionResolver](../src/main/java/com/quaxt/claudia/AgentInstructionResolver.java): ordering, root discovery, overrides and best-effort reads.
- [ClaudiaOperations](../src/main/java/com/quaxt/claudia/ClaudiaOperations.java): prompt refresh and descendant discovery through workspace tools.

## See also

- [Developer guide](developer.md)
- [Sessions and workspaces](sessions.md)
- [Embedding](embedding.md)
