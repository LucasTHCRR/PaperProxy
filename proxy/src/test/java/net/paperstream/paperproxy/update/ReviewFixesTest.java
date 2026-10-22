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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;

class ReviewFixesTest {

  private static byte[] jar(final String version) throws Exception {
    final Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    if (version != null) {
      manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
    }
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    new JarOutputStream(out, manifest).close();
    return out.toByteArray();
  }

  @Test
  void oldSignedJarUnderNewTagIsRefused() throws Exception {
    final Version running = Version.parse("1.2.1-RELEASE");
    final Version tag = Version.parse("1.3.0-RELEASE");
    assertNull(UpdateChecker.checkJarVersion(jar("1.3.0-RELEASE"), tag, running));
    // Rollback: an old jar, validly signed, put under a new tag.
    assertNotNull(UpdateChecker.checkJarVersion(jar("1.0.0-ALPHA"), tag, running));
    // Same numbers but another stage is not the same release.
    assertNotNull(UpdateChecker.checkJarVersion(jar("1.3.0-BETA"), tag, running));
    // Tag and jar agree but are not newer than what runs.
    assertNotNull(UpdateChecker.checkJarVersion(jar("1.2.1-RELEASE"),
        Version.parse("1.2.1-RELEASE"), running));
    assertNotNull(UpdateChecker.checkJarVersion(jar(null), tag, running));
    assertNotNull(UpdateChecker.checkJarVersion(new byte[] {1, 2, 3}, tag, running));
  }

  @Test
  void onlyGithubLinksAreTrusted() {
    assertTrue(UpdateChecker.trusted("https://github.com/LucasTHCRR/PaperProxy/releases", false));
    assertTrue(UpdateChecker.trusted(
        "https://release-assets.githubusercontent.com/x/paperproxy.jar", false));
    assertFalse(UpdateChecker.trusted("http://github.com/x", false));
    assertFalse(UpdateChecker.trusted("https://github.com.evil.example/x", false));
    assertFalse(UpdateChecker.trusted("https://evil.example/github.com", false));
    assertFalse(UpdateChecker.trusted("javascript:alert(1)", false));
    assertTrue(UpdateChecker.trusted("http://127.0.0.1:8770/x", true));
  }

  @Test
  void assetIsChosenByExactNameAndCounterAgreesWithOffer() {
    final JsonArray releases = JsonParser.parseString("""
        [
          {"tag_name": "v1.3.0-RELEASE", "prerelease": false,
           "html_url": "https://github.com/LucasTHCRR/PaperProxy/releases/tag/v1.3.0-RELEASE",
           "assets": [
             {"name": "paperproxy-1.3.0-RELEASE-full.jar",
              "browser_download_url": "https://github.com/f/full.jar"},
             {"name": "paperproxy-old-tool.jar",
              "browser_download_url": "https://github.com/f/old.jar"},
             {"name": "paperproxy-1.3.0-RELEASE.jar",
              "browser_download_url": "https://github.com/f/right.jar"},
             {"name": "paperproxy-1.3.0-RELEASE.jar.sha512",
              "browser_download_url": "https://github.com/f/right.jar.sha512"},
             {"name": "paperproxy-1.3.0-RELEASE.jar.sig",
              "browser_download_url": "https://github.com/f/right.jar.sig"}
           ]},
          {"tag_name": "v1.2.5-RELEASE", "prerelease": true,
           "html_url": "https://github.com/LucasTHCRR/PaperProxy/releases/tag/v1.2.5-RELEASE"}
        ]
        """).getAsJsonArray();
    final UpdateChecker.Release release = UpdateChecker.newest(releases, "release");
    assertEquals("https://github.com/f/right.jar", release.jarUrl());
    assertEquals("https://github.com/f/right.jar.sig", release.signatureUrl());
    // The flagged pre-release is neither offered nor counted on the release channel.
    assertEquals(1, UpdateChecker.behind(releases, "release", Version.parse("1.2.1-RELEASE")));
  }

  @Test
  void releasesWithForeignLinksAreSkipped() {
    final JsonArray releases = JsonParser.parseString("""
        [{"tag_name": "v9.0.0-RELEASE", "html_url": "https://evil.example/x",
          "assets": []}]
        """).getAsJsonArray();
    assertNull(UpdateChecker.newest(releases, "release"));
  }
}
