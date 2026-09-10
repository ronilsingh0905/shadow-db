package com.shadowdb;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Module 1.0: Shadow-DB Netty TCP Proxy Server.
 * Listens on client port (default 5432) and proxies traffic to PostgreSQL (default localhost:5433).
 */
public class Server {

    private static final Logger log = LoggerFactory.getLogger(Server.class);

    public static final int DEFAULT_LISTEN_PORT = 5432;
    public static final String DEFAULT_TARGET_HOST = "127.0.0.1";
    public static final int DEFAULT_TARGET_PORT = 5433;

    private final int listenPort;
    private final String targetHost;
    private final int targetPort;
    private final CacheManager cacheManager;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public Server() {
        this(DEFAULT_LISTEN_PORT, DEFAULT_TARGET_HOST, DEFAULT_TARGET_PORT, new CacheManager());
    }

    public Server(int listenPort, String targetHost, int targetPort) {
        this(listenPort, targetHost, targetPort, new CacheManager());
    }

    public Server(int listenPort, String targetHost, int targetPort, CacheManager cacheManager) {
        this.listenPort = listenPort;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.cacheManager = cacheManager;
    }

    /**
     * Starts the proxy server asynchronously, binding to the listen port.
     */
    public synchronized ChannelFuture startAsync() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
         .channel(NioServerSocketChannel.class)
         .childHandler(new ChannelInitializer<SocketChannel>() {
             @Override
             protected void initChannel(SocketChannel ch) {
                 ch.pipeline().addLast(new ProxyHandler(targetHost, targetPort, cacheManager));
             }
         })
         .childOption(ChannelOption.AUTO_READ, true)
         .childOption(ChannelOption.TCP_NODELAY, true)
         .option(ChannelOption.SO_BACKLOG, 128);

        log.info("Starting Shadow-DB Proxy listening on port {} -> forwarding to {}:{}",
                listenPort, targetHost, targetPort);

        ChannelFuture future = b.bind(listenPort);
        future.addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                serverChannel = f.channel();
                log.info("Shadow-DB successfully listening on port {}", listenPort);
            } else {
                log.error("Failed to bind Shadow-DB on port {}", listenPort, f.cause());
            }
        });
        return future;
    }

    /**
     * Starts the proxy server synchronously and waits until shutdown.
     */
    public void start() throws InterruptedException {
        ChannelFuture future = startAsync().sync();
        serverChannel = future.channel();
        serverChannel.closeFuture().sync();
    }

    /**
     * Gracefully stops the proxy server and shuts down all event loops.
     */
    public synchronized void stop() {
        log.info("Stopping Shadow-DB Proxy on port {}...", listenPort);
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup = null;
        }
        log.info("Shadow-DB Proxy stopped.");
    }

    public int getListenPort() {
        return listenPort;
    }

    public String getTargetHost() {
        return targetHost;
    }

    public int getTargetPort() {
        return targetPort;
    }

    public CacheManager getCacheManager() {
        return cacheManager;
    }

    public static void main(String[] args) {
        int listenPort = DEFAULT_LISTEN_PORT;
        String targetHost = DEFAULT_TARGET_HOST;
        int targetPort = DEFAULT_TARGET_PORT;

        if (args.length >= 1) {
            listenPort = Integer.parseInt(args[0]);
        }
        if (args.length >= 2) {
            targetHost = args[1];
        }
        if (args.length >= 3) {
            targetPort = Integer.parseInt(args[2]);
        }

        Server server = new Server(listenPort, targetHost, targetPort);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        try {
            server.start();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Server interrupted, exiting.");
        }
    }
}
