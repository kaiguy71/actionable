# Configuration

Set these JVM arguments in the Minecraft launcher profile:

| Property | Default |
| --- | --- |
| `-Dactionable.model.provider` | `ollama` (`claude` selects Claude Code print mode) |
| `-Dactionable.ollama.url` | `http://localhost:11434/api/chat` |
| `-Dactionable.ollama.model` | `gemma3:4b` |
| `-Dactionable.claude.command` | `claude` (executable name or full path; no extra arguments) |
| `-Dactionable.claude.model` | `claude-sonnet-5-5` |

## Ollama

The default provider expects Ollama at the configured URL and the selected model to be installed.
For example, `ollama pull gemma3:4b` installs the default model. Ollama runs as a separate local
service rather than inside the mod JAR because model files and inference runtimes are large, and GPU
support depends on native platform-specific libraries. A 4B model such as `gemma3:4b` is a practical
starting point on consumer hardware.

If using a thinking model such as `qwen3.5:9b`, note that it can spend thousands of tokens reasoning
before answering. One measured over two minutes per planning call, compared with a 10-second
planning cadence. Making it usable requires adding `"think": false` to the request body in
`ActionPlanner`.

## Claude Code

Install the native Claude Code CLI and run `claude auth login` in a terminal using the same account
that launches Minecraft. Verify that the account can use the model:

```powershell
claude -p --model claude-sonnet-5-5 "Reply with OK"
```

Set the provider and model in the launcher profile, then restart Minecraft:

```text
-Dactionable.model.provider=claude -Dactionable.claude.model=claude-sonnet-5-5
```

Ollama is not needed in Claude mode. If Minecraft cannot find `claude`, set the full native
executable path, for example
`"-Dactionable.claude.command=C:\Users\YOUR_NAME\.local\bin\claude.exe"`. On Windows, use the native
executable; PowerShell aliases and npm `.cmd` shims cannot be launched directly. The
`actionable.claude.model` property can be set to any model ID accepted by the CLI.

Each of the four planning layers starts a fresh `claude -p` request on the background planner thread.
Prompts use UTF-8 files/stdin, a JSON Schema for each stage, and validated `structured_output`
replies. Built-in tools are disabled, the MCP configuration is empty, and no session is persisted.
Each request has a 180-second timeout. Claude receives the same world snapshot and action history as
Ollama and uses the account's usage limits. The CLI runs in a temporary directory and loads user
settings rather than Minecraft project settings. Action allowlists and validation still apply.

See the [Claude Code CLI reference](https://code.claude.com/docs/en/cli-reference) and
[Claude model configuration](https://support.claude.com/en/articles/11940350-claude-code-model-configuration).
