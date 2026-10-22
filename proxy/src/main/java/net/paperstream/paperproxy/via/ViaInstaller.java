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

package net.paperstream.paperproxy.via;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.proxy.VelocityServer;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Installs and updates ViaVersion, ViaBackwards and ViaRewind from Modrinth. Downloads are
 * checked against Modrinth's SHA-512 before they are written. Via cannot be loaded at runtime,
 * so changes become active after a restart.
 */
public final class ViaInstaller {

  /** The Via projects, in install order. */
  public static final List<String> PROJECTS = List.of("viaversion", "viabackwards", "viarewind");
  private static final String API = "https://api.modrinth.com/v2/project/%s/version?loaders=%s";

  /**
   * One line of the result.
   *
   * @param project the Modrinth project
   * @param key the message key
   * @param detail version or error
   */
  public record Line(String project, String key, String detail) {
  }

  private final VelocityServer server;
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

  /**
   * Creates the installer.
   *
   * @param server the proxy
   */
  public ViaInstaller(final VelocityServer server) {
    this.server = server;
  }

  /**
   * Installs missing Via plugins, or with {@code update} also replaces outdated ones.
   *
   * @param update whether installed plugins are updated
   * @param withRewind whether ViaRewind (1.7/1.8 clients) is included
   * @return one line per project
   */
  public CompletableFuture<List<Line>> run(final boolean update, final boolean withRewind) {
    return CompletableFuture.supplyAsync(() -> {
      final List<Line> lines = new ArrayList<>();
      for (final String project : PROJECTS) {
        if (project.equals("viarewind") && !withRewind
            && server.getPluginManager().getPlugin("viarewind").isEmpty()) {
          continue;
        }
        lines.add(install(project, update));
      }
      return lines;
    });
  }

  private Line install(final String project, final boolean update) {
    final Optional<PluginContainer> installed = server.getPluginManager().getPlugin(project);
    try {
      final JsonObject version = newestRelease(project);
      if (version == null) {
        return new Line(project, "paperproxy.via.failed", "no release for Velocity on Modrinth");
      }
      final String number = version.get("version_number").getAsString();
      if (installed.isPresent()) {
        final String current = installed.get().getDescription().getVersion().orElse("");
        if (!update || number.startsWith(current) || current.startsWith(number)) {
          return new Line(project, "paperproxy.via.current", current);
        }
      }
      JsonObject file = null;
      for (final JsonElement element : version.getAsJsonArray("files")) {
        if (element.getAsJsonObject().get("primary").getAsBoolean()) {
          file = element.getAsJsonObject();
        }
      }
      if (file == null) {
        return new Line(project, "paperproxy.via.failed", "release has no file");
      }
      final byte[] jar = download(file.get("url").getAsString());
      final String expected = file.getAsJsonObject("hashes").get("sha512").getAsString();
      if (!sha512(jar).equalsIgnoreCase(expected)) {
        return new Line(project, "paperproxy.via.failed", "checksum mismatch, nothing installed");
      }
      final Path plugins = Path.of("plugins");
      if (installed.isPresent()) {
        installed.get().getDescription().getSource().ifPresent(old -> {
          try {
            // The running copy stays loaded until restart; rename so it is not loaded again.
            Files.move(old, old.resolveSibling(old.getFileName() + ".old"));
          } catch (IOException ignored) {
            // Keep going; the new file still wins by name if the old one cannot be moved.
          }
        });
      }
      final String fileName = file.get("filename").getAsString();
      Files.write(plugins.resolve(fileName), jar);
      return new Line(project, installed.isPresent() ? "paperproxy.via.updated"
          : "paperproxy.via.installed", number);
    } catch (IOException e) {
      return new Line(project, "paperproxy.via.failed", e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Line(project, "paperproxy.via.failed", "interrupted");
    }
  }

  private @Nullable JsonObject newestRelease(final String project)
      throws IOException, InterruptedException {
    final String url = String.format(Locale.ROOT, API, project,
        URLEncoder.encode("[\"velocity\"]", StandardCharsets.UTF_8));
    final HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", "LucasTHCRR/PaperProxy (https://github.com/LucasTHCRR/PaperProxy)")
        .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("Modrinth answered " + response.statusCode());
    }
    final JsonArray versions = JsonParser.parseString(response.body()).getAsJsonArray();
    for (final JsonElement element : versions) {
      final JsonObject version = element.getAsJsonObject();
      if ("release".equals(version.get("version_type").getAsString())) {
        return version;
      }
    }
    return null;
  }

  private byte[] download(final String url) throws IOException, InterruptedException {
    final HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", "LucasTHCRR/PaperProxy").timeout(Duration.ofMinutes(2)).build(),
        HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() != 200) {
      throw new IOException("Download failed with " + response.statusCode());
    }
    return response.body();
  }

  private static String sha512(final byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
