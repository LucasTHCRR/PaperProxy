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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.proxy.network.Connections;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * One link connection from a PaperStream backend, on the proxy side.
 */
public final class LinkHandler extends SimpleChannelInboundHandler<ByteBuf> {

  private static final Logger logger = LogManager.getLogger(LinkHandler.class);
  private static final String HANDLER = "paperstream-link";
  private static final long WARN_INTERVAL_MILLIS = 10 * 60 * 1000L;
  private static final Map<String, Long> WARNED = new ConcurrentHashMap<>();
  /** Frames before the backend proved its key are small; only a linked backend sends more. */
  private static final int MAX_UNAUTHENTICATED_FRAME = 8 * 1024;

  private enum State {
    START, PAIR_PROOF, PAIR_CONFIRM, LINK_AUTH, LINKED, CLOSED
  }

  private final ServerLinks links;
  private final String software;
  private @Nullable Channel channel;
  private State state = State.START;
  private byte[] clientNonce = new byte[0];
  private byte[] serverNonce = new byte[0];
  private byte[] clientPublic = new byte[0];
  private int backendPort;
  private @Nullable KeyPair keyPair;
  private ServerLinks.@Nullable Pending pairing;
  private LinkProtocol.@Nullable Session pairingSession;
  private byte[] newLinkKey = new byte[0];
  private ServerLinks.@Nullable Linked linked;
  private LinkProtocol.@Nullable Session session;
  private final FrameDecoder decoder = new FrameDecoder();

  private LinkHandler(final ServerLinks links, final String software) {
    this.links = links;
    this.software = software;
  }

  /**
   * Turns a fresh Minecraft connection that sent the link handshake into a link connection.
   *
   * @param channel the channel
   * @param links the registry
   * @param software the proxy name and version
   */
  public static void takeOver(final Channel channel, final ServerLinks links,
                              final String software) {
    final ChannelPipeline pipeline = channel.pipeline();
    // Only the framing and the read timeout stay. Everything else, Velocity's packet codecs
    // and handlers that plugins such as ViaVersion inject, would treat link frames as packets.
    final Set<String> keep = Set.of(Connections.FRAME_DECODER, Connections.FRAME_ENCODER,
        Connections.READ_TIMEOUT, Connections.HANDLER);
    for (final String name : List.copyOf(pipeline.names())) {
      if (!keep.contains(name) && pipeline.context(name) != null
          && !name.startsWith("DefaultChannelPipeline$TailContext")) {
        pipeline.remove(name);
      }
    }
    final LinkHandler handler = new LinkHandler(links, software);
    pipeline.replace(Connections.HANDLER, HANDLER, handler);
    // Replacing the frame decoder last hands over bytes that arrived behind the handshake.
    pipeline.replace(Connections.FRAME_DECODER, Connections.FRAME_DECODER, handler.decoder);
  }

  @Override
  public void handlerAdded(final ChannelHandlerContext ctx) {
    this.channel = ctx.channel();
    final JsonObject hello = new JsonObject();
    hello.addProperty("op", "hello");
    hello.addProperty("v", LinkProtocol.VERSION);
    hello.addProperty("software", software);
    writePlain(hello);
    // Unauthenticated connections get ten seconds.
    ctx.executor().schedule(() -> {
      if (state != State.LINKED) {
        close();
      }
    }, 10, TimeUnit.SECONDS);
  }

  @Override
  protected void channelRead0(final ChannelHandlerContext ctx, final ByteBuf frame) {
    final byte[] bytes = ByteBufUtil.getBytes(frame);
    try {
      switch (state) {
        case START -> start(json(bytes));
        case PAIR_PROOF -> pairProof(json(bytes));
        case PAIR_CONFIRM -> pairConfirm(bytes);
        case LINK_AUTH -> linkAuth(json(bytes));
        case LINKED -> {
          final ServerLinks.Linked server = linked;
          if (server != null && session != null) {
            links.receive(server.name(), this, json(session.open(bytes)));
          }
        }
        default -> close();
      }
    } catch (GeneralSecurityException | RuntimeException e) {
      logger.warn("PaperStream link from {} closed: {}", address(), e.getMessage());
      close();
    }
  }

  private static JsonObject json(final byte[] bytes) {
    return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
  }

