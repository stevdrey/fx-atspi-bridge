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
package dev.desktopcontrol.fxatspi.fx;

import dev.desktopcontrol.fxatspi.adapter.SemanticAction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

/** FX-only ordered metadata digest. Original strings are never retained or sent to the provider. */
final class ActionFingerprint {
    private final MessageDigest digest;
    private final byte[] block = new byte[1024];

    ActionFingerprint() {
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("Action digest unavailable", unavailable); }
    }

    byte[] of(List<SemanticAction> actions) {
        digest.reset();
        length(actions.size());
        for (var action : actions) {
            text(action.name()); text(action.description()); text(action.keyBinding());
        }
        return digest.digest();
    }

    private void length(int length) {
        digest.update((byte) (length >>> 24)); digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8)); digest.update((byte) length);
    }

    private void text(String text) {
        length(text.length());
        for (var offset = 0; offset < text.length();) {
            var size = 0;
            while (offset < text.length() && size < block.length) {
                var unit = text.charAt(offset++);
                block[size++] = (byte) (unit >>> 8); block[size++] = (byte) unit;
            }
            digest.update(block, 0, size);
        }
        // Do not retain the last source characters in reusable scratch storage.
        Arrays.fill(block, (byte) 0);
    }
}
