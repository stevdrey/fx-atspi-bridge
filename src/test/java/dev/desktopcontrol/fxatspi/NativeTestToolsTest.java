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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

class NativeTestToolsTest {
    @Test void missingOptionalCommandAbortsOnlyTheCallingTest() {
        assertThrows(TestAbortedException.class,
                () -> NativeTestTools.requireCommand(false, "sc10-nonexistent-native-command"));
    }

    @Test void missingRequiredCommandFails() {
        assertThrows(AssertionFailedError.class,
                () -> NativeTestTools.requireCommand(true, "sc10-nonexistent-native-command"));
    }

    @Test void aFailedCommandIsNotAvailable() {
        assertThrows(AssertionFailedError.class, () -> NativeTestTools.requireCommand(true,
                ProcessHandle.current().info().command().orElseThrow(), "--sc10-invalid-option"));
    }

    @Test @Timeout(20) void timedOutProbeTerminatesItsProcess(@TempDir Path directory) throws Exception {
        var pidFile = directory.resolve("probe.pid");
        try {
            assertThrows(TestAbortedException.class, () -> NativeTestTools.requireCommand(false,
                    "/bin/sh", "-c", "echo $$ > \"$1\"; exec sleep 60", "probe", pidFile.toString()));
            var pid = Long.parseLong(Files.readString(pidFile).strip());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            if (Files.exists(pidFile)) {
                var pid = Long.parseLong(Files.readString(pidFile).strip());
                ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test void aSuccessfulCommandIsAvailable() {
        assertDoesNotThrow(() -> NativeTestTools.requireCommand(true,
                ProcessHandle.current().info().command().orElseThrow(), "-version"));
    }
}
