package com.caseyleonard.pngtogcode.service;

import com.caseyleonard.pngtogcode.model.GenerationSettings;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class PipelineService {

    private final ToolLocator toolLocator = new ToolLocator();

    public Path generate(GenerationSettings settings, Consumer<String> logSink)
            throws IOException, InterruptedException {
        validate(settings);

        ToolPaths toolPaths = toolLocator.locate();
        Files.createDirectories(settings.outputDirectory());
        logSink.accept(ToolLocator.describe(toolPaths));

        logSink.accept("Starting native image pipeline...");
        runCppStage(settings, toolPaths, logSink);

        logSink.accept("Moving native output into selected output folder...");
        moveOutputFolder(settings, logSink);

        logSink.accept("Looking for newest sequence file...");
        Path sequenceFile = findNewestSequenceFile(settings.outputDirectory());
        logSink.accept("Sequence file found: " + sequenceFile);

        logSink.accept("Starting Python G-code conversion...");
        runPythonStage(settings, toolPaths, sequenceFile, logSink);

        Path gcodeFile = deriveExpectedGcodePath(settings.outputDirectory(), settings);
        if (!Files.exists(gcodeFile)) {
            throw new IOException("Python stage finished, but expected G-code file was not found: " + gcodeFile);
        }

        logSink.accept("Done. G-code created at: " + gcodeFile);
        return gcodeFile;
    }

    public String describeResolvedTools() {
        return ToolLocator.describe(toolLocator.locate());
    }

    private void validate(GenerationSettings settings) {
        if (settings.inputPng() == null || !Files.exists(settings.inputPng())) {
            throw new IllegalArgumentException("Input PNG not found.");
        }
        if (settings.outputDirectory() == null) {
            throw new IllegalArgumentException("Output folder not selected.");
        }
    }

    private void runCppStage(GenerationSettings settings, ToolPaths toolPaths, Consumer<String> logSink)
            throws IOException, InterruptedException {
        List<String> command = List.of(
                toolPaths.cppExecutable().toAbsolutePath().toString(),
                settings.inputPng().toAbsolutePath().toString()
        );

        int exit = ProcessUtils.runCommand(
                command,
                settings.inputPng().getParent(),
                line -> logSink.accept("[CPP] " + line)
        );

        if (exit != 0) {
            throw new IOException("C++ stage failed with exit code " + exit);
        }
    }

    private void moveOutputFolder(GenerationSettings settings, Consumer<String> logSink) throws IOException {
    Path inputDir = settings.inputPng().getParent();
    String expectedPrefix = settings.inputPng().getFileName().toString() + "_";

    logSink.accept("Input dir for native output search: " + inputDir);
    logSink.accept("Expected native output prefix: " + expectedPrefix);
    logSink.accept("Selected output dir: " + settings.outputDirectory());

    try (var stream = Files.list(inputDir)) {
        List<Path> candidates = stream
                .filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().startsWith(expectedPrefix))
                .toList();

        logSink.accept("Native output candidates found: " + candidates.size());
        for (Path candidate : candidates) {
            logSink.accept("Candidate: " + candidate);
        }

        Path newest = candidates.stream()
                .max((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified()))
                .orElseThrow(() -> new IOException(
                        "No native output folder was found in input directory: " + inputDir));

        Path target = settings.outputDirectory().resolve(newest.getFileName());

        logSink.accept("Chosen native output folder: " + newest);
        logSink.accept("Copying native output folder to: " + target);

        copyDirectory(newest, target);

        logSink.accept("Copy finished. Verifying target exists...");
        logSink.accept("Target exists: " + Files.exists(target));

        deleteDirectory(newest);

        logSink.accept("Delete finished. Source still exists: " + Files.exists(newest));
        logSink.accept("Move complete.");
    }
    
}
        private void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);

                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    if (destination.getParent() != null) {
                        Files.createDirectories(destination.getParent());
                    }
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteDirectory(Path directory) throws IOException {
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Path findNewestSequenceFile(Path outputDirectory) throws IOException {
        try (Stream<Path> stream = Files.walk(outputDirectory, 3)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).contains("_sequence_"))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .max(Comparator.comparing(this::lastModifiedSafe))
                    .orElseThrow(() -> new IOException(
                            "No sequence text file was found. Make sure the native app wrote its output into the selected output folder."));
        }
    }

    private FileTime lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    private void runPythonStage(GenerationSettings settings, ToolPaths toolPaths, Path sequenceFile, Consumer<String> logSink)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(toolPaths.pythonExecutable().toAbsolutePath().toString());
        command.add(toolPaths.pythonScript().toAbsolutePath().toString());
        command.add(sequenceFile.toAbsolutePath().toString());
        command.add("--resolution");
        command.add(String.valueOf(settings.resolution()));
        command.add("--save_gcode");
        command.add("1");
        command.add("--save_svg");
        command.add(settings.saveSvg() ? "1" : "0");
        command.add("--paper_size");
        command.add(settings.paperSize());
        command.add("--paper_margin");
        command.add(String.valueOf(settings.paperMargin()));
        command.add("--feedrate");
        command.add(String.valueOf(settings.feedrate()));
        command.add("--z_feedrate");
        command.add(String.valueOf(settings.zFeedrate()));
        command.add("--z_safe");
        command.add(String.valueOf(settings.zSafe()));
        command.add("--z_draw");
        command.add(String.valueOf(settings.zDraw()));

        int exit = ProcessUtils.runCommand(
                command,
                settings.outputDirectory(),
                line -> logSink.accept("[PY] " + line)
        );

        if (exit != 0) {
            throw new IOException("Python stage failed with exit code " + exit);
        }
    }

    private Path deriveExpectedGcodePath(Path outputDirectory, GenerationSettings settings) {
        String baseName = settings.inputPng().getFileName().toString();
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) {
            baseName = baseName.substring(0, dot);
        }
        return outputDirectory.resolve("output").resolve(baseName + "_smooth.gcode");
    }
}