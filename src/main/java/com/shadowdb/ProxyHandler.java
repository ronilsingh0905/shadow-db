package com.shadowdb;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Module 1.0, 3.0 & 4.0: Netty TCP Proxy Handler.
 * Bridges client channel and PostgreSQL backend channel,
 * intercepts SELECT queries for cache hits/misses,
 * and intercepts mutations for reactive cache invalidation.
 */
public class ProxyHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(ProxyHandler.class);

    private final String remoteHost;
    private final int remotePort;
    private final CacheManager cacheManager;

    private Channel outboundChannel;
    private ProxyBackendHandler backendHandler;
    private final Queue<Object> pendingMessages = new LinkedList<>();
    private volatile boolean connected = false;

    public ProxyHandler(String remoteHost, int remotePort, CacheManager cacheManager) {
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.cacheManager = cacheManager;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        final Channel inboundChannel = ctx.channel();
        inboundChannel.config().setAutoRead(false);

        Bootstrap b = new Bootstrap();
        backendHandler = new ProxyBackendHandler(inboundChannel, cacheManager);

        b.group(inboundChannel.eventLoop())
         .channel(inboundChannel.getClass())
         .handler(backendHandler)
         .option(ChannelOption.TCP_NODELAY, true)
         .option(ChannelOption.AUTO_READ, true);

        ChannelFuture f = b.connect(remoteHost, remotePort);
        outboundChannel = f.channel();

        f.addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                log.debug("Successfully connected to backend {}:{}", remoteHost, remotePort);
                connected = true;
                // Flush buffered messages if any
                while (!pendingMessages.isEmpty()) {
                    outboundChannel.write(pendingMessages.poll());
                }
                outboundChannel.flush();
                inboundChannel.config().setAutoRead(true);
            } else {
                log.error("Failed to connect to backend {}:{}", remoteHost, remotePort, future.cause());
                closeOnFlush(inboundChannel);
            }
        });
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf byteBuf)) {
            forward(msg);
            return;
        }

        // Try extracting SQL statement from inbound traffic
        String sql = QueryParser.extractSql(byteBuf);

        if (sql != null) {
            QueryParser.ParsedQuery parsed = QueryParser.parse(sql);
            log.debug("Intercepted query: {} | Type: {} | Tables: {}", sql, parsed.type(), parsed.tableNames());

            if (parsed.isSelect()) {
                // Module 3.0: Read Logic
                byte[] cachedBytes = cacheManager.get(parsed.hashKey());
                if (cachedBytes != null) {
                    // CACHE HIT: sub-3ms SLA fast path directly back to client socket
                    log.info("CACHE HIT [{}]: returning {} cached bytes", parsed.hashKey(), cachedBytes.length);
                    ReferenceCountUtil.release(byteBuf);

                    ByteBuf responseBuf = ctx.alloc().buffer(cachedBytes.length);
                    responseBuf.writeBytes(cachedBytes);
                    ctx.writeAndFlush(responseBuf);
                    return;
                }

                // CACHE MISS: Forward query to PostgreSQL and prepare to capture response
                log.info("CACHE MISS [{}]: querying backend PostgreSQL", parsed.hashKey());
                boolean isWire = isWireProtocol(byteBuf);
                if (backendHandler != null) {
                    backendHandler.setPendingSelect(parsed, isWire);
                }
            } else if (parsed.isMutation()) {
                // Module 4.0: Reactive Invalidation
                log.info("MUTATION DETECTED [{}]: invalidating tables {}", parsed.type(), parsed.tableNames());
                cacheManager.invalidateTables(parsed.tableNames());
            }
        }

        forward(msg);
    }

    private void forward(Object msg) {
        if (connected && outboundChannel != null && outboundChannel.isActive()) {
            outboundChannel.writeAndFlush(msg);
        } else {
            pendingMessages.add(msg);
        }
    }

    private static boolean isWireProtocol(ByteBuf buf) {
        if (buf.readableBytes() >= 1) {
            return buf.getByte(buf.readerIndex()) == 'Q';
        }
        return false;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (outboundChannel != null) {
            closeOnFlush(outboundChannel);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Exception in ProxyHandler", cause);
        closeOnFlush(ctx.channel());
    }

    public static void closeOnFlush(Channel ch) {
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * Backend handler handling traffic from PostgreSQL to the client.
     */
    public static class ProxyBackendHandler extends ChannelInboundHandlerAdapter {

        private final Channel inboundChannel;
        private final CacheManager cacheManager;

        private volatile QueryParser.ParsedQuery pendingSelect;
        private volatile boolean isWireProtocol = true;
        private final ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();

        public ProxyBackendHandler(Channel inboundChannel, CacheManager cacheManager) {
            this.inboundChannel = inboundChannel;
            this.cacheManager = cacheManager;
        }

        public void setPendingSelect(QueryParser.ParsedQuery query, boolean isWireProtocol) {
            this.pendingSelect = query;
            this.isWireProtocol = isWireProtocol;
            this.responseBuffer.reset();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf buf) {
                if (pendingSelect != null) {
                    recordChunk(buf);
                }
                inboundChannel.writeAndFlush(msg);
            } else {
                inboundChannel.writeAndFlush(msg);
            }
        }

        private void recordChunk(ByteBuf buf) {
            int readerIdx = buf.readerIndex();
            int readable = buf.readableBytes();
            byte[] chunk = new byte[readable];
            buf.getBytes(readerIdx, chunk);
            responseBuffer.write(chunk, 0, chunk.length);

            byte[] accumulated = responseBuffer.toByteArray();
            boolean isComplete = false;

            if (isWireProtocol) {
                // In PostgreSQL wire protocol, query execution completes when 'ReadyForQuery' (Z) is received
                if (PostgresProtocolHelper.isReadyForQueryAtEnd(accumulated)) {
                    isComplete = true;
                }
            } else {
                // In raw text protocol, completes on newline or non-empty response
                if (accumulated.length > 0 && (accumulated[accumulated.length - 1] == '\n' || accumulated[accumulated.length - 1] == '\r')) {
                    isComplete = true;
                } else if (accumulated.length > 0) {
                    isComplete = true;
                }
            }

            if (isComplete && pendingSelect != null) {
                log.info("Recording cache entry for key [{}]: {} bytes on tables {}",
                        pendingSelect.hashKey(), accumulated.length, pendingSelect.tableNames());
                cacheManager.put(pendingSelect.hashKey(), pendingSelect.tableNames(), accumulated);
                pendingSelect = null;
                responseBuffer.reset();
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            closeOnFlush(inboundChannel);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Exception in ProxyBackendHandler", cause);
            closeOnFlush(ctx.channel());
        }
    }
}
