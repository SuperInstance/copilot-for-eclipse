# Multi-Model Integration Design for copilot-for-eclipse

## Overview

This document describes how to extend the BYOK (Bring Your Own Key) system in copilot-for-eclipse to support a fleet of model providers (GLM/ZhipuAI, DeepSeek, local models) and implement intelligent model routing using constraint-terrain principles — cheaper models for simple terrain, expensive models for complex terrain.

## Architecture Summary

The BYOK system is an LSP-based model management layer:

```
Eclipse Plugin (Java)
  ├── ByokModelProvider enum → defines supported providers
  ├── ByokModel → model config (id, provider, capabilities, apiKey, deploymentUrl)
  ├── ByokApiKey → provider credential storage
  ├── ByokService (UI) → orchestrates API keys + model CRUD via LSP
  ├── ByokPreferencePage → SWT UI for managing providers/models
  ├── ModelService → selects active chat model from CopilotModel list
  └── CopilotLanguageServerConnection → LSP calls to copilot/byok/* endpoints
          ↓
  Copilot Language Server (Node.js) — handles actual API routing
```

Key insight: **The Eclipse plugin is a thin client.** All actual model API calls go through the Copilot Language Server. The plugin sends `ByokModel` configurations via LSP (`copilot/byok/saveModel`, `copilot/byok/listModels`, etc.), and the language server handles provider-specific API translation.

---

## Part 1: Adding New Providers

### File: `ByokModelProvider.java`

**Path:** `com.microsoft.copilot.eclipse.core/src/.../lsp/protocol/byok/ByokModelProvider.java`

Add new enum values:

```java
public enum ByokModelProvider {
  AZURE("Azure"),
  OPENAI("OpenAI"),
  GEMINI("Gemini"),
  GROQ("Groq"),
  OPENROUTER("OpenRouter"),
  ANTHROPIC("Anthropic"),
  // --- New providers ---
  ZHIPUAI("ZhipuAI"),       // GLM models (glm-4, glm-4-flash, etc.)
  DEEPSEEK("DeepSeek"),     // DeepSeek models (deepseek-chat, deepseek-coder)
  LOCAL("Local");           // Local/self-hosted models (Ollama, vLLM, etc.)

  // ... existing code unchanged ...
}
```

### Supporting Files (no changes needed)

The following files are **provider-agnostic** and need no modification:
- `ByokModel.java` — uses `providerName` (String), not the enum directly
- `ByokApiKey.java` — uses `providerName` (String)
- `ByokModelCapabilities.java` — generic (name, maxTokens, toolCalling, vision)
- `ByokStatusResponse.java`, `ByokListModelResponse.java`, `ByokListApiKeyResponse.java`, `ByokListModelParams.java`

### UI Files That Auto-Adapt

- **`ByokPreferencePage.java`** — iterates `ByokModelProvider.values()` for the tree, so new enum values appear automatically as provider nodes
- **`ByokService.java`** — fully provider-agnostic; routes by `providerName` string
- **`ModelPickerGroupsBuilder.java`** — separates BYOK models into a "Custom Models" group via `providerName != null` check

### Language Server Side

The Copilot Language Server (Node.js, not in this repo) must be extended to understand the new provider names. This is where the actual HTTP API integration lives:
- `ZhipuAI` → `https://open.bigmodel.cn/api/paas/v4/chat/completions`
- `DeepSeek` → `https://api.deepseek.com/chat/completions`
- `Local` → configurable endpoint (default `http://localhost:11434/v1/chat/completions` for Ollama)

All three use OpenAI-compatible APIs, so the language server's OpenAI adapter can likely be reused with URL overrides.

---

## Part 2: Model Routing Design

### The Bathymetric Principle

**Simpler terrain → cheaper model. Complex terrain → expensive model.**

Terrain is classified by constraint complexity:

| Terrain Level | Constraint Signature | Model Tier | Example Models |
|---|---|---|---|
| **Shallow** | Single-line completion, no context | Budget | GLM-4-Flash, DeepSeek-Lite |
| **Mid** | Multi-line completion, file context | Standard | DeepSeek-V3, GLM-4 |
| **Deep** | Chat with tool calling, reasoning | Premium | GPT-4o, Claude 3.5, GLM-4-Plus |
| **Abyssal** | Agent mode, multi-file, debugging | Flagship | Claude 3.5 Sonnet, o3, GLM-5 |

### Configuration Format

Add a new preference/configuration file: `model-routing.json`

```json
{
  "routing": {
    "completion": {
      "default": "deepseek-chat",
      "shallow": { "provider": "ZhipuAI", "model": "glm-4-flash" },
      "mid":     { "provider": "DeepSeek", "model": "deepseek-chat" },
      "deep":    { "provider": "OpenRouter", "model": "anthropic/claude-3.5-sonnet" }
    },
    "chat": {
      "default": "deepseek-chat",
      "shallow": { "provider": "DeepSeek", "model": "deepseek-chat" },
      "mid":     { "provider": "ZhipuAI", "model": "glm-4-plus" },
      "deep":    { "provider": "Anthropic", "model": "claude-3.5-sonnet" }
    },
    "agent": {
      "default": "claude-3.5-sonnet",
      "deep":    { "provider": "Anthropic", "model": "claude-3.5-sonnet" }
    }
  },
  "fallback": {
    "provider": "OpenRouter",
    "model": "meta-llama/llama-3-70b"
  },
  "costLimits": {
    "dailyBudgetUsd": 1.00,
    "perRequestMaxUsd": 0.05
  }
}
```

### Terrain Classification Logic

Create a new class: `ConstraintTerrainAnalyzer.java`

**Path:** `com.microsoft.copilot.eclipse.core/src/.../lsp/protocol/byok/ConstraintTerrainAnalyzer.java`

```java
package com.microsoft.copilot.eclipse.core.lsp.protocol.byok;

/**
 * Analyzes the constraint terrain of a request to determine model routing.
 * Bathymetric principle: simpler terrain = cheaper model.
 */
public class ConstraintTerrainAnalyzer {

  public enum TerrainDepth {
    SHALLOW,  // Single token/line prediction, no cross-file context
    MID,      // Multi-line with file context, standard completions
    DEEP,     // Chat with tool calling, reasoning, code generation
    ABYSSAL   // Agent mode: multi-file, debugging, complex workflows
  }

  public static TerrainDepth classifyCompletion(
      int prefixLength,
      int suffixLength,
      String languageId,
      boolean hasImports,
      boolean hasTypeContext) {
    // Shallow: single line, no context needed
    if (prefixLength < 200 && suffixLength < 50) return TerrainDepth.SHALLOW;
    // Mid: multi-line, needs file understanding
    if (prefixLength < 2000) return TerrainDepth.MID;
    // Deep: large context, complex completion
    return TerrainDepth.DEEP;
  }

  public static TerrainDepth classifyChat(
      int turnCount,
      boolean hasToolCalls,
      boolean hasFileReferences,
      boolean isAgentMode,
      String chatMode) {
    if (isAgentMode) return TerrainDepth.ABYSSAL;
    if (hasToolCalls || "agent".equals(chatMode)) return TerrainDepth.DEEP;
    if (turnCount > 3 || hasFileReferences) return TerrainDepth.MID;
    return TerrainDepth.SHALLOW;
  }
}
```

### Model Router

Create: `ModelRouter.java`

**Path:** `com.microsoft.copilot.eclipse.core/src/.../lsp/protocol/byok/ModelRouter.java`

```java
package com.microsoft.copilot.eclipse.core.lsp.protocol.byok;

import java.util.Map;
import java.util.Optional;

/**
 * Routes requests to the appropriate BYOK model based on constraint terrain.
 * Bathymetric principle: simpler terrain = cheaper model, complex terrain = expensive model.
 */
public class ModelRouter {

  private final ModelRoutingConfig config;
  private final Map<String, ByokModel> availableModels; // keyed by providerName_modelId

  public ByokModel routeCompletion(
      int prefixLength, int suffixLength,
      String languageId, boolean hasImports, boolean hasTypeContext) {

    var depth = ConstraintTerrainAnalyzer.classifyCompletion(
        prefixLength, suffixLength, languageId, hasImports, hasTypeContext);
    return resolveModel(config.getCompletionRoute(depth), depth);
  }

  public ByokModel routeChat(
      int turnCount, boolean hasToolCalls,
      boolean hasFileReferences, boolean isAgentMode, String chatMode) {

    var depth = ConstraintTerrainAnalyzer.classifyChat(
        turnCount, hasToolCalls, hasFileReferences, isAgentMode, chatMode);
    return resolveModel(config.getChatRoute(depth), depth);
  }

  private ByokModel resolveModel(ModelRoute route, TerrainDepth depth) {
    // Try the configured model first
    Optional<ByokModel> model = findRegistered(route.getProvider(), route.getModel());
    if (model.isPresent()) return model.get();

    // Fallback: try default for the operation type
    // Fallback: try any registered model at this terrain depth
    // Final fallback: the config's fallback model
    return findRegistered(config.getFallback().getProvider(), config.getFallback().getModel())
        .orElseThrow(() -> new IllegalStateException("No available model for terrain: " + depth));
  }

  private Optional<ByokModel> findRegistered(String provider, String modelId) {
    String key = provider + "_" + modelId;
    ByokModel m = availableModels.get(key);
    return (m != null && m.isRegistered()) ? Optional.of(m) : Optional.empty();
  }
}
```

