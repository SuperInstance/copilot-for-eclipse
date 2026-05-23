# CONSTRAINT-EDITOR-DESIGN.md

## The Bathymetric Compiler — An IDE That Feels the Constraint Surface of Code

> "When you write code in 12 languages, you aren't precalculating 12 scenarios. You are creating a bathymetric chart of the landscape of mathematical possibilities on the metal."

---

## 0. Preamble: Why This Exists

The copilot-for-eclipse plugin is a completion engine. It sends positions to a language server, receives suggestions, and renders them as ghost text or inline edits. It does not *understand* the code — it *predicts* it.

This design asks: what if the editing surface itself understood the **constraint geometry** of the code being written? Not as a tool Copilot calls, but as a property of the editor — as fundamental as syntax highlighting.

The five substrate primitives from constraint theory (`lattice_snap`, `funnel`, `is_laman`, `consensus`, `holonomy`) are not abstractions. They are real forces acting on code at every keystroke. The editor should make them visible, audible, and navigable — like a bathymetric chart showing the depth of water beneath a ship.

---

## 1. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Eclipse IDE Surface                          │
│                                                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐  │
│  │ Code Mining  │  │ Ruler Column │  │ Annotation Model         │  │
│  │ (inline text)│  │ (gutter icons│  │ (underlines, highlights) │  │
│  │              │  │  depth marks)│  │                          │  │
│  └──────┬───────┘  └──────┬───────┘  └────────────┬─────────────┘  │
│         │                 │                        │                 │
│  ┌──────┴─────────────────┴────────────────────────┴─────────────┐  │
│  │              CONSTRAINT SURFACE LAYER                         │  │
│  │                                                              │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌──────────────────────┐  │  │
│  │  │ Constraint  │  │ Terrain     │  │ Flow Monitor         │  │  │
│  │  │ Analyzer    │  │ Classifier  │  │ (developer state)    │  │  │
│  │  │ Engine      │  │ (language → │  │                      │  │  │
│  │  │             │  │  terrain)   │  │                      │  │  │
│  │  └──────┬──────┘  └──────┬──────┘  └──────────┬───────────┘  │  │
│  │         │                │                     │               │  │
│  │  ┌──────┴────────────────┴─────────────────────┴────────────┐ │  │
│  │  │              COMPLETION INTERCEPTOR                       │ │  │
│  │  │   (wraps CompletionProvider, reorders/reroutes)          │ │  │
│  │  └──────────────────────────┬───────────────────────────────┘ │  │
│  └─────────────────────────────┼────────────────────────────────┘  │
│                                │                                    │
│  ┌─────────────────────────────┴────────────────────────────────┐  │
│  │            COPILOT LANGUAGE SERVER (existing)                │  │
│  │   CompletionProvider → CopilotLanguageServerConnection       │  │
│  └──────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

The design adds two layers between the existing completion infrastructure and the visual surface:

1. **Constraint Surface Layer** — computes constraint properties of the code in real-time
2. **Completion Interceptor** — wraps the existing `CompletionProvider` to reorder, filter, or augment suggestions based on constraint state and developer flow

---

## 2. Constraint Annotations — The Five Primitives

Each substrate primitive maps to a measurable property of code. These are not metaphors — they are formal analogies where the same mathematical structure appears in both domains.

### 2.1 `lattice_snap` → Type Safety

**Formal analogy:** A lattice is a partially ordered set where every pair has a unique supremum (join) and infimum (meet). In type theory, the subtype relation forms a lattice. "Snapping" means the code resolves to the nearest lattice point — the most specific valid type.

**Code property:** How tightly do expressions resolve to their most specific valid type? Loose typing (dynamic casts, `Object` everywhere) = far from lattice points. Tight typing (generics, pattern matching) = snapping to lattice.

**Computation:**
- Parse the AST (Eclipse JDT already has full type binding information)
- For each expression node, compute the *type specificity ratio*: `specificity = depth_in_type_hierarchy(declared_type) / depth_in_type_hierarchy(actual_type)`
- A value near 1.0 = tight lattice snap. A value near 0.0 = floating free.

**Visualization:**
- Code mining annotation: `[snap: 0.92]` next to type declarations
- Green when specificity > 0.8, yellow 0.5–0.8, red < 0.5
- Annotation underline on expressions with poor snap (dotted yellow = loose, solid green = tight)

