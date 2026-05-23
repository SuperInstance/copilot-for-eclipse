# Architecture Audit: GitHub Copilot for Eclipse

**Version:** 0.18.0-SNAPSHOT  
**Date:** 2026-05-22  
**Codebase:** ~507 Java files, ~83,669 lines  
**Build:** Maven + Tycho 4.0.13, targets JavaSE-21  

---

## 1. System Overview

GitHub Copilot for Eclipse is an Eclipse IDE plugin that wraps the **Copilot Language Server (CLS)** — a proprietary binary/JS agent from Microsoft — and exposes its capabilities through Eclipse's LSP4E framework. The plugin provides:

- **Inline code completions** (ghost text)
- **Chat panel** with multi-turn conversations, multiple models, and vision support
- **Agent mode** with autonomous tool execution (file create/edit, terminal, debugger, etc.)
- **Next Edit Suggestions** (inline edits beyond just completion)
- **MCP (Model Context Protocol)** integration for external tool servers
- **BYOK (Bring Your Own Key)** for custom LLM providers
- **Coding Agent** for autonomous coding tasks
- **Git integration** (commit message generation, PR search)

The plugin is structured as an **OSGi multi-bundle** system with Tycho building platform-specific fragments for the native Copilot Language Server binary.

### Module Layout

| Module | Purpose |
|--------|---------|
| `com.microsoft.copilot.eclipse.core` | LSP connection, protocol types, auth, persistence, chat events, MCP protocol |
| `com.microsoft.copilot.eclipse.ui` | Chat view, tool implementations, completions UI, settings pages, extension points |
| `com.microsoft.copilot.eclipse.ui.jobs` | Background job infrastructure |
| `com.microsoft.copilot.eclipse.terminal.api` | Terminal SPI (`IRunInTerminalTool`, `ShellIntegrationScripts`) |
| `com.microsoft.copilot.eclipse.ui.terminal` | Modern terminal tool (TM Terminal based) |
| `com.microsoft.copilot.eclipse.ui.terminal.tm` | Alternate terminal tool |
| `com.microsoft.copilot.eclipse.core.agent.{platform}` | Platform-specific native binary fragments (5 platforms) |
| `com.microsoft.copilot.eclipse.branding` | Plugin branding |
| `com.microsoft.copilot.eclipse.feature` | Eclipse feature descriptor |
| `com.microsoft.copilot.eclipse.repository` | P2 update site |

---

## 2. LSP Layer

### 2.1 Language Server Startup (`LsStreamConnectionProvider`)

**Class:** `com.microsoft.copilot.eclipse.core.lsp.LsStreamConnectionProvider`  
**Extends:** `ProcessStreamConnectionProvider` (from LSP4E)

The startup follows a **binary-first, JS-fallback** strategy:

1. **Binary agent** — attempts to locate and launch `copilot-language-server` (or `.exe` on Windows) from platform-specific fragment bundles
2. **JS fallback** — if binary fails, falls back to running `language-server.js` via Node.js from the Wild Web Developer embedded Node
3. **Debug mode** — `COPILOT_DEBUG_LOCAL=true` env var forces JS agent with `--inspect`

Both paths append `--stdio` and enforce UTF-8 encoding.

**Binary resolution flow:**
```
getPlatformSpecificFragmentBundleId()  →  identifies correct fragment bundle
  → com.microsoft.copilot.eclipse.core.agent.linux.x64
  → com.microsoft.copilot.eclipse.core.agent.linux.aarch64
  → com.microsoft.copilot.eclipse.core.agent.win32
  → com.microsoft.copilot.eclipse.core.agent.macosx.x64
  → com.microsoft.copilot.eclipse.core.agent.macosx.aarch64

findBinaryFromFragment()  →  loads "copilot-agent" entry from fragment bundle
  → resolves to: copilot-language-server (or copilot-language-server.exe)
```

**Initialization options** sent to the server:
```java
InitializationOptions {
  editorInfo: NameAndVersion("Eclipse", <version>)
  editorPluginInfo: NameAndVersion("copilot-eclipse", <bundle-version>)
  capabilities: CopilotCapabilities {
    supportsSemanticHighlighting: false,
    workspaceContextEnabled: <feature-flag>,
    subAgentEnabled: <feature-flag>,
    supportedUriSchemes: ["file", ...]
  }
}
```

