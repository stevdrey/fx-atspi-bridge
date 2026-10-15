import dev.desktopcontrol.fxatspi.FxAtSpiBridge;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/** SC-04 desktop smoke target: "Delete" blocks in showAndWait() on a modal "Confirm" dialog until "OK" closes it. */
public class ModalSmokeApp extends Application {
    private FxAtSpiBridge bridge;

    public static void main(String[] args) { launch(args); }
    private static void say(String line) { System.out.println(line); System.out.flush(); }

    @Override public void start(Stage stage) {
        var status = new Label("idle");
        var dialog = new Stage();
        dialog.initOwner(stage);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Confirm");
        var ok = new Button("OK");
        ok.setOnAction(e -> dialog.close());
        dialog.setScene(new Scene(new VBox(new Label("Really delete?"), ok), 200, 80));
        dialog.setOnShown(e -> say("DIALOG_SHOWN"));
        var delete = new Button("Delete");
        delete.setOnAction(e -> {
            status.setText("confirming");
            dialog.showAndWait(); // nested event loop
            status.setText("deleted"); // runs after the request may already have been answered with a timeout
            say("DELETE_HANDLER_RETURNED");
        });
        var other = new Button("Other");
        other.setOnAction(e -> { status.setText("other"); say("OTHER_FIRED"); });
        stage.setTitle("ModalSmoke");
        stage.setScene(new Scene(new VBox(status, delete, other), 300, 200));
        stage.show();
        bridge = FxAtSpiBridge.install(m -> System.err.println("STATUS " + m)); // default ActionLimits
        say("PID=" + ProcessHandle.current().pid());
        var watchdog = new Thread(() -> { try { Thread.sleep(180_000); } catch (InterruptedException e) { return; } System.exit(4); });
        watchdog.setDaemon(true); watchdog.start();
        var commands = new Thread(this::readCommands, "smoke-commands");
        commands.setDaemon(true); commands.start();
    }

    private void readCommands() {
        try (var in = new BufferedReader(new InputStreamReader(System.in))) {
            for (String line; (line = in.readLine()) != null;) {
                if (line.trim().equals("exit")) Platform.runLater(this::closeAndExit);
            }
        } catch (Exception ignored) { /* stdin closed */ }
    }

    private void closeAndExit() {
        bridge.close();
        bridge.closed().whenComplete((done, failure) -> { say("EXIT_CLOSED_COMPLETED"); Platform.exit(); System.exit(0); });
    }
}
