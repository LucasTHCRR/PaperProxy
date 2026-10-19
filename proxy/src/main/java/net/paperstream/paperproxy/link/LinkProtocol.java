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

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The PaperStream link: a control connection from a PaperStream backend to PaperProxy.
 *
 * <p>The backend connects to the normal proxy port and sends a status handshake with the
 * host {@link #HOST}. From then on both sides exchange frames with a VarInt length prefix.
 * Pairing proves a one-time code with X25519 and HMAC, the code itself never travels.
 * Afterwards every connection is authenticated with the link key and every frame is
 * encrypted with AES-GCM and a strict counter. This class is shared with PaperStream and
 * has no dependencies. The same file lives in PaperStream, keep both copies identical.</p>
 */
public final class LinkProtocol {

  /** The handshake host that marks a link connection. */
  public static final String HOST = "paperstream-link.invalid";
  /** The protocol version. */
  public static final int VERSION = 1;
  /** The largest frame either side accepts. */
  public static final int MAX_FRAME = 1 << 20;
  /** How long a pairing code stays valid. */
  public static final long CODE_TTL_MILLIS = 10 * 60 * 1000L;
  private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private static final int CODE_LENGTH = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  private LinkProtocol() {
  }

  /**
   * Creates a pairing code such as {@code K7QD-M2XP-9RTA}: 60 random bits.
   *
   * @return the code
   */
  public static String newCode() {
    final StringBuilder code = new StringBuilder();
    for (int i = 0; i < CODE_LENGTH; i++) {
      if (i > 0 && i % 4 == 0) {
        code.append('-');
      }
      code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return code.toString();
  }

  /**
   * Normalizes a typed code: upper case, without dashes and spaces.
   *
   * @param code the code
   * @return the normalized code
   */
  public static String normalizeCode(final String code) {
    final StringBuilder out = new StringBuilder();
    for (final char c : code.toUpperCase(Locale.ROOT).toCharArray()) {
      if (ALPHABET.indexOf(c) >= 0) {
        out.append(c);
      }
    }
    return out.toString();
  }

  /**
   * Returns random bytes.
   *
   * @param length the length
   * @return the bytes
   */
  public static byte[] random(final int length) {
    final byte[] bytes = new byte[length];
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  /**
   * Creates an ephemeral X25519 key pair.
   *
   * @return the key pair
   * @throws GeneralSecurityException if X25519 is missing
   */
  public static KeyPair newKeyPair() throws GeneralSecurityException {
    final KeyPairGenerator generator = KeyPairGenerator.getInstance("XDH");
    generator.initialize(NamedParameterSpec.X25519);
    return generator.generateKeyPair();
  }

  /**
   * Computes the shared X25519 secret.
   *
   * @param own the own private key
   * @param peer the encoded public key of the other side
   * @return the shared secret
   * @throws GeneralSecurityException if the key is invalid
   */
  public static byte[] agree(final PrivateKey own, final byte[] peer)
      throws GeneralSecurityException {
    final PublicKey peerKey = KeyFactory.getInstance("XDH")
        .generatePublic(new X509EncodedKeySpec(peer));
    final KeyAgreement agreement = KeyAgreement.getInstance("XDH");
    agreement.init(own);
    agreement.doPhase(peerKey, true);
    return agreement.generateSecret();
  }

  /**
   * Joins byte strings, each with a length prefix, so no two inputs give the same output.
   *
   * @param parts the parts
   * @return the joined bytes
   */
  public static byte[] join(final byte[]... parts) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (final byte[] part : parts) {
      out.writeBytes(ByteBuffer.allocate(4).putInt(part.length).array());
      out.writeBytes(part);
    }
    return out.toByteArray();
  }

  /**
   * HMAC-SHA256 over a label and parts.
   *
   * @param key the key
   * @param label what the MAC is for
   * @param parts the data
   * @return the MAC
   */
  public static byte[] mac(final byte[] key, final String label, final byte[]... parts) {
    try {
      final Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      mac.update(join(label.getBytes(StandardCharsets.UTF_8)));
      mac.update(join(parts));
      return mac.doFinal();
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * HKDF-SHA256 (RFC 5869).
   *
   * @param ikm the input key material
   * @param salt the salt
   * @param info the context
   * @param length the output length, at most 32 * 255
   * @return the key
   */
  public static byte[] hkdf(final byte[] ikm, final byte[] salt, final byte[] info,
                            final int length) {
    try {
      final Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
      final byte[] prk = mac.doFinal(ikm);
      mac.init(new SecretKeySpec(prk, "HmacSHA256"));
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] block = new byte[0];
      for (int i = 1; out.size() < length; i++) {
        mac.update(block);
        mac.update(info);
        mac.update((byte) i);
        block = mac.doFinal();
        out.writeBytes(block);
      }
      final byte[] result = new byte[length];
      System.arraycopy(out.toByteArray(), 0, result, 0, length);
      return result;
    } catch (final GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Compares in constant time.
   *
   * @param a one value
   * @param b another value
   * @return whether both are equal
   */
  public static boolean same(final byte[] a, final byte[] b) {
    return a != null && b != null && MessageDigest.isEqual(a, b);
  }

  /**
   * The key material of a code.
   *
   * @param code the code, typed in any form
   * @return the bytes
   */
  public static byte[] codeBytes(final String code) {
    return normalizeCode(code).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * The session for the rest of a pairing connection.
   *
   * @param shared the X25519 secret
   * @param code the pairing code
   * @param transcript everything both sides sent so far
   * @param client whether this is the backend side
   * @return the session
   */
  public static Session pairSession(final byte[] shared, final String code,
                                    final byte[] transcript, final boolean client) {
    return new Session(hkdf(shared, codeBytes(code),
        join("paperstream-link pair".getBytes(StandardCharsets.UTF_8), transcript), 32), client);
  }

  /**
   * The session for a linked connection.
   *
   * @param linkKey the key both sides got when pairing
   * @param server the server name
   * @param clientNonce the nonce of the backend
   * @param serverNonce the nonce of the proxy
   * @param client whether this is the backend side
   * @return the session
   */
  public static Session linkSession(final byte[] linkKey, final String server,
                                    final byte[] clientNonce, final byte[] serverNonce,
                                    final boolean client) {
    return new Session(hkdf(linkKey, serverNonce, join(
        "paperstream-link session".getBytes(StandardCharsets.UTF_8),
        server.getBytes(StandardCharsets.UTF_8), clientNonce), 32), client);
  }

  /**
   * Base64 encodes.
   *
   * @param bytes the bytes
   * @return the text
   */
  public static String b64(final byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  /**
   * Base64 decodes.
   *
   * @param text the text
   * @return the bytes
   * @throws IllegalArgumentException if the text is not Base64
   */
  public static byte[] unb64(final String text) {
    return Base64.getDecoder().decode(text);
  }

  /**
   * The handshake that opens a link connection, already with its length prefix.
   *
   * @param host the address the backend connects to
   * @param port the port the backend connects to
   * @return the bytes to send first
   */
  public static byte[] handshake(final String host, final int port) {
    final ByteArrayOutputStream packet = new ByteArrayOutputStream();
    writeVarInt(packet, 0);
    writeVarInt(packet, -1);
    final byte[] name = HOST.getBytes(StandardCharsets.UTF_8);
    writeVarInt(packet, name.length);
    packet.writeBytes(name);
    packet.write((port >> 8) & 0xFF);
    packet.write(port & 0xFF);
    writeVarInt(packet, 1);
    final ByteArrayOutputStream framed = new ByteArrayOutputStream();
    writeVarInt(framed, packet.size());
    framed.writeBytes(packet.toByteArray());
    return framed.toByteArray();
  }

  /**
   * Writes a frame to a blocking stream.
   *
   * @param out the stream
   * @param payload the payload
   * @throws IOException if writing fails
   */
  public static void writeFrame(final OutputStream out, final byte[] payload)
      throws IOException {
    final ByteArrayOutputStream framed = new ByteArrayOutputStream(payload.length + 5);
    writeVarInt(framed, payload.length);
    framed.writeBytes(payload);
    out.write(framed.toByteArray());
    out.flush();
  }

  /**
   * Reads a frame from a blocking stream.
   *
   * @param in the stream
   * @return the payload
   * @throws IOException if reading fails or the frame is too large
   */
  public static byte[] readFrame(final InputStream in) throws IOException {
    int length = 0;
    for (int shift = 0; ; shift += 7) {
      if (shift >= 35) {
        throw new IOException("Frame length is too long");
      }
      final int b = in.read();
      if (b < 0) {
        throw new EOFException();
      }
      length |= (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        break;
      }
    }
    if (length < 0 || length > MAX_FRAME) {
      throw new IOException("Frame of " + length + " bytes is too large");
    }
    final byte[] payload = new byte[length];
    new DataInputStream(in).readFully(payload);
    return payload;
  }

  private static void writeVarInt(final ByteArrayOutputStream out, final int value) {
    int v = value;
    while ((v & ~0x7F) != 0) {
      out.write((v & 0x7F) | 0x80);
      v >>>= 7;
    }
    out.write(v);
  }

  /**
   * One direction pair of an encrypted connection. Frames carry an 8 byte counter that must
   * go up by exactly one, so frames cannot be replayed, dropped or reordered.
   */
  public static final class Session {

    private final SecretKeySpec key;
    private final int sendTag;
    private final int receiveTag;
    private long sent;
    private long received;

    Session(final byte[] key, final boolean client) {
      this.key = new SecretKeySpec(key, "AES");
      this.sendTag = client ? 'C' : 'S';
      this.receiveTag = client ? 'S' : 'C';
    }

    /**
     * Encrypts one frame.
     *
     * @param plain the payload
     * @return the frame
     */
    public synchronized byte[] seal(final byte[] plain) {
      try {
        final long counter = sent++;
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key,
            new GCMParameterSpec(TAG_BITS, nonce(sendTag, counter)));
        final byte[] sealed = cipher.doFinal(plain);
        return ByteBuffer.allocate(8 + sealed.length).putLong(counter).put(sealed).array();
      } catch (final GeneralSecurityException e) {
        throw new IllegalStateException(e);
      }
    }

    /**
     * Decrypts one frame.
     *
     * @param frame the frame
     * @return the payload
     * @throws GeneralSecurityException if the frame is forged, replayed or out of order
     */
    public synchronized byte[] open(final byte[] frame) throws GeneralSecurityException {
      if (frame.length < 8 + TAG_BITS / 8) {
        throw new GeneralSecurityException("Frame too short");
      }
      final ByteBuffer buffer = ByteBuffer.wrap(frame);
      final long counter = buffer.getLong();
      if (counter != received) {
        throw new GeneralSecurityException("Unexpected frame counter " + counter);
      }
      final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key,
            new GCMParameterSpec(TAG_BITS, nonce(receiveTag, counter)));
      final byte[] plain = cipher.doFinal(frame, 8, frame.length - 8);
      received++;
      return plain;
    }

    private static byte[] nonce(final int tag, final long counter) {
      return ByteBuffer.allocate(12).putInt(tag).putLong(counter).array();
    }
  }
}