**Eclipse integration:**
- Uses `org.eclipse.jdt.core.dom.ASTVisitor` to walk type bindings
- Annotation type: `com.bathymetric.constraint.snap` registered via `org.eclipse.ui.editors.annotationTypes`
- Marker specification via `org.eclipse.ui.editors.markerAnnotationSpecification`

### 2.2 `funnel` → Code Navigation Gravity

**Formal analogy:** A funnel is a convergence structure where nearby starting points all flow to the same attractor. In code, certain symbols (core interfaces, base classes, entry points) act as attractors — most navigation paths lead toward them.

**Code property:** Which symbols in this file act as gravitational attractors? How strongly does the call/reference graph funnel toward them?

**Computation:**
- Build a local reference graph using Eclipse's `IIndex` and `ICallHierarchy`
- For each symbol, compute fan-in ratio: `gravity = inbound_references / (inbound + outbound_references)`
- Symbols with high gravity are funnels — they concentrate the navigation flow

**Visualization:**
- Ruler column markers: `●` (high gravity), `○` (medium), `·` (low) in the gutter
- Hovering shows: `[funnel: 0.78 — 47 inbound refs converge here]`
- When navigating, highlight the "gravitational well" — the nearest high-gravity symbol

**Eclipse integration:**
- Uses `org.eclipse.jdt.ui.actions.OpenCallHierarchyAction` / `CallHierarchyViewPart` APIs
- Ruler column via `org.eclipse.ui.workbench.texteditor.rulerColumns` (same pattern as existing `RulerColumn` in NES)

### 2.3 `is_laman` → Structural Rigidity

**Formal analogy:** A Laman graph is a minimally rigid structure — remove any edge and it becomes flexible. In dependency graphs, a Laman structure means every dependency is load-bearing; none are redundant.

**Code property:** Is the dependency graph of this module rigid (every import matters) or flexible (redundant connections)? A rigid dependency graph fails predictably when a dependency is removed. A flexible one degrades gracefully.

**Computation:**
- Build the import/dependency graph for the current file
- Compute the Laman condition: for `n` nodes, a graph is minimally rigid if it has exactly `2n - 3` edges and every subgraph of `k` nodes has at most `2k - 3` edges
- Rigidity ratio: `actual_edges / (2n - 3)` where n = number of connected dependencies
- Ratio = 1.0 → minimally rigid. > 1.0 → over-constrained (fragile). < 1.0 → under-constrained (floppy).

**Visualization:**
- Code mining at file header: `[rigidity: 1.12 — over-constrained, 3 redundant deps]`
- Color-coded: green (0.9–1.1), yellow (1.1–1.5 or 0.6–0.9), red (> 1.5 or < 0.6)
- Quick-fix: "Remove redundant dependencies" for over-constrained graphs

**Eclipse integration:**
- Parse imports using JDT's `ICompilationUnit.getImports()`
- Build graph with `org.eclipse.jdt.core.search.IJavaSearchConstants.REFERENCES`
- Contributes quick-fix via `org.eclipse.jdt.ui.quickFixProcessors`

### 2.4 `consensus` → Linter/Analyzer Agreement

**Formal analogy:** Consensus in distributed systems means multiple independent agents reach the same conclusion. For code quality, if multiple independent analyzers (compiler, linter, type checker, static analysis) all agree a region is fine, that's high consensus.

**Code property:** Do the available analyzers agree on the quality of this code region? Disagreement flags areas that need attention.

**Computation:**
- Eclipse already aggregates: compiler warnings (`IMarker`), JDT warnings, checkstyle, PMD, SpotBugs
- For each code region: `consensus = 1.0 - (disagreeing_analyzers / total_analyzers)`
- An analyzer "disagrees" if it flags a problem that no other analyzer flags (unique finding = low consensus)

**Visualization:**
- Code mining: `[consensus: 0.4 — 2 unique findings]`
- Annotations: underline regions where consensus is low with a distinctive pattern (wavy purple)
- Marker aggregation in Problems view: group by consensus level

**Eclipse integration:**
- Reads `IMarker` from `IResource.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)`
- Aggregates across builder participants and `org.eclipse.core.resources.incrementalProjectBuilder` extensions

