package com.caseyleonard.pngtogcode;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(MainApp.class.getResource("/com/caseyleonard/pngtogcode/main-view.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root, 1100, 760);
        scene.getStylesheets().add(MainApp.class.getResource("/com/caseyleonard/pngtogcode/application.css").toExternalForm());

        stage.setTitle("PNG to G-code Generator");
        stage.setScene(scene);
        stage.setMinWidth(1000);
        stage.setMinHeight(700);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