---

## Part 3: Integration Points

### Where to Wire Model Routing

#### 3a. Chat Model Selection — `ModelService.java`

**Path:** `com.microsoft.copilot.eclipse.ui/src/.../chat/services/ModelService.java`

Currently, `ModelService` manages an `activeModelObservable<CopilotModel>` that the user picks from the dropdown. To add automatic routing:

```java
// In ModelService, add:
private ModelRouter modelRouter; // initialized with routing config + available BYOK models

/**
 * For chat: use the user-selected model (existing behavior).
 * For auto-routed scenarios (inline completion), use the router.
 */
public CopilotModel getRoutedModel(String operationType, TerrainDepth depth) {
  if ("chat".equals(operationType)) {
    return getActiveModel(); // User's explicit choice for chat
  }
  ByokModel routed = modelRouter.routeForTerrain(operationType, depth);
  return convertToCopilotModel(routed);
}
```

#### 3b. Inline Completion — `CopilotLanguageServerConnection.java`

**Path:** `com.microsoft.copilot.eclipse.core/src/.../lsp/CopilotLanguageServerConnection.java`

The inline completion flow sends requests through the LSP. To route via terrain:

1. Before sending `textDocument/inlineCompletion`, classify the terrain using `ConstraintTerrainAnalyzer.classifyCompletion()`
2. Select the appropriate BYOK model
3. Include the model selection in the LSP request parameters

#### 3c. Chat Requests — `ChatView.java`

**Path:** `com.microsoft.copilot.eclipse.ui/src/.../chat/ChatView.java`

Currently uses `chatServiceManager.getModelService().getActiveModel()`. For agent mode with constraint terrain:

```java
// Before sending chat request, optionally override the model:
if (isAgentMode) {
  CopilotModel routed = modelService.getRoutedModel("agent", TerrainDepth.ABYSSAL);
  // Use routed model instead of activeModel
}
```

---

## Part 4: Files to Modify (Summary)

### Must Modify

| File | Change |
|---|---|
| `ByokModelProvider.java` | Add `ZHIPUAI`, `DEEPSEEK`, `LOCAL` enum values |

### New Files to Create

| File | Purpose |
|---|---|
| `ConstraintTerrainAnalyzer.java` | Classifies request complexity into terrain depth levels |
| `ModelRouter.java` | Routes requests to models based on terrain + config |
| `ModelRoutingConfig.java` | Loads/represents `model-routing.json` configuration |
| `model-routing.json` | User-editable routing configuration (in plugin state area) |

### Optional Modifications

| File | Change | Why |
|---|---|---|
| `ModelService.java` | Add `getRoutedModel()` method | Auto-routing for non-chat operations |
| `ByokPreferencePage.java` | Add routing config section | Let users configure terrain→model mapping |
| `ByokService.java` | Expose `ModelRouter` instance | Central access point for routing |
| `AddApiKeyDialog.java` | Handle new provider URL patterns | Provider-specific hints |
| `AddByokModelDialog.java` | Provider-specific model ID suggestions | Better UX for new providers |

### No Changes Needed

| File | Reason |
|---|---|
| `ByokModel.java` | Already provider-agnostic (String-based) |
| `ByokApiKey.java` | Already provider-agnostic |
| `ByokModelCapabilities.java` | Generic, no provider specifics |
| `CopilotCapabilities.java` | Server capabilities, not model-related |
| `InitializationOptions.java` | LSP init, not model-related |
| `FeatureFlags.java` | Feature toggles, no provider-specific logic |
| `ModelPickerGroupsBuilder.java` | Auto-adapts via `ByokModelProvider.values()` iteration |
| `CopilotLanguageServer.java` | LSP protocol interface, provider-agnostic endpoints |

---

## Part 5: OpenRouter as Universal Gateway

OpenRouter is already supported and serves as our **universal fallback gateway**. It provides API access to:

- GLM-4 models (ZhipuAI)
- DeepSeek models
- Claude, GPT-4, Llama, Mistral, etc.