### 2.5 `holonomy` → Import Cycle Detection

**Formal analogy:** Holonomy measures how much a vector rotates when parallel-transported around a closed loop. In code, import cycles are closed loops — importing A→B→C→A means the dependency vector returns to its origin having "rotated" through the package structure.

**Code property:** Are there cycles in the import/dependency graph? How "tight" are they (how many hops)?

**Computation:**
- Build the package-level dependency graph using JDT's `IPackageFragment` dependencies
- Detect cycles with DFS; for each cycle compute: `holonomy = cycle_length / total_packages`
- Smaller cycles = tighter holonomy = more problematic

**Visualization:**
- Code mining at package declaration: `[holonomy: cycle detected — A→B→C→A (3 hops)]`
- Annotation: red underline on import statements that participate in cycles
- Quick-fix: "Break cycle by extracting interface"

**Eclipse integration:**
- Uses `org.eclipse.jdt.core.IPackageFragment` and cross-reference search
- Cycle detection runs as an incremental builder via `org.eclipse.core.resources.incrementalProjectBuilder`

---

## 3. Bathymetric Code Mining — The Depth Sounder

### 3.1 Concept

Eclipse's code mining API (`org.eclipse.jface.text.codemining`) renders inline annotations in the editor text area. The copilot-for-eclipse plugin already uses this for ghost text (see `GhostTextProvider`, `LineEndGhostText`, `BlockGhostText`). We extend this to show constraint depth values.

The constraint depth is a composite metric:

```
depth = (w_snap * lattice_snap + w_funnel * funnel + w_rigidity * is_laman 
       + w_consensus * consensus + w_holonomy * (1 - holonomy_violation)) 
       / sum(weights)
```

Default weights: all equal (0.2). The depth value ranges from 0.0 (chaos) to 1.0 (perfect constraint satisfaction).

### 3.2 Implementation

```java
package com.bathymetric.editor.mining;

/**
 * Code mining provider that shows constraint depth inline.
 * Registered via org.eclipse.ui.workbench.texteditor.codeMiningProviders.
 */
public class ConstraintDepthMiningProvider extends AbstractCodeMiningProvider {

    private ConstraintAnalyzerEngine engine;

    @Override
    public CompletableFuture<List<? extends ICodeMining>> provideCodeMinings(
            ITextViewer viewer, IProgressMonitor monitor) {
        
        return CompletableFuture.supplyAsync(() -> {
            IDocument document = viewer.getDocument();
            IFile file = resolveFile(viewer);
            if (file == null) return Collections.emptyList();
            
            // Compute constraint depth for each method/class declaration
            ConstraintSurface surface = engine.analyze(file, document);
            List<ICodeMining> minings = new ArrayList<>();
            
            for (ConstraintRegion region : surface.getRegions()) {
                // Line-header mining: [depth: 0.87 ●]
                minings.add(new ConstraintDepthCodeMining(
                    region.getStartLine(),
                    document,
                    this,
                    region.getDepth(),
                    region.getDominantPrimitive()
                ));
            }
            return minings;
        });
    }
}
```

### 3.3 Visual Design

```
  24 │ [depth: 0.92 ●] public class PaymentProcessor {
  25 │     [depth: 0.87 ●] public void processOrder(Order order) {
  26 │         // lattice_snap detects: order param is typed, good
  27 │         PaymentResult result = validate(order);  // [snap: 0.95]
  28 │         if (result.isValid()) {
  29 │             charge(result.getAmount());  // [consensus: 0.4 ⚠] ← unique PMD finding
  30 │         }
  31 │     }
  32 │ }
```

**Color mapping:**
- `depth ≥ 0.8`: Green `●` — constraint surface is optimal
- `0.5 ≤ depth < 0.8`: Yellow `◐` — acceptable, some tension
- `depth < 0.5`: Red `○` — constraint violations, needs attention

### 3.4 Registration (plugin.xml)

