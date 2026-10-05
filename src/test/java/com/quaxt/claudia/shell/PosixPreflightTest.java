package com.quaxt.claudia.shell;

import com.quaxt.claudia.agent.AgentTool;
import com.quaxt.claudia.ClaudiaOperations;
import com.quaxt.claudia.ai.util.AbortSignal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class PosixPreflightTest {
    @TempDir Path cwd;

    @Test
    void detectsGitToolsAheadOfMsys2AndReportsMissingPackagesAndUnwritableTemp() throws Exception {
        Path msys = Files.createDirectories(cwd.resolve("msys2/usr/bin"));
        Path git = Files.createDirectories(cwd.resolve("Git/usr/bin"));
        for (String name : List.of("bash.exe", "sh.exe", "find.exe", "mktemp.exe")) {
            Files.writeString(msys.resolve(name), "");
            Files.writeString(git.resolve(name), "");
        }
        Map<String, String> environment = Map.of(
                "PATH", git + ";" + msys,
                "TMPDIR", cwd.resolve("does-not-exist").toString());
        AgentTool.ToolResult result = PosixPreflight.inspect(cwd, "msys2", cwd.resolve("msys2").toString(),
                environment, List.of(), false);
        Map<?, ?> details = (Map<?, ?>) result.details;
        assertTrue(result.isError);
        assertTrue(((List<?>) details.get("mixed_tools")).stream().anyMatch(item -> item.toString().startsWith("bash ")));
        assertTrue(((List<?>) details.get("missing_tools")).contains("zip"));
        assertEquals("zip", ((Map<?, ?>) details.get("missing_packages")).get("zip"));
        assertEquals(false, details.get("tmpdir_writable"));
        assertTrue(((List<?>) details.get("remediation")).toString().contains("TMPDIR"));
        assertTrue(((List<?>) details.get("remediation")).toString().contains("PATH"));

        try (var runtime = new ClaudiaOperations()) {
            AgentTool tool = runtime.builtInTools(cwd, ignored -> {}).stream()
                    .filter(candidate -> ClaudiaOperations.toolName(candidate).equals("preflight_posix"))
                    .findFirst().orElseThrow();
            var args = ClaudiaOperations.jsonObject().put("layer", "msys2")
                    .put("installation_root", cwd.resolve("msys2").toString()).put("inherit_environment", false);
            args.putObject("environment").put("PATH", environment.get("PATH"))
                    .put("TMPDIR", environment.get("TMPDIR"));
            AgentTool.ToolResult toolResult = runtime.executeTool(tool, "test", args, new AbortSignal(), ignored -> {});
            assertTrue(toolResult.isError);
            assertEquals(details.get("mixed_tools"), ((Map<?, ?>) toolResult.details).get("mixed_tools"));
        }
    }
}
