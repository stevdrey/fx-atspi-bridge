# SC-07 evidence

`ConfigurationSmokeApp.java` and `configuration_smoke.py` form a reproducible synthetic-data desktop check. Run the Python script with the current built bridge jar and the Gradle-resolved JavaFX 27 Linux module path. Requires the real session accessibility bus, Java 27 and Python GI bindings for Atspi/Gio.

`desktop/results.json` records individual checks, libatspi/session/backend attribution and presence of another same-process provider. `desktop/events.json` contains the bridge's observed safe event payloads. Status output is checked for synthetic markers by the collector; no real application data is captured. The client identifies the configured bridge rather than selecting the first provider with the same PID. The log filename is ignored by Git.

The script does not change desktop settings, terminate unrelated processes or claim native Wayland support. It closes only its own application. Privacy results concern this bridge; GTK's separate provider is outside BridgeOptions.
