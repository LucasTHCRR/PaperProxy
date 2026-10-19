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

package net.paperstream.paperproxy.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.paperstream.paperproxy.config.PaperProxyConfig;
import net.paperstream.paperproxy.network.DiscordWebhook;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Looks for new PaperProxy releases on GitHub, announces them and, if enabled, downloads and
 * verifies them. A downloaded update is applied when the proxy shuts down; the previous jar is
 * kept as {@code paperproxy-old.jar}.
 */
public final class UpdateChecker {

  /** Permission to be told about updates on join. */
  public static final String PERMISSION = "paperproxy.update";
  private static final Logger logger = LogManager.getLogger(UpdateChecker.class);
  private static final String DEFAULT_API =
      "https://api.github.com/repos/OPaperStream/PaperProxy/releases";
  /** Where a downloaded update waits; the launcher looks in the same place. */
  static final Path UPDATE_DIRECTORY = Path.of(System.getProperty("paperproxy.updateDir",
      "update"));
  private static final String JAR_SIGNATURE = ".sig";
  private static final String JAR_CHECKSUM = ".sha512";
  /** Release jars are about 2 MB; anything far bigger is not a PaperProxy release. */
  private static final long MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024;
  /** After this many failed checks in a row the admin is told. */
  private static final int WARN_AFTER_FAILURES = 3;

  /** A release found on GitHub. */
  public record Release(Version version, String url, @Nullable String jarUrl,
                        @Nullable String checksumUrl, @Nullable String signatureUrl) {
  }

  private final VelocityServer server;
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
  private final ReleaseVerifier verifier;
  private final String api;
  private volatile @Nullable Release available;
  private volatile int behind;
  private volatile @Nullable Path downloaded;
  private final boolean customApi;
  private int failures;
  private @Nullable String etag;
  private @Nullable JsonArray cachedReleases;

  /**
   * Creates the checker.
   *
   * @param server the proxy
   */
  public UpdateChecker(final VelocityServer server) {
    this(server, new ReleaseVerifier(), System.getProperty("paperproxy.updateApi", DEFAULT_API));
  }

  UpdateChecker(final VelocityServer server, final ReleaseVerifier verifier, final String api) {
    this.server = server;
    this.verifier = verifier;
    this.api = api;
    // Links from a custom API (tests, mirrors) are trusted; GitHub links must stay on GitHub.
    this.customApi = !DEFAULT_API.equals(api);
  }

  /**
   * Checks now and then every six hours, in the background.
   */
  public void start() {
    server.getScheduler().buildTask(VelocityVirtualPlugin.INSTANCE, this::check)
        .delay(5, TimeUnit.SECONDS).repeat(6, TimeUnit.HOURS).schedule();
  }

  /**
   * Returns the newest release found, if it is newer than the running version.
   *
   * @return the release or null
   */
  public @Nullable Release available() {
    return available;
  }

  /**
   * Returns the verified jar waiting for the next restart.
   *
   * @return the path or null
   */
  public @Nullable Path downloaded() {
    return downloaded;
  }

