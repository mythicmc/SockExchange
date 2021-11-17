/*
 * SockExchange - Server and Client for BungeeCord and Spigot communication
 * Copyright (C) 2017 tracebachi@gmail.com (GeeItsZee)
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.gmail.tracebachi.SockExchange.Velocity;

import com.gmail.tracebachi.SockExchange.Utilities.CaseInsensitiveSet;
import com.gmail.tracebachi.SockExchange.Utilities.MessageFormatMap;
import ninja.leaping.configurate.ConfigurationNode;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

/**
 * @author GeeItsZee (tracebachi@gmail.com)
 */
class SockExchangeConfiguration
{
  private int port;
  private int connectionThreads;
  private String registrationPassword;
  private MessageFormatMap messageFormatMap;
  private boolean debugMode;
  private final CaseInsensitiveSet privateServers = new CaseInsensitiveSet(new HashSet<>());

  void read(ConfigurationNode configuration)
  {
    port = configuration.getNode("SockExchangeServer", "Port").getInt(20000);
    connectionThreads = configuration.getNode("SockExchangeServer", "Threads").getInt(2);
    registrationPassword = configuration.getNode("SockExchangeServer", "Password").getString("FreshSocks");
    debugMode = configuration.getNode("DebugMode").getBoolean(false);
    messageFormatMap = new MessageFormatMap();

    privateServers.clear();
    privateServers.addAll(configuration.getNode("PrivateServers").getList(item -> (String) item));

    ConfigurationNode formats = configuration.getNode("Formats");
    if (!formats.isVirtual())
    {
      for (Map.Entry<Object, ? extends ConfigurationNode> format : formats.getChildrenMap().entrySet())
      {
        messageFormatMap.put((String) format.getKey(), format.getValue().getString());
      }
    }
  }

  int getPort()
  {
    return port;
  }

  int getConnectionThreads()
  {
    return connectionThreads;
  }

  boolean doesRegistrationPasswordMatch(String input)
  {
    return Objects.equals(registrationPassword, input);
  }

  MessageFormatMap getMessageFormatMap()
  {
    return messageFormatMap;
  }

  boolean inDebugMode()
  {
    return debugMode;
  }

  boolean isPrivateServer(String serverName)
  {
    return privateServers.contains(serverName);
  }
}