```xml
<extension point="org.eclipse.ui.workbench.texteditor.codeMiningProviders">
    <codeMiningProvider
        class="com.bathymetric.editor.mining.ConstraintDepthMiningProvider"
        id="com.bathymetric.editor.constraintDepth"
        label="Constraint Depth Mining">
    </codeMiningProvider>
</extension>

<extension point="org.eclipse.ui.editors.annotationTypes">
    <type name="com.bathymetric.constraint.snap" />
    <type name="com.bathymetric.constraint.funnel" />
    <type name="com.bathymetric.constraint.rigidity" />
    <type name="com.bathymetric.constraint.consensus" />
    <type name="com.bathymetric.constraint.holonomy" />
</extension>

<extension point="org.eclipse.ui.editors.markerAnnotationSpecification">
    <specification
        annotationType="com.bathymetric.constraint.snap"
        colorPreferenceKey="constraint_snap_color"
        colorPreferenceValue="0,180,0"
        highlightPreferenceValue="false"
        label="Constraint Snap"
        overviewRulerPreferenceValue="true"
        textPreferenceValue="true"
        textStylePreferenceValue="UNDERLINE" />
    <!-- ... similar for other primitives ... -->
</extension>
```

---

## 4. The Terrain Map — Language as Landscape

### 4.1 Concept

Different programming languages have fundamentally different constraint surfaces. Writing Java is like navigating classical counterpoint — strict rules, high rigidity, every voice must resolve correctly. Writing Python is modal jazz — flexible scales, many valid paths through the changes. The terrain you're on determines how the depth sounder should be calibrated.

### 4.2 Terrain Classifications

| Language | Musical Analogy | Terrain | Rigidity | Snap Weight | Funnel Weight | Holonomy Concern |
|----------|----------------|---------|----------|-------------|---------------|------------------|
| Java | Classical Counterpoint | Mountains | High | 0.35 | 0.25 | 0.15 |
| Python | Modal Jazz | Rolling Hills | Low | 0.15 | 0.20 | 0.10 |
| Rust | Bebop | Cliffs/Dense | Very High | 0.30 | 0.30 | 0.25 |
| C | Delta Blues | Desert/Flat | Medium | 0.20 | 0.15 | 0.15 |
| JavaScript | Free Improvisation | Ocean | Very Low | 0.10 | 0.10 | 0.05 |
| TypeScript | Cool Jazz | Foothills | Medium-High | 0.25 | 0.20 | 0.15 |
| Go | Minimalism | Plains | High | 0.25 | 0.25 | 0.20 |
| Haskell | Serialism | Crystal Lattice | Very High | 0.40 | 0.20 | 0.30 |

### 4.3 Terrain-Aware Depth Calibration

The terrain classifier adjusts the constraint weights based on file type:

```java
package com.bathymetric.editor.terrain;

public class TerrainClassifier {
    
    private static final Map<String, TerrainProfile> TERRAINS = Map.of(
        "java",       new TerrainProfile("classical_counterpoint", 0.35, 0.25, 0.20, 0.10, 0.10),
        "python",     new TerrainProfile("modal_jazz",             0.15, 0.20, 0.10, 0.15, 0.10),
        "rust",       new TerrainProfile("bebop",                  0.30, 0.30, 0.25, 0.10, 0.20),
        "c",          new TerrainProfile("delta_blues",            0.20, 0.15, 0.15, 0.15, 0.10),
        "javascript", new TerrainProfile("free_improvisation",     0.10, 0.10, 0.05, 0.20, 0.05),
        "typescript", new TerrainProfile("cool_jazz",              0.25, 0.20, 0.15, 0.15, 0.10),
        "go",         new TerrainProfile("minimalism",             0.25, 0.25, 0.20, 0.15, 0.15),
        "haskell",    new TerrainProfile("serialism",              0.40, 0.20, 0.30, 0.05, 0.25)
    );
    
    public TerrainProfile classify(IFile file) {
        String ext = file.getFileExtension();
        return TERRAINS.getOrDefault(ext, 
            new TerrainProfile("default", 0.20, 0.20, 0.20, 0.20, 0.20));
    }
}
    
record TerrainProfile(
    String name,
    double snapWeight,
    double funnelWeight,
    double rigidityWeight,
    double consensusWeight,
    double holonomyWeight
) {}
```

### 4.4 Terrain Display

The terrain is shown subtly in the editor status area or as a persistent code mining at the file header:

```
  1 │ [terrain: classical_counterpoint — rigidity matters here]
  2 │ package com.example.payments;
```