**macOS shell environment:** On macOS, it spawns `/bin/zsh -i -l -c env` with a 5-second timeout to harvest login shell environment variables. This is necessary because MCP servers launched from the agent may need PATH and other env vars from the user's shell profile.

### 2.2 Client → Server Protocol (`CopilotLanguageServer`)

**Interface:** `com.microsoft.copilot.eclipse.core.lsp.CopilotLanguageServer`  
**Extends:** `LanguageServer` (LSP4J)

All methods use `@JsonRequest` / `@JsonNotification` annotations for LSP4J's JSON-RPC serialization.

**Authentication:**
| Method | LSP Method | Purpose |
|--------|-----------|---------|
| `checkStatus(CheckStatusParams)` | `checkStatus` | Check login status |
| `signInInitiate(NullParams)` | `signInInitiate` | Start device-code OAuth flow |
| `signInConfirm(SignInConfirmParams)` | `signInConfirm` | Complete sign-in with user code |
| `signOut(NullParams)` | `signOut` | Sign out |

**Completions:**
| Method | LSP Method | Purpose |
|--------|-----------|---------|
| `getCompletions(CompletionParams)` | — | Inline code suggestions |
| `notifyShown(NotifyShownParams)` | — | Telemetry: suggestion displayed |
| `notifyAccepted(NotifyAcceptedParams)` | — | Telemetry: suggestion accepted |
| `notifyRejected(NotifyRejectedParams)` | — | Telemetry: suggestion rejected |
| `getNextEditSuggestions(...)` | `textDocument/copilotInlineEdit` | Next Edit Suggestions |

**Chat:**
| Method | LSP Method | Purpose |
|--------|-----------|---------|
| `create(ConversationCreateParams)` | `conversation/create` | Create new conversation |
| `addTurn(ConversationTurnParams)` | `conversation/turn` | Add turn to conversation |
| `listTemplates(ConversationTemplatesParams)` | `conversation/templates` | List prompt templates |
| `listModes(ConversationModesParams)` | `conversation/modes` | List chat modes (agent, ask, etc.) |
| `listAgents(NullParams)` | `conversation/agents` | List conversation agents |
| `copyCode(...)` | `conversation/copyCode` | Telemetry: code copy |
| `persistence(NullParams)` | `conversation/persistence` | Get persistence token |
| `destroy(ConversationDestroyParams)` | `conversation/destroy` | Destroy conversation |
| `registerTools(RegisterToolsParams)` | `conversation/registerTools` | Register client-side tools |
| `updateConversationToolsStatus(...)` | `conversation/updateToolsStatus` | Enable/disable built-in tools |
| `notifyCodeAcceptance(...)` | `conversation/notifyCodeAcceptance` | Code acceptance telemetry |

**MCP:**
| Method | LSP Method | Purpose |
|--------|-----------|---------|
| `updateMcpToolsStatus(...)` | `mcp/updateToolsStatus` | Enable/disable MCP tools |
| `listMcpServers(ListServersParams)` | `mcp/registry/listServers` | List MCP servers from registry |
| `getMcpServer(GetServerParams)` | `mcp/registry/getServer` | Get MCP server details |
| `getMcpAllowlist(Object)` | `mcp/registry/getAllowlist` | Get allowlist |

**BYOK:**
| Method | LSP Method | Purpose |
|--------|-----------|---------|
| `listByokModels(ByokListModelParams)` | `copilot/byok/listModels` | List BYOK models |
| `saveByokModel(ByokModel)` | `copilot/byok/saveModel` | Save BYOK model |
| `deleteByokModel(ByokModel)` | `copilot/byok/deleteModel` | Delete BYOK model |
| `saveByokApiKey(ByokApiKey)` | `copilot/byok/saveApiKey` | Save API key |
| `deleteByokApiKey(ByokApiKey)` | `copilot/byok/deleteApiKey` | Delete API key |
| `listByokApiKeys(ByokApiKey)` | `copilot/byok/listApiKeys` | List API keys |

