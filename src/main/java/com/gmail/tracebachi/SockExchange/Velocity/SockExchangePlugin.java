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

import com.gmail.tracebachi.SockExchange.BuildMetadata;
import com.gmail.tracebachi.SockExchange.Bungee.BungeeKeepAliveSender;
import com.gmail.tracebachi.SockExchange.Bungee.BungeeTieIn;
import com.gmail.tracebachi.SockExchange.Bungee.ChatMessageChannelListener;
import com.gmail.tracebachi.SockExchange.Bungee.SockExchangeApi;
import com.gmail.tracebachi.SockExchange.ExpirableConsumer;
import com.gmail.tracebachi.SockExchange.Messages.ReceivedMessageNotifier;
import com.gmail.tracebachi.SockExchange.Messages.ResponseMessage;
import com.gmail.tracebachi.SockExchange.Messages.ResponseStatus;
import com.gmail.tracebachi.SockExchange.Netty.BungeeToSpigotConnection;
import com.gmail.tracebachi.SockExchange.Netty.SockExchangeServer;
import com.gmail.tracebachi.SockExchange.Scheduler.AwaitableExecutor;
import com.gmail.tracebachi.SockExchange.Scheduler.ScheduledExecutorServiceWrapper;
import com.gmail.tracebachi.SockExchange.SpigotServerInfo;
import com.gmail.tracebachi.SockExchange.Utilities.*;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * @author GeeItsZee (tracebachi@gmail.com)
 */
@Plugin(id = "sockexchange", name = "SockExchange", version = BuildMetadata.VERSION,
        authors = {"GeeItsZee (tracebachi@gmail.com)"})
public class SockExchangePlugin implements BungeeTieIn
{
  private final SockExchangeConfiguration configuration = new SockExchangeConfiguration();
  private final ProxyServer server;
  private final Logger logger;
  private final File dataFolder;

  private BasicLogger basicLogger;
  private ScheduledThreadPoolExecutor threadPoolExecutor;
  private AwaitableExecutor awaitableExecutor;
  private ReceivedMessageNotifier messageNotifier;
  private LongIdCounterMap<ExpirableConsumer<ResponseMessage>> responseConsumerMap;
  private ScheduledFuture<?> consumerTimeoutCleanupFuture;
  private CaseInsensitiveMap<BungeeToSpigotConnection> spigotConnectionMap;
  private SockExchangeServer sockExchangeServer;

  private OnlinePlayerUpdateSender onlinePlayerUpdateSender;
  private BungeeKeepAliveSender bungeeKeepAliveSender;
  private RunCmdBungeeCommand runCmdBungeeCommand;
  private MovePlayersChannelListener movePlayersChannelListener;
  private RunCmdChannelListener runCmdChannelListener;
  private ChatMessageChannelListener chatMessageChannelListener;

