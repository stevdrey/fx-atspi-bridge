/*
 * Copyright 2026 Steven Rey Sequeira
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.desktopcontrol.fxlab;

import javafx.application.*;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import dev.desktopcontrol.fxatspi.FxAtSpiBridge;

/** A real JavaFX UI; the provider publishes these same controls through AT-SPI2. */
public final class FxLab extends Application {
    private FxAtSpiBridge provider;
    @Override public void start(Stage stage) {
        var input = new TextField();
        input.setId("input"); input.setAccessibleText("Entrada");
        input.setPromptText("Escribe texto para agregarlo al área");
        var output = new TextArea();
        output.setId("output"); output.setAccessibleText("Contenido");
        output.setPrefRowCount(8);
        var append = new Button("Agregar"); append.setId("append");
        var clear = new Button("Limpiar"); clear.setId("clear");
        append.disableProperty().bind(input.textProperty().isEmpty());
        append.setOnAction(event -> output.appendText(input.getText() + "\n"));
        clear.setOnAction(event -> { input.clear(); output.clear(); });
        var status = new Label("Preparando puente AT-SPI2…");
        var box = new VBox(12, new Label("Laboratorio JavaFX / AT-SPI2"),
                new Label("Entrada"), input, new HBox(12, append, clear),
                new Label("Contenido editable"), output, status);
        var dynamic = new VBox(8);
        var addControl = new Button("Añadir campo");
        var removeControl = new Button("Quitar campo");
        addControl.setOnAction(event -> {
            if (dynamic.getChildren().isEmpty()) {
                var field = new TextField(); field.setAccessibleText("Campo dinámico");
                dynamic.getChildren().add(field);
            }
        });
        removeControl.setOnAction(event -> dynamic.getChildren().clear());
        var second = new Button("Abrir segunda ventana");
        second.setOnAction(event -> {
            var popup = new Stage(); popup.initOwner(stage); popup.setTitle("Ventana secundaria");
            var field = new TextField(); field.setAccessibleText("Entrada secundaria");
            var close = new Button("Cerrar segunda ventana"); close.setOnAction(e -> popup.close());
            var content = new VBox(12,field,close); content.setPadding(new Insets(24));
            popup.setScene(new Scene(content,360,160)); popup.show();
        });
        var change = new Button("Cambiar escena");
        change.setOnAction(event -> {
            Scene original=stage.getScene();
            var alternate = new TextArea(); alternate.setAccessibleText("Texto alternativo");
            var back = new Button("Volver al laboratorio"); back.setOnAction(e -> stage.setScene(original));
            var content = new VBox(12,alternate,back); content.setPadding(new Insets(24));
            stage.setScene(new Scene(content,760,620));
        });
        box.getChildren().addAll(new HBox(12,addControl,removeControl),dynamic,new HBox(12,second,change));
        box.setPadding(new Insets(24));
        stage.setTitle("Desktop Control — JavaFX AT-SPI Lab");
        stage.setScene(new Scene(box, 760, 620)); stage.show();
        if (getParameters().getRaw().contains("--no-atspi")) {
            status.setText("Puente desactivado: comparación con JavaFX sin proveedor."); return;
        }
        provider = FxAtSpiBridge.install(message -> Platform.runLater(() -> status.setText(message)));
    }
    @Override public void stop() { if (provider != null) provider.close(); }
}
