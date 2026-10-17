import dev.desktopcontrol.fxatspi.BridgeOptions;
import dev.desktopcontrol.fxatspi.FxAtSpiBridge;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Set;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Synthetic data only. Independent libatspi/Gio clients verify this app on the real session bus. */
public class ConfigurationSmokeApp extends Application {
    private FxAtSpiBridge bridge;
    private BridgeOptions options;
    private TextField field;
    public static void main(String[] args) { launch(args); }
    private static void say(String value) { System.out.println(value); System.out.flush(); }
    @Override public void start(Stage stage) {
        field = new TextField("PUBLIC_BEFORE_POLICY");
        field.setAccessibleText("Field");
        var password = new PasswordField();
        password.setText("PASSWORD_ONLY");
        password.setAccessibleText("PASSWORD_ONLY");
        var button = new Button("Press");
        button.setOnAction(event -> say("FIRED"));
        stage.setTitle("Config SECRETMARKER");
        stage.setScene(new Scene(new VBox(field, password, button), 300, 150));
        stage.show();
        options = BridgeOptions.builder().applicationName("Config SECRETMARKER")
                .redactor((kind, sensitive, value) -> {
                    if (value.contains("PASSWORD_ONLY")) throw new IllegalStateException("Password reached redactor");
                    return value.replace("SECRETMARKER", "[safe]");
                }).build();
        bridge = FxAtSpiBridge.installConfigured(options, message -> System.err.println("STATUS " + message));
        say("PID=" + ProcessHandle.current().pid());
        Thread.ofPlatform().daemon().name("configuration-smoke-input").start(() -> {
            try (var input = new BufferedReader(new InputStreamReader(System.in))) {
                for (String line; (line = input.readLine()) != null;) {
                    var command = line;
                    Platform.runLater(() -> command(command));
                }
            } catch (Exception failure) { say("INPUT_FAILED"); }
        });
    }
    private void command(String command) {
        switch (command) {
            case "sensitive" -> {
                options = options.toBuilder().sensitiveNodes(node -> node == field).build();
                field.setText("SECRETMARKER");
            }
            case "exclude" -> options = options.toBuilder().excludedNodes(node -> node == field).build();
            case "include" -> options = options.toBuilder().excludedNodes(node -> false).build();
            case "window" -> options = options.toBuilder().redactor((kind, sensitive, value) ->
                    kind == BridgeOptions.ContentKind.WINDOW_NAME ? "Changed safe title" : value.replace("SECRETMARKER", "[safe]")).build();
            case "deny" -> options = options.toBuilder().allowedActionSenders(Set.of()).build();
            case "allow" -> options = options.toBuilder().allowAllActionSenders().build();
            case "exit" -> {
                bridge.close();
                bridge.closed().thenRun(() -> { say("CLOSED"); Platform.exit(); });
                return;
            }
            default -> throw new IllegalArgumentException("Unknown smoke command");
        }
        bridge.updateOptions(options).whenComplete((done, failure) -> say(failure == null ? "UPDATED=" + command : "UPDATE_FAILED"));
    }
}
