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
package dev.desktopcontrol.fxatspi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.desktopcontrol.fxatspi.model.AccessibleNode;
import dev.desktopcontrol.fxatspi.model.Capability;
import dev.desktopcontrol.fxatspi.model.Geometry;
import dev.desktopcontrol.fxatspi.model.Geometry.Rect;
import dev.desktopcontrol.fxatspi.model.Role;
import dev.desktopcontrol.fxatspi.model.Snapshot;
import dev.desktopcontrol.fxatspi.fx.ActionExecutor;
import dev.desktopcontrol.fxatspi.fx.TargetRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Wire-level geometry with no JavaFX toolkit. A forbidden executor detects accidental FX scheduling. */
@Timeout(60)
class ComponentTest {
    private PrivateBus bus;
    private AtSpiProvider provider;

    private static AccessibleNode node(String id, Role role, Geometry geometry) {
        return new AccessibleNode(id, id, role, "", false, true, true, false, false, 0,
                id.equals("root") ? "" : "root", id.equals("root") ? List.of("frame", "field") : List.of(),
                id.equals("root") ? Set.of() : Set.of(Capability.COMPONENT), false, id.equals("field"), geometry);
    }

    @BeforeEach void start() throws Exception {
        bus = PrivateBus.start();
        if (bus == null && "1".equals(System.getenv("REQUIRE_NATIVE_BUS"))) fail("Bus unavailable");
        Assumptions.assumeTrue(bus != null, "Bus unavailable");
        var geometry = new Geometry(new Rect(-10, 20, 30, 40), new Rect(5, 6, 30, 40), new Rect(1, 2, 30, 40));
        var snapshot = new Snapshot(Map.of("root", node("root", Role.APPLICATION, Geometry.UNAVAILABLE),
                "frame", node("frame", Role.FRAME, new Geometry(geometry.screen(), geometry.window(), null)),
                "field", node("field", Role.ENTRY, geometry)));
        provider = new AtSpiProvider(_ -> {}, () -> snapshot, new ActionExecutor(new TargetRegistry()), _ -> bus.address(), false,
                _ -> { throw new AssertionError("A geometry read attempted to schedule FX work"); });
        provider.start();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (provider.state() != AtSpiProvider.State.RUNNING && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(AtSpiProvider.State.RUNNING, provider.state());
    }

    @AfterEach void close() throws Exception {
        if (provider != null) {
            provider.stop();
            provider.terminated().toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(0, provider.pendingAcquired(), "queries retain no action invocation");
            assertEquals(provider.exportsAcquired(), provider.exportsReleased());
            assertEquals(provider.definitionsAcquired(), provider.definitionsReleased());
        }
        if (bus != null) bus.close();
    }

    private String call(String id, String method, String... args) throws Exception {
        var command = new ArrayList<>(List.of("gdbus", "call", "--address", bus.address(), "--dest", provider.busName(),
                "--object-path", "/org/a11y/atspi/accessible/" + id, "--method", method));
        command.add("--");
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) fail("Client timed out");
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        } finally { process.destroyForcibly(); }
    }

    private String component(String method, String... args) throws Exception {
        return call("field", "org.a11y.atspi.Component." + method, args);
    }

    @Test void allCoordinatesSizesAndHalfOpenContainsAreReadFromTheSnapshot() throws Exception {
        assertEquals("((-10, 20, 30, 40),)", component("GetExtents", "0"));
        assertEquals("((5, 6, 30, 40),)", component("GetExtents", "1"));
        assertEquals("((1, 2, 30, 40),)", component("GetExtents", "2"));
        assertEquals("(-10, 20)", component("GetPosition", "0"));
        assertEquals("(30, 40)", component("GetSize"));
        assertEquals("(true,)", component("Contains", "-10", "20", "0"));
        assertEquals("(false,)", component("Contains", "20", "20", "0"));
        assertEquals("(false,)", component("Contains", "-10", "60", "0"));
        assertEquals("(true,)", component("Contains", "1", "2", "2"));
        var interfaces = call("field", "org.a11y.atspi.Accessible.GetInterfaces");
        assertTrue(interfaces.contains("Component"));
        assertFalse(interfaces.contains("Text"));
    }

    @Test void invalidUnsignedTypesUnsupportedOperationsAndUnavailableCoordinatesHaveStableErrors() throws Exception {
        for (var type : List.of("3", "2147483648", "4294967295")) {
            assertTrue(component("GetExtents", type).contains("org.freedesktop.DBus.Error.InvalidArgs"));
            assertTrue(component("Contains", "0", "0", type).contains("org.freedesktop.DBus.Error.InvalidArgs"));
        }
        assertTrue(call("frame", "org.a11y.atspi.Component.GetExtents", "2").contains("org.freedesktop.DBus.Error.NotSupported"));
        for (var method : List.of("GrabFocus", "GetLayer", "GetMDIZOrder", "GetAlpha"))
            assertTrue(component(method).contains("org.freedesktop.DBus.Error.NotSupported"));
        assertTrue(component("SetExtents", "0", "0", "10", "10", "1").contains("NotSupported"));
        assertTrue(component("SetPosition", "0", "0", "1").contains("NotSupported"));
        assertTrue(component("SetSize", "10", "10").contains("NotSupported"));
        assertTrue(component("ScrollTo", "0").contains("NotSupported"));
        assertTrue(component("ScrollToPoint", "1", "0", "0").contains("NotSupported"));
        assertTrue(component("GetAccessibleAtPoint", "0", "0", "1").contains("NotSupported"));
        var old = provider.publishedSnapshot();
        var hidden = node("field", Role.ENTRY, Geometry.UNAVAILABLE);
        provider.publish(new Snapshot(Map.of("root", old.get("root"), "frame", old.get("frame"), "field", hidden)), Set.of("field"));
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (provider.publishedSnapshot().get("field") != hidden && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals("(false,)", component("Contains", "0", "0", "1"));
        assertTrue(component("GetExtents", "1").contains("NotSupported"));
        assertTrue(component("GetSize").contains("NotSupported"));
        assertTrue(call("missing", "org.a11y.atspi.Component.GetExtents", "1").contains("Error"));
    }
}
