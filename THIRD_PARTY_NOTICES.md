# Componentes de terceros

Apache-2.0 cubre el código propio del puente y el ejemplo, no modifica las licencias de sus dependencias.

| Componente | Uso | Licencia / fuente |
| --- | --- | --- |
| OpenJFX 27 (base, graphics, controls) | Dependencias Maven; no se incorporan al JAR de la biblioteca | GPL-2.0 con Classpath Exception; [OpenJFX](https://openjfx.io/), [licencia upstream](https://github.com/openjdk/jfx/blob/master/LICENSE) |
| GLib, GIO, GObject | Bibliotecas compartidas del sistema, llamadas mediante FFM; no se redistribuyen aquí | LGPL-2.1-or-later; [GLib COPYING](https://gitlab.gnome.org/GNOME/glib/-/blob/main/COPYING) |
| Gradle wrapper | Scripts y JAR de arranque incluidos en este repositorio | Apache-2.0; [licencia Gradle](https://github.com/gradle/gradle/blob/master/LICENSE) |
| JUnit 5 / JUnit Platform | Dependencias solo de pruebas | EPL-2.0; [licencia JUnit](https://github.com/junit-team/junit5/blob/main/LICENSE.md) |

El JAR del puente contiene únicamente sus clases y avisos, no JavaFX ni bibliotecas nativas. El Classpath Exception de OpenJFX contempla enlazar módulos independientes sujetos a sus condiciones. Si empaquetas o redistribuyes runtimes JavaFX o bibliotecas del sistema con tu aplicación, conserva sus licencias y cumple también sus requisitos de distribución; la licencia Apache del puente no los sustituye.

El protocolo implementado se referencia en las [definiciones AT-SPI2](https://github.com/GNOME/at-spi2-core/tree/main/xml). Esta implementación usa sus nombres y firmas para interoperabilidad; no incorpora las bibliotecas libatspi ni el código de implementación de AT-SPI2.
