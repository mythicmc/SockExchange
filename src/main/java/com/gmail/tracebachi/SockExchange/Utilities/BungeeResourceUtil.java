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
package com.gmail.tracebachi.SockExchange.Utilities;

import com.google.common.io.ByteStreams;
import net.md_5.bungee.api.plugin.Plugin;

import java.io.*;
import java.util.function.Function;

/**
 * @author GeeItsZee (tracebachi@gmail.com)
 */
public class BungeeResourceUtil
{
  /**
   * This is a static utility class, so construction of a new object
   * is not allowed.
   */
  private BungeeResourceUtil()
  {
  }

  /**
   * Loads the resource from the JAR and saves it to the destination under the plugin's
   * data folder. By default, the destination file will not be replaced if it exists.
   *
   * @param plugin Plugin that contains the resource in its JAR
   * @param resourceName Filename of the resource
   * @param destinationName Filename of the destination
   *
   * @return Destination File
   */
  public static File saveResource(Plugin plugin, String resourceName, String destinationName)
  {
    return saveResource(plugin::getResourceAsStream, plugin.getDataFolder(),
            resourceName, destinationName, false);
  }

  /**
   * Loads the resource from the JAR and saves it to the destination under the plugin's
   * data folder. The destination file will be replaced if specified in the argument.
   *
   * @param plugin Plugin that contains the resource in its JAR
   * @param resourceName Filename of the resource
   * @param destinationName Filename of the destination
   * @param replaceIfDestExists Whether or not to replace destination file if it exists
   *
   * @return Destination File
   */
  public static File saveResource(
    Plugin plugin, String resourceName, String destinationName,
    boolean replaceIfDestExists)
  {
    return saveResource(plugin::getResourceAsStream, plugin.getDataFolder(),
            resourceName, destinationName, replaceIfDestExists);
  }

  /**
   * Source for the majority of this method can be found at:
   * https://www.spigotmc.org/threads/bungeecords-configuration-api.11214/#post-119017
   * <p>
   * Originally authored by: vemacs, Feb 15, 2014
   * </p>
   */
  public static File saveResource(
    Function<String, InputStream> getResourceAsStream,
    File folder, String resourceName, String destinationName, boolean replaceIfDestExists)
  {
    if (!folder.exists() && !folder.mkdir())
    {
      return null;
    }

    File destinationFile = new File(folder, destinationName);

    try
    {
      if (!destinationFile.exists() || replaceIfDestExists)
      {
        if (destinationFile.createNewFile())
        {
          try (InputStream in = getResourceAsStream.apply(resourceName);
            OutputStream out = new FileOutputStream(destinationFile))
          {
            ByteStreams.copy(in, out);
          }
        }
        else
        {
          return null;
        }
      }

      return destinationFile;
    }
    catch (IOException e)
    {
      e.printStackTrace();
      return null;
    }
  }
}
