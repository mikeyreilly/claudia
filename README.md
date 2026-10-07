# Claudia

![Claudia](claudia.png)

Claudia is a coding agent for your terminal. I wanted my own coding
agent that I could understand and hack into whatever shape I want. I
started with [Pi](https://github.com/earendil-works/pi), translated
its most basic functionality to java and then added features as I
needed them.

It comes with a built in code intelligece mcp server,
[code-lens](code-lens/README.md), which supports c, java and clojure.

## Warning

This agent has no permissions system. So it has permssion to do
everything you can do. You should either run it in a sandbox or not
run it.

## Getting started

Install a JDK 25 or newer and Maven 3.9 or newer. Set `JAVA_HOME` or put
`java` on your `PATH`, and make sure `mvn` is on your `PATH`.

From this checkout, run the installer for your platform:

```sh
./install.sh     # macOS / Linux
```

```powershell
.\install.cmd    # Windows
```

Add `~/.local/bin` (`%USERPROFILE%\.local\bin` on Windows) to your `PATH`
if needed. Then open a terminal in the project you want to work on and run:

```sh
claudia
```

Run `/login` to sign in to a model provider, then `/models` to choose
a model. Type a request and press Enter to get started. Use `/help` to
see a list of commands and shortcuts available. Use `/exit` to quit.

## Configuration

Claudia stores your configuration in `~/.claudia/`
(`%USERPROFILE%\.claudia\` on Windows).

### MCP servers

Add servers to the `mcp` object in `~/.claudia/settings.json`. If the file
already exists, keep your other settings and merge in the server entries.
For example:

```json
{
  "mcp": {
    "local-tools": {
      "type": "local",
      "command": ["my-mcp-server", "--stdio"],
      "enabled": true
    },
    "remote-tools": {
      "type": "remote",
      "url": "https://example.com/mcp",
      "enabled": true
    }
  }
}
```

Replace the example command and URL with those supplied by your server.
Local servers run as subprocesses over standard input/output; `command`
contains the executable followed by its arguments. Use `environment` for
local server environment variables and `headers` for remote HTTP headers.
Values support `{env:NAME}` substitutions, such as `Bearer {env:MCP_TOKEN}`
for an `Authorization` header.

Restart Claudia after editing the settings, then run `/mcp` to manage servers.
Select a server and press Enter to connect, disconnect, retry, or authenticate.
Press Tab on a connected server to view its tools, then Enter to enable or
disable a tool. These choices are saved for future sessions.

### Personal instructions

Create or edit `~/.claudia/AGENTS.md` to set your personal defaults across
projects. Write your preferences as ordinary Markdown, for example:

```markdown
- Prefer code-lens to grep for code discovery for Clojure, Java and C.
- Keep responses concise.
- Follow the project's existing coding style.
- Run relevant checks before finishing code changes.
```

Claudia reads this file before each agent turn. Project `AGENTS.md` files
are loaded after your personal defaults, from the repository root through
the current directory, so you can put more specific project instructions there.

See [docs/developer.md](docs/developer.md) for how Claudia's main loop works and
for a reference to its built-in tools.

Licensed under the [MIT licence](LICENSE).