**Other:**
| Method | LSP Method | Purpose |
|--------|-----------|---------|
| `listModels(NullParams)` | `copilot/models` | List available Copilot models |
| `generateCommitMessage(...)` | `git/commitGenerate` | Generate git commit message |
| `generateThinkingTitle(...)` | `thinking/generateTitle` | Summarize thinking block |
| `searchPr(SearchPrParams)` | `githubApi/searchPR` | Search GitHub PRs |
| `checkQuota(NullParams)` | `checkQuota` | Check usage quota |
| `sendExceptionTelemetry(...)` | `telemetry/exception` | Send to GitHub Sentry |
| `didShowInlineEdit(...)` | `textDocument/didShowInlineEdit` | Inline edit shown notification |

### 2.3 Server → Client Protocol (`CopilotLanguageClient`)

**Class:** `com.microsoft.copilot.eclipse.core.lsp.CopilotLanguageClient`  
**Extends:** `LanguageClientImpl` (LSP4E)

Handles **requests from the language server to the Eclipse client:**

| Method | LSP Method | Purpose |
|--------|-----------|---------|
| `getConversationContext(...)` | `conversation/context` | Provide current editor context |
| `invokeClientTool(...)` | `conversation/invokeClientTool` | Server requests tool execution |
| `confirmClientTool(...)` | `conversation/invokeClientToolConfirmation` | User confirmation for tool |
| `getWatchedFiles(...)` | `copilot/watchedFiles` | Return watched file list |
| `mcpTools(...)` | `copilot/mcpTools` | MCP tool change notification |
| `mcpRuntimeLogs(...)` | `copilot/mcpRuntimeLogs` | MCP runtime log notification |
| `onRateLimitWarning(...)` | `$/copilot/rateLimitWarning` | Rate limit warning |
| `onQuotaWarning(...)` | `copilot/quotaWarning` | Quota warning |
| `onDidChangeFeatureFlags(...)` | `copilot/didChangeFeatureFlags` | Server pushes feature flags |
| `onDidChangePolicy(...)` | `policy/didChange` | Enterprise policy changes |
| `onDidChangeCustomSkill(...)` | `copilot/customSkill/didChange` | Custom skill change |
| `onDidChangeCustomPrompt(...)` | `copilot/customPrompt/didChange` | Custom prompt change |
| `mcpOauth(...)` | `copilot/dynamicOAuth` | MCP OAuth flow |
| `onCodingAgentMessage(...)` | `copilot/codingAgentMessage` | Coding agent message |
| `readFile(String uri)` | `workspace/readFile` | Read file contents + stats |
| `readDirectory(String uri)` | `workspace/readDirectory` | List directory entries |
| `findFiles(FindFilesParams)` | `workspace/findFiles` | Glob file search |
| `findTextInFiles(...)` | `workspace/findTextInFiles` | Text/regex search |
| `notifyProgress(...)` | `$/progress` | Chat progress updates |
| `showDocument(...)` | `window/showDocument` | Open file/URL (overrides for browser) |

**Event propagation:** Most notifications post to Eclipse's `IEventBroker` using constants from `CopilotEventConstants`, enabling loose coupling between the LSP layer and UI.

### 2.4 Connection Layer (`CopilotLanguageServerConnection`)

**Class:** `com.microsoft.copilot.eclipse.core.lsp.CopilotLanguageServerConnection`

This is the **high-level API facade** that wraps `LanguageServerWrapper` (LSP4E) and provides typed methods for all operations. Key patterns:

- All requests go through `languageServerWrapper.execute(fn)` which queues the request to the language server
- All notifications go through `languageServerWrapper.sendNotification(fn)`
- Document lifecycle: `connectDocument(IDocument, IFile)` / `disconnectDocument(URI)`
- `SERVER_ID = "com.microsoft.copilot.eclipse.ls"`

**Conversation creation flow:**
```java
createConversation(workDoneToken, message, files, currentFile, selection,
    activeModel, reasoningEffort, chatModeName, customChatModeId,
    todos, agentSlug, agentJobWorkspaceFolder, conversationId,
    restoreToTurnId, workspaceFolders)
```

This assembles `ConversationCreateParams` with:
- Prompt as `Either<String, List<ChatCompletionContentPart>>` (supports images for vision models)
- Historical turns prepended
- Model info with optional `reasoningEffort`
- `needToolCallConfirmation = true` (client handles tool confirmations)
- Chat mode and custom mode ID
- Agent slug for sub-agent conversations
- Optional `conversationId` + `restoreToTurnId` for session restoration

