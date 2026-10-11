# Puente experimental JavaFX → AT-SPI2

Biblioteca Gradle independiente del laboratorio. Publica las ventanas visibles del **proceso JavaFX que la integra** en el bus de accesibilidad de la sesión mediante Java 27 FFM y GIO/GLib/GObject. No requiere JNI/JNA, sudo, capturas ni RemoteDesktop.

## Integración

Construye la biblioteca con JDK 27:

```bash
./gradlew build
```

El JAR está en `build/libs/fx-atspi-bridge-0.1.0-SNAPSHOT.jar`. Es un JAR de la biblioteca; JavaFX y las bibliotecas del sistema se resuelven por separado.

Para probar la interfaz de ejemplo dentro de una sesión gráfica Linux:

```bash
./gradlew :examples:fx-lab:run
```

Incluye campos de texto, botones, un campo dinámico, una segunda ventana y sustitución de escenas. `--args='--no-atspi'` permite ejecutar el ejemplo sin el puente. La biblioteca no incluye un servidor MCP: un cliente AT-SPI externo puede consultar su árbol y activar sus acciones.

Para consumirla desde otro proyecto, como Monada Forge, primero publica en Maven local:

```bash
./gradlew publishToMavenLocal
```

Y agrega a ese proyecto:

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}
dependencies {
    implementation 'io.github.stevdrey:fx-atspi-bridge:0.1.0-SNAPSHOT'
}
```

Estas coordenadas son para la publicación local. Todavía no se ha publicado en Maven Central ni se ha creado una versión estable. La aplicación consumidora debe usar JDK 27 y una versión compatible de JavaFX; antes de integrar Monada Forge verifica sus versiones.

Para aplicaciones modulares, agrega `requires dev.desktopcontrol.fxatspi;` a `module-info.java`. El lanzador debe incluir:

```text
--enable-native-access=dev.desktopcontrol.fxatspi,javafx.graphics
```

En aplicaciones por classpath utiliza `--enable-native-access=ALL-UNNAMED`. El puente y sus dependencias JavaFX deben estar presentes en el module-path o classpath apropiado; evita duplicar versiones de JavaFX.

```java
import dev.desktopcontrol.fxatspi.FxAtSpiBridge;

public class MyApp extends javafx.application.Application {
    private FxAtSpiBridge accessibility;

    @Override public void start(javafx.stage.Stage stage) {
        // Construir la interfaz y asignar su Scene.
        stage.show();
        accessibility = FxAtSpiBridge.install();
    }

    @Override public void stop() {
        if (accessibility != null) accessibility.close();
    }
}
```

Instalar y cerrar en el hilo JavaFX. Se permite un puente por proceso. `install(Consumer<String>)` permite recibir el estado desde el hilo del proveedor; para modificar una etiqueta desde ese callback usa `Platform.runLater`.

No se registra ningún control manualmente. Usa `node.setAccessibleText("Nombre útil")` cuando la etiqueta del control no sea suficiente para que un agente lo identifique. El proveedor asigna referencias internas por identidad del objeto, distintas de los IDs temporales que entrega el MCP. Al desaparecer un objeto, su referencia se retira; si vuelve a aparecer tras una observación de su ausencia, recibe una nueva referencia.

## Descubrimiento y eventos

Cada 100 ms, en un pulso JavaFX, se recorre `Window.getWindows()`, las escenas y los hijos de contenedores. Los controles se tratan como hojas semánticas: sus nodos internos de skin no se publican. Se incluyen ventanas adicionales y cambios completos de escena. El hilo GDBus registra nuevos objetos y retira los antiguos, emitiendo `Cache.AddAccessible`, `Cache.RemoveAccessible` y `ChildrenChanged`; también notifica cambios de nombre, texto, cursor y estado observados.

La revisión periódica agrupa cambios entre observaciones: no es un registro exhaustivo de cada transición ni garantiza observar controles que aparecen y desaparecen dentro de 100 ms. Su coste crece con el tamaño del árbol. Un puente de producción debería suscribirse a propiedades y cambios estructurales para evitar recorrer todo el árbol periódicamente.

## Cobertura real

- Ventanas y contenedores, etiquetas, botones, TextField y TextArea.
- Acción de botones y lectura/escritura de texto, con offsets Unicode por puntos de código.
- Textos de contraseña ocultos; PasswordField no expone Text ni EditableText en este experimento.
- Interfaces parciales de AT-SPI: no implementa geometría, selección completa, tablas virtualizadas, menús ni equivalencias semánticas para todos los controles personalizados. Un control no soportado se publica como panel sin acciones específicas.
- No se afirma compatibilidad completa con lectores de pantalla. Activar un botón accesible prueba su comportamiento, no un clic físico ni su apariencia.
- ABI inicial Linux x86_64, JavaFX 27. La interfaz puede seguir usando XWayland: el canal AT-SPI se comunica por D-Bus y no depende de capturas X11.

No inyecta accesibilidad en otra JVM ya abierta: cada aplicación debe integrar la biblioteca. El cliente genérico sí puede usar libatspi para consultar aplicaciones que tengan su propio proveedor AT-SPI.

## Requisitos y pruebas

Linux x86_64 con sesión D-Bus y proveedor del bus de accesibilidad `org.a11y.Bus`. Se cargan `libgio-2.0.so.0`, `libglib-2.0.so.0` y `libgobject-2.0.so.0` desde el sistema. Los tests de variantes y offsets Unicode usan GLib, pero no requieren una sesión gráfica. `./gradlew test` ejecuta esas pruebas; el ejemplo es la comprobación interactiva de integración.

El prototipo original fue verificado en Fedora/GNOME mediante un cliente MCP/libatspi externo: edición Unicode, botones, creación/eliminación de controles, segunda ventana, sustitución/restauración de escena y eventos de texto/estado/hijos. Esas pruebas de escritorio no forman parte del CI de este repositorio.

## Licencia

El código propio y el ejemplo se distribuyen bajo [Apache License 2.0](LICENSE), con copyright de Steven Rey Sequeira. Se permite uso comercial, modificación y redistribución conforme a sus condiciones; incluye una concesión de patentes de los contribuidores. Al redistribuir, conserva los avisos y la licencia e identifica los archivos modificados. Las dependencias mantienen sus licencias: consulta [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## CI opcional

`ci/github-actions-build.yml.example` contiene una plantilla que construye biblioteca y ejemplo y ejecuta los tests nativos en Linux con JDK 27. Para activarla, cópiala a `.github/workflows/build.yml` usando una sesión de GitHub con permisos para modificar workflows. La plantilla está incluida, pero GitHub Actions todavía no está activado en este repositorio.
