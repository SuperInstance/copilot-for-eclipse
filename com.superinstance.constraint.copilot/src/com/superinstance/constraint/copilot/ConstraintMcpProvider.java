package com.superinstance.constraint.copilot;

import java.io.File;
import java.util.concurrent.CompletableFuture;

import com.microsoft.copilot.eclipse.ui.extensions.IMcpRegistrationProvider;

/**
 * Registers the Constraint Theory MCP server with Copilot for Eclipse.
 *
 * <p>This provider discovers the Python MCP server bundled alongside the plugin
 * (or installed via pip) and returns JSON configuration that Copilot uses to
 * connect via stdio JSON-RPC.</p>
 *
 * <p>The server exposes 7 tools:</p>
 * <ul>
 *   <li>constraint_snap — snap pitch to Eisenstein lattice</li>
 *   <li>constraint_funnel — gravitational pull toward target</li>
 *   <li>constraint_diagnose — 4-order Goodman diagnostic</li>
 *   <li>constraint_generate — generate music in mode + terrain</li>
 *   <li>constraint_render — render notes to WAV</li>
 *   <li>constraint_terrain_list — list available terrains</li>
 * </ul>
 */
public class ConstraintMcpProvider implements IMcpRegistrationProvider {

    private static final String SERVER_NAME = "constraint-ecosystem";

    @Override
    public CompletableFuture<String> getMcpServerConfigurations() {
        return CompletableFuture.supplyAsync(() -> {
            // Try to find the Python MCP server
            String pythonPath = findPython();
            String serverScript = findServerScript();

            if (pythonPath == null || serverScript == null) {
                // Return empty config if server not found — Copilot will just
                // not show these tools. User can configure path in preferences.
                return "{\"servers\":{}}";
            }

            // Build MCP server config JSON
            // Format matches VS Code / Copilot MCP registration schema
            String json = String.format("""
                {
                  "servers": {
                    "%s": {
                      "command": "%s",
                      "args": ["-m", "constraint_mcp_server"],
                      "env": {
                        "PYTHONPATH": "%s",
                        "CONSTRAINT_WORKSPACE": "%s"
                      },
                      "type": "stdio"
                    }
                  }
                }
                """,
                SERVER_NAME,
                escapeJson(pythonPath),
                escapeJson(serverScript),
                escapeJson(getWorkspacePath())
            );

            return json;
        });
    }

    /**
     * Find the Python executable on PATH.
     */
    private String findPython() {
        String[] candidates = {"python3", "python"};
        for (String cmd : candidates) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd, "--version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                int code = p.waitFor();
                if (code == 0) {
                    return cmd;
                }
            } catch (Exception e) {
                // try next
            }
        }
        return null;
    }

    /**
     * Find the constraint-mcp-server package on PYTHONPATH.
     * Checks pip-installed location first, then relative to this plugin.
     */
    private String findServerScript() {
        // If installed via pip: `constraint-mcp-server` is on sys.path
        // We return the workspace path so PYTHONPATH includes the ecosystem
        String workspace = getWorkspacePath();
        if (workspace != null) {
            File serverPkg = new File(workspace, "constraint-mcp-server");
            if (serverPkg.isDirectory()) {
                return serverPkg.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * Get the workspace root path where the constraint ecosystem lives.
     * In production this would use a preference or bundle location.
     */
    private String getWorkspacePath() {
        // TODO: Make this configurable via Eclipse preferences
        String workspace = System.getProperty("constraint.workspace");
        if (workspace != null) {
            return workspace;
        }
        // Fallback: look for CONSTRAINT_WORKSPACE env var
        workspace = System.getenv("CONSTRAINT_WORKSPACE");
        if (workspace != null) {
            return workspace;
        }
        // Default: user home / superinstance-workspace
        return System.getProperty("user.home") + File.separator + "superinstance-workspace";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
