import dev.desktopcontrol.fxatspi.FxAtSpiBridge;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class SmokeApp extends Application {
    public static void main(String[] a) { launch(a); }
    @Override public void start(Stage stage) throws Exception {
        var result = new Label("idle"); result.setId("result");
        var go = new Button("Go"); go.setOnAction(e -> result.setText("fired"));
        var off = new Button("Off"); off.setDisable(true);
        var field = new TextField("a🧪b"); field.setId("field");
        var area = new TextArea("line1\nline2"); area.setPrefRowCount(2);
        var pw = new PasswordField(); pw.setText("hunter2"); pw.setAccessibleText("pw");
        stage.setTitle("SmokeApp");
        stage.setScene(new Scene(new VBox(result, go, off, field, area, pw), 300, 400));
        stage.show();
        var bridge = FxAtSpiBridge.install(m -> System.err.println("STATUS " + m));
        System.out.println("PID=" + ProcessHandle.current().pid()); System.out.flush();
        new Thread(() -> { try { Thread.sleep(40000); } catch (InterruptedException e) {}
            Platform.runLater(() -> { bridge.close(); Platform.exit(); System.exit(0); }); }).start();
    }
}
