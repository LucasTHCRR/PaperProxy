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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class UpdateTest {

  @Test
  void suffixOrder() {
    assertTrue(Version.parse("1.2.0-RELEASE").compareTo(Version.parse("1.2.0-ALPHA")) > 0);
    assertTrue(Version.parse("1.2.0").compareTo(Version.parse("1.2.0-BETA")) > 0);
    assertTrue(Version.parse("1.2.0-BETA").compareTo(Version.parse("1.2.0-SNAPSHOT")) > 0);
    assertTrue(Version.parse("v1.10.0").compareTo(Version.parse("1.9.9")) > 0);
    assertEquals(0, Version.parse("1.2").compareTo(Version.parse("1.2.0")));
  }

  @Test
  void channels() {
    assertTrue(Version.parse("1.0.0").allowedOn("release"));
    assertTrue(!Version.parse("1.0.0-BETA").allowedOn("release"));
    assertTrue(Version.parse("1.0.0-BETA").allowedOn("beta"));
    assertTrue(!Version.parse("1.0.0-ALPHA").allowedOn("beta"));
    assertTrue(Version.parse("1.0.0-ALPHA").allowedOn("alpha"));
  }

  @Test
  void newestReleasePerChannel() {
    final JsonArray releases = JsonParser.parseString("""
        [
          {"tag_name": "v1.1.0-BETA", "prerelease": true, "html_url": "https://github.com/b",
           "assets": []},
          {"tag_name": "v1.0.1", "prerelease": false, "html_url": "https://github.com/r",
           "assets": [
             {"name": "paperproxy-1.0.1.jar", "browser_download_url": "https://github.com/jar"},
             {"name": "paperproxy-1.0.1.jar.sha512", "browser_download_url": "https://github.com/sha"},
             {"name": "paperproxy-1.0.1.jar.sig", "browser_download_url": "https://github.com/sig"}]},
          {"tag_name": "v2.0.0", "draft": true, "html_url": "https://github.com/d", "assets": []}
        ]""").getAsJsonArray();
    final UpdateChecker.Release release = UpdateChecker.newest(releases, "release");
    assertNotNull(release);
    assertEquals("1.0.1", release.version().text());
    assertEquals("https://github.com/jar", release.jarUrl());
    assertEquals("https://github.com/sig", release.signatureUrl());
    assertEquals("1.1.0-BETA", UpdateChecker.newest(releases, "beta").version().text());
  }

  @Test
  void verifierAcceptsOnlySignedAndMatchingJars() throws Exception {
    final KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    final ReleaseVerifier verifier = new ReleaseVerifier(
        Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
    final byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
    final String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(jar));
    final Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(pair.getPrivate());
    signer.update(jar);
    final String sig = Base64.getEncoder().encodeToString(signer.sign());

    assertNull(verifier.verify(jar, sha + "  paperproxy.jar\n", sig));
    assertEquals("checksum mismatch", verifier.verify("other".getBytes(StandardCharsets.UTF_8),
        sha, sig));

    final byte[] evil = "evil".getBytes(StandardCharsets.UTF_8);
    final String evilSha = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-512").digest(evil));
    // Attacker controls GitHub (jar and checksum) but not the private key.
    assertEquals("invalid signature", verifier.verify(evil, evilSha, sig));
  }

  @Test
  void verifierAcceptsAnyTrustedKeyDuringRotation() throws Exception {
    final KeyPair oldKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    final KeyPair newKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    final KeyPair stranger = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    final ReleaseVerifier verifier = new ReleaseVerifier(
        Base64.getEncoder().encodeToString(oldKey.getPublic().getEncoded()),
        Base64.getEncoder().encodeToString(newKey.getPublic().getEncoded()));
    final byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);
    final String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(jar));

    assertNull(verifier.verify(jar, sha, sign(oldKey, jar)));
    assertNull(verifier.verify(jar, sha, sign(newKey, jar)));
    assertEquals("invalid signature", verifier.verify(jar, sha, sign(stranger, jar)));
    assertEquals("invalid signature", verifier.verify(jar, sha, "AAAA"));
  }

  private static String sign(final KeyPair pair, final byte[] data) throws Exception {
    final Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(pair.getPrivate());
    signer.update(data);
    return Base64.getEncoder().encodeToString(signer.sign());
  }

  @Test
  void channelFollowsTheRunningVersion() {
    assertEquals("alpha", Version.effectiveChannel("auto", Version.parse("1.0.0-ALPHA")));
    assertEquals("beta", Version.effectiveChannel("auto", Version.parse("1.2.0-BETA")));
    assertEquals("release", Version.effectiveChannel("auto", Version.parse("1.2.0")));
    // The old default "release" must not hide pre-releases from pre-release users.
    assertEquals("alpha", Version.effectiveChannel("release", Version.parse("1.0.0-ALPHA")));
    assertEquals("release", Version.effectiveChannel("release", Version.parse("2.0.0")));
    // An explicit choice is kept.
    assertEquals("beta", Version.effectiveChannel("beta", Version.parse("1.0.0-ALPHA")));
  }

  @Test
  void alphaUsersHearAboutTheBetaAndCountWhatTheyMissed() {
    final JsonArray releases = new JsonArray();
    for (final String tag : new String[] {"v1.0.0-ALPHA", "v1.1.0-ALPHA", "v1.2.0-BETA"}) {
      final JsonObject release = new JsonObject();
      release.addProperty("tag_name", tag);
      release.addProperty("html_url", "https://github.com/LucasTHCRR/PaperProxy/releases/tag/" + tag);
      release.addProperty("prerelease", true);
      releases.add(release);
    }
    final Version running = Version.parse("1.0.0-ALPHA");
    final String channel = Version.effectiveChannel("release", running);
    assertEquals("1.2.0-BETA", UpdateChecker.newest(releases, channel).version().text());
    assertEquals(2, UpdateChecker.behind(releases, channel, running));
  }

  @Test
  void builtInKeyLoads() {
    assertNotNull(new ReleaseVerifier());
  }
}
