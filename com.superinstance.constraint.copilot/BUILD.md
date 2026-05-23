# Building the Constraint Copilot Eclipse Plugin

## Prerequisites

- **Java 17+** (JDK, not JRE)
- **Maven 3.9+**
- **Eclipse 4.34+** target platform (handled by Tycho + `base.target`)

## Quick Build

```bash
cd copilot-for-eclipse
mvn clean install -DskipTests
```

This runs the full Tycho reactor build including our two new modules:
- `com.superinstance.constraint.copilot` — the plugin bundle
- `com.superinstance.constraint.feature` — the Eclipse feature

## Build Just Our Modules

```bash
cd copilot-for-eclipse
mvn clean install -pl com.superinstance.constraint.copilot,com.superinstance.constraint.feature -am -DskipTests
```

The `-am` flag also builds required upstream modules (core, ui, etc.).

## What Gets Built

| Module | Output |
|--------|--------|
| `com.superinstance.constraint.copilot` | OSGi bundle JAR in `target/` |
| `com.superinstance.constraint.feature` | Feature JAR (for p2 update site) |

## How It Works

1. **`ConstraintMcpProvider`** implements `IMcpRegistrationProvider` from the Copilot Eclipse UI bundle.
2. At runtime, the `plugin.xml` extension point `com.microsoft.copilot.eclipse.ui.mcpRegistration` registers our provider.
3. Copilot calls `getMcpServerConfigurations()` which returns JSON pointing to the Python `constraint_mcp_server`.
4. If Python + the server package aren't found, it gracefully returns an empty config (no tools shown, no errors).

## Installing in Eclipse

After building, install the feature via:
1. **Help → Install New Software → Add → Archive…** — point to `com.microsoft.copilot.eclipse.repository/target/repository/`
2. Or drop the built JARs directly into Eclipse's `dropins/` folder.

## Troubleshooting

- **"Cannot resolve IMcpRegistrationProvider"**: The Copilot UI bundle must build first. Use `-am` or build the full reactor.
- **Checkstyle failures**: Use `-Dcheckstyle.skip=true` to bypass.
- **Target platform errors**: Ensure `base.target` references a valid Eclipse 4.34+ target definition.
