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

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;

/** Optional CLI prerequisites affect only the tests which actually use them. */
final class NativeTestTools {
    private NativeTestTools() {}

    static void requireCommand(boolean required, String... command) throws InterruptedException {
        Process process = null;
        var available = false;
        try {
            process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            available = process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException unavailable) {
            // Missing executable; do not expose command output or environment values.
        } finally {
            if (process != null) PrivateBus.terminate(process);
        }
        if (!available) {
            if (required) fail("Required native test command unavailable");
            Assumptions.abort("Optional native test command unavailable");
        }
    }
}
