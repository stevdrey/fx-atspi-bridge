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

import dev.desktopcontrol.fxatspi.BridgeOptions;
import dev.desktopcontrol.fxatspi.FxAtSpiBridge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.stage.Screen;
import javafx.stage.Stage;

/** Disposable, synthetic desktop fixture. Numeric reference bounds are analytic, not read from bridge internals. */
public final class GeometryApp extends Application {
    private static final List<String> CASES = List.of("initial", "window-move", "window-resize", "scale", "rotation",
            "own-clip", "ancestor-clip", "clip-resize", "empty-clip", "hidden", "restored", "reparent", "scene-replace");
    private Rectangle target;
    private Rectangle clip;
    private Pane first;
    private Pane second;
    private Button next;
    private FxAtSpiBridge bridge;
    private Stage stage;
    private Path output;
    private int step;

    @Override public void start(Stage value) throws IOException {
        stage = value;
        var argument = getParameters().getRaw().stream().filter(arg -> arg.startsWith("--geometry-out=")).findFirst().orElseThrow();
        output = Path.of(argument.substring("--geometry-out=".length())).toAbsolutePath();
        Files.createFile(output); // refuse to replace an unrelated pre-existing file
        target = new Rectangle(80, 50);
        clip = new Rectangle(50, 100);
        first = new Pane(target);
        second = new Pane();
        next = new Button("Next geometry case");
        target.setAccessibleText("Geometry target");
        target.relocate(10, 10);
        first.setManaged(false);
        first.resizeRelocate(20, 30, 200, 150);
        second.setManaged(false);
        second.resizeRelocate(220, 40, 200, 150);
        next.setManaged(false);
        next.resizeRelocate(10, 270, 180, 32);
        next.setDisable(true);
        next.setOnAction(_ -> {
            if (step + 1 >= CASES.size()) return;
            next.setDisable(true);
            step++;
            change();
            checkpoint();
        });
        stage.setTitle("AT-SPI Geometry QA");
        stage.setScene(new Scene(new Pane(first, second, next), 520, 380));
        stage.show();
        bridge = FxAtSpiBridge.installConfigured(BridgeOptions.builder().applicationName("Geometry QA").build());
        checkpoint();
    }

    private void change() {
        switch (step) {
            case 1 -> { stage.setX(stage.getX() + 45); stage.setY(stage.getY() + 30); }
            case 2 -> { stage.setWidth(stage.getWidth() - 60); stage.setHeight(stage.getHeight() - 20); }
            case 3 -> target.getTransforms().setAll(new Scale(1.5, 1.25, 0, 0));
            case 4 -> target.getTransforms().setAll(new Rotate(90, 0, 0));
            case 5 -> { target.getTransforms().clear(); target.setClip(new Rectangle(35, 50)); }
            case 6 -> { target.setClip(null); first.setClip(clip); }
            case 7 -> clip.setWidth(65);
            case 8 -> clip.setWidth(0);
            case 9 -> target.setVisible(false);
            case 10 -> { first.setClip(null); target.setVisible(true); }
            case 11 -> { first.getChildren().remove(target); second.getChildren().add(target); }
            case 12 -> {
                var old = (Pane) stage.getScene().getRoot();
                old.getChildren().removeAll(second, next);
                stage.setScene(new Scene(new Pane(second, next), 460, 340));
            }
            default -> throw new IllegalStateException("Unknown fixture step");
        }
    }

    private void checkpoint() {
        var scene = stage.getScene();
        scene.addPostLayoutPulseListener(new Runnable() {
            private List<Double> previous = List.of();
            private int pulses, stable;

            @Override public void run() {
                var metrics = List.of(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(),
                        scene.getX(), scene.getY(), scene.getWidth(), scene.getHeight(), stage.getOutputScaleX(), stage.getOutputScaleY());
                stable = metrics.equals(previous) ? stable + 1 : 0;
                previous = metrics;
                pulses++;
                // WM placement/resize acknowledgements are asynchronous even after a JavaFX layout pulse.
                var minimum = step <= 2 || step == 12 ? 30 : 4;
                if (pulses < 120 && (stable < 4 || pulses < minimum)) { Platform.requestNextPulse(); return; }
                scene.removePostLayoutPulseListener(this);
                if (stable < 4) { System.err.println("Geometry fixture did not settle"); Platform.exit(); return; }
                Platform.runLater(() -> {
                    var reference = reference();
                    // Only one checkpoint can be pending. File IO never holds the FX or provider owner.
                    CompletableFuture.runAsync(() -> {
                        try {
                            var temporary = output.resolveSibling(output.getFileName() + ".new");
                            Files.writeString(temporary, reference);
                            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                            Platform.runLater(() -> next.setDisable(step + 1 == CASES.size()));
                        } catch (IOException failure) { System.err.println("Geometry fixture write failed"); Platform.exit(); }
                    });
                });
            }
        });
        Platform.requestNextPulse();
    }

    private static String rectangle(double x, double y, double right, double bottom) {
        var left = Math.floor(x);
        var top = Math.floor(y);
        var width = right == x ? 0 : Math.ceil(right) - left;
        var height = bottom == y ? 0 : Math.ceil(bottom) - top;
        return "[" + (int) left + "," + (int) top + "," + (int) width + "," + (int) height + "]";
    }

    /** Explicit expected shape/clip coordinates for each case, independent of GeometryProjector. */
    private String reference() {
        var x = step >= 11 ? 230.0 : step == 4 ? -20.0 : 30.0;
        var y = step >= 11 ? 50.0 : 40.0;
        var right = step >= 11 ? 310.0 : step == 3 ? 150.0 : step == 4 ? 30.0 : step == 5 ? 65.0
                : step == 6 ? 70.0 : step == 7 ? 85.0 : step == 8 ? 30.0 : 110.0;
        var bottom = step >= 11 ? 100.0 : step == 3 ? 102.5 : step == 4 ? 120.0 : 90.0;
        var scene = stage.getScene();
        x = Math.max(x, 0);
        y = Math.max(y, 0);
        right = Math.max(x, Math.min(right, scene.getWidth()));
        bottom = Math.max(y, Math.min(bottom, scene.getHeight()));
        var origin = scene.getRoot().localToScreen(0, 0);
        var hidden = step == 9;
        var screen = hidden || origin == null ? "null" : rectangle(x + origin.getX(), y + origin.getY(), right + origin.getX(), bottom + origin.getY());
        var window = hidden ? "null" : rectangle(x + scene.getX(), y + scene.getY(), right + scene.getX(), bottom + scene.getY());
        var parentX = step >= 11 ? 220.0 : 20.0;
        var parentY = step >= 11 ? 40.0 : 30.0;
        var parent = hidden ? "null" : rectangle(x - parentX, y - parentY, right - parentX, bottom - parentY);
        return "{\"step\":" + step + ",\"case\":\"" + CASES.get(step) + "\",\"count\":" + CASES.size()
                + ",\"rectangles\":[" + screen + "," + window + "," + parent + "],\"screens\":" + Screen.getScreens().size()
                + ",\"outputScale\":[" + stage.getOutputScaleX() + "," + stage.getOutputScaleY() + "]}";
    }

    @Override public void stop() { if (bridge != null) bridge.close(); }
}
