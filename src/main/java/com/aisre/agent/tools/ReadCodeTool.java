package com.aisre.agent.tools;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Tool 4: read_code(file_path) -> contents of a source file.
 *
 * Phase 1 stub: returns the canned buggy OrderService sample regardless of path.
 * Phase 2+/stretch: would read the requested file from a real (synthetic) codebase.
 */
@Component
public class ReadCodeTool implements Tool {

    @Override
    public String name() {
        return "read_code";
    }

    @Override
    public String description() {
        return "Return the contents of a source file so the agent can inspect the suspect code.";
    }

    @Override
    public String execute(Map<String, String> args) {
        // file_path arg accepted but ignored in the stub.
        return Resources.read("sample-incident/OrderService.java");
    }
}