---

## 3. Chat System

### 3.1 Conversations & Turns

**Conversation lifecycle:**
1. `create()` → `ChatCreateResult { conversationId, turnId, agentSlug, modelName, modelProviderName, billingMultiplier }`
2. Server streams `$/progress` notifications with `ChatProgressValue` containing incremental reply content
3. `addTurn()` adds subsequent user messages
4. `destroy()` terminates in-progress processing

**Chat modes** (from `conversation/modes`):
- Built-in: Agent, Ask (etc.)
- Custom: User-defined via `CustomChatMode` → supports tool configuration, custom model, hand-offs

**Conversation agents:** Currently hard-coded to only expose `@project` agent (slug: `"project"`). The `@github` agent is not yet enabled.

### 3.2 Progress & Streaming

The server sends `$/progress` notifications with `ChatProgressValue` containing:
- Incremental reply text
- Tool calls and their results
- Thinking blocks with collapsible summaries
- Step status updates
- Todo list updates

These are routed through `ChatEventsManager.notifyProgress()` → `IEventBroker` → UI update.

### 3.3 Templates & Customization

- **Conversation templates** (`conversation/templates`): Built-in prompt templates discovered from workspace folders
- **Custom skills** (`copilot/customSkill/didChange`): User-defined skills
- **Custom prompts** (`copilot/customPrompt/didChange`): User-defined prompt files

---

## 4. Tool System

### 4.1 Architecture

**Tool registration flow:**
```
AgentToolService.registerDefaultTools()
  → Creates tool instances (CreateFileTool, EditFileTool, GetErrorsTool, JavaDebuggerToolAdapter)
  → registerToolWithServer() → lsConnection.registerTools(RegisterToolsParams)
    → Server returns List<LanguageModelToolInformation> (server's view of registered tools)
```

**Tool invocation flow:**
```
CLS sends "conversation/invokeClientTool" request
  → CopilotLanguageClient.invokeClientTool()
    → ChatEventsManager.invokeAgentTool()
      → AgentToolService.onToolInvocation()
        → BaseTool.invoke()
          → Tool executes, returns LanguageModelToolResult[]
```

**Tool confirmation flow:**
```
CLS sends "conversation/invokeClientToolConfirmation" request
  → CopilotLanguageClient.confirmClientTool()
    → ChatEventsManager.confirmAgentToolInvocation()
      → AgentToolService.onToolConfirmation()
        → Finds TurnWidget in ChatView
        → activeTurnWidget.requestToolExecutionConfirmation()
        → User sees confirmation dialog → approve/deny
        → Returns LanguageModelToolConfirmationResult { APPROVE / DENY / DISMISS }
```

### 4.2 BaseTool Interface

```java
abstract class BaseTool {
  String name;
  CompletableFuture<LanguageModelToolResult[]> invoke(Map<String, Object> input, ChatView chatView);
  LanguageModelToolInformation getToolInformation();    // includes InputSchema, confirmation messages
  boolean needConfirmation();                           // default: false
  ConfirmationMessages getConfirmationMessages();
}
```

### 4.3 Built-in Tools

| Tool | Class | Confirmation | Purpose |
|------|-------|-------------|---------|
| `create_file` | `CreateFileTool` | ✅ | Create new files with content |
| `edit_file` | `EditFileTool` | ✅ | Edit existing files with diff |
| `get_errors` | `GetErrorsTool` | ❌ | Get diagnostic errors for files |
| `java_debugger` | `JavaDebuggerToolAdapter` | ✅ | Full Java debugger control (1334 lines) |
| `run_in_terminal` | `RunInTerminalToolAdapter` | ✅ | Execute commands in terminal |
| `get_terminal_output` | `GetTerminalOutputTool` | ❌ | Retrieve terminal output |

### 4.4 JavaDebuggerToolAdapter

This is the most complex tool (1334 lines). It provides **complete Java debug session control**:

**Actions:** `get_state`, `get_variables`, `get_stack_trace`, `evaluate_expression`, `set_variable`, `set_breakpoint`, `remove_breakpoint`, `list_breakpoints`, `set_exception_breakpoint`, `remove_exception_breakpoint`, `step_over`, `step_into`, `step_out`, `continue`, `suspend`

**Key features:**
- Conditional breakpoints with Java boolean expressions
- Hit count breakpoints
- Expression evaluation using JDT's `IAstEvaluationEngine` with 5-second timeout
- Nested variable inspection with configurable depth
- Thread selection by unique ID
- Automatic fallback thread selection (first suspended thread)
- Exception breakpoints (caught/uncaught)
- Variable modification for primitives, strings, and null

**Only registered when:** `JdtUtils.isJdtDebugAvailable() && PlatformUtils.isNightly()`

### 4.5 File Tool Service

`FileToolService` manages the "working set" — files created or edited by the agent that await user review. It provides:
- `WorkingSetBar` UI showing pending file changes
- Keep/undo/diff-view operations per file
- Batch keep-all / undo-all
- Observables (`IObservableValue`) for reactive UI updates

---

## 5. Completion System

### 5.1 Inline Code Completions

**Core:** `CompletionProvider` → `CopilotLanguageServerConnection.getCompletions(CompletionParams)`  
**UI:** `CompletionManager` manages ghost text rendering in editors

Ghost text types:
- `EolGhostText` — end-of-line suggestions
- `InlineGhostText` — inline suggestions
- `GhostText` — base ghost text abstraction
- `BlockGhostText` — multi-line block suggestions
- `LineEndGhostText` / `LineContentGhostText` — code mining-based

### 5.2 Next Edit Suggestions (NES)

**Request:** `textDocument/copilotInlineEdit` → `NextEditSuggestionsResult`  
**Notification:** `textDocument/didShowInlineEdit` (telemetry)  
**Accept:** `github.copilot.didAcceptNextEditSuggestionItem` workspace command

---

## 6. Terminal Integration

### 6.1 SPI Architecture

**API bundle:** `com.microsoft.copilot.eclipse.terminal.api`
- `IRunInTerminalTool` — interface for terminal implementations
- `ShellIntegrationScripts` — shell integration markers for output capture
- `TerminalServiceManager` — singleton service locator with listener pattern

**Two implementations:**
1. `com.microsoft.copilot.eclipse.ui.terminal.RunInTerminalTool` — modern, TM Terminal based (OSGi `@Component`)
2. `com.microsoft.copilot.eclipse.ui.terminal.tm.RunInTerminalTool` — alternate

### 6.2 Terminal Tool Adapter

`RunInTerminalToolAdapter` wraps `IRunInTerminalTool` as a `BaseTool`:
- Executes commands in a dedicated "Copilot" terminal tab
- Supports background execution with `executionId` tracking
- Uses shell integration markers to detect command completion
- `GetTerminalOutputTool` retrieves output from background commands

---

## 7. MCP (Model Context Protocol)

### 7.1 Protocol Types

**Package:** `com.microsoft.copilot.eclipse.core.lsp.mcp`

| Class | Purpose |
|-------|---------|
| `McpServerToolsCollection` | Server → tool mappings |
| `McpServerStatus` | Server status enum |
| `McpRegistryAllowList` | User/org allowlist |
| `McpRegistryEntry` | Registry entry |
| `McpRegistryOwner` | Registry ownership |
| `McpOauthRequest` | OAuth flow data |
| `McpRuntimeLog` | Runtime log events |
| `McpPrompt` / `McpResource` / `McpResourceTemplate` | MCP primitives |
| `McpToolsStatusCollection` / `McpServerToolsStatusCollection` | Tool status tracking |

**Registry sub-package:** `com.microsoft.copilot.eclipse.core.lsp.mcp.registry`
- `ServerList`, `ServerResponse`, `ServerDetail` — registry API responses
- `StdioTransport`, `SseTransport`, `UrlBasedTransport` — transport types
- `TransportType` — enum of transport types

### 7.2 MCP Lifecycle

1. **Discovery:** `listMcpServers(ListServersParams)` → fetches from Copilot MCP registry
2. **Tool notification:** Server sends `copilot/mcpTools` → `CopilotLanguageClient.mcpTools()` → posts to event broker
3. **Status management:** `updateMcpToolsStatus(UpdateMcpToolsStatusParams)` → enable/disable individual tools
4. **OAuth:** `copilot/dynamicOAuth` → `IMcpConfigService.mcpOauth()` → shows dialog, returns credentials
5. **Runtime logs:** `copilot/mcpRuntimeLogs` → forwarded to UI for display