This means even without direct provider integrations, we can route to any model through OpenRouter. The dedicated `ZHIPUAI` and `DEEPSEEK` enum values are for users who have their own API keys and want direct, lower-latency, lower-cost access.

**Routing priority:**
1. Direct provider API key (if configured) — lowest cost
2. OpenRouter (if configured) — universal access
3. GitHub Copilot default models — built-in fallback

---

## Part 6: Constraint-Substrate Lattice Snap for Completion

The "constraint-substrate lattice snap" concept: when the completion engine identifies a constraint (type signature, method signature, known pattern), it "snaps" to the simplest model that can satisfy that constraint.

**Implementation:**

```java
public class CompletionConstraintAnalyzer {

  /**
   * Snap to the cheapest model that can handle this completion's constraints.
   */
  public static ByokModel snapToModel(
      CompletionContext ctx,
      Map<TerrainDepth, ByokModel> modelLattice) {

    // Constraint primitives:
    // 1. Type constraint: return type, parameter types known
    // 2. Pattern constraint: implements known pattern (builder, factory, etc.)
    // 3. Context constraint: imports, file structure available

    int constraintCount = 0;
    if (ctx.hasTypeConstraint()) constraintCount++;
    if (ctx.hasPatternConstraint()) constraintCount++;
    if (ctx.hasContextConstraint()) constraintCount++;

    // More constraints = tighter lattice = cheaper model can snap
    // Fewer constraints = looser lattice = need more capable model

    if (constraintCount >= 2) return modelLattice.get(TerrainDepth.SHALLOW);
    if (constraintCount == 1) return modelLattice.get(TerrainDepth.MID);
    return modelLattice.get(TerrainDepth.DEEP);
  }
}
```

**Counter-intuitive insight:** More constraints → simpler model. When we know the type signature and the pattern, the model just needs to fill in boilerplate. A cheap model can do that. When we have *no* constraints, we need a smart model to *infer* intent.

---

## Part 7: Monitor Integration (Chat Flow Detection)

For chat, the **Monitor** system detects conversation flow patterns and adapts model selection:

```java
public class ChatFlowMonitor {

  public enum FlowState {
    EXPLORATORY,   // User asking questions → cheap model
    IMPLEMENTING,  // Writing code → standard model
    DEBUGGING,     // Troubleshooting → capable model
    REFACTORING    // Complex multi-step → flagship model
  }

  /**
   * Detect flow state from conversation turns.
   */
  public static FlowState detectFlow(List<Turn> turns) {
    Turn lastTurn = turns.get(turns.size() - 1);

    if (lastTurn.containsToolCalls()) return FlowState.DEBUGGING;
    if (lastTurn.referencesMultipleFiles()) return FlowState.REFACTORING;
    if (lastTurn.containsCodeBlocks()) return FlowState.IMPLEMENTING;
    return FlowState.EXPLORATORY;
  }

  /**
   * Map flow state to model terrain → model selection.
   */
  public static ByokModel selectModelForFlow(
      FlowState flow,
      ModelRouter router) {
    return switch (flow) {
      case EXPLORATORY -> router.routeForTerrain(TerrainDepth.SHALLOW);
      case IMPLEMENTING -> router.routeForTerrain(TerrainDepth.MID);
      case DEBUGGING -> router.routeForTerrain(TerrainDepth.DEEP);
      case REFACTORING -> router.routeForTerrain(TerrainDepth.ABYSSAL);
    };
  }
}
```

---

## Implementation Order

1. **Phase 1 — Provider Extension** (Low effort, high impact)
   - Add `ZHIPUAI`, `DEEPSEEK`, `LOCAL` to `ByokModelProvider`
   - Test that the preference page shows them
   - Verify API key management works

2. **Phase 2 — Routing Config** (Medium effort)
   - Create `ModelRoutingConfig` + `model-routing.json` loader
   - Create `ModelRouter` with terrain-based selection
   - Add routing config to preference page

3. **Phase 3 — Terrain Classification** (Medium effort)
   - Implement `ConstraintTerrainAnalyzer`
   - Wire into completion flow
   - Measure accuracy of terrain classification

4. **Phase 4 — Constraint-Substrate Snap** (Advanced)
   - Implement `CompletionConstraintAnalyzer`
   - Build the lattice of constraint primitives
   - Test snap accuracy across terrains

5. **Phase 5 — Chat Flow Monitor** (Advanced)
   - Implement `ChatFlowMonitor`
   - Wire into chat request flow
   - Adaptive model switching mid-conversation