The terrain classification also affects the ruler column icon style — mountain terrain uses sharper icons, ocean terrain uses wavy ones.

---

## 5. The Monitor — Developer Flow State

### 5.1 Concept

Just as the musical Monitor tracks whether the performer is in flow or struggling, the Code Monitor watches the developer's editing pattern and adjusts the constraint surface's visibility and the completion engine's behavior accordingly.

### 5.2 Flow State Detection

The monitor tracks these signals from the existing editor event listeners (the plugin already listens to `KeyListener`, `MouseListener`, `ITextListener` in `BaseCompletionManager`):

| Signal | How to Measure | Flow Indicator |
|--------|---------------|----------------|
| Typing cadence | Inter-keystroke interval via existing `KeyListener` | Regular = flowing, bursts+pauses = struggling |
| Error rate | `IMarker` creation/deletion frequency | Low = flowing, high = struggling |
| Acceptance rate | Copilot `notifyAccepted`/`notifyRejected` ratio | High = flowing, low = uncertain |
| Navigation frequency | Editor part activation events | Low = deep focus, high = searching |
| Undo frequency | `ITextListener` detecting reversed changes | Low = flowing, high = thrashing |
| Completion dismissal rate | Ghost text shown but not accepted | Low = flowing, high = noise |

```java
package com.bathymetric.editor.monitor;

public class FlowMonitor implements KeyListener, ITextListener {
    
    private Deque<Long> keystrokeTimestamps = new ArrayDeque<>();
    private AtomicInteger errorsIntroduced = new AtomicInteger(0);
    private AtomicInteger completionsAccepted = new AtomicInteger(0);
    private AtomicInteger completionsRejected = new AtomicInteger(0);
    private AtomicInteger undoCount = new AtomicInteger(0);
    
    private static final int WINDOW_SECONDS = 60;
    
    enum FlowState {
        FLOWING,        // Regular cadence, low errors, high acceptance
        EXPLORING,      // Irregular cadence, navigation-heavy
        STRUGGLING,     // High errors, frequent undos, low acceptance
        IDLE            // No activity
    }
    
    public FlowState computeState() {
        double cadence = computeCadence();
        double errorRate = errorsIntroduced.get() / (double) WINDOW_SECONDS;
        double acceptanceRate = computeAcceptanceRate();
        
        if (cadence < 0.1) return FlowState.IDLE;
        if (errorRate > 0.1 && undoCount.get() > 3) return FlowState.STRUGGLING;
        if (cadence > 0.5 && acceptanceRate > 0.6) return FlowState.FLOWING;
        return FlowState.EXPLORING;
    }
    
    // ... measurement methods ...
}
```

### 5.3 Adaptive Behavior

The flow state drives three behaviors:

**When FLOWING:**
- Dim the constraint annotations (lower opacity)
- Don't interfere with completions — pass through directly
- The depth sounder goes quiet: minimal visual noise

**When EXPLORING:**
- Show constraint annotations at normal intensity
- Highlight funnels (navigation attractors) to help orient
- Completions pass through, but constraint depth shown in completion proposals

**When STRUGGLING:**
- Full constraint annotation visibility
- Reorder completion proposals to prefer ones that improve constraint satisfaction
- Show "constraint hint" popups explaining why the current code has low depth
- Increase `CompletionJob` timeout slightly (more time for better suggestions)

**When IDLE:**
- Full constraint surface visible — this is when developers read and review
- Show the terrain map overlay
- Annotate all regions with their dominant primitive

### 5.4 Vanishing

The key insight from the musical Monitor: the constraint surface should **vanish** when the developer has internalized the patterns. We track internalization through:

1. **Declining error rate over time** for a given constraint type
2. **Declining rejection rate** for constraint-aware completion suggestions
3. **Consistent high depth scores** in recent code

When internalization is detected for a specific primitive, that primitive's annotations fade out. They return only when depth drops below a threshold — like a check-engine light that only illuminates when there's actually a problem.

---

## 6. The Completion Interceptor — Wrapping Copilot

### 6.1 Architecture

The interceptor sits between the existing `CompletionProvider`/`CompletionListener` chain and the visual rendering layer. It does **not** replace the Copilot language server — it wraps it.

