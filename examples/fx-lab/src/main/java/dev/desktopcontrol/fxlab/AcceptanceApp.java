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

import dev.desktopcontrol.fxatspi.ActionLimits;
import dev.desktopcontrol.fxatspi.AutomationIds;
import dev.desktopcontrol.fxatspi.BridgeOptions;
import dev.desktopcontrol.fxatspi.FxAtSpiBridge;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/** Disposable SC-10 fixture. The stdin control channel is test infrastructure, never library transport. */
public final class AcceptanceApp extends Application {
    private FxAtSpiBridge bridge;
    private final CountDownLatch release = new CountDownLatch(1);
    private int effects;
    private Label result;

    private static <T extends Node> T id(T node, String value) {
        AutomationIds.set(node, value);
        return node;
    }
    private Button button(String name, Runnable action) {
        var button = id(new Button(name), name);
        button.setOnAction(_ -> action.run());
        return button;
    }
    private void effect() { result.setText(Integer.toString(++effects)); }
    @Override public void start(Stage stage) {
        Platform.setImplicitExit(false);
        result = id(new Label("0"), "effects");
        var input = id(new TextField(), "unicode");
        var password = new PasswordField(); password.setText("SC10_SECRET_PASSWORD");
        password.setAccessibleText("SC10_SECRET_PASSWORD_NAME");
        var sensitive = id(new TextField("SC10_SECRET_TEXT"), "sensitive"); sensitive.setId("sensitive");
        var excluded = id(new Label("SC10_SECRET_EXCLUDED"), "excluded"); excluded.setId("excluded");
        var dynamic = new VBox();
        var root = new VBox(8, input, password, sensitive, excluded, result,
                button("private-change", () -> {
                    password.setText("SC10_SECRET_CHANGED_PASSWORD");
                    sensitive.setText("SC10_SECRET_CHANGED_TEXT");
                }));
        root.getChildren().addAll(
                button("add", () -> dynamic.getChildren().setAll(button("ephemeral", this::effect))),
                button("remove", () -> dynamic.getChildren().clear()), dynamic,
                button("second", () -> {
                    var second = new Stage(); second.initOwner(stage); second.setTitle("SC10 second");
                    second.setScene(new Scene(new VBox(id(new TextField(), "second-input"),
                            button("close-second", second::close)), 300, 120)); second.show();
                }),
                button("scene", () -> {
                    var original = stage.getScene();
                    stage.setScene(new Scene(new VBox(id(new TextField(), "alternate"),
                            button("restore", () -> stage.setScene(original))), 400, 300));
                }),
                button("modal", () -> {
                    var dialog = new Stage(); dialog.initOwner(stage); dialog.initModality(Modality.WINDOW_MODAL);
                    dialog.setTitle("SC10 modal");
                    dialog.setScene(new Scene(new VBox(button("confirm", dialog::close)), 240, 120));
                    dialog.showAndWait(); effect();
                }),
                button("hold", () -> {
                    System.out.println("QA_HOLD");
                    try {
                        if (!release.await(15, TimeUnit.SECONDS)) System.out.println("QA_HOLD_WATCHDOG");
                    } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }), button("queued", this::effect), button("overflow", this::effect));
        stage.setTitle("SC10 Acceptance"); stage.setScene(new Scene(root, 500, 650)); stage.show();
        bridge = FxAtSpiBridge.installConfigured(BridgeOptions.builder().applicationName("SC10 Acceptance")
                .sensitiveNodes(node -> "sensitive".equals(node.getId()))
                .excludedNodes(node -> "excluded".equals(node.getId()))
                .actionLimits(new ActionLimits(2, 2, Duration.ofMillis(1500), Duration.ofSeconds(10))).build(),
                message -> System.out.println(message.startsWith("Error") ? "QA_PROVIDER_ERROR" : "QA_READY"));
        bridge.closed().thenRun(() -> System.out.println("QA_CLOSED"));
        Thread.ofPlatform().daemon().name("acceptance-control").start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
                String command;
                while ((command = reader.readLine()) != null) {
                    switch (command) {
                        case "release" -> release.countDown(); // no JavaFX objects on this thread
                        case "pulse" -> Platform.runLater(() -> System.out.println("QA_PULSE"));
                        case "close" -> Platform.runLater(bridge::close);
                        case "exit" -> { release.countDown(); Platform.runLater(Platform::exit); return; }
                        default -> { System.out.println("QA_BAD_COMMAND"); return; }
                    }
                }
            } catch (Exception failure) { System.out.println("QA_CONTROL_ERROR"); }
            release.countDown();
            Platform.runLater(Platform::exit);
        });
    }
    @Override public void stop() { release.countDown(); if (bridge != null) bridge.close(); }
}
