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

package net.paperstream.paperproxy;

import com.velocitypowered.api.util.ProxyVersion;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.apache.logging.log4j.Logger;

/**
 * Name, links and the mandatory "not official" notice.
 */
public final class PaperProxyBranding {

  public static final String NAME = "PaperProxy";
  public static final String AUTHOR = "LucasTHCR";
  public static final String DISCLAIMER = "NOT OFFICIAL PAPER / not affiliated with PaperMC";
  public static final String REPOSITORY_URL = "https://github.com/OPaperStream/PaperProxy";
  public static final String DISCORD_URL = "https://dc.gg/paperstream";

  private static final String VELOCITY_VERSION = readVelocityVersion();
  private static final String[] LOGO = {
      " ____                       ____",
      "|  _ \\ __ _ _ __   ___ _ __|  _ \\ _ __ _____  ___   _",
      "| |_) / _` | '_ \\ / _ \\ '__| |_) | '__/ _ \\ \\/ / | | |",
      "|  __/ (_| | |_) |  __/ |  |  __/| | | (_) >  <| |_| |",
      "|_|   \\__,_| .__/ \\___|_|  |_|   |_|  \\___/_/\\_\\\\__, |",
      "           |_|                                  |___/",
      "                                  made by LucasTHCR"
  };


  private PaperProxyBranding() {
    throw new AssertionError();
  }

  /**
   * Returns the Velocity version this build is based on.
   *
   * @return for example {@code 4.2.1-SNAPSHOT}
   */
  public static String velocityVersion() {
    return VELOCITY_VERSION;
  }

  /**
   * Returns the PaperProxy version itself. Plugins see the Velocity version first through
   * {@code ProxyServer#getVersion()}, because many of them read the Velocity major version
   * from it.
   *
   * @return for example {@code 1.2.3-RELEASE}
   */
  public static String version() {
    final Package pkg = PaperProxyBranding.class.getPackage();
    final String version = pkg == null ? null : pkg.getImplementationVersion();
    return version == null ? "<unknown>" : version;
  }

  /**
   * Logs the start banner. It cannot be turned off on purpose: PaperProxy must never be
   * mistaken for an official PaperMC project.
   *
   * @param logger the logger to write to
   * @param version the running version
   */
  public static void printBanner(final Logger logger, final ProxyVersion version) {
    logger.info("------------------------------------------------------------");
    for (final String line : LOGO) {
      logger.info(line);
    }
    logger.info("");
    logger.info("{} {} - {}", NAME, version(), DISCLAIMER);
    logger.info("Based on Velocity {} (GPL-3.0). {}", VELOCITY_VERSION, REPOSITORY_URL);
    logger.info("Please report problems to PaperProxy, not to PaperMC: {}", DISCORD_URL);
    logger.info("------------------------------------------------------------");
  }

  private static String readVelocityVersion() {
    try (InputStream in = PaperProxyBranding.class
        .getResourceAsStream("/paperproxy/build.properties")) {
      if (in == null) {
        return "<unknown>";
      }
      final Properties properties = new Properties();
      properties.load(in);
      final String version = properties.getProperty("velocity.version", "<unknown>");
      final String commit = properties.getProperty("velocity.commit", "");
      // The exact upstream commit makes a SNAPSHOT base reproducible.
      return commit.length() >= 7 ? version + " (" + commit.substring(0, 7) + ")" : version;
    } catch (final IOException e) {
      return "<unknown>";
    }
  }
}