```
Existing flow:
  Editor events → CompletionProvider → LS → completions → BaseCompletionManager → ghost text

New flow:
  Editor events → CompletionProvider → LS → completions 
    → CompletionInterceptor (reorder, annotate, filter)
    → BaseCompletionManager → ghost text (with constraint metadata)
```

### 6.2 Implementation Strategy

The interceptor implements `CompletionListener` and registers itself between the provider and the rendering manager:

```java
package com.bathymetric.editor.interceptor;

public class ConstraintCompletionInterceptor implements CompletionListener {
    
    private final List<CompletionListener> downstreamListeners;
    private final ConstraintAnalyzerEngine engine;
    private final FlowMonitor flowMonitor;
    
    @Override
    public void onCompletionResolved(String uriString, List<CompletionItem> completions) {
        if (completions == null || completions.isEmpty()) {
            forward(uriString, completions);
            return;
        }
        
        FlowState state = flowMonitor.computeState();
        
        switch (state) {
            case FLOWING:
                // Pass through unmodified
                forward(uriString, completions);
                break;
                
            case STRUGGLING:
                // Reorder: prefer completions that improve constraint depth
                List<CompletionItem> reordered = reorderForConstraints(uriString, completions);
                forward(uriString, reordered);
                break;
                
            case EXPLORING:
                // Annotate with constraint metadata but don't reorder
                List<CompletionItem> annotated = annotateWithDepth(uriString, completions);
                forward(uriString, annotated);
                break;
                
            default:
                forward(uriString, completions);
        }
    }
    
    private List<CompletionItem> reorderForConstraints(String uri, List<CompletionItem> items) {
        ConstraintSurface surface = engine.getCachedSurface(uri);
        return items.stream()
            .sorted(Comparator.comparingDouble(item -> 
                -estimateConstraintImprovement(surface, item)))
            .collect(Collectors.toList());
    }
    
    // Forward to all downstream listeners (the rendering managers)
    private void forward(String uri, List<CompletionItem> completions) {
        for (CompletionListener listener : downstreamListeners) {
            listener.onCompletionResolved(uri, completions);
        }
    }
}
```

### 6.3 Constraint-Aware Completion Scoring

Each completion proposal is scored by how much it would improve the constraint surface:

```java
private double estimateConstraintImprovement(ConstraintSurface current, CompletionItem item) {
    double score = 0.0;
    String text = item.getDisplayText();
    
    // lattice_snap: does this completion introduce typed references?
    if (introducesTypeAnnotation(text)) score += 0.15;
    
    // is_laman: does this reduce over-constraint (remove redundant deps)?
    if (removesRedundantDependency(text, current)) score += 0.20;
    
    // consensus: does this fix a region flagged by multiple analyzers?
    if (fixesConsensusViolation(text, current)) score += 0.25;
    
    // holonomy: does this break an import cycle?
    if (breaksCycle(text, current)) score += 0.30;
    
    return score;
}
```

---

## 7. Implementation Path

### 7.1 What Can Be a Separate Plugin vs. What Needs Fork Modifications

| Component | Separate Plugin | Fork Modification | Reason |
|-----------|:-:|:-:|--------|
| Constraint Analyzer Engine | ✅ | | Pure computation, no Eclipse API dependency |
| Terrain Classifier | ✅ | | File extension → profile mapping |
| Flow Monitor | ✅ | | Uses standard Eclipse APIs |
| Constraint Depth Code Mining | ✅ | | Standard `codeMiningProviders` extension point |
| Constraint Annotations | ✅ | | Standard `annotationTypes` extension |
| Ruler Column (constraint gutter) | ✅ | | Standard `rulerColumns` extension point |
| **CompletionInterceptor** | | ⚠️ | Needs to be inserted into `CompletionProvider`'s listener chain |
| **Flow-aware completion timeout** | | ⚠️ | Needs access to `CompletionJob.COMPLETION_TIMEOUT_MILLIS` |
| **Ghost text constraint overlay** | | ⚠️ | Needs to extend `GhostTextProvider` or `BaseCompletionManager` |

### 7.2 Recommended Approach: Hybrid Plugin

Create a new Eclipse plugin `com.bathymetric.editor` that:

1. **Depends on** `com.microsoft.copilot.eclipse.core` and `com.microsoft.copilot.eclipse.ui` (the existing Copilot plugins)
2. **Contributes** all new extension points (code mining, annotations, ruler column, builder)
3. **Hooks into** the completion system via the `CompletionListener` interface — no fork needed for the interceptor if we register as a listener

