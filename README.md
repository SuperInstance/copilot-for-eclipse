# GitHub Copilot for Eclipse

GitHub Copilot for Eclipse brings AI-assisted coding to the Eclipse IDE. This is a fork maintained by **SuperInstance** that adds Constraint Theory integration via MCP and a multi-model BYOK routing system.

## What This Fork Adds

### Constraint Theory MCP Server (`com.superinstance.constraint.copilot`)

A bundled Eclipse plugin that registers a Python MCP (Model Context Protocol) server with Copilot. The server exposes 6 tools:

| Tool | Description |
|------|-------------|
| `constraint_snap` | Snap pitch to the Eisenstein A₂ lattice |
| `constraint_funnel` | Gravitational pull toward a target constraint |
| `constraint_diagnose` | 4-order Goodman diagnostic for constraint health |
| `constraint_generate` | Generate music in a given mode + terrain |
| `constraint_render` | Render notes to WAV audio |
| `constraint_terrain_list` | List available musical terrains |

The MCP server is discovered automatically via `ConstraintMcpProvider` (implements `IMcpRegistrationProvider`). It locates the `constraint-mcp-server` Python package on `PYTHONPATH` and connects via stdio JSON-RPC. If the server is not found, the plugin degrades gracefully — Copilot simply won't show those tools.

### Multi-Model BYOK Routing (`MULTI-MODEL-DESIGN.md`)

An architecture for routing code completions and chat requests to different AI models based on "constraint terrain" complexity:

- **Shallow terrain** (single-line completion, no context) → budget models (GLM-4-Flash, DeepSeek-Lite)
- **Mid terrain** (multi-line, file context) → standard models (DeepSeek-V3, GLM-4)
- **Deep terrain** (chat with tools, reasoning) → premium models (GPT-4o, Claude 3.5)
- **Abyssal terrain** (agent mode, multi-file) → flagship models (Claude 3.5 Sonnet, o3)

See [`MULTI-MODEL-DESIGN.md`](MULTI-MODEL-DESIGN.md) for the full design document.

## Core Capabilities (from upstream)

- **Code completions** — Inline ghost-text suggestions as you type
- **Next Edit Suggestions** — Predict your next edit location and propose changes
- **Agent Mode** — Autonomous project-aware assistance with tool calling
- **Ask Mode** — Conversational AI for explanations, refactors, debugging
- **MCP integration** — Connect Copilot with external tools and services
- **Custom Agents, Subagents, Plan Agent** — Advanced agentic workflows
- **BYOK (Bring Your Own Key)** — Use your own API keys for Azure, OpenAI, Gemini, Groq, OpenRouter, Anthropic

## Prerequisites

- [Eclipse IDE](https://www.eclipse.org/downloads/)
- Java 17+ (Bundle-RequiredExecutionEnvironment: JavaSE-17)
- Active [GitHub Copilot subscription](https://github.com/features/copilot)
- Python 3.10+ (for Constraint Theory MCP tools — optional)

## Install

### Eclipse Marketplace

1. Open [Eclipse Marketplace](https://marketplace.eclipse.org/) → [GitHub Copilot](https://marketplace.eclipse.org/content/github-copilot)
2. Drag **Install** to your running Eclipse workspace
3. Restart Eclipse
4. Sign in to GitHub Copilot

### Update Site

1. **Help → Install New Software…**
2. Add update site: `https://azuredownloads-g3ahgwb5b8bkbxhd.b01.azurefd.net/github-copilot/`
3. Select **GitHub Copilot**, complete installation, restart Eclipse

## Building from Source

```bash
git clone https://github.com/SuperInstance/copilot-for-eclipse.git
cd copilot-for-eclipse
./mvnw clean verify
```

The build uses Maven + Tycho 4.0.13. Modules:

| Module | Description |
|--------|-------------|
| `com.microsoft.copilot.eclipse.core` | Core plugin: LSP client, completion, chat, BYOK, persistence |
| `com.microsoft.copilot.eclipse.ui` | UI layer: chat view, inline completion, model picker, preferences |
| `com.microsoft.copilot.eclipse.ui.terminal` | Terminal integration |
| `com.superinstance.constraint.copilot` | Constraint Theory MCP registration |
| `com.superinstance.constraint.feature` | Feature packaging for the constraint plugin |
| `com.microsoft.copilot.eclipse.core.agent.*` | Platform-specific Copilot agent binaries |

## Project Structure

```
com.microsoft.copilot.eclipse.core/src/
├── lsp/                  # LSP client + protocol types
│   ├── CopilotLanguageClient.java
│   └── protocol/         # BYOK, quota, conversation, coding-agent types
├── completion/           # Inline completion provider + suggestion management
├── chat/                 # Chat modes (Ask, Agent), custom modes, MCP config
├── nes/                  # Next Edit Suggestion provider
├── persistence/          # Conversation serialization (XML-based)
├── format/               # Language-specific formatting (Java, CDT)
└── IdeCapabilities.java  # IDE feature detection

com.superinstance.constraint.copilot/src/
└── ConstraintMcpProvider.java  # MCP server discovery + registration
```

## Configuration

### Constraint Theory MCP

Set the workspace path where the constraint ecosystem lives:

```bash
# Option 1: JVM system property
-Dconstraint.workspace=/path/to/constraint-ecosystem

# Option 2: Environment variable
export CONSTRAINT_WORKSPACE=/path/to/constraint-ecosystem

# Option 3: Default fallback
~/superinstance-workspace
```

The `constraint-mcp-server` Python package must be on `PYTHONPATH` within that workspace.

### Multi-Model Routing

See [`MULTI-MODEL-DESIGN.md`](MULTI-MODEL-DESIGN.md) for the full routing configuration format (`model-routing.json`).

## Related Repos

- [constraint-theory-core](https://github.com/SuperInstance/constraint-theory-core) — Mathematical primitives for the constraint ecosystem
- [snapkit-v2](https://github.com/SuperInstance/snapkit-v2) — Eisenstein lattice snap (Python)
- [snapkit-js](https://github.com/SuperInstance/snapkit-js) — Eisenstein lattice snap (JS/TS)
- [style-dna](https://github.com/SuperInstance/style-dna) — Musical DNA extraction and style morphing

## Upstream

This is a fork of [github/copilot-for-eclipse](https://github.com/microsoft/copilot-for-eclipse) (Apache 2.0 License).

## License

Apache License 2.0
