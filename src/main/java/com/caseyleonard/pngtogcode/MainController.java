package com.caseyleonard.pngtogcode;

import com.caseyleonard.pngtogcode.model.GenerationSettings;
import com.caseyleonard.pngtogcode.service.PipelineService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;

public class MainController {

    @FXML private TextField pngField;
    @FXML private TextField outputField;
    @FXML private ComboBox<String> resolutionCombo;
    @FXML private ComboBox<String> paperSizeCombo;
    @FXML private Spinner<Integer> marginSpinner;
    @FXML private TextField feedrateField;
    @FXML private TextField zFeedrateField;
    @FXML private TextField zSafeField;
    @FXML private TextField zDrawField;
    @FXML private CheckBox saveSvgCheckBox;
    @FXML private Button generateButton;
    @FXML private Label statusLabel;
    @FXML private Label toolsStatusLabel;
    @FXML private TextArea logArea;

    private final PipelineService pipelineService = new PipelineService();

    @FXML
    private void initialize() {
        resolutionCombo.getItems().addAll(
                "1 - Very rough / fast",
                "2 - Low detail",
                "3 - Medium detail",
                "4 - High detail",
                "5 - Very high detail / slow"
        );
        resolutionCombo.setValue("3 - Medium detail");

        marginSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 100, 10)
        );

        paperSizeCombo.getItems().addAll("A0", "A1", "A2", "A3", "A4", "A5");
        paperSizeCombo.setValue("A4");

        feedrateField.setText("6000");
        zFeedrateField.setText("2000");
        zSafeField.setText("5.0");
        zDrawField.setText("0.0");
        saveSvgCheckBox.setSelected(true);
        statusLabel.setText("Ready");
        refreshToolsStatus();
    }

    @FXML
    private void onBrowsePng() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Input PNG");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG Files", "*.png"));
        File file = chooser.showOpenDialog(getStage());
        if (file != null) {
            pngField.setText(file.getAbsolutePath());
            if (outputField.getText() == null || outputField.getText().isBlank()) {
                File parent = file.getParentFile();
                if (parent != null) {
                    outputField.setText(parent.getAbsolutePath());
                }
            }
        }
    }

    @FXML
    private void onBrowseOutputFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Output Folder");
        File directory = chooser.showDialog(getStage());
        if (directory != null) {
            outputField.setText(directory.getAbsolutePath());
        }
    }

    @FXML
    private void onGenerate() {
        clearLog();
        GenerationSettings settings = readSettings();

        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                appendLog("GUI job started...");
                return pipelineService.generate(settings, MainController.this::appendLog);
            }
        };

        task.setOnRunning(event -> {
            generateButton.setDisable(true);
            statusLabel.setText("Running...");
        });

        task.setOnSucceeded(event -> {
            generateButton.setDisable(false);
            Path result = task.getValue();
            statusLabel.setText("Success");
            appendLog("Generated file: " + result.toAbsolutePath());
            refreshToolsStatus();
        });

        task.setOnFailed(event -> {
            generateButton.setDisable(false);
            statusLabel.setText("Failed");
            Throwable ex = task.getException();
            appendLog("ERROR: " + (ex == null ? "Unknown error" : ex.getMessage()));
            refreshToolsStatus();
        });

        Thread worker = new Thread(task, "png-to-gcode-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private GenerationSettings readSettings() {
        return new GenerationSettings(
                toPath(pngField.getText()),
                toPath(outputField.getText()),
                Integer.parseInt(resolutionCombo.getValue().substring(0, 1)),
                paperSizeCombo.getValue().substring(1),
                marginSpinner.getValue(),
                Double.parseDouble(feedrateField.getText().trim()),
                Double.parseDouble(zFeedrateField.getText().trim()),
                Double.parseDouble(zSafeField.getText().trim()),
                Double.parseDouble(zDrawField.getText().trim()),
                saveSvgCheckBox.isSelected()
        );
    }

    private Path toPath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Path.of(value.trim());
    }

    private Stage getStage() {
        return (Stage) generateButton.getScene().getWindow();
    }

    private void appendLog(String message) {
        Platform.runLater(() -> logArea.appendText(message + System.lineSeparator()));
    }

    private void clearLog() {
        logArea.clear();
    }

    private void refreshToolsStatus() {
        try {
            toolsStatusLabel.setText(pipelineService.describeResolvedTools());
        } catch (Exception e) {
            toolsStatusLabel.setText(e.getMessage());
        }
    }
}