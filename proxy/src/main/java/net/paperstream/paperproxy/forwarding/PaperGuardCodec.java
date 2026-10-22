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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The PaperGuard wire format, see https://github.com/LucasTHCRR/PaperGuard. The PaperGuard
 * plugin implements the same format; both are checked against the same test vectors.
 *
 * <p>A PaperGuard login is a BungeeCord legacy handshake ({@code host\0ip\0uuid\0properties})
 * whose property list carries one extra property named {@value #PROPERTY}:
 * {@code v1:<timestamp seconds>:<nonce>:<signature>}. The signature is HMAC-SHA256 with the
 * per-server key over version, server name, timestamp, nonce, host, IP, UUID and all other
 * properties. Changing any of them, replaying an old login or sending a login meant for another
 * server breaks it.
 */
public final class PaperGuardCodec {

  /** Name of the property that carries the signature. */
  public static final String PROPERTY = "paperguard";
  private static final String VERSION = "v1";
  private static final String KEY_CONTEXT = "paperproxy-paperguard-v1:";
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  private PaperGuardCodec() {
    throw new AssertionError();
  }

  /**
   * One profile property, as in the legacy forwarding JSON.
   *
   * @param name the name
   * @param value the value
   * @param signature the Mojang signature, may be empty
   */
  public record Property(String name, String value, String signature) {
  }

  /**
   * Derives the key one backend server uses. A backend only ever learns its own key, so a
   * compromised backend cannot forge logins for the others.
   *
   * @param masterSecret the proxy's PaperGuard secret
   * @param serverName the server name as configured in velocity.toml
   * @return the 32 byte key
   */
  public static byte[] serverKey(final byte[] masterSecret, final String serverName) {
    return hmac(masterSecret, (KEY_CONTEXT + serverName.toLowerCase(Locale.ROOT))
        .getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Builds the property value for a login.
   *
   * @param serverKey the server's key
   * @param serverName the target server
   * @param timestamp unix time in seconds
   * @param nonce 16 random bytes
   * @param host the host field of the handshake
   * @param ip the player's IP
   * @param undashedUuid the player's UUID without dashes
   * @param properties the other profile properties
   * @return the property value
   */
  public static String sign(final byte[] serverKey, final String serverName, final long timestamp,
                            final byte[] nonce, final String host, final String ip,
                            final String undashedUuid, final List<Property> properties) {
    final String nonceText = B64.encodeToString(nonce);
    final byte[] mac = hmac(serverKey, payload(serverName, timestamp, nonceText, host, ip,
        undashedUuid, properties));
    return VERSION + ":" + timestamp + ":" + nonceText + ":" + B64.encodeToString(mac);
  }

  /**
   * Checks a signature in constant time.
   *
   * @param serverKey the server's key
   * @param serverName this server's name
   * @param value the property value
   * @param host the host field
   * @param ip the IP field
   * @param undashedUuid the UUID field
   * @param properties the other properties
   * @return true if the signature matches (age and replays are checked separately)
   */
  public static boolean verify(final byte[] serverKey, final String serverName, final String value,
                               final String host, final String ip, final String undashedUuid,
                               final List<Property> properties) {
    final String[] parts = value.split(":", -1);
    if (parts.length != 4 || !VERSION.equals(parts[0])) {
      return false;
    }
    final long timestamp;
    try {
      timestamp = Long.parseLong(parts[1]);
    } catch (NumberFormatException e) {
      return false;
    }
    final byte[] given;
    try {
      given = Base64.getUrlDecoder().decode(parts[3]);
    } catch (IllegalArgumentException e) {
      return false;
    }
    final byte[] expected = hmac(serverKey, payload(serverName, timestamp, parts[2], host, ip,
        undashedUuid, properties));
    return MessageDigest.isEqual(expected, given);
  }

  /**
   * The signed bytes.
   *
   * @param serverName the server name
   * @param timestamp unix time in seconds
   * @param nonce the nonce as sent
   * @param host the host field
   * @param ip the IP field
   * @param undashedUuid the UUID field
   * @param properties the properties
   * @return the payload
   */
  public static byte[] payload(final String serverName, final long timestamp, final String nonce,
                        final String host, final String ip, final String undashedUuid,
                        final List<Property> properties) {
    final StringBuilder out = new StringBuilder(256)
        .append(VERSION).append('\n')
        .append(serverName.toLowerCase(Locale.ROOT)).append('\n')
        .append(timestamp).append('\n')
        .append(nonce).append('\n')
        .append(host).append('\n')
        .append(ip).append('\n')
        .append(undashedUuid.toLowerCase(Locale.ROOT)).append('\n');
    for (final Property property : properties) {
      if (PROPERTY.equals(property.name())) {
        continue;
      }
      out.append(property.name()).append('\0')
          .append(property.value()).append('\0')
          .append(property.signature() == null ? "" : property.signature()).append('\n');
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] hmac(final byte[] key, final byte[] data) {
    try {
      final Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }
}
