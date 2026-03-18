/*
 * Copyright (C) 2026 Lucas Nishimura <lucas.nishimura@gmail.com>
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
package dev.nishisan.ishin.gateway.tunnel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Core do Tunnel Mode — gerencia listeners TCP dinâmicos e faz pipe bidirecional.
 * <p>
 * Para cada porta virtual, abre um {@link ServerSocketChannel} e aceita conexões.
 * Cada conexão aceita é roteada para um backend via {@link VirtualPortGroup#selectNext()}.
 * O pipe TCP é realizado em duas Virtual Threads (client→backend e backend→client).
 * <p>
 * Implementa detecção de falha Camada 1 via IOException no connect.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
public class TunnelEngine {

    private static final Logger logger = LogManager.getLogger(TunnelEngine.class);
    private static final int BUFFER_SIZE = 8192;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final int MAX_RETRIES_PER_CONNECTION = 3;

    private final TunnelRegistry registry;
    private final TunnelMetrics metrics;
    private final String bindAddress;

    private final ConcurrentHashMap<Integer, ServerSocketChannel> listeners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Thread> acceptorThreads = new ConcurrentHashMap<>();
    private final AtomicInteger totalActiveConnections = new AtomicInteger(0);

    // Callbacks de eventos para o dashboard event bridge
    private Consumer<Integer> onListenerOpened;
    private Consumer<Integer> onListenerClosed;
    private TriConsumer<Integer, String, String> onConnectError; // (vPort, backend, errorType)

    private volatile boolean running = false;

    public TunnelEngine(TunnelRegistry registry, TunnelMetrics metrics, String bindAddress) {
        this.registry = registry;
        this.metrics = metrics;
        this.bindAddress = bindAddress;
    }

    public void start() {
        this.running = true;
        metrics.registerGlobalActiveConnections(totalActiveConnections);
        logger.info("TunnelEngine started — bindAddress: {}", bindAddress);
    }

    // ─── Callback Setters ────────────────────────────────────────────────

    public void setOnListenerOpened(Consumer<Integer> onListenerOpened) {
        this.onListenerOpened = onListenerOpened;
    }

    public void setOnListenerClosed(Consumer<Integer> onListenerClosed) {
        this.onListenerClosed = onListenerClosed;
    }

    public void setOnConnectError(TriConsumer<Integer, String, String> onConnectError) {
        this.onConnectError = onConnectError;
    }

    /**
     * Functional interface para callbacks com 3 parâmetros.
     */
    @FunctionalInterface
    public interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }

    public void stop() {
        this.running = false;

        // Fechar todos os listeners
        listeners.forEach((port, ssc) -> {
            try {
                ssc.close();
                metrics.listenerClosed();
                logger.info("Listener closed — vPort:{}", port);
            } catch (IOException e) {
                logger.error("Error closing listener vPort:{}", port, e);
            }
        });
        listeners.clear();

        // Interromper acceptor threads
        acceptorThreads.values().forEach(Thread::interrupt);
        acceptorThreads.clear();

        logger.info("TunnelEngine stopped — all listeners closed");
    }

    /**
     * Abre um listener TCP na porta virtual especificada.
     * Chamado pelo TunnelRegistry quando o primeiro membro de um grupo se registra.
     */
    public void openListener(int virtualPort) {
        if (listeners.containsKey(virtualPort)) {
            logger.warn("Listener already open for vPort:{}", virtualPort);
            return;
        }

        try {
            ServerSocketChannel ssc = ServerSocketChannel.open();
            ssc.bind(new InetSocketAddress(bindAddress, virtualPort));
            listeners.put(virtualPort, ssc);
            metrics.listenerOpened();
            if (onListenerOpened != null) {
                onListenerOpened.accept(virtualPort);
            }

            // Acceptor thread para este listener
            Thread acceptor = Thread.ofVirtual()
                    .name("tunnel-acceptor-" + virtualPort)
                    .start(() -> acceptLoop(virtualPort, ssc));
            acceptorThreads.put(virtualPort, acceptor);

            logger.info("Listener opened — vPort:{} on {}", virtualPort, bindAddress);
        } catch (IOException e) {
            logger.error("Failed to open listener on vPort:{}", virtualPort, e);
        }
    }

    /**
     * Fecha o listener da porta virtual especificada.
     * Chamado pelo TunnelRegistry quando o último membro de um grupo é removido.
     */
    public void closeListener(int virtualPort) {
        ServerSocketChannel ssc = listeners.remove(virtualPort);
        if (ssc != null) {
            try {
                ssc.close();
                metrics.listenerClosed();
                if (onListenerClosed != null) {
                    onListenerClosed.accept(virtualPort);
                }
                logger.info("Listener closed — vPort:{}", virtualPort);
            } catch (IOException e) {
                logger.error("Error closing listener vPort:{}", virtualPort, e);
            }
        }

        Thread acceptor = acceptorThreads.remove(virtualPort);
        if (acceptor != null) {
            acceptor.interrupt();
        }
    }

    /**
     * Loop de accept para um listener — cada conexão aceita é processada
     * em uma Virtual Thread separada.
     */
    private void acceptLoop(int virtualPort, ServerSocketChannel ssc) {
        while (running && ssc.isOpen()) {
            try {
                SocketChannel clientChannel = ssc.accept();
                Thread.ofVirtual()
                        .name("tunnel-conn-" + virtualPort + "-" + totalActiveConnections.get())
                        .start(() -> handleConnection(virtualPort, clientChannel));
            } catch (IOException e) {
                if (running) {
                    logger.error("Accept error on vPort:{}", virtualPort, e);
                }
            }
        }
    }

    /**
     * Processa uma conexão TCP aceita — seleciona backend, conecta, faz pipe.
     */
    private void handleConnection(int virtualPort, SocketChannel clientChannel) {
        long startTime = System.currentTimeMillis();
        totalActiveConnections.incrementAndGet();

        BackendMember selected = null;

        try {
            VirtualPortGroup group = registry.getGroup(virtualPort);
            if (group == null) {
                logger.warn("No group for vPort:{} — closing client", virtualPort);
                clientChannel.close();
                return;
            }

            // Tentar conectar a um backend com retry
            SocketChannel backendChannel = null;
            int retries = 0;

            while (backendChannel == null && retries < MAX_RETRIES_PER_CONNECTION) {
                // ─── Routing duration: mede lookup do grupo + seleção de membro ───
                long routingStart = System.nanoTime();
                selected = group.selectNext();
                long routingDurationMs = (System.nanoTime() - routingStart) / 1_000_000;
                metrics.recordRoutingDuration(virtualPort, routingDurationMs);

                if (selected == null) {
                    logger.warn("No active backends for vPort:{} — closing client", virtualPort);
                    clientChannel.close();
                    return;
                }

                long connectStart = System.currentTimeMillis();
                try {
                    backendChannel = connectToBackend(selected);
                    long connectDuration = System.currentTimeMillis() - connectStart;
                    metrics.recordConnectDuration(virtualPort, selected.getKey(), connectDuration);
                } catch (ConnectException e) {
                    // Conexão recusada — remoção imediata (Camada 1)
                    metrics.recordConnectError(virtualPort, selected.getKey(), "refused");
                    if (onConnectError != null) {
                        onConnectError.accept(virtualPort, selected.getKey(), "refused");
                    }
                    logger.warn("Connection refused to {} — immediate removal from vPort:{}",
                            selected.getKey(), virtualPort);
                    registry.removeMemberImmediate(virtualPort, selected.getKey(), "io_exception");
                    selected = null;
                    retries++;
                } catch (NoRouteToHostException e) {
                    metrics.recordConnectError(virtualPort, selected.getKey(), "no_route");
                    if (onConnectError != null) {
                        onConnectError.accept(virtualPort, selected.getKey(), "no_route");
                    }
                    logger.warn("No route to {} — immediate removal from vPort:{}",
                            selected.getKey(), virtualPort);
                    registry.removeMemberImmediate(virtualPort, selected.getKey(), "io_exception");
                    selected = null;
                    retries++;
                } catch (SocketTimeoutException e) {
                    metrics.recordConnectError(virtualPort, selected.getKey(), "timeout");
                    if (onConnectError != null) {
                        onConnectError.accept(virtualPort, selected.getKey(), "timeout");
                    }
                    int failures = selected.getConsecutiveFailures().incrementAndGet();
                    if (failures >= MAX_CONSECUTIVE_FAILURES) {
                        logger.warn("Connect timeout threshold reached for {} — removing from vPort:{}",
                                selected.getKey(), virtualPort);
                        registry.removeMemberImmediate(virtualPort, selected.getKey(), "io_exception");
                    }
                    selected = null;
                    retries++;
                }
            }

            if (backendChannel == null || selected == null) {
                logger.error("All retry attempts exhausted for vPort:{} — closing client", virtualPort);
                clientChannel.close();
                return;
            }

            // Pipe estabelecido
            metrics.recordConnectionAccepted(virtualPort, selected.getKey());
            metrics.registerPerBackendActiveConnections(virtualPort, selected.getKey(),
                    selected.getActiveConnections());
            selected.getActiveConnections().incrementAndGet();

            // Pipe bidirecional em duas Virtual Threads
            final SocketChannel finalBackend = backendChannel;
            final BackendMember finalMember = selected;

            Thread upstream = Thread.ofVirtual()
                    .name("tunnel-pipe-up-" + virtualPort)
                    .start(() -> pipe(clientChannel, finalBackend, virtualPort, finalMember.getKey(), true));

            // Downstream no thread atual
            pipe(finalBackend, clientChannel, virtualPort, finalMember.getKey(), false);

            // Aguardar upstream terminar
            try {
                upstream.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Cleanup
            finalMember.getActiveConnections().decrementAndGet();
            long sessionDuration = System.currentTimeMillis() - startTime;
            metrics.recordSessionDuration(virtualPort, finalMember.getKey(), sessionDuration);

            closeQuietly(finalBackend);
            closeQuietly(clientChannel);

        } catch (IOException e) {
            logger.debug("Connection error on vPort:{} — {}", virtualPort, e.getMessage());
            closeQuietly(clientChannel);
        } finally {
            totalActiveConnections.decrementAndGet();
        }
    }

    /**
     * Conecta a um backend com timeout.
     */
    private SocketChannel connectToBackend(BackendMember member) throws IOException {
        SocketChannel channel = SocketChannel.open();
        channel.configureBlocking(true);
        channel.socket().connect(
                new InetSocketAddress(member.getHost(), member.getRealPort()),
                CONNECT_TIMEOUT_MS
        );
        return channel;
    }

    /**
     * Pipe unidirecional: lê de source e escreve em destination.
     */
    private void pipe(SocketChannel source, SocketChannel destination,
                      int virtualPort, String memberKey, boolean isUpstream) {
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        try {
            while (source.isOpen() && destination.isOpen()) {
                buffer.clear();
                int bytesRead = source.read(buffer);
                if (bytesRead == -1) {
                    break; // EOF
                }
                if (bytesRead > 0) {
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        destination.write(buffer);
                    }
                    // Métricas de throughput
                    if (isUpstream) {
                        metrics.recordBytesSent(virtualPort, memberKey, bytesRead);
                    } else {
                        metrics.recordBytesReceived(virtualPort, memberKey, bytesRead);
                    }
                }
            }
        } catch (IOException e) {
            // EOF ou pipe broken — normal em TCP
            logger.trace("Pipe closed ({}) vPort:{} member:{} — {}",
                    isUpstream ? "upstream" : "downstream", virtualPort, memberKey, e.getMessage());
        }
        // Fechar o lado de escrita para sinalizar EOF
        try {
            if (destination.isOpen()) {
                destination.shutdownOutput();
            }
        } catch (IOException ignored) {
            // Canal já fechado
        }
    }

    private void closeQuietly(SocketChannel channel) {
        if (channel != null && channel.isOpen()) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }

    public int getTotalActiveConnections() {
        return totalActiveConnections.get();
    }

    public int getListenerCount() {
        return listeners.size();
    }

    /**
     * Retorna o conjunto de portas virtuais com listeners abertos.
     */
    public Set<Integer> getOpenListenerPorts() {
        return Collections.unmodifiableSet(listeners.keySet());
    }

    /**
     * Verifica se há listener aberto para a porta virtual.
     */
    public boolean isListenerOpen(int virtualPort) {
        return listeners.containsKey(virtualPort);
    }
}
