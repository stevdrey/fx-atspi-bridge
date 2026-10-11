# FX AT-SPI Bridge

An opt-in Java 27 / JavaFX 27 library that publishes the integrating application's accessible controls through Linux AT-SPI2 using FFM and GIO/GLib/GObject.

Third-party accessibility applications can discover control roles, text (subject to privacy policy), states, supported actions and, after the geometry milestone, locations. Each JavaFX application integrates the library. This project includes no MCP server, tools or client.

## Documentation

- [Roadmap](Roadmap.md)
- [Architecture](Architecture.md)
- [Security Model](Security-Model.md)
- [Development Model](Development-Model.md)
- [Wiki Publication](Wiki-Publication.md)

Prototype status remains explicit: current support is limited and production gates are pending. Semantic accessibility actions do not verify appearance or physical pointer behavior. Project code remains Apache 2.0; dependencies retain their licenses.

Edit `docs/wiki/` through repository PRs. The Wiki is a synchronized surface. Detailed ADRs, specs and verification live in the main repository.
