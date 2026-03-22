package com.caseyleonard.pngtogcode.model;

import java.nio.file.Path;

public record GenerationSettings(
        Path inputPng,
        Path outputDirectory,
        int resolution,
        String paperSize,
        int paperMargin,
        double feedrate,
        double zFeedrate,
        double zSafe,
        double zDraw,
        boolean saveSvg
) {
}
