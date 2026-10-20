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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import dev.desktopcontrol.fxatspi.adapter.AdapterCommand;
import dev.desktopcontrol.fxatspi.adapter.SemanticState;
import dev.desktopcontrol.fxatspi.model.AccessibleNode;
import dev.desktopcontrol.fxatspi.model.ActionPort;
import dev.desktopcontrol.fxatspi.model.Capability;
import dev.desktopcontrol.fxatspi.model.Role;
import dev.desktopcontrol.fxatspi.model.Semantics;
import dev.desktopcontrol.fxatspi.model.Snapshot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real D-Bus admission, queued index resolution, errors and deterministic invocation cleanup. No FX toolkit. */
@Timeout(45)
class SelectionProtocolTest {
    private PrivateBus bus;
    private AtSpiProvider provider;
    private final ArrayBlockingQueue<Runnable> tasks = new ArrayBlockingQueue<>(8);
    private final AtomicReference<Snapshot> current = new AtomicReference<>();
    private final AtomicReference<String> effect = new AtomicReference<>();
    private static AccessibleNode node(String id, Role role, String parent, List<String> children, Set<Capability> capabilities) {
        return new AccessibleNode(id,id,role,"",false,true,true,false,false,0,parent,children,capabilities);
    }
    private static Snapshot snapshot(List<String> children) {
        var root = node("root",Role.APPLICATION,"",List.of("list"),Set.of());
        var list = node("list",Role.LIST,"root",children,Set.of(Capability.SELECTION))
                .semantics(new Semantics("stable-list",List.of(),Set.of(SemanticState.MULTISELECTABLE),true));
        var nodes = new HashMap<String,AccessibleNode>(); nodes.put("root",root); nodes.put("list",list);
        for (var id : children) nodes.put(id,node(id,Role.LIST_ITEM,"list",List.of(),Set.of()).semantics(
                new Semantics("",List.of(),Set.of(SemanticState.SELECTABLE,SemanticState.SELECTED,SemanticState.INDETERMINATE),false)));
        return new Snapshot(nodes);
    }
    @BeforeEach void start() throws Exception {
        bus = PrivateBus.start(); assertNotNull(bus); current.set(snapshot(List.of("a","b")));
        var port = new ActionPort() {
            public boolean press(String id) { return false; }
            public boolean setText(String id,String value) { return false; }
            public boolean isLive(String id) { return current.get().get(id) != null; }
            public boolean execute(String id,AdapterCommand command,String childId) { effect.set(childId); return true; }
        };
        provider = new AtSpiProvider(_ -> {},current::get,port,_ -> bus.address(),false,tasks::add);
        provider.start();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (provider.state() != AtSpiProvider.State.RUNNING && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(AtSpiProvider.State.RUNNING,provider.state());
    }
    @AfterEach void stop() throws Exception {
        if (provider != null) {
            provider.stop(); provider.terminated().toCompletableFuture().get(10,TimeUnit.SECONDS);
            assertEquals(provider.pendingAcquired(),provider.pendingReleased());
            assertEquals(provider.exportsAcquired(),provider.exportsReleased());
            assertEquals(provider.definitionsAcquired(),provider.definitionsReleased());
        }
        if (bus != null) bus.close();
    }
    private Process request(String id,String method,String... args) throws Exception {
        var command = new ArrayList<>(List.of("gdbus","call","--address",bus.address(),"--dest",provider.busName(),
                "--object-path","/org/a11y/atspi/accessible/"+id,"--method",method,"--"));
        command.addAll(List.of(args)); return new ProcessBuilder(command).redirectErrorStream(true).start();
    }
    private String output(Process process) throws Exception {
        try { assertTrue(process.waitFor(10,TimeUnit.SECONDS)); return new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip(); }
        finally { process.destroyForcibly(); }
    }
    private String call(String method,String... args) throws Exception { return output(request("list","org.a11y.atspi.Selection."+method,args)); }
    @Test void readsUseSnapshotsAndValidateAsymmetricIndicesWithoutQueueingFx() throws Exception {
        assertTrue(call("GetSelectedChild","1").contains("/accessible/b"));
        assertTrue(call("GetSelectedChild","99").contains("/org/a11y/atspi/null"));
        assertEquals("(true,)",call("IsChildSelected","0"));
        assertTrue(call("SelectChild","-1").contains("InvalidArgs"));
        assertTrue(output(request("a","org.a11y.atspi.Accessible.GetState")).contains("uint32 1"));
        assertTrue(output(request("list","org.freedesktop.DBus.Properties.Get","org.a11y.atspi.Accessible","AccessibleId")).contains("stable-list"));
        assertTrue(tasks.isEmpty()); assertEquals(0,provider.pendingAcquired());
    }
    @Test void queuedIndexKeepsItsOriginalChildAcrossPermutationAndContainerConflicts() throws Exception {
        var first = request("list","org.a11y.atspi.Selection.SelectChild","0");
        try {
            var work = tasks.poll(5,TimeUnit.SECONDS); assertNotNull(work);
            assertTrue(call("SelectChild","1").contains("conflicts"));
            current.set(snapshot(List.of("b","a"))); work.run();
            assertEquals("(true,)",output(first)); assertEquals("a",effect.get());
        } finally { first.destroyForcibly(); }
    }
    @Test void queuedRemovalReturnsStaleWithoutEffects() throws Exception {
        var first = request("list","org.a11y.atspi.Selection.DeselectSelectedChild","0");
        try {
            var work = tasks.poll(5,TimeUnit.SECONDS); assertNotNull(work);
            current.set(snapshot(List.of("b"))); work.run();
            assertTrue(output(first).contains("UnknownObject")); assertEquals(null,effect.get());
        } finally { first.destroyForcibly(); }
    }
}
