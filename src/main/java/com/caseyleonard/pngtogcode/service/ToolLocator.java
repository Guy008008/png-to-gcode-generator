package com.caseyleonard.pngtogcode.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ToolLocator {

    public ToolPaths locate() {
        Path appDir = detectAppDirectory();
        Path toolsDir = locateToolsDirectory(appDir);
        Path cppExe = locateCppExecutable(toolsDir);
        Path pythonScript = locatePythonScript(toolsDir);
        Path pythonExe = locatePythonExecutable();
        return new ToolPaths(appDir, toolsDir, cppExe, pythonExe, pythonScript);
    }

    private Path detectAppDirectory() {
        try {
            Path codeSource = Path.of(ToolLocator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(codeSource)) {
                return codeSource.getParent();
            }
            return codeSource;
        } catch (Exception e) {
            return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        }
    }

    private Path locateToolsDirectory(Path appDir) {
        for (Path candidate : candidateToolsDirectories(appDir)) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }

        Path fallback = Path.of("tools").toAbsolutePath().normalize();
        throw new IllegalStateException("Missing tools folder. Expected one of: " + candidateToolsDirectories(appDir) + " | Current fallback: " + fallback);
    }

    private List<Path> candidateToolsDirectories(Path appDir) {
        Set<Path> candidates = new LinkedHashSet<>();
        Path currentDir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();

        candidates.add(currentDir.resolve("tools").normalize());
        candidates.add(appDir.resolve("tools").normalize());

        Path parent = appDir.getParent();
        if (parent != null) {
            candidates.add(parent.resolve("tools").normalize());

            Path grandParent = parent.getParent();
            if (grandParent != null) {
                candidates.add(grandParent.resolve("tools").normalize());
            }
        }

        return List.copyOf(candidates);
    }

    private Path locateCppExecutable(Path toolsDir) {
        List<String> candidates = List.of(
                "CircularScribbleArt.exe",
                "circularscribbleart.exe"
        );
        for (String candidate : candidates) {
            Path path = toolsDir.resolve(candidate);
            if (Files.exists(path)) {
                return path;
            }
        }
        throw new IllegalStateException("Missing C++ executable. Put CircularScribbleArt.exe in: " + toolsDir.toAbsolutePath());
    }

    private Path locatePythonScript(Path toolsDir) {
        List<String> candidates = List.of(
                "CGV_LAB_to_SVG_GCODE.py",
                "cgv_lab_to_svg_gcode.py"
        );
        for (String candidate : candidates) {
            Path path = toolsDir.resolve(candidate);
            if (Files.exists(path)) {
                return path;
            }
        }
        throw new IllegalStateException("Missing Python script. Put CGV_LAB_to_SVG_GCODE.py in: " + toolsDir.toAbsolutePath());
    }

    private Path locatePythonExecutable() {
        List<Path> candidates = new ArrayList<>();

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            candidates.add(Path.of(localAppData, "Programs", "Python", "Python313", "python.exe"));
            candidates.add(Path.of(localAppData, "Programs", "Python", "Python312", "python.exe"));
            candidates.add(Path.of(localAppData, "Programs", "Python", "Python311", "python.exe"));
            candidates.add(Path.of(localAppData, "Programs", "Python", "Python310", "python.exe"));
        }

        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null && !programFiles.isBlank()) {
            candidates.add(Path.of(programFiles, "Python313", "python.exe"));
            candidates.add(Path.of(programFiles, "Python312", "python.exe"));
            candidates.add(Path.of(programFiles, "Python311", "python.exe"));
            candidates.add(Path.of(programFiles, "Python310", "python.exe"));
        }

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        Path fromPath = findOnPath("python.exe");
        if (fromPath != null) {
            return fromPath;
        }

        throw new IllegalStateException("Python executable was not found automatically. Install Python or add python.exe to PATH.");
    }

    private Path findOnPath(String fileName) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            try {
                Path candidate = Path.of(dir, fileName);
                if (Files.exists(candidate)) {
                    return candidate;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public static String describe(ToolPaths toolPaths) {
        return String.format(Locale.US,
                "Using tools folder: %s | C++: %s | Python: %s | Script: %s",
                toolPaths.toolsDirectory().toAbsolutePath(),
                toolPaths.cppExecutable().getFileName(),
                toolPaths.pythonExecutable().toAbsolutePath(),
                toolPaths.pythonScript().getFileName());
    }
}