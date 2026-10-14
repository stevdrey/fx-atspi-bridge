import dev.desktopcontrol.fxatspi.FxAtSpiBridge;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** SC-03 desktop smoke: same UI as the SC-02 harness, plus stdin commands "close" and "exit" to exercise the lifecycle. */
public class SmokeApp extends Application {
    private final AtomicReference<FxAtSpiBridge> bridge = new AtomicReference<>();
    public static void main(String[] a) { launch(a); }
    private static void say(String line) { System.out.println(line); System.out.flush(); }
    private static FxAtSpiBridge install() { return FxAtSpiBridge.install(m -> System.err.println("STATUS " + m)); }

    @Override public void start(Stage stage) {
        var result = new Label("idle"); result.setId("result");
        var go = new Button("Go"); go.setOnAction(e -> result.setText("fired"));
        var off = new Button("Off"); off.setDisable(true);
        var field = new TextField("a🧪b"); field.setId("field");
        var area = new TextArea("line1\nline2"); area.setPrefRowCount(2);
        var pw = new PasswordField(); pw.setText("hunter2"); pw.setAccessibleText("pw");
        stage.setTitle("SmokeApp");
        stage.setScene(new Scene(new VBox(result, go, off, field, area, pw), 300, 400));
        stage.show();
        bridge.set(install());
        say("PID=" + ProcessHandle.current().pid());
        var watchdog = new Thread(() -> { try { Thread.sleep(180_000); } catch (InterruptedException e) { return; } System.exit(4); });
        watchdog.setDaemon(true); watchdog.start();
        var commands = new Thread(this::readCommands, "smoke-commands");
        commands.setDaemon(true); commands.start();
    }

    private void readCommands() {
        try (var in = new BufferedReader(new InputStreamReader(System.in))) {
            for (String line; (line = in.readLine()) != null;) {
                var command = line.trim();
                if (command.equals("close")) Platform.runLater(this::closeAndReinstall);
                else if (command.equals("exit")) Platform.runLater(this::closeAndExit);
            }
        } catch (Exception ignored) { /* stdin closed */ }
    }

    private void closeAndReinstall() {
        var current = bridge.get();
        current.close();
        current.close(); // closing twice must be safe
        try { bridge.set(install()); say("REINSTALL_NOT_REJECTED"); return; }
        catch (IllegalStateException rejected) { say("REINSTALL_REJECTED:" + rejected.getMessage()); }
        current.closed().whenComplete((ok, failure) -> {
            say("CLOSED_COMPLETED");
            Platform.runLater(() -> {
                try { bridge.set(install()); say("REINSTALLED"); } catch (Throwable error) { say("REINSTALL_FAILED:" + error); }
            });
        });
    }

    private void closeAndExit() {
        var current = bridge.get();
        current.close();
        current.closed().whenComplete((ok, failure) -> { say("EXIT_CLOSED_COMPLETED"); Platform.exit(); System.exit(0); });
    }
}