The interceptor works because `CompletionProvider` already supports multiple `CompletionListener` instances via `CopyOnWriteArraySet`. We register our interceptor *first* so it receives completions before the rendering managers. The interceptor holds a reference to the *original* listeners, removes them from the provider, adds itself, then forwards to the originals after processing.

```java
// In plugin activation:
CompletionProvider provider = /* get from CopilotCore */;
List<CompletionListener> original = new ArrayList<>(provider.getListeners());
original.forEach(provider::removeCompletionListener);
ConstraintCompletionInterceptor interceptor = 
    new ConstraintCompletionInterceptor(original, engine, monitor);
provider.addCompletionListener(interceptor);
```

### 7.3 Minimal Fork Changes (for deeper integration)

If we want flow-aware timeout adjustment or constraint metadata in ghost text rendering, we need small modifications to the fork:

**Change 1: Expose completion timeout as configurable**
```java
// CompletionProvider.java — make timeout mutable
private static int completionTimeoutMillis = 5000;
public static void setCompletionTimeout(int millis) {
    completionTimeoutMillis = millis;
}
```

**Change 2: Add constraint metadata to CompletionItem rendering**
```java
// BaseCompletionManager.java — add constraint depth to ghost text
protected double constraintDepth = Double.NaN; // NaN = not computed

// In onCompletionResolved, read constraint metadata:
@Override
public void onCompletionResolved(String uriString, List<CompletionItem> completions) {
    if (completions != null && !completions.isEmpty()) {
        // Read constraint depth from completion item metadata
        CompletionItem first = completions.get(0);
        // If the interceptor annotated the item, extract depth
        this.constraintDepth = first.getConstraintDepth();
    }
    // ... existing rendering logic ...
}
```

**Change 3: Add extension point for completion post-processing**
```java
// New extension point in plugin.xml:
// org.eclipse.ui.completionPostProcessors
// Allows third-party plugins to intercept/reorder completions
// This is the cleanest way — proper Eclipse extension point pattern
```

### 7.4 Build and Dependency Structure

```
com.bathymetric.editor/
├── META-INF/
│   └── MANIFEST.MF                     # Requires-Bundle: 
│                                       #   com.microsoft.copilot.eclipse.core,
│                                       #   com.microsoft.copilot.eclipse.ui,
│                                       #   org.eclipse.jdt.core,
│                                       #   org.eclipse.jface.text
├── plugin.xml                          # All extension points
├── src/com/bathymetric/editor/
│   ├── analyzer/
│   │   ├── ConstraintAnalyzerEngine.java
│   │   ├── LatticeSnapAnalyzer.java
│   │   ├── FunnelAnalyzer.java
│   │   ├── RigidityAnalyzer.java
│   │   ├── ConsensusAnalyzer.java
│   │   └── HolonomyAnalyzer.java
│   ├── terrain/
│   │   ├── TerrainClassifier.java
│   │   └── TerrainProfile.java
│   ├── monitor/
│   │   ├── FlowMonitor.java
│   │   └── FlowState.java
│   ├── mining/
│   │   ├── ConstraintDepthMiningProvider.java
│   │   └── ConstraintDepthCodeMining.java
│   ├── annotation/
│   │   ├── ConstraintAnnotationProvider.java
│   │   └── ConstraintReconciler.java
│   ├── interceptor/
│   │   └── ConstraintCompletionInterceptor.java
│   ├── ruler/
│   │   └── ConstraintRulerColumn.java
│   └── internal/
│       └── BathymetricPlugin.java      # Activator
└── icons/
    ├── depth-green.png
    ├── depth-yellow.png
    ├── depth-red.png
    ├── terrain-mountain.png
    ├── terrain-ocean.png
    └── terrain-foothills.png
```

### 7.5 Performance Considerations

The constraint analysis must not block the UI thread. Strategy:

1. **Incremental analysis:** Only re-analyze regions that changed (use `IDocumentExtension.IIncrementalRulerColumn` pattern)
2. **Cached surfaces:** `ConstraintSurface` objects are cached per-file and invalidated on document change
3. **Background jobs:** Analysis runs in Eclipse `Job` with `INTERACTIVE` priority (same as existing `CompletionJob`)
4. **Debouncing:** Analysis is triggered with a 300ms debounce after last keystroke (not on every keystroke)
5. **Lazy primitives:** Each constraint primitive is computed independently and only when needed for display

```java
public class ConstraintAnalyzerEngine {
    
    private final Map<String, ConstraintSurface> cache = new ConcurrentHashMap<>();
    private final DebouncingJob analyzerJob;
    
    public ConstraintSurface analyze(IFile file, IDocument document) {
        String key = file.getFullPath().toString();
        return cache.computeIfAbsent(key, k -> computeSurface(file, document));
    }
    
    public void invalidate(IFile file) {
        cache.remove(file.getFullPath().toString());
        analyzerJob.schedule(300); // debounced
    }
}
```

---

## 8. Eclipse Extension Points Used

| Extension Point | Purpose | Existing in copilot-for-eclipse? |
|----------------|---------|:-:|
| `org.eclipse.ui.workbench.texteditor.codeMiningProviders` | Inline constraint depth annotations | ✅ Yes (GhostTextProvider) |
| `org.eclipse.ui.workbench.texteditor.rulerColumns` | Constraint gutter icons | ✅ Yes (RulerColumn for NES) |
| `org.eclipse.ui.editors.annotationTypes` | Constraint violation underlines | ✅ Yes (NES change/delete) |
| `org.eclipse.ui.editors.markerAnnotationSpecification` | Visual styling for annotations | ✅ Yes |
| `org.eclipse.core.resources.incrementalProjectBuilder` | Holonomy cycle detection builder | No — new |
| `org.eclipse.jdt.ui.quickFixProcessors` | Quick fixes for constraint violations | No — new |
| `org.eclipse.ui.preferencePages` | Terrain weight configuration | No — new |
| `org.eclipse.ui.views` | Constraint surface overview view | No — new |

---

## 9. The Constraint Surface View

A dedicated Eclipse view (`org.eclipse.ui.views`) that shows the full constraint surface for the current file:

```
┌─ Constraint Surface ─────────────────────────────────┐
│ File: PaymentProcessor.java                           │
│ Terrain: classical_counterpoint (Java)                │
│                                                       │
│ Overall Depth: 0.82 ●                                │
│                                                       │
│ ┌─ Primitives ──────────────────────────────────────┐ │
│ │ lattice_snap  ████████████████░░░░  0.92         │ │
│ │ funnel        ██████████████░░░░░░  0.78         │ │
│ │ is_laman      ██████████░░░░░░░░░░  0.63 ⚠       │ │
│ │ consensus     █████████████████░░░  0.88         │ │
│ │ holonomy      ████████████████████  1.00         │ │
│ └───────────────────────────────────────────────────┘ │
│                                                       │
│ Violations:                                           │
│   ⚠ Line 29: consensus 0.4 — unique PMD finding      │
│   ⚠ Line 45: rigidity 1.3 — 2 redundant deps         │
│                                                       │
│ Flow State: EXPLORING                                 │
│ Internalized: holonomy, consensus                     │
└───────────────────────────────────────────────────────┘
```

---

## 10. Summary: What This Builds

The Bathymetric Compiler transforms the Eclipse IDE from a surface that *displays code* into a surface that *feels the constraint geometry* of code. It does this by:

1. **Mapping five formal constraint primitives** to measurable code properties (type safety, navigation gravity, dependency rigidity, analyzer agreement, import cycles)
2. **Rendering constraint depth inline** via Eclipse's existing code mining infrastructure — always visible, but ignorable, like a depth sounder
3. **Classifying programming languages as terrains** with different constraint weight profiles
4. **Monitoring developer flow state** to adapt visibility and completion behavior — vanishing when patterns are internalized
5. **Wrapping the Copilot completion chain** to reorder and annotate suggestions based on constraint satisfaction

The result is an editor where the *geometry of possibility* is always perceptible. The developer navigates not just syntax, but the constraint surface — the landscape of what the code can and cannot become.

---

*This document describes a design for the most ambitious extension to copilot-for-eclipse: an IDE that understands constraint surfaces. Implementation should begin with the separate plugin approach (Section 7.2) and iterate from there.*