### 7.3 MCP Configuration Service

`IMcpConfigService` (from `IChatServiceManager.getMcpConfigService()`):
- Manages MCP server configurations
- Handles OAuth flows
- Provides MCP server configs for persistence

---

## 8. BYOK (Bring Your Own Key)

Supports plugging in external LLM providers (OpenAI, Anthropic, etc.) with your own API keys.

**Protocol types:** `com.microsoft.copilot.eclipse.core.lsp.protocol.byok`
- `ByokModel` — model definition (provider, model ID, capabilities)
- `ByokApiKey` — API key (provider, key reference)
- `ByokListModelParams` / `ByokListModelResponse` — list models
- `ByokStatusResponse` — operation result
- `ByokListApiKeyResponse` — key listing

**Server methods:** `listByokModels`, `saveByokModel`, `deleteByokModel`, `saveByokApiKey`, `deleteByokApiKey`, `listByokApiKeys`

**Feature flag:** `FeatureFlags.isByokEnabled()` — controlled by server via `copilot/didChangeFeatureFlags`

---

## 9. Coding Agent

**Protocol types:** `com.microsoft.copilot.eclipse.core.lsp.protocol.codingagent`

The coding agent enables autonomous coding tasks:

```java
CodingAgentMessageRequestParams {
  String title;
  String description;
  String prLink;
  String conversationId;
  String turnId;
}

CodingAgentMessageResult {
  boolean success;
  String error;
}
```

**Flow:**
1. Server sends `copilot/codingAgentMessage` request to client
2. `CopilotLanguageClient.onCodingAgentMessage()` → posts to event broker as `TOPIC_CHAT_CODING_AGENT_MESSAGE`
3. Client returns `CodingAgentMessageResult { success: true }`
4. UI renders the coding agent message in the chat

This is a **server-initiated request** — the coding agent runs server-side and sends status updates to the client.

---

## 10. Persistence

### 10.1 Architecture

**Package:** `com.microsoft.copilot.eclipse.core.persistence`

| Class | Purpose |
|-------|---------|
| `ConversationPersistenceManager` | Business logic, lifecycle, state management |
| `ConversationPersistenceService` | File I/O (XML index + JSON per-conversation) |
| `ConversationDataFactory` | Creates data objects from progress events |
| `ConversationData` | Full conversation state |
| `ConversationXmlData` | Summary for index (conversationId, title, creationDate, lastMessageDate) |
| `AbstractTurnData` | Base for turn data |
| `UserTurnData` | User message with attached files |
| `CopilotTurnData` | Copilot reply with ReplyData, ToolCallData, ThinkingBlockData, EditAgentRoundData |
| `Turn` | Protocol-level turn representation |

### 10.2 Storage Format

- **Index:** `conversation_index.xml` in Eclipse metadata area
  - XML structure: `<user><conversations><conversation id="" title="" created="" lastMessage=""/></conversations></user>`
  - Scoped per user (GitHub account)
- **Per-conversation:** JSON files in `conversations/` folder
  - Contains full turn history with tool calls, thinking blocks, code references
  - Max 256 conversations persisted

### 10.3 Conversation Restoration

Conversations can be restored from history by passing `conversationId` + `restoreToTurnId` in `ConversationCreateParams`. The server re-fetches the conversation state and the client replays the turns.

---

## 11. Extension Points

### 11.1 `mcpRegistration` Extension Point

**Defined in:** `com.microsoft.copilot.eclipse.ui/plugin.xml`

```xml
<extension-point
    id="mcpRegistration"
    name="MCP Registration"
    schema="schema/mcpRegistration.exsd" />
```

**Interface:** `IMcpRegistrationProvider`
```java
public interface IMcpRegistrationProvider {
    CompletableFuture<String> getMcpServerConfigurations();
    // Returns JSON: {"servers":{"serverName1":{...config...}}}
}
```

Third-party plugins can implement this interface and register via the extension point to provide MCP server configurations to Copilot.

### 11.2 Chat Modes

**`BaseChatMode`** (abstract):
```java
abstract class BaseChatMode {
    String id, displayName, description;
    List<String> tools;
    String model;
    List<HandOff> handOffs;
    abstract boolean allowsToolConfiguration();
    abstract boolean isBuiltIn();
}
```