  /**
   * Runs one check. Network errors are logged once at debug level; GitHub rate limits simply
   * delay the next check.
   */
  public void check() {
    final PaperProxyConfig.Values values = server.getPaperProxyConfig().values();
    if (!values.updateCheck()) {
      return;
    }
    final Version current;
    try {
      current = Version.parse(net.paperstream.paperproxy.PaperProxyBranding.version());
    } catch (IllegalArgumentException e) {
      return;
    }
    try {
      final JsonArray releases = fetch(api);
      if (failures >= WARN_AFTER_FAILURES) {
        logger.info("Update checks work again.");
      }
      failures = 0;
      final String channel = Version.effectiveChannel(values.updateChannel(), current);
      final Release newest = newest(releases, channel, customApi);
      if (newest == null || newest.version().compareTo(current) <= 0) {
        available = null;
        return;
      }
      final boolean announce = available == null
          || !available.version().equals(newest.version());
      available = newest;
      behind = behind(releases, channel, current);
      if (announce) {
        banner(newest, current);
        notifyStaff(newest);
        server.getDiscordWebhook().send(DiscordWebhook.Kind.UPDATE, "paperproxy.discord.update",
            "version", newest.version().text(), "current", current.text(), "url", newest.url());
      }
      if (values.autoUpdate() && downloaded == null) {
        if (newest.version().major() != current.major() && !values.autoUpdateAllowMajor()) {
          logger.info("Not installing {} automatically: it is a new major version. "
              + "Set auto-update.allow-major = true or update by hand.", newest.version().text());
        } else {
          download(newest);
        }
      }
    } catch (IOException | RuntimeException e) {
      failures++;
      if (failures == WARN_AFTER_FAILURES) {
        logger.warn("Could not check for PaperProxy updates {} times in a row ({}). Updates "
            + "are not announced until GitHub is reachable again.", failures, e.getMessage());
      } else {
        logger.debug("Update check failed", e);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Counts the releases on a channel that are newer than the running version.
   *
   * @param releases the GitHub releases
   * @param channel the channel
   * @param current the running version
   * @return the number of newer releases
   */
  static int behind(final JsonArray releases, final String channel, final Version current) {
    int count = 0;
    for (final JsonElement element : releases) {
      final Version version = eligible(element.getAsJsonObject(), channel);
      if (version != null && version.compareTo(current) > 0) {
        count++;
      }
    }
    return count;
  }

  /**
   * The one rule for which releases count on a channel, used for the offer and the counter.
   *
   * @param release a GitHub release
   * @param channel the channel
   * @return its version, or null if it does not count
   */
  static @Nullable Version eligible(final JsonObject release, final String channel) {
    if (release.has("draft") && release.get("draft").getAsBoolean()) {
      return null;
    }
    final Version version;
    try {
      version = Version.parse(release.get("tag_name").getAsString());
    } catch (IllegalArgumentException | NullPointerException | IllegalStateException e) {
      return null;
    }
    final boolean prerelease = release.has("prerelease")
        && release.get("prerelease").getAsBoolean();
    if (!version.allowedOn(channel) || (prerelease && channel.equals("release"))) {
      return null;
    }
    return version;
  }

  private void banner(final Release release, final Version current) {
    final String line = "*".repeat(64);
    logger.warn(line);
    logger.warn("  A new PaperProxy version is available: {}", release.version().text());
    logger.warn("  You are running {}{}.", current.text(),
        behind > 1 ? " (" + behind + " versions behind)" : "");
    logger.warn("  Changelog: {}", release.url());
    logger.warn("  Update: run \"paperproxy update\" or download the jar from the link above.");
    logger.warn(line);
  }

  private void notifyStaff(final Release release) {
    for (final Player player : server.getAllPlayers()) {
      if (player.hasPermission(PERMISSION)) {
        player.sendMessage(notice(release));
      }
    }
  }

  /**
   * Builds the in-game update notice with buttons.
   *
   * @param release the release
   * @return the message
   */
  public Component notice(final Release release) {
    final String current = net.paperstream.paperproxy.PaperProxyBranding.version();
    final String jar = release.jarUrl() != null ? release.jarUrl() : release.url();
    final Component download = Component.translatable("paperproxy.update.button-download")
        .clickEvent(ClickEvent.openUrl(jar))
        .hoverEvent(HoverEvent.showText(Component.text(jar)));
    final Component changelog = Component.translatable("paperproxy.update.button-changelog")
        .clickEvent(ClickEvent.openUrl(release.url()))
        .hoverEvent(HoverEvent.showText(Component.text(release.url())));
    final Component install = Component.translatable("paperproxy.update.button-install")
        .clickEvent(ClickEvent.suggestCommand("/paperproxy update"))
        .hoverEvent(HoverEvent.showText(Component.text("/paperproxy update")));
    return Component.translatable(behind > 1 ? "paperproxy.update.notice-behind"
            : "paperproxy.update.notice",
        Argument.string("version", release.version().text()),
        Argument.string("current", current),
        Argument.string("behind", String.valueOf(behind)),
        Argument.component("download", download),
        Argument.component("changelog", changelog),
        Argument.component("install", install));
  }

  static @Nullable Release newest(final JsonArray releases, final String channel) {
    return newest(releases, channel, false);
  }

  static @Nullable Release newest(final JsonArray releases, final String channel,
                                  final boolean anyHost) {
    Release best = null;
    for (final JsonElement element : releases) {
      final JsonObject release = element.getAsJsonObject();
      final Version version = eligible(release, channel);
      if (version == null || (best != null && version.compareTo(best.version()) <= 0)) {
        continue;
      }
      final String page = release.has("html_url") ? release.get("html_url").getAsString() : "";
      if (!trusted(page, anyHost)) {
        continue;
      }
      final Map<String, String> assets = new HashMap<>();
      if (release.has("assets")) {
        for (final JsonElement asset : release.getAsJsonArray("assets")) {
          final JsonObject object = asset.getAsJsonObject();
          final String url = object.get("browser_download_url").getAsString();
          if (trusted(url, anyHost)) {
            assets.put(object.get("name").getAsString(), url);
          }
        }
      }
      // Exactly this name, so a release with several jars still installs the right one.
      final String jar = "paperproxy-" + version.text() + ".jar";
      final boolean complete = assets.containsKey(jar);
      best = new Release(version, page,
          complete ? assets.get(jar) : null,
          complete ? assets.get(jar + JAR_CHECKSUM) : null,
          complete ? assets.get(jar + JAR_SIGNATURE) : null);
    }
    return best;
  }

  /**
   * Only https links to GitHub are shown to staff or downloaded.
   *
   * @param url the link
   * @param anyHost whether a custom update API is configured
   * @return true if the link may be used
   */
  static boolean trusted(final String url, final boolean anyHost) {
    final URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException e) {
      return false;
    }
    if (anyHost) {
      return uri.getScheme() != null && uri.getScheme().startsWith("http");
    }
    final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    return "https".equals(uri.getScheme())
        && (host.equals("github.com") || host.endsWith(".githubusercontent.com"));
  }

  private JsonArray fetch(final String url) throws IOException, InterruptedException {
    final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "PaperProxy-UpdateChecker")
        .timeout(Duration.ofSeconds(20));
    final String knownTag = etag;
    final JsonArray cached = cachedReleases;
    if (knownTag != null && cached != null) {
      // Unchanged answers do not count against GitHub's rate limit.
      request.header("If-None-Match", knownTag);
    }
    final HttpResponse<String> response = http.send(request.build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() == 304 && cached != null) {
      return cached;
    }
    if (response.statusCode() != 200) {
      throw new IOException("GitHub answered " + response.statusCode());
    }
    final JsonArray releases = JsonParser.parseString(response.body()).getAsJsonArray();
    etag = response.headers().firstValue("ETag").orElse(null);
    cachedReleases = releases;
    return releases;
  }

  /**
   * Downloads and verifies the available release for the next restart, for /pp update.
   *
   * @return completes with null on success, otherwise with the reason
   */
  public CompletableFuture<@Nullable String> install() {
    final Release release = available;
    if (release == null) {
      return CompletableFuture.completedFuture("no update available");
    }
    return CompletableFuture.supplyAsync(() -> {
      try {
        return download(release);
      } catch (IOException e) {
        return e.getMessage();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return "interrupted";
      }
    });
  }

  private @Nullable String download(final Release release)
      throws IOException, InterruptedException {
    if (release.jarUrl() == null || release.checksumUrl() == null
        || release.signatureUrl() == null) {
      logger.warn("Release {} has no signed jar; not installing it automatically.",
          release.version().text());
      return "the release has no signed jar";
    }
    final byte[] jar = bytes(release.jarUrl());
    final String checksum = new String(bytes(release.checksumUrl()), StandardCharsets.UTF_8);
    final String signature = new String(bytes(release.signatureUrl()), StandardCharsets.UTF_8);
    String problem = verifier.verify(jar, checksum, signature);
    if (problem == null) {
      problem = checkJarVersion(jar, release.version(), currentVersion());
    }
    if (problem != null) {
      logger.error("Downloaded PaperProxy {} failed verification ({}); it was discarded and "
          + "will not be installed.", release.version().text(), problem);
      return "verification failed: " + problem;
    }
    final Path directory = UPDATE_DIRECTORY;
    Files.createDirectories(directory);
    final Path target = directory.resolve("paperproxy.jar");
    // Write next to the target and move it in one step, so a crash never leaves half a jar.
    final Path temp = Files.createTempFile(directory, "paperproxy", ".tmp");
    try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
      channel.write(ByteBuffer.wrap(jar));
      channel.force(true);
    }
    try {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (final AtomicMoveNotSupportedException e) {
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }
    downloaded = target;
    logger.warn("PaperProxy {} was downloaded and verified. It becomes active after the next "
        + "restart.", release.version().text());
    return null;
  }

  private byte[] bytes(final String url) throws IOException, InterruptedException {
    final HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", "PaperProxy-UpdateChecker").timeout(Duration.ofMinutes(2)).build(),
        HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream in = response.body()) {
      if (response.statusCode() != 200) {
        throw new IOException("Download failed with " + response.statusCode() + ": " + url);
      }
      final long announced = response.headers().firstValueAsLong("Content-Length").orElse(-1);
      if (announced > MAX_DOWNLOAD_BYTES) {
        throw new IOException("Download is too big (" + announced + " bytes): " + url);
      }
      final byte[] data = in.readNBytes((int) MAX_DOWNLOAD_BYTES + 1);
      if (data.length > MAX_DOWNLOAD_BYTES) {
        throw new IOException("Download is too big: " + url);
      }
      return data;
    }
  }

