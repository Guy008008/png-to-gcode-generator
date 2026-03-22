package com.caseyleonard.pngtogcode.service;

import com.caseyleonard.pngtogcode.model.GenerationSettings;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class PipelineService {

    private static final Set<String> SUPPORTED_INPUT_EXTENSIONS = Set.of(
            "png",
            "jpg",
            "jpeg",
            "tif",
            "tiff",
            "bmp"
    );

    private final ToolLocator toolLocator = new ToolLocator();

    public Path generate(GenerationSettings settings, Consumer<String> logSink)
            throws IOException, InterruptedException {
        validate(settings);

        PreparedInput preparedInput = prepareInput(settings, logSink);
        ToolPaths toolPaths = toolLocator.locate();
        Files.createDirectories(settings.outputDirectory());
        Set<Path> directoriesBeforeRun = listDirectories(preparedInput.pipelineInput().getParent());

        try {
            logSink.accept(ToolLocator.describe(toolPaths));

            logSink.accept("Starting native image pipeline...");
            runCppStage(preparedInput.pipelineInput(), toolPaths, logSink);

            logSink.accept("Moving native output into selected output folder...");
            moveOutputFolder(preparedInput.pipelineInput(), settings.outputDirectory(), directoriesBeforeRun, logSink);

            logSink.accept("Looking for newest sequence file...");
            Path sequenceFile = findNewestSequenceFile(settings.outputDirectory());
            logSink.accept("Sequence file found: " + sequenceFile);

            logSink.accept("Starting Python G-code conversion...");
            runPythonStage(settings, toolPaths, sequenceFile, logSink);

            Path gcodeFile = findNewestGcodeFile(settings.outputDirectory());
            logSink.accept("Done. G-code created at: " + gcodeFile);
            return gcodeFile;
        } finally {
            cleanupPreparedInput(preparedInput, logSink);
        }
    }

    public String describeResolvedTools() {
        return ToolLocator.describe(toolLocator.locate());
    }

    private void validate(GenerationSettings settings) {
        if (settings.inputPng() == null || !Files.exists(settings.inputPng())) {
            throw new IllegalArgumentException("Input image not found.");
        }
        String filename = settings.inputPng().getFileName().toString();
        int dotIndex = filename.lastIndexOf('.');
        String extension = dotIndex >= 0 ? filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT) : "";
        if (!SUPPORTED_INPUT_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    "Unsupported input image type. Supported formats: PNG, JPG, JPEG, TIF, TIFF, BMP."
            );
        }
        if (settings.outputDirectory() == null) {
            throw new IllegalArgumentException("Output folder not selected.");
        }
    }

    private PreparedInput prepareInput(GenerationSettings settings, Consumer<String> logSink) throws IOException {
        Path inputPath = settings.inputPng().toAbsolutePath();
        if ("png".equals(getExtension(inputPath))) {
            return new PreparedInput(inputPath, null);
        }

        logSink.accept("Converting input image to PNG for native processing...");
        BufferedImage sourceImage = ImageIO.read(inputPath.toFile());
        if (sourceImage == null) {
            throw new IOException("Unable to read input image format: " + inputPath);
        }

        Path tempDirectory = Files.createTempDirectory("png-to-gcode-input-");
        Path convertedPng = tempDirectory.resolve("pipeline-input.png");

        if (!ImageIO.write(sourceImage, "png", convertedPng.toFile())) {
            throw new IOException("Unable to convert input image to PNG: " + inputPath);
        }

        logSink.accept("Temporary PNG created at: " + convertedPng);
        return new PreparedInput(convertedPng, tempDirectory);
    }

    private void cleanupPreparedInput(PreparedInput preparedInput, Consumer<String> logSink) {
        if (preparedInput.temporaryDirectory() == null) {
            return;
        }
        try {
            deleteDirectory(preparedInput.temporaryDirectory());
            logSink.accept("Cleaned up temporary converted input: " + preparedInput.temporaryDirectory());
        } catch (IOException e) {
            logSink.accept("WARNING: Failed to delete temporary converted input: " + preparedInput.temporaryDirectory());
            logSink.accept("WARNING: " + e.getMessage());
        }
    }

    private void runCppStage(Path pipelineInput, ToolPaths toolPaths, Consumer<String> logSink)
            throws IOException, InterruptedException {
        List<String> command = List.of(
                toolPaths.cppExecutable().toAbsolutePath().toString(),
                pipelineInput.toAbsolutePath().toString()
        );

        int exit = ProcessUtils.runCommand(
                command,
                pipelineInput.getParent(),
                line -> logSink.accept("[CPP] " + line)
        );

        if (exit != 0) {
            throw new IOException("C++ stage failed with exit code " + exit);
        }
    }

    private void moveOutputFolder(Path pipelineInput,
                                  Path outputDirectory,
                                  Set<Path> directoriesBeforeRun,
                                  Consumer<String> logSink) throws IOException {
        Path inputDir = pipelineInput.getParent();
        String expectedPrefix = pipelineInput.getFileName().toString() + "_";

        logSink.accept("Input dir for native output search: " + inputDir);
        logSink.accept("Expected native output prefix: " + expectedPrefix);
        logSink.accept("Selected output dir: " + outputDirectory);

        List<Path> candidates = findNewDirectories(inputDir, directoriesBeforeRun);
        if (candidates.isEmpty()) {
            logSink.accept("No newly created directories detected; falling back to prefix-based search.");
            try (var stream = Files.list(inputDir)) {
                candidates = stream
                        .filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().startsWith(expectedPrefix))
                        .toList();
            }
        }

        logSink.accept("Native output candidates found: " + candidates.size());
        for (Path candidate : candidates) {
            logSink.accept("Candidate: " + candidate);
        }

        Path newest = candidates.stream()
                .max(Comparator.comparingLong(path -> path.toFile().lastModified()))
                .orElseThrow(() -> new IOException(
                        "No native output folder was found in input directory: " + inputDir));

        Path target = outputDirectory.resolve(newest.getFileName());

        logSink.accept("Chosen native output folder: " + newest);
        logSink.accept("Copying native output folder to: " + target);

        copyDirectory(newest, target);

        logSink.accept("Copy finished. Verifying target exists...");
        logSink.accept("Target exists: " + Files.exists(target));

        deleteDirectory(newest);

        logSink.accept("Delete finished. Source still exists: " + Files.exists(newest));
        logSink.accept("Move complete.");
    }

    private Set<Path> listDirectories(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(path -> path.toAbsolutePath().normalize())
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    private List<Path> findNewDirectories(Path directory, Set<Path> directoriesBeforeRun) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(path -> path.toAbsolutePath().normalize())
                    .filter(path -> !directoriesBeforeRun.contains(path))
                    .toList();
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

    private Path findNewestGcodeFile(Path outputDirectory) throws IOException {
        Path generatedOutputDirectory = outputDirectory.resolve("output");
        try (Stream<Path> stream = Files.walk(generatedOutputDirectory, 2)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gcode"))
                    .max(Comparator.comparing(this::lastModifiedSafe))
                    .orElseThrow(() -> new IOException(
                            "Python stage finished, but no G-code file was found in: " + generatedOutputDirectory));
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

    private String getExtension(Path path) {
        String filename = path.getFileName().toString();
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private record PreparedInput(Path pipelineInput, Path temporaryDirectory) {
    }
}