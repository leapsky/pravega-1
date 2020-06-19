/**
 * Copyright (c) Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.client.connection.impl;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import io.netty.buffer.ByteBuf;
import io.pravega.client.ClientConfig;
import io.pravega.common.Exceptions;
import io.pravega.common.concurrent.ExecutorServiceHelpers;
import io.pravega.common.util.CertificateUtils;
import io.pravega.shared.protocol.netty.Append;
import io.pravega.shared.protocol.netty.AppendBatchSizeTracker;
import io.pravega.shared.protocol.netty.ConnectionFailedException;
import io.pravega.shared.protocol.netty.EnhancedByteBufInputStream;
import io.pravega.shared.protocol.netty.InvalidMessageException;
import io.pravega.shared.protocol.netty.PravegaNodeUri;
import io.pravega.shared.protocol.netty.Reply;
import io.pravega.shared.protocol.netty.ReplyProcessor;
import io.pravega.shared.protocol.netty.WireCommand;
import io.pravega.shared.protocol.netty.WireCommandType;
import io.pravega.shared.protocol.netty.WireCommands;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class TcpClientConnection implements ClientConnection {

    private static final int TCP_BUFFER_SIZE = 256 * 1024;
    
    private final Socket socket;
    private final CommandEncoder encoder;
    private final ConnectionReader reader;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final PravegaNodeUri location;

    @VisibleForTesting
    static class ConnectionReader {
        
        private final String name;
        private final InputStream in;
        private final ReplyProcessor callback;
        private final ScheduledExecutorService thread;
        private final AppendBatchSizeTracker batchSizeTracker;
        private final AtomicBoolean stop = new AtomicBoolean(false);

        public ConnectionReader(String name, InputStream in, ReplyProcessor callback,
                                AppendBatchSizeTracker batchSizeTracker) {
            this.name = name;
            this.in = in;
            this.callback = callback;
            this.thread = ExecutorServiceHelpers.newScheduledThreadPool(1, "Reading from " + name);
            this.batchSizeTracker = batchSizeTracker;
        }
        
        public void start() {
            thread.submit(() -> {
                IoBuffer buffer = new IoBuffer();
                while (!stop.get()) {
                    try {
                        WireCommand command = readCommand(in, buffer);
                        if (command instanceof WireCommands.DataAppended) {
                            WireCommands.DataAppended dataAppended = (WireCommands.DataAppended) command;
                            batchSizeTracker.recordAck(dataAppended.getEventNumber());
                        }
                        try {
                            callback.process((Reply) command);
                        } catch (Exception e) {
                            callback.processingFailure(e);
                        }
                    } catch (Exception e) {
                        log.error("Error processing data from from server " + name, e);
                        stop();
                    }
                }
            });
        }

        @VisibleForTesting
        static WireCommand readCommand(InputStream in, IoBuffer buffer) throws IOException {
            ByteBuf header = buffer.getBuffOfSize(in, 8);

            int t = header.getInt(0);
            WireCommandType type = WireCommands.getType(t);
            if (type == null) {
                throw new InvalidMessageException("Unknown wire command: " + t);
            }

            int length = header.getInt(4);
            if (length < 0 || length > WireCommands.MAX_WIRECOMMAND_SIZE) {
                throw new InvalidMessageException("Event of invalid length: " + length);
            }

            ByteBuf payload = buffer.getBuffOfSize(in, length);
            
            return type.readFrom(new EnhancedByteBufInputStream(payload), length);
        }
        
        public void stop() {
            stop.set(true);
            thread.shutdown();
            callback.connectionDropped();
        }
    }

    public static CompletableFuture<TcpClientConnection> connect(PravegaNodeUri location, ClientConfig clientConfig, ReplyProcessor callback,
                                              ScheduledExecutorService executor) {
        return CompletableFuture.supplyAsync(() -> {
            Socket socket = createClientSocket(location, clientConfig); 
            try {
                InputStream inputStream = socket.getInputStream();
                AppendBatchSizeTrackerImpl batchSizeTracker = new AppendBatchSizeTrackerImpl();
                ConnectionReader reader = new ConnectionReader(location.toString(), inputStream, callback, batchSizeTracker);
                reader.start();
                CommandEncoder encoder = new CommandEncoder(l -> batchSizeTracker, null, socket.getOutputStream(), 
                                                            executor);
                return new TcpClientConnection(socket, encoder, reader, location);
            } catch (IOException e) {
                try {
                    socket.close();
                } catch (IOException e1) {
                    log.warn("Failed to close socket while failing.", e1);
                }
                throw Exceptions.sneakyThrow(e);
            }
        }, executor);
    }

    private static TrustManagerFactory createFromCert(String trustStoreFilePath)
            throws CertificateException, IOException, NoSuchAlgorithmException, KeyStoreException {
        TrustManagerFactory factory = null;
        if (!Strings.isNullOrEmpty(trustStoreFilePath)) {
            KeyStore trustStore = CertificateUtils.createTrustStore(trustStoreFilePath);

            factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trustStore);
        }
        return factory;
    }

    /**
     * Creates a socket connected to the provided endpoint. 
     * Note that this is a sync call even though it is called in an async context. 
     * While this is normally frowned upon, it is simply not possible to construct an SSL socket asynchronously in Java.
     */
    @SneakyThrows //Is called inside a completable future.
    private static Socket createClientSocket(PravegaNodeUri location, ClientConfig clientConfig) {        
        Socket result;
        if (clientConfig.isEnableTlsToSegmentStore()) {
            TrustManagerFactory trustMgrFactory = createFromCert(clientConfig.getTrustStore());

            // Prepare a TLS context that uses the trust manager
            SSLContext tlsContext = SSLContext.getInstance("TLS");
            tlsContext.init(null,
                    trustMgrFactory != null ? trustMgrFactory.getTrustManagers() : null,
                    null);

            SSLSocket s = (SSLSocket) tlsContext.getSocketFactory().createSocket();
            // SSLSocket does not perform hostname verification by default. So, we must explicitly enable it.
            if (clientConfig.isValidateHostName()) {
                SSLParameters tlsParams = new SSLParameters();
                tlsParams.setEndpointIdentificationAlgorithm("HTTPS");
                s.setSSLParameters(tlsParams);
            }
            result = s;

        } else {
            result = new Socket();
        }
        result.setSendBufferSize(TCP_BUFFER_SIZE);
        result.setReceiveBufferSize(TCP_BUFFER_SIZE);
        result.setTcpNoDelay(true);
        result.connect(new InetSocketAddress(location.getEndpoint(), location.getPort()));
        return result;
    }

    @Override
    public void send(WireCommand cmd) throws ConnectionFailedException {
        if (closed.get()) {
            throw new ConnectionFailedException("Connection is closed");
        }
        try {
            encoder.write(cmd);
        } catch (IOException e) {
            log.warn("Error writing to connection");
            close();
            throw new ConnectionFailedException(e);
        }
    }

    @Override
    public void send(Append append) throws ConnectionFailedException {
        if (closed.get()) {
            throw new ConnectionFailedException("Connection is closed");
        }
        try {
            encoder.write(append);
        } catch (IOException e) {
            log.warn("Error writing to connection");
            close();
            throw new ConnectionFailedException(e);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            reader.stop();
            try {
                socket.close();
            } catch (IOException e) {
                log.warn("Error closing socket", e);
            }
        }
    }

    @Override
    public void sendAsync(List<Append> appends, CompletedCallback callback) {
        try {
            for (Append append : appends) {
                encoder.write(append);
            }
            callback.complete(null);
        } catch (IOException e) {
            log.warn("Error writing to connection");
            close();
            callback.complete(new ConnectionFailedException(e));
        }
    }
    
    @Override
    public String toString() {
        return "TcpClientConnection [location=" + location + ", isClosed=" + closed.get() + "]";
    }

}