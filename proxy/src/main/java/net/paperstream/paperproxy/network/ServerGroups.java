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

package net.paperstream.paperproxy.network;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.VelocityServer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Server groups: several servers that act as one. Players go to the member with the fewest
 * players that they are allowed to use and that has room.
 */
public final class ServerGroups {

  /** Permission to join servers that are full. */
  public static final String FULL_BYPASS = "paperproxy.queue.bypass";

  private final VelocityServer server;

  /**
   * Creates the groups.
   *
   * @param server the proxy
   */
  public ServerGroups(final VelocityServer server) {
    this.server = server;
  }

  private Map<String, List<String>> groups() {
    final Map<String, List<String>> linked = server.getServerLinks().groups();
    if (linked.isEmpty()) {
      return server.getPaperProxyConfig().values().groups();
    }
    final Map<String, List<String>> merged =
        new java.util.LinkedHashMap<>(server.getPaperProxyConfig().values().groups());
    linked.forEach((group, members) -> merged.merge(group, members, (a, b) -> {
      final List<String> all = new java.util.ArrayList<>(a);
      b.stream().filter(m -> !all.contains(m)).forEach(all::add);
      return all;
    }));
    return merged;
  }

  /**
   * Returns the group a server belongs to.
   *
   * @param serverName the server
   * @return the group name, or null if the server is in no group
   */
  public @Nullable String groupOf(final String serverName) {
    final String lower = serverName.toLowerCase(Locale.ROOT);
    for (final Map.Entry<String, List<String>> entry : groups().entrySet()) {
      if (entry.getValue().contains(lower)) {
        return entry.getKey();
      }
    }
    return null;
  }

  /**
   * Returns the members of a group.
   *
   * @param group the group name
   * @return the member names, empty if there is no such group
   */
  public List<String> members(final String group) {
    return groups().getOrDefault(group.toLowerCase(Locale.ROOT), List.of());
  }

  /**
   * Tells whether a server has no room for this player.
   *
   * @param player the player
   * @param target the server
   * @return true if the server reported a player limit that is reached
   */
  public boolean isFull(final Player player, final RegisteredServer target) {
    if (player.hasPermission(FULL_BYPASS)) {
      return false;
    }
    final int max = server.getHealthChecker().maxPlayers(target.getServerInfo().getName());
    if (max <= 0) {
      return false;
    }
    final boolean alreadyThere = player.getCurrentServer()
        .map(c -> c.getServer().equals(target)).orElse(false);
    return !alreadyThere && target.getPlayersConnected().size() >= max;
  }

  /**
   * Picks the best server for a player out of a server or group name.
   *
   * @param player the player
   * @param name a server or group name
   * @param exclude a server to skip, may be null
   * @return the server, empty if none is usable
   */
  public Optional<RegisteredServer> best(final Player player, final String name,
                                         final @Nullable RegisteredServer exclude) {
    final String lower = name.toLowerCase(Locale.ROOT);
    final List<String> candidates;
    if (groups().containsKey(lower)) {
      candidates = groups().get(lower);
    } else {
      final String group = groupOf(lower);
      candidates = group == null ? List.of(lower) : groups().get(group);
    }
    return candidates.stream()
        .map(server::getServer)
        .flatMap(Optional::stream)
        .filter(candidate -> !candidate.equals(exclude))
        .filter(candidate -> server.getNetworkRules().problem(player, candidate) == null)
        .filter(candidate -> !isFull(player, candidate))
        .min(Comparator.comparingInt(candidate -> candidate.getPlayersConnected().size()));
  }
}
