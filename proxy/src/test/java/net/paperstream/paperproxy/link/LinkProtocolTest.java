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

package net.paperstream.paperproxy.link;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import org.junit.jupiter.api.Test;

class LinkProtocolTest {

  @Test
  void codesHaveSixtyBitsAndNormalize() {
    final String code = LinkProtocol.newCode();
    assertEquals(14, code.length());
    assertEquals(12, LinkProtocol.normalizeCode(code).length());
    assertEquals(LinkProtocol.normalizeCode(code),
        LinkProtocol.normalizeCode(" " + code.toLowerCase().replace("-", " ") + " "));
  }

  @Test
  void pairingDerivesTheSameSessionOnBothSides() throws GeneralSecurityException {
    final KeyPair backend = LinkProtocol.newKeyPair();
    final KeyPair proxy = LinkProtocol.newKeyPair();
    final byte[] transcript = LinkProtocol.join(backend.getPublic().getEncoded(),
        LinkProtocol.random(32), proxy.getPublic().getEncoded(), LinkProtocol.random(32));
    final String code = "ABCD-EFGH-JKLM";
    final LinkProtocol.Session client = LinkProtocol.pairSession(
        LinkProtocol.agree(backend.getPrivate(), proxy.getPublic().getEncoded()), code,
        transcript, true);
    final LinkProtocol.Session server = LinkProtocol.pairSession(
        LinkProtocol.agree(proxy.getPrivate(), backend.getPublic().getEncoded()),
        "abcd efgh jklm", transcript, false);
    final byte[] secret = "forwarding".getBytes(StandardCharsets.UTF_8);
    assertArrayEquals(secret, client.open(server.seal(secret)));

    final LinkProtocol.Session wrongCode = LinkProtocol.pairSession(
        LinkProtocol.agree(backend.getPrivate(), proxy.getPublic().getEncoded()),
        "ABCD-EFGH-JKLN", transcript, true);
    assertThrows(GeneralSecurityException.class, () -> wrongCode.open(server.seal(secret)));
  }

  @Test
  void proofsDependOnCodeAndTranscript() {
    final byte[] transcript = LinkProtocol.random(64);
    final byte[] proof = LinkProtocol.mac(LinkProtocol.codeBytes("ABCD-EFGH-JKLM"), "backend",
        transcript);
    assertTrue(LinkProtocol.same(proof, LinkProtocol.mac(
        LinkProtocol.codeBytes("abcdefghjklm"), "backend", transcript)));
    assertFalse(LinkProtocol.same(proof, LinkProtocol.mac(
        LinkProtocol.codeBytes("ABCD-EFGH-JKLM"), "proxy", transcript)));
    assertFalse(LinkProtocol.same(proof, LinkProtocol.mac(
        LinkProtocol.codeBytes("ABCD-EFGH-JKLM"), "backend", LinkProtocol.random(64))));
  }

  @Test
  void framesCannotBeForgedReplayedOrReflected() throws GeneralSecurityException {
    final byte[] key = LinkProtocol.random(32);
    final byte[] clientNonce = LinkProtocol.random(32);
    final byte[] serverNonce = LinkProtocol.random(32);
    final LinkProtocol.Session client = LinkProtocol.linkSession(key, "game", clientNonce,
        serverNonce, true);
    final LinkProtocol.Session server = LinkProtocol.linkSession(key, "game", clientNonce,
        serverNonce, false);
    final byte[] first = client.seal("one".getBytes(StandardCharsets.UTF_8));
    assertEquals("one", new String(server.open(first), StandardCharsets.UTF_8));
    // Replay
    assertThrows(GeneralSecurityException.class, () -> server.open(first));
    // Tampered
    final byte[] second = client.seal("two".getBytes(StandardCharsets.UTF_8));
    second[second.length - 1] ^= 1;
    assertThrows(GeneralSecurityException.class, () -> server.open(second));
    // A frame of the proxy sent back to the proxy
    final LinkProtocol.Session other = LinkProtocol.linkSession(key, "game", clientNonce,
        serverNonce, false);
    final byte[] own = other.seal("x".getBytes(StandardCharsets.UTF_8));
    assertThrows(GeneralSecurityException.class, () -> LinkProtocol.linkSession(key, "game",
        clientNonce, serverNonce, false).open(own));
    // Another server name gives another key
    assertThrows(GeneralSecurityException.class, () -> LinkProtocol.linkSession(key, "lobby",
        clientNonce, serverNonce, false).open(LinkProtocol.linkSession(key, "game",
        clientNonce, serverNonce, true).seal(new byte[1])));
  }

  @Test
  void framesRoundTripAndRejectHugeLengths() throws IOException {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final byte[] payload = LinkProtocol.random(300);
    LinkProtocol.writeFrame(out, payload);
    assertArrayEquals(payload, LinkProtocol.readFrame(new ByteArrayInputStream(out.toByteArray())));
    final byte[] huge = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x7F};
    assertThrows(IOException.class, () -> LinkProtocol.readFrame(new ByteArrayInputStream(huge)));
  }

  @Test
  void hkdfMatchesRfc5869() {
    final byte[] ikm = new byte[22];
    java.util.Arrays.fill(ikm, (byte) 0x0b);
    final byte[] salt = java.util.HexFormat.of().parseHex("000102030405060708090a0b0c");
    final byte[] info = java.util.HexFormat.of().parseHex("f0f1f2f3f4f5f6f7f8f9");
    assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
        + "34007208d5b887185865", java.util.HexFormat.of().formatHex(
        LinkProtocol.hkdf(ikm, salt, info, 42)));
  }
}
