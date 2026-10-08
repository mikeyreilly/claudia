# MCP servers

Claudia exposes tools from local and remote Model Context Protocol (MCP)
servers alongside its local tools.

See also [build and install](build.md), [RPC automation](rpc.md),
[subagents](subagents.md), and the [developer guide](developer.md).

## Built-in code-lens

[code-lens](../code-lens/README.md) is a semantic code index for Clojure, Java
and C, supplied as a built-in MCP server named `code-lens`. No server definition
is needed. With Claudia's JAR or native executable in a checkout's `target/`
directory, it locates that checkout's `code-lens/build/code-lens`
(`code-lens.exe` on Windows) and runs it with the `mcp` argument in the agent
workspace. If the executable is absent, the server is not offered. Classes-only
runs (tests/IDE) do not automatically discover it. See [installation](build.md)
for building the optional binary.

Its tools are exposed as `code-lens_query`, `code-lens_context` and
`code-lens_sql`. Query finds definitions, context resolves definitions and call
sites, and SQL provides bounded read-only access to extracted code data. For
example, a tool call can use:

```json
{"repo":"/absolute/path/to/checkout","name":"com.example.Calculator"}
```

Pass an explicit absolute `repo` when inspecting a repository other than the
server's starting workspace. See the code-lens README for full schemas and
language-specific behavior.

For a Clojure tools.deps monorepo, `--aliases :dev:reporting` forces that alias
basis on every code-lens query, context lookup and SQL read during this run.
It is a per-run override, not a saved setting:

```sh
java -jar target/claudia.jar --aliases :dev:reporting
```

`/mcp` marks this server as `code-lens (built-in)`. Server/tool toggles work as
for configured servers, but are saved under `builtInMcp`, not `mcp`:

```json
{
  "builtInMcp": {
    "code-lens": { "enabled": true, "disabledTools": ["sql"] }
  }
}
```

## Configured servers

Definitions come from the `mcp` object in `~/.claudia/settings.json`
(`%USERPROFILE%\.claudia\settings.json` on Windows). The definition shape is
OpenCode-compatible; Claudia does **not** discover OpenCode settings or
project-local MCP configuration files.

Merge definitions into your existing settings rather than replacing unrelated
settings:

```json
{
  "mcp": {
    "local-tools": {
      "type": "local",
      "command": ["npx", "-y", "@modelcontextprotocol/server-everything"],
      "environment": { "TOKEN": "{env:LOCAL_TOKEN}" },
      "enabled": true
    },
    "remote-tools": {
      "type": "remote",
      "url": "https://example.com/mcp",
      "headers": { "Authorization": "Bearer {env:MCP_TOKEN}" },
      "enabled": true,
      "timeout": 30000
    }
  }
}
```

| Field | Meaning |
| --- | --- |
| `type` | Required: `local` or `remote`. |
| `command` | Local only: nonempty array of executable and argument strings. |
| `cwd` | Optional local working directory; relative paths resolve from the agent workspace. Omitted means the workspace. |
| `environment` | Optional local environment overrides, as string values. |
| `url` | Remote only: required absolute HTTP(S) endpoint. |
| `headers` | Optional remote request headers, as string values. |
| `enabled` | Optional boolean, default `true`. |
| `timeout` | Optional positive integer request timeout in milliseconds; default 30000. |
| `disabledTools` | Optional array of raw MCP tool names to exclude. |
| `resultFilters` | Optional result filtering rules (below). |
| `oauth` | Remote OAuth configuration object, or `false` to disable discovery. |

Streamable HTTP and legacy HTTP+SSE remote servers are supported. Local servers
use stdio; use server software that speaks MCP on its protocol streams.

### Substitutions and tool names

`{env:NAME}` substitutes an environment variable (missing variables become an
empty string). `{file:path}` reads a UTF-8 file and trims surrounding whitespace;
relative file references resolve from the settings file's directory, **not**
the workspace. `~/` is supported for file references. For example:

