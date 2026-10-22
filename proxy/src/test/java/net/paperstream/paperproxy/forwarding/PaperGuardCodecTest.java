/*
 * Copyright (C) 2026 PaperProxy Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.paperstream.paperproxy.forwarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The proxy must sign exactly like the PaperGuard plugin expects. The vectors are copied from
 * https://github.com/LucasTHCRR/PaperGuard/blob/main/test-vectors.json.
 */
class PaperGuardCodecTest {

  @Test
  void matchesThePaperGuardTestVectors() throws IOException {
    final String json;
    try (InputStream in = getClass().getResourceAsStream("/paperguard/test-vectors.json")) {
      json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    int checked = 0;
    for (final JsonElement element : JsonParser.parseString(json).getAsJsonObject()
        .getAsJsonArray("vectors")) {
      final JsonObject vector = element.getAsJsonObject();
      final String serverName = vector.get("serverName").getAsString();
      final byte[] key = PaperGuardCodec.serverKey(
          vector.get("masterSecret").getAsString().getBytes(StandardCharsets.UTF_8), serverName);
      assertEquals(vector.get("serverKey").getAsString(),
          Base64.getEncoder().encodeToString(key));

      final List<PaperGuardCodec.Property> properties = new ArrayList<>();
      for (final JsonElement p : vector.getAsJsonArray("properties")) {
        final JsonObject property = p.getAsJsonObject();
        properties.add(new PaperGuardCodec.Property(property.get("name").getAsString(),
            property.get("value").getAsString(),
            property.get("signature").isJsonNull() ? null
                : property.get("signature").getAsString()));
      }
      final String host = vector.get("host").getAsString();
      final String ip = vector.get("ip").getAsString();
      final String uuid = vector.get("uuid").getAsString();
      final String value = PaperGuardCodec.sign(key, serverName,
          vector.get("timestamp").getAsLong(),
          HexFormat.of().parseHex(vector.get("nonceHex").getAsString()), host, ip, uuid,
          properties);
      assertEquals(vector.get("value").getAsString(), value);
      assertTrue(PaperGuardCodec.verify(key, serverName, value, host, ip, uuid, properties));
      checked++;
    }
    assertEquals(3, checked);
  }

  @Test
  void controlCharactersNeverReachTheSignedHost() {
    assertEquals("play.example.net1.2.3.4",
        Forwarding.withoutControlCharacters("play.example.net\n1.2.3.4"));
    assertEquals("ab", Forwarding.withoutControlCharacters("a\u0000b\u007f"));
  }
}