  @Inject
  public SockExchangePlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory)
  {
    this.server = server;
    this.logger = logger;
    this.dataFolder = dataDirectory.toFile();
  }

  public ProxyServer getProxy() {
    return server;
  }

  @Subscribe
  public void onProxyInitialize(ProxyInitializeEvent event)
  {
    if (!reloadConfiguration())
    {
      return;
    }

    boolean debugMode = configuration.inDebugMode();
    int port = configuration.getPort();
    int connectionThreads = configuration.getConnectionThreads();
    MessageFormatMap messageFormatMap = configuration.getMessageFormatMap();

    // Create the logger based on Java.Util.Logging
    basicLogger = new SlfBasicLogger(logger, debugMode);

    // Create the shared thread pool executor
    buildThreadPoolExecutor();
    ScheduledExecutorServiceWrapper wrappedThreadPool =
      new ScheduledExecutorServiceWrapper(threadPoolExecutor);

    // Create the AwaitableExecutor
    awaitableExecutor = new AwaitableExecutor(wrappedThreadPool);

    // Create the message notifier which will run consumers on SockExchange messages
    messageNotifier = new ReceivedMessageNotifier(awaitableExecutor);

    // Create the map that manages consumers for responses to sent message
    responseConsumerMap = new LongIdCounterMap<>();

    // Schedule a task to clean up the responseConsumerMap (handling timeouts)
    consumerTimeoutCleanupFuture = threadPoolExecutor.scheduleWithFixedDelay(
      this::checkForConsumerTimeouts, 5, 5, TimeUnit.SECONDS);

    // Create the map of known spigot servers that can connect to Bungee
    spigotConnectionMap = new CaseInsensitiveMap<>(new ConcurrentHashMap<>());
    for (RegisteredServer server : server.getAllServers())
    {
      BungeeToSpigotConnection connection = new BungeeToSpigotConnection(server.getServerInfo().getName(),
              awaitableExecutor, messageNotifier, responseConsumerMap, basicLogger, this);

      spigotConnectionMap.put(server.getServerInfo().getName(), connection);
    }

    // Create the API
    SockExchangeApi api = new SockExchangeApi(this, wrappedThreadPool, messageNotifier);
    SockExchangeApi.setInstance(api);

    onlinePlayerUpdateSender = new OnlinePlayerUpdateSender(this, api, 5000);
    onlinePlayerUpdateSender.register();

    bungeeKeepAliveSender = new BungeeKeepAliveSender(this, api, 2000);
    bungeeKeepAliveSender.register();

    runCmdBungeeCommand = new RunCmdBungeeCommand(this, messageFormatMap, api);
    runCmdBungeeCommand.register();

    movePlayersChannelListener = new MovePlayersChannelListener(this, api);
    movePlayersChannelListener.register();

    runCmdChannelListener = new RunCmdChannelListener(this, basicLogger, api);
    runCmdChannelListener.register();

    chatMessageChannelListener = new ChatMessageChannelListener(api);
    chatMessageChannelListener.register();

    try
    {
      sockExchangeServer = new SockExchangeServer(port, connectionThreads, this);
      sockExchangeServer.start();
    }
    catch (Exception e)
    {
      logger.error("============================================================");
      logger.error("The SockExchange server could not be started. Refer to the stacktrace below.");
      e.printStackTrace();
      logger.error("============================================================");
    }
  }

  @Subscribe
  public void onProxyShutdown(ProxyShutdownEvent event)
  {
    // Shut down the AwaitableExecutor first so tasks are not running
    // when shutting down everything else
    if (awaitableExecutor != null)
    {
      shutdownAwaitableExecutor();
      awaitableExecutor = null;
    }

    if (sockExchangeServer != null)
    {
      sockExchangeServer.shutdown();
      sockExchangeServer = null;
    }

    if (chatMessageChannelListener != null)
    {
      chatMessageChannelListener.unregister();
      chatMessageChannelListener = null;
    }

    if (runCmdChannelListener != null)
    {
      runCmdChannelListener.unregister();
      runCmdChannelListener = null;
    }

    if (movePlayersChannelListener != null)
    {
      movePlayersChannelListener.unregister();
      movePlayersChannelListener = null;
    }

    if (runCmdBungeeCommand != null)
    {
      runCmdBungeeCommand.unregister();
      runCmdBungeeCommand = null;
    }

    if (bungeeKeepAliveSender != null)
    {
      bungeeKeepAliveSender.unregister();
      bungeeKeepAliveSender = null;
    }

    if (onlinePlayerUpdateSender != null)
    {
      onlinePlayerUpdateSender.unregister();
      onlinePlayerUpdateSender = null;
    }

    SockExchangeApi.setInstance(null);

    if (spigotConnectionMap != null)
    {
      spigotConnectionMap.clear();
      spigotConnectionMap = null;
    }

    if (consumerTimeoutCleanupFuture != null)
    {
      consumerTimeoutCleanupFuture.cancel(false);
      consumerTimeoutCleanupFuture = null;
    }

    if (responseConsumerMap != null)
    {
      responseConsumerMap.clear();
      responseConsumerMap = null;
    }

    if (threadPoolExecutor != null)
    {
      shutdownThreadPoolExecutor();
      threadPoolExecutor = null;
    }

    messageNotifier = null;
    basicLogger = null;
  }

  @Override
  public boolean doesRegistrationPasswordMatch(String password)
  {
    return configuration.doesRegistrationPasswordMatch(password);
  }

  @Override
  public BungeeToSpigotConnection getConnection(String serverName)
  {
    ExtraPreconditions.checkNotEmpty(serverName, "serverName");

    return spigotConnectionMap.get(serverName);
  }

  @Override
  public Collection<BungeeToSpigotConnection> getConnections()
  {
    return Collections.unmodifiableCollection(spigotConnectionMap.values());
  }

  @Override
  public SpigotServerInfo getServerInfo(String serverName)
  {
    Preconditions.checkNotNull(serverName, "serverName");

    BungeeToSpigotConnection connection = spigotConnectionMap.get(serverName);

    if (connection == null)
    {
      return null;
    }

    boolean isPrivate = configuration.isPrivateServer(connection.getServerName());
    return new SpigotServerInfo(connection.getServerName(), connection.hasChannel(), isPrivate);
  }

  @Override
  public List<SpigotServerInfo> getServerInfos()
  {
    Collection<BungeeToSpigotConnection> connections = spigotConnectionMap.values();
    List<SpigotServerInfo> result = new ArrayList<>(connections.size());

    for (BungeeToSpigotConnection connection : connections)
    {
      boolean isPrivate = configuration.isPrivateServer(connection.getServerName());
      SpigotServerInfo serverInfo = new SpigotServerInfo(connection.getServerName(),
        connection.hasChannel(), isPrivate);

      result.add(serverInfo);
    }

    return Collections.unmodifiableList(result);
  }

  @Override
  public String getServerNameForPlayer(String playerName)
  {
    Optional<Player> player = server.getPlayer(playerName);

    if (!player.isPresent())
    {
      return null;
    }

    Optional<ServerConnection> server = player.get().getCurrentServer();

    return server.map(serverConnection -> serverConnection.getServerInfo().getName()).orElse(null);
  }

  @Override
  public void sendChatMessagesToPlayer(String playerName, List<String> messages)
  {
    Preconditions.checkNotNull(playerName, "receiverName");
    Preconditions.checkNotNull(messages, "messages");

    Optional<Player> proxyPlayer = server.getPlayer(playerName);
    if (proxyPlayer.isPresent())
    {
      for (String message : messages)
      {
        proxyPlayer.get().sendMessage(
                LegacyComponentSerializer.legacySection().deserialize(message));
      }
    }
  }

  @Override
  public void sendChatMessagesToConsole(List<String> messages)
  {
    Preconditions.checkNotNull(messages, "messages");

    CommandSource console = server.getConsoleCommandSource();
    for (String message : messages)
    {
      console.sendMessage(
              LegacyComponentSerializer.legacySection().deserialize(message));
    }
  }

  private boolean reloadConfiguration()
  {
    File file = BungeeResourceUtil.saveResource(this, dataFolder,
            "bungee-config.yml", "config.yml");

    try
    {
      if (file == null)
        throw new IOException("File is null!");
      YAMLConfigurationLoader yamlProvider = YAMLConfigurationLoader.builder()
              .setPath(file.toPath()).build();
      ConfigurationNode loadedConfig = yamlProvider.load();
      configuration.read(loadedConfig);
      return true;
    }
    catch (IOException ex)
    {
      logger.error("============================================================");
      logger.error("The SockExchange configuration file could not be loaded.");
      ex.printStackTrace();
      logger.error("============================================================");
    }

    return false;
  }

  private void checkForConsumerTimeouts()
  {
    long currentTimeMillis = System.currentTimeMillis();

    responseConsumerMap.removeIf((entry) ->
    {
      ExpirableConsumer<ResponseMessage> responseConsumer = entry.getValue();

      if (responseConsumer.getExpiresAtMillis() > currentTimeMillis)
      {
        // Keep the entry
        return false;
      }

      awaitableExecutor.execute(() ->
      {
        ResponseMessage responseMessage = new ResponseMessage(ResponseStatus.TIMED_OUT);
        responseConsumer.accept(responseMessage);
      });

      // Remove the entry
      return true;
    });
  }

  private void shutdownAwaitableExecutor()
  {
    try
    {
      awaitableExecutor.setAcceptingTasks(false);
      awaitableExecutor.awaitTasksWithSleep(10, 1000);
      awaitableExecutor.shutdown();
    }
    catch (InterruptedException ex)
    {
      ex.printStackTrace();
    }
  }

  private void buildThreadPoolExecutor()
  {
    ThreadFactoryBuilder factoryBuilder = new ThreadFactoryBuilder();
    factoryBuilder.setNameFormat("SockExchange-Scheduler-Thread-%d");

    ThreadFactory threadFactory = factoryBuilder.build();
    threadPoolExecutor = new ScheduledThreadPoolExecutor(2, threadFactory);

    threadPoolExecutor.setMaximumPoolSize(8);
    threadPoolExecutor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    threadPoolExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  private void shutdownThreadPoolExecutor()
  {
    if (!threadPoolExecutor.isShutdown())
    {
      // Disable new tasks from being submitted to service
      threadPoolExecutor.shutdown();

      logger.info("ScheduledThreadPoolExecutor being shutdown()");

      try
      {
        // Await termination for a minute
        if (!threadPoolExecutor.awaitTermination(60, TimeUnit.SECONDS))
        {
          // Force shutdown
          threadPoolExecutor.shutdownNow();

          logger.error("ScheduledThreadPoolExecutor being shutdownNow()");

          // Await termination again for another minute
          if (!threadPoolExecutor.awaitTermination(60, TimeUnit.SECONDS))
          {
            logger.error("ScheduledThreadPoolExecutor not shutdown after shutdownNow()");
          }
        }
      }
      catch (InterruptedException ex)
      {
        logger.error("ScheduledThreadPoolExecutor shutdown interrupted");

        // Re-cancel if current thread also interrupted
        threadPoolExecutor.shutdownNow();

        logger.error("ScheduledThreadPoolExecutor being shutdownNow()");

        // Preserve interrupt status
        Thread.currentThread().interrupt();
      }
    }
  }
}
