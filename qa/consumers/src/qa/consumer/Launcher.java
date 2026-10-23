package qa.consumer;

import dev.desktopcontrol.fxatspi.AutomationIds;
import dev.desktopcontrol.fxatspi.BridgeOptions;
import dev.desktopcontrol.fxatspi.FxAtSpiBridge;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Compiled solely against the Maven publication. No bridge internals or test dependencies. */
public final class Launcher {
    public static void main(String[] args) { Application.launch(ConsumerApp.class, args); }
    public static final class ConsumerApp extends Application {
        private FxAtSpiBridge bridge;
        @Override public void start(Stage stage) {
            var result = new Label("0"); AutomationIds.set(result, "consumer-result");
            var action = new Button("Increment"); AutomationIds.set(action, "consumer-action");
            action.setOnAction(_ -> result.setText("1"));
            stage.setTitle("SC10 Consumer"); stage.setScene(new Scene(new VBox(action,result),300,150)); stage.show();
            bridge = FxAtSpiBridge.installConfigured(BridgeOptions.builder().applicationName("SC10 Consumer").build());
            bridge.closed().thenRun(() -> { System.out.println("CONSUMER_CLOSED"); Platform.runLater(Platform::exit); });
            Thread.ofPlatform().daemon().name("consumer-stop").start(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(System.in))) { reader.readLine(); }
                catch (Exception failure) { System.out.println("CONSUMER_INPUT_ERROR"); }
                Platform.runLater(bridge::close);
            });
        }
        @Override public void stop() { if (bridge != null) bridge.close(); }
    }
}