  private void start(final JsonObject message) throws GeneralSecurityException {
    final String op = message.get("op").getAsString();
    if (message.get("v").getAsInt() != LinkProtocol.VERSION) {
      refuse("version", "PaperStream link from {} uses an unsupported protocol version");
      return;
    }
    clientNonce = LinkProtocol.unb64(message.get("nonce").getAsString());
    serverNonce = LinkProtocol.random(32);
    if (op.equals("pair")) {
      if (links.pendingPairings().isEmpty()) {
        refuse("no-pairing", "PaperStream server at {} tried to pair, but no pairing code is "
            + "open. Run /paperproxy link pair <name> first.");
        return;
      }
      if (links.forwardingSecret().isBlank()) {
        refuse("no-secret", "PaperStream server at {} tried to pair, but the forwarding secret "
            + "of this proxy is empty. Put a secret into forwarding.secret first.");
        return;
      }
      clientPublic = LinkProtocol.unb64(message.get("pub").getAsString());
      backendPort = message.get("port").getAsInt();
      if (backendPort < 1 || backendPort > 65535) {
        refuse("port", "PaperStream server at {} sent an invalid port");
        return;
      }
      keyPair = LinkProtocol.newKeyPair();
      final JsonObject challenge = new JsonObject();
      challenge.addProperty("op", "pair-challenge");
      challenge.addProperty("pub", LinkProtocol.b64(keyPair.getPublic().getEncoded()));
      challenge.addProperty("nonce", LinkProtocol.b64(serverNonce));
      state = State.PAIR_PROOF;
      writePlain(challenge);
    } else if (op.equals("link")) {
      linked = links.linked(message.get("server").getAsString());
      if (linked == null) {
        refuse("unknown", "Refused a PaperStream link from {}: that server is not paired "
            + "with this proxy");
        return;
      }
      final JsonObject challenge = new JsonObject();
      challenge.addProperty("op", "challenge");
      challenge.addProperty("nonce", LinkProtocol.b64(serverNonce));
      state = State.LINK_AUTH;
      writePlain(challenge);
    } else {
      close();
    }
  }

  private byte[] transcript() {
    final KeyPair pair = keyPair;
    return LinkProtocol.join(clientPublic, clientNonce,
        pair == null ? new byte[0] : pair.getPublic().getEncoded(), serverNonce);
  }

  private void pairProof(final JsonObject message) throws GeneralSecurityException {
    final byte[] proof = LinkProtocol.unb64(message.get("proof").getAsString());
    final byte[] transcript = transcript();
    ServerLinks.Pending match = null;
    for (final ServerLinks.Pending pairing : links.pendingPairings()) {
      if (LinkProtocol.same(proof,
          LinkProtocol.mac(LinkProtocol.codeBytes(pairing.code()), "backend", transcript))) {
        match = pairing;
        break;
      }
    }
    if (match == null || keyPair == null) {
      links.failedPairing();
      refuse("wrong-code", "PaperStream server at {} sent a wrong pairing code");
      return;
    }
    // The code works once, even if this pairing does not finish.
    final String name = links.claim(match);
    if (name == null) {
      refuse("wrong-code", "PaperStream server at {} used a pairing code that was just used");
      return;
    }
    final byte[] shared = LinkProtocol.agree(keyPair.getPrivate(), clientPublic);
    pairingSession = LinkProtocol.pairSession(shared, match.code(), transcript, false);
    pairing = match;
    newLinkKey = LinkProtocol.random(32);

    final JsonObject ok = new JsonObject();
    ok.addProperty("op", "pair-ok");
    ok.addProperty("proof", LinkProtocol.b64(
        LinkProtocol.mac(LinkProtocol.codeBytes(match.code()), "proxy", transcript)));
    writePlain(ok);
    final JsonObject result = new JsonObject();
    result.addProperty("server", name);
    result.addProperty("secret", links.forwardingSecret());
    result.addProperty("key", LinkProtocol.b64(newLinkKey));
    write(pairingSession.seal(result.toString().getBytes(StandardCharsets.UTF_8)));
    state = State.PAIR_CONFIRM;
  }

  /** The backend saved the pairing; only now the proxy keeps it too. */
  private void pairConfirm(final byte[] frame) throws GeneralSecurityException {
    final LinkProtocol.Session current = pairingSession;
    final ServerLinks.Pending match = pairing;
    if (current == null || match == null
        || !"paired".equals(json(current.open(frame)).get("op").getAsString())) {
      close();
      return;
    }
    links.completePairing(match, address(), backendPort, newLinkKey);
    state = State.CLOSED;
    close();
  }

  private void linkAuth(final JsonObject message) {
    final ServerLinks.Linked server = linked;
    if (server == null) {
      close();
      return;
    }
    final byte[] name = server.name().getBytes(StandardCharsets.UTF_8);
    final byte[] mac = LinkProtocol.unb64(message.get("mac").getAsString());
    if (!LinkProtocol.same(mac,
        LinkProtocol.mac(server.key(), "backend", name, clientNonce, serverNonce))) {
      refuse("auth", "Refused a PaperStream link for " + server.name().replace("{", "")
          + " from {}: wrong link key. Pair the server again if it was reset.");
      return;
    }
    session = LinkProtocol.linkSession(server.key(), server.name(), clientNonce, serverNonce,
        false);
    final JsonObject ok = new JsonObject();
    ok.addProperty("op", "ok");
    ok.addProperty("mac", LinkProtocol.b64(
        LinkProtocol.mac(server.key(), "proxy", name, clientNonce, serverNonce)));
    writePlain(ok);
    state = State.LINKED;
    decoder.maxFrame = LinkProtocol.MAX_FRAME;
    links.attach(server, this, address());
  }

  private void refuse(final String reason, final String log) {
    // A backend with an old key retries every few minutes; one warning in ten is enough.
    final long now = System.currentTimeMillis();
    final Long last = WARNED.get(reason + address());
    if (last == null || now - last > WARN_INTERVAL_MILLIS) {
      if (WARNED.size() > 1000) {
        WARNED.clear();
      }
      WARNED.put(reason + address(), now);
      logger.warn(log, address());
    }
    final JsonObject error = new JsonObject();
    error.addProperty("op", "error");
    error.addProperty("reason", reason);
    state = State.CLOSED;
    writePlain(error).addListener(ChannelFutureListener.CLOSE);
  }

  /**
   * Sends an encrypted message on a linked connection.
   *
   * @param message the message
   */
  void send(final JsonObject message) {
    final LinkProtocol.Session current = session;
    if (current != null && state == State.LINKED) {
      write(current.seal(message.toString().getBytes(StandardCharsets.UTF_8)));
    }
  }

  private io.netty.channel.ChannelFuture writePlain(final JsonObject message) {
    return write(message.toString().getBytes(StandardCharsets.UTF_8));
  }

  private io.netty.channel.ChannelFuture write(final byte[] payload) {
    final Channel ch = channel;
    if (ch == null) {
      throw new IllegalStateException("Not connected");
    }
    return ch.writeAndFlush(Unpooled.wrappedBuffer(payload));
  }

  /**
   * Closes the connection.
   */
  void close() {
    state = State.CLOSED;
    final Channel ch = channel;
    if (ch != null) {
      ch.close();
    }
  }

  private String address() {
    final Channel ch = channel;
    if (ch != null && ch.remoteAddress() instanceof InetSocketAddress inet) {
      return inet.getAddress().getHostAddress();
    }
    return "?";
  }

  @Override
  public void channelInactive(final ChannelHandlerContext ctx) {
    state = State.CLOSED;
    final ServerLinks.Linked server = linked;
    if (server != null) {
      links.detach(server.name(), this);
    }
  }

  @Override
  public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
    logger.debug("PaperStream link error", cause);
    close();
  }

  /**
   * Splits the stream into frames with a VarInt length.
   */
  static final class FrameDecoder extends ByteToMessageDecoder {

    private volatile int maxFrame = MAX_UNAUTHENTICATED_FRAME;

    @Override
    protected void decode(final ChannelHandlerContext ctx, final ByteBuf in,
                          final List<Object> out) {
      while (in.isReadable()) {
        final int start = in.readerIndex();
        int length = 0;
        boolean complete = false;
        for (int shift = 0; shift < 35 && in.isReadable(); shift += 7) {
          final byte b = in.readByte();
          length |= (b & 0x7F) << shift;
          if ((b & 0x80) == 0) {
            complete = true;
            break;
          }
        }
        if (!complete) {
          if (in.readerIndex() - start >= 5) {
            ctx.close();
            return;
          }
          in.readerIndex(start);
          return;
        }
        if (length < 0 || length > maxFrame) {
          in.skipBytes(in.readableBytes());
          ctx.close();
          return;
        }
        if (in.readableBytes() < length) {
          in.readerIndex(start);
          return;
        }
        out.add(in.readRetainedSlice(length));
      }
    }
  }
}