**`CustomChatMode`** extends `BaseChatMode`:
- Loaded from `conversation/modes` API response
- `allowsToolConfiguration() = true`
- `isBuiltIn() = false`
- Can specify custom tools list, model, and hand-off targets

### 11.3 Feature Flags (Server-Controlled)

`FeatureFlags` class tracks server-pushed capabilities:

| Flag | Default | Purpose |
|------|---------|---------|
| `agentModeEnabled` | `true` | Agent mode availability |
| `mcpEnabled` | `true` | MCP feature availability |
| `byokEnabled` | `true` | BYOK feature availability |
| `clientPreviewFeatureEnabled` | `true` | Preview features |
| `mcpContributionPointEnabled` | `false` | MCP extension point (policy-gated) |
| `subAgentPolicyEnabled` | `true` | Sub-agent availability |
| `customAgentPolicyEnabled` | `true` | Custom agent availability |

Updated via `copilot/didChangeFeatureFlags` and `policy/didChange` notifications.

---

## 12. Agent Binary

### 12.1 Platform Fragments

Five platform-specific OSGi fragment bundles ship the native `copilot-language-server` binary:

| Bundle ID | OS | Arch | Binary |
|-----------|-----|------|--------|
| `com.microsoft.copilot.eclipse.core.agent.linux.x64` | Linux | x86_64 | `copilot-agent/copilot-language-server` |
| `com.microsoft.copilot.eclipse.core.agent.linux.aarch64` | Linux | aarch64 | `copilot-agent/copilot-language-server` |
| `com.microsoft.copilot.eclipse.core.agent.win32` | Windows | x86_64 | `copilot-agent/copilot-language-server.exe` |
| `com.microsoft.copilot.eclipse.core.agent.macosx.x64` | macOS | x86_64 | `copilot-agent/copilot-language-server` |
| `com.microsoft.copilot.eclipse.core.agent.macosx.aarch64` | macOS | aarch64 | `copilot-agent/copilot-language-server` |

Binary is made executable via `File.setExecutable(true)` if needed. On Linux, there's a retry with 1-second delay (workaround for filesystem race condition).

### 12.2 Fallback JS Agent

If no binary is found, falls back to:
1. Locate Node.js from Wild Web Developer's `NodeJSManager`
2. Find `language-server.js` from `copilot-agent/dist` bundle entry
3. Run: `node [--inspect] language-server.js --stdio`

Env var `COPILOT_LS_JS_PATH` can override the JS path.

---

## 13. Integration Surface (Constraint Ecosystem Hooks)

### Where constraints can plug in:

1. **Tool confirmation (`AgentToolService.onToolConfirmation`)** — All tool executions requiring confirmation flow through here. This is the gate for constraint enforcement before tool execution.

2. **Tool invocation (`AgentToolService.onToolInvocation`)** — Every tool call from the server passes through here. Pre/post conditions can be added.

3. **MCP tool registration/update (`CopilotLanguageServer`)** — `registerTools()`, `updateMcpToolsStatus()`, and `updateConversationToolsStatus()` control which tools are available.

4. **Feature flags / Policy (`FeatureFlags`, `policy/didChange`)** — Enterprise policies already gate features. Additional constraints can be enforced here.

5. **Chat mode tool configuration (`BaseChatMode.tools`)** — Each mode has a tool allowlist. Constraints can filter tools per mode.

6. **MCP extension point (`mcpRegistration`)** — Third-party MCP servers enter through this extension point. Validation/filtering can be applied.

7. **File operations (`FileToolService`)** — All file create/edit operations are tracked. Pre-commit hooks can validate changes.

8. **Terminal execution (`IRunInTerminalTool`)** — Command execution can be intercepted.

9. **Debugger operations (`JavaDebuggerToolAdapter`)** — All 15 debug actions flow through `executeAction()` switch.

10. **Conversation persistence (`ConversationPersistenceManager`)** — Conversation storage/retrieval can be augmented.

11. **`CopilotLanguageClient` notifications** — All server→client notifications can be intercepted via `IEventBroker` subscribers.

---

## 14. Low-Level Metal (Wire Protocol)

### 14.1 Transport

