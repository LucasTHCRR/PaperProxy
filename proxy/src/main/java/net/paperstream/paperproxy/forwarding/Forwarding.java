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

import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.PlayerInfoForwarding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.paperstream.paperproxy.config.PaperProxyConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Decides the forwarding mode per server and creates PaperGuard logins.
 */
public final class Forwarding {

  private static final Logger logger = LogManager.getLogger(Forwarding.class);
  private static final String SECRET_FILE = "paperguard.secret";
  private static final char SEPARATOR = '\0';

  private final VelocityServer server;
  private final PaperProxyConfig config;
  private final Path secretFile;
  private final SecureRandom random = new SecureRandom();
  private volatile byte[] masterSecret;

  /**
   * Creates the forwarding settings.
   *
   * @param server the proxy
   * @param config paperproxy.toml
   * @param directory the proxy root
   */
  public Forwarding(final VelocityServer server, final PaperProxyConfig config,
                    final Path directory) {
    this.server = server;
    this.config = config;
    this.secretFile = directory.resolve(SECRET_FILE);
  }

  /**
   * Returns the forwarding mode for a backend server.
   *
   * @param serverName the server name
   * @return the mode from paperproxy.toml, or velocity.toml's global mode
   */
  public PlayerInfoForwarding modeFor(final String serverName) {
    final PlayerInfoForwarding override = config.values().forwarding()
        .get(serverName.toLowerCase(Locale.ROOT));
    if (override != null) {
      return override;
    }
    // Paired PaperStream servers got the modern forwarding secret.
    return server.getServerLinks().isLinked(serverName) ? PlayerInfoForwarding.MODERN
        : server.getConfiguration().getPlayerInfoForwardingMode();
  }

  /**
   * Tells whether every configured server uses modern forwarding. Only then can clients older
   * than 1.13 be refused right at the handshake.
   *
   * @return true if all servers use modern forwarding
   */
  public boolean everyServerModern() {
    if (server.getConfiguration().getPlayerInfoForwardingMode() != PlayerInfoForwarding.MODERN) {
      return false;
    }
    for (final PlayerInfoForwarding mode : config.values().forwarding().values()) {
      if (mode != PlayerInfoForwarding.MODERN) {
        return false;
      }
    }
    return true;
  }

  /**
   * Tells whether any server uses PaperGuard.
   *
   * @return true if PaperGuard is in use
   */
  public boolean paperGuardInUse() {
    return server.getConfiguration().getPlayerInfoForwardingMode() == PlayerInfoForwarding.PAPERGUARD
        || config.values().forwarding().containsValue(PlayerInfoForwarding.PAPERGUARD);
  }

  /**
   * Loads the PaperGuard secret, creating a new random one (owner read/write only) if needed.
   *
   * @throws IOException if the file cannot be read or written
   */
  public void loadSecret() throws IOException {
    if (!Files.exists(secretFile)) {
      final byte[] secret = new byte[32];
      random.nextBytes(secret);
      Files.writeString(secretFile, Base64.getEncoder().encodeToString(secret));
      try {
        Files.setPosixFilePermissions(secretFile, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
        // Not a POSIX file system (Windows); the file still works.
      }
      logger.info("Created a new PaperGuard secret in {}", SECRET_FILE);
    }
    final String text = Files.readString(secretFile).trim();
    final byte[] secret;
    try {
      secret = Base64.getDecoder().decode(text);
    } catch (IllegalArgumentException e) {
      throw new IOException(SECRET_FILE + " is not valid Base64");
    }
    if (secret.length < 32) {
      throw new IOException(SECRET_FILE + " must contain at least 32 random bytes");
    }
    this.masterSecret = secret;
  }

  /**
   * Replaces the secret with a new random one. Every backend needs its new key afterwards.
   *
   * @throws IOException if the file cannot be written
   */
  public void rotateSecret() throws IOException {
    Files.deleteIfExists(secretFile);
    loadSecret();
  }

  /**
   * Returns the key to put into PaperGuard's config on a backend.
   *
   * @param serverName the server name
   * @return the key, Base64
   */
  public String serverKey(final String serverName) {
    return Base64.getEncoder().encodeToString(PaperGuardCodec.serverKey(secret(), serverName));
  }

  /**
   * Builds the handshake host field for a PaperGuard login.
   *
   * @param rawHost the host the player connected to
   * @param ip the player's IP
   * @param profile the player's profile
   * @param serverName the target server
   * @return the host field
   */
  public String paperGuardAddress(final String rawHost, final String ip,
                                  final GameProfile profile, final String serverName) {
    // The host comes from the client. Control characters (\0 or \n) could shift the field
    // boundaries of the handshake or the signed payload, so they never reach either.
    final String host = withoutControlCharacters(rawHost);
    final List<PaperGuardCodec.Property> properties = new ArrayList<>();
    for (final GameProfile.Property property : profile.getProperties()) {
      properties.add(new PaperGuardCodec.Property(property.getName(), property.getValue(),
          property.getSignature()));
    }
    final byte[] nonce = new byte[16];
    random.nextBytes(nonce);
    final String undashed = profile.getUndashedId();
    final String value = PaperGuardCodec.sign(PaperGuardCodec.serverKey(secret(), serverName),
        serverName, System.currentTimeMillis() / 1000L, nonce, host, ip, undashed, properties);

    final List<Map<String, String>> json = new ArrayList<>();
    for (final PaperGuardCodec.Property property : properties) {
      json.add(property.signature() == null || property.signature().isEmpty()
          ? Map.of("name", property.name(), "value", property.value())
          : Map.of("name", property.name(), "value", property.value(),
              "signature", property.signature()));
    }
    json.add(Map.of("name", PaperGuardCodec.PROPERTY, "value", value));
    return host + SEPARATOR + ip + SEPARATOR + undashed + SEPARATOR
        + new com.google.gson.Gson().toJson(json);
  }

  static String withoutControlCharacters(final String text) {
    final StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (c >= 0x20 && c != 0x7F) {
        out.append(c);
      }
    }
    return out.toString();
  }

  private byte[] secret() {
    final byte[] secret = masterSecret;
    if (secret == null) {
      throw new IllegalStateException("The PaperGuard secret is not loaded");
    }
    return secret;
  }
}