```json
{"headers":{"Authorization":"Bearer {file:tokens/mcp-token.txt}"}}
```

File contents are JSON-escaped before parsing. Environment replacement is textual
before JSON parsing, so values containing unescaped quotes or backslashes can
make settings invalid. Do not put secrets directly into version-controlled
configuration or transcripts.

Tools are exposed as `<server>_<tool>`. Characters outside ASCII letters,
digits, `_` and `-` in either component become `_`, matching OpenCode's
sanitization. `disabledTools` and filter patterns use the **raw server tool
name**, not the prefixed model-facing name.

## Interactive connection and tool controls

Use `/mcp` to open the server list. Select a server and press Enter to connect,
disconnect, authenticate or retry it. On a connected server, Tab opens its tool
list; Enter enables/disables the selected tool. These changes require Main
selected and the entire agent group idle.

Server and tool preferences are saved to settings and restored by later
processes. Disabled tools remain disabled after reconnection in the current
process. Configured servers store overrides on their `mcp` definition;
built-ins use `builtInMcp` as shown above. Children inherit effective MCP
configuration but own independent connections; see [subagents](subagents.md).

## Result filters

Remove noisy keys recursively from JSON results with per-server `resultFilters`.
Patterns support `*` and `?`; plain-text results pass through unchanged:

```json
{
  "mcp": {
    "atlassian-mcp": {
      "type": "remote",
      "url": "https://example.atlassian.net/mcp",
      "resultFilters": [
        { "tool": "*", "dropKeys": ["avatarUrls", "self"] }
      ]
    }
  }
}
```

Each rule requires a nonempty tool pattern and nonempty `dropKeys` array of
nonempty strings. Filtering reduces result noise; it is not an access-control
policy.

## Remote OAuth

Claudia discovers OAuth from the server's `WWW-Authenticate` challenge and
well-known metadata. When `/mcp` reports `Authentication required`, select the
server and press Enter. Claudia opens the authorization page, listens for an
HTTP loopback callback, uses PKCE S256 and dynamically registers a client when
the authorization server supports registration.

Tokens and registered-client details are stored with user-only permissions in
`~/.claudia/mcp-auth.json`. Access tokens refresh automatically. Claudia can
also import a matching OpenCode credential: both the configured server name
and exact URL must match. This credential import does not import OpenCode
server configuration.

For servers requiring a pre-registered client:

```json
{
  "mcp": {
    "remote-tools": {
      "type": "remote",
      "url": "https://example.com/mcp",
      "oauth": {
        "clientId": "my-client-id",
        "clientSecret": "{env:MCP_CLIENT_SECRET}",
        "scope": "tools:read tools:write",
        "callbackPort": 19876
      }
    }
  }
}
```

`callbackPort` must be 1–65535. Instead, supply `redirectUri` as an HTTP
loopback URL without a fragment, on an address/port Claudia can listen on.
Explicit `Authorization` headers take precedence over OAuth discovery.
Set `"oauth": false` to disable it. RPC has no MCP authentication/toggle
commands; configure/authenticate servers through settings and the interactive
workflow before relying on them in [headless automation](rpc.md).

## Verification pointers

- [ClaudiaOperations](../src/main/java/com/quaxt/claudia/ClaudiaOperations.java): configuration parsing, transports, OAuth, tool exposure, filtering and saved preferences.
- [BuiltInMcpServers](../src/main/java/com/quaxt/claudia/mcp/BuiltInMcpServers.java) and [McpClient](../src/main/java/com/quaxt/claudia/mcp/McpClient.java): binary discovery and protocol/default timeout constants.
- [ClaudiaCli](../src/main/java/com/quaxt/claudia/ClaudiaCli.java): `/mcp` server/tool selector.
- [ClaudiaOperationsTest](../src/test/java/com/quaxt/claudia/ClaudiaOperationsTest.java): substitutions, invalid configuration, built-in preferences/discovery, persisted toggles and legacy SSE calls.