- **JSON-RPC 2.0** over **stdio** (the only transport)
- Implemented via LSP4J's `LanguageServerWrapper` which manages the process lifecycle
- All messages are UTF-8 encoded

### 14.2 Request/Response Pattern

Requests use LSP4J's `@JsonRequest` annotation → method name derived from Java method name unless specified (e.g., `@JsonRequest("conversation/create")`).

Responses return `CompletableFuture<T>` — LSP4J handles the JSON-RPC correlation.

### 14.3 Key Protocol Messages

**Client → Server (requests):**
```
conversation/create       → ChatCreateResult
conversation/turn         → ChatTurnResult
conversation/templates    → ConversationTemplate[]
conversation/modes        → ConversationMode[]
conversation/registerTools → LanguageModelToolInformation[]
copilot/models            → CopilotModel[]
copilot/byok/*            → Byok responses
mcp/registry/*            → MCP registry responses
textDocument/copilotInlineEdit → NextEditSuggestionsResult
git/commitGenerate        → GenerateCommitMessageResult
```

**Server → Client (requests):**
```
conversation/context                → Object[]
conversation/invokeClientTool       → LanguageModelToolResult[]
conversation/invokeClientToolConfirmation → Object[]
copilot/watchedFiles                → GetWatchedFilesResponse
copilot/dynamicOAuth                → Map<String, String>
copilot/codingAgentMessage          → CodingAgentMessageResult
workspace/readFile                  → ReadFileResult
workspace/readDirectory             → ReadDirectoryResult
workspace/findFiles                 → FindFilesResult
workspace/findTextInFiles           → FindTextInFilesResult
```

**Server → Client (notifications):**
```
copilot/mcpTools
copilot/mcpRuntimeLogs
$/copilot/rateLimitWarning
copilot/quotaWarning
copilot/didChangeFeatureFlags
policy/didChange
copilot/customSkill/didChange
copilot/customPrompt/didChange
$/progress (with ChatProgressValue)
```

### 14.4 ConversationCreateParams Wire Format

```json
{
  "workDoneToken": "string",
  "turns": [{
    "prompt": "string | ChatCompletionContentPart[]",
    "agentSlug": "string?",
    "response": null
  }],
  "capabilities": { "skills": ["currentEditor"] },
  "computeSuggestions": true,
  "textDocument": { "uri": "file:///..." },
  "selection": { "start": {...}, "end": {...} },
  "references": [...],
  "source": "panel",
  "workspaceFolder": "file:///...",
  "workspaceFolders": [...],
  "model": "gpt-4",
  "modelProviderName": "openai",
  "modelInfo": { "id": "...", "providerName": "...", "reasoningEffort": "medium" },
  "chatMode": "agent",
  "customChatModeId": "...",
  "needToolCallConfirmation": true,
  "todoList": [...],
  "conversationId": "uuid?",
  "restoreToTurnId": "uuid?"
}
```

### 14.5 Tool Registration Wire Format

```json
{
  "tools": [{
    "name": "create_file",
    "description": "...",
    "inputSchema": {
      "type": "object",
      "properties": { ... },
      "required": [...]
    },
    "confirmationMessages": {
      "title": "...",
      "message": "..."
    }
  }]
}
```

### 14.6 Tool Invocation Wire Format

**Server → Client request:**
```json
{
  "conversationId": "uuid",
  "turnId": "uuid",
  "name": "edit_file",
  "input": { "file": "/path/to/File.java", "content": "..." }
}
```

**Client → Server response:**
```json
[{
  "content": ["result text"],
  "status": "success | error | cancelled"
}]
```

---

## Summary

This is a sophisticated LSP client that wraps GitHub's Copilot Language Server. The architecture is clean:

- **LSP4E** handles the JSON-RPC transport and document synchronization
- **CopilotLanguageServer** / **CopilotLanguageClient** define the full protocol surface
- **CopilotLanguageServerConnection** provides the high-level API
- **AgentToolService** manages the tool lifecycle (registration, invocation, confirmation)
- **BaseTool** is the SPI for all tools — new tools just extend it
- **MCP** extends the tool surface to external servers
- **Persistence** uses XML index + JSON files, scoped per GitHub user
- **Feature flags** and **enterprise policies** gate capabilities server-side

The main extension points for constraint enforcement are the tool confirmation flow, the MCP registration extension point, and the feature flag system.