  private Version currentVersion() {
    return Version.parse(net.paperstream.paperproxy.PaperProxyBranding.version());
  }

  /**
   * The signature covers the jar but not the release tag, so an old signed jar could be put
   * under a new tag. The version inside the jar must match the tag and be newer than what runs.
   *
   * @param jar the downloaded jar
   * @param tag the version of the release tag
   * @param running the running version
   * @return null if fine, otherwise the reason
   */
  static @Nullable String checkJarVersion(final byte[] jar, final Version tag,
                                          final Version running) {
    final String inJar;
    try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(jar))) {
      final Manifest manifest = in.getManifest();
      inJar = manifest == null ? null
          : manifest.getMainAttributes().getValue("Implementation-Version");
    } catch (final IOException e) {
      return "the jar cannot be read";
    }
    if (inJar == null) {
      return "the jar has no version";
    }
    final Version version;
    try {
      version = Version.parse(inJar);
    } catch (final IllegalArgumentException e) {
      return "the jar has no valid version";
    }
    if (version.compareTo(tag) != 0 || version.stage() != tag.stage()) {
      return "the jar is version " + inJar + ", not " + tag.text();
    }
    if (version.compareTo(running) <= 0) {
      return "the jar is not newer than the running version";
    }
    return null;
  }

  /**
   * Replaces the running jar with a downloaded update. Called at the very end of shutdown.
   * Linux and macOS allow replacing a jar that is in use; on Windows this fails and the update
   * stays in the update folder with a message.
   */
  public void applyOnShutdown() {
    final Path update = downloaded;
    if (update == null || !Files.exists(update)
        || System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
      // On Windows the running jar is locked; the launcher installs the update on the next start.
      return;
    }
    try {
      // Started through the small launcher jar: replace that one, not the extracted core.
      final String launcher = System.getProperty("paperproxy.launcherJar");
      final Path running = launcher != null ? Path.of(launcher)
          : Path.of(UpdateChecker.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      if (!Files.isRegularFile(running)) {
        return;
      }
      Files.copy(running, running.resolveSibling("paperproxy-old.jar"),
          StandardCopyOption.REPLACE_EXISTING);
      Files.move(update, running, StandardCopyOption.REPLACE_EXISTING);
      logger.info("Installed the update. The previous version is kept as paperproxy-old.jar.");
    } catch (Exception e) {
      logger.error("Could not install the downloaded update; it stays in {}", update, e);
    }
  }

  /**
   * Builds a clickable link.
   *
   * @param url the address
   * @return the component
   */
  public static Component link(final String url) {
    return Component.text(url).clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(url));
  }

  /**
   * Tells staff about an available update when they join.
   *
   * @param event the event
   */
  @Subscribe
  public void onJoin(final PostLoginEvent event) {
    final Release release = available;
    if (release != null && event.getPlayer().hasPermission(PERMISSION)) {
      event.getPlayer().sendMessage(notice(release));
    }
  }
}
