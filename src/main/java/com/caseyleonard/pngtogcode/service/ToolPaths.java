package com.caseyleonard.pngtogcode.service;

import java.nio.file.Path;

public record ToolPaths(
        Path appDirectory,
        Path toolsDirectory,
        Path cppExecutable,
        Path pythonExecutable,
        Path pythonScript
) {
}
