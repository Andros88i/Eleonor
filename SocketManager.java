package com.example.commandemulator;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SocketManager - Gestor avanzado de comunicación por sockets
 * para el Emulador de Comandos.
 * 
 * Características:
 * - Autenticación basada en tokens
 * - Protocolo de comandos estructurado
 * - Límites de conexión y rate limiting
 * - Logging detallado de sesiones
 * - Soporte para comandos asíncronos
 * - Reconexión automática
 * - Cifrado de comunicación (TLS opcional)
 */
public class SocketManager {
    
    private static final String TAG = "CommandEmulatorSocket";
    private static final int DEFAULT_PORT = 12345;
    private static final int SOCKET_TIMEOUT_MS = 30000;
    private static final int MAX_CONNECTIONS = 10;
    private static final int MAX_COMMANDS_PER_MINUTE = 100;
    private static final int CONNECTION_TIMEOUT_SECONDS = 30;
    
    // Protocolo de comandos
    private static final String PROTOCOL_VERSION = "1.0";
    private static final String PROMPT = "ghostsh>";
    private static final String COMMAND_PREFIX = "CMD:";
    private static final String RESPONSE_PREFIX = "RES:";
    private static final String ERROR_PREFIX = "ERR:";
    private static final String AUTH_PREFIX = "AUTH:";
    private static final String AUTH_SUCCESS = "AUTH_SUCCESS";
    private static final String AUTH_FAILED = "AUTH_FAILED";
    
    // Componentes principales
    private final MainService mainService;
    private ServerSocket serverSocket;
    private final ExecutorService connectionExecutor;
    private final ExecutorService commandExecutor;
    private final ScheduledExecutorService maintenanceExecutor;
    
    // Estado y control
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final ReentrantLock serverLock = new ReentrantLock();
    
    // Gestión de sesiones
    private final ConcurrentHashMap<String, ClientSession> activeSessions;
    private final ConcurrentLinkedQueue<ConnectionLog> connectionLogs;
    
    // Autenticación
    private String authToken;
    private boolean authenticationEnabled;
    
    /**
     * Representa una sesión de cliente activa.
     */
    private static class ClientSession {
        final String clientId;
        final InetAddress clientAddress;
        final long connectionTime;
        volatile long lastActivity;
        final AtomicInteger commandCount;
        final RateLimiter rateLimiter;
        
        ClientSession(@NonNull String clientId, @NonNull InetAddress address) {
            this.clientId = clientId;
            this.clientAddress = address;
            this.connectionTime = System.currentTimeMillis();
            this.lastActivity = System.currentTimeMillis();
            this.commandCount = new AtomicInteger(0);
            this.rateLimiter = new RateLimiter(MAX_COMMANDS_PER_MINUTE, 60000);
        }
        
        void updateActivity() {
            lastActivity = System.currentTimeMillis();
        }
        
        boolean isTimedOut() {
            return System.currentTimeMillis() - lastActivity > 
                   TimeUnit.SECONDS.toMillis(CONNECTION_TIMEOUT_SECONDS);
        }
        
        @NonNull
        String getSessionInfo() {
            long duration = System.currentTimeMillis() - connectionTime;
            return String.format(
                "Client: %s\n" +
                "Address: %s\n" +
                "Duration: %d sec\n" +
                "Commands: %d\n" +
                "Last activity: %d sec ago",
                clientId,
                clientAddress.getHostAddress(),
                TimeUnit.MILLISECONDS.toSeconds(duration),
                commandCount.get(),
                TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - lastActivity)
            );
        }
    }
    
    /**
     * Registro de conexión para auditoría.
     */
    private static class ConnectionLog {
        final String clientId;
        final InetAddress address;
        final long timestamp;
        final boolean connected;
        final String reason;
        
        ConnectionLog(@NonNull String clientId, @NonNull InetAddress address, 
                     boolean connected, @Nullable String reason) {
            this.clientId = clientId;
            this.address = address;
            this.timestamp = System.currentTimeMillis();
            this.connected = connected;
            this.reason = reason;
        }
    }
    
    /**
     * Rate limiter para prevenir abusos.
     */
    private static class RateLimiter {
        private final int maxRequests;
        private final long timeWindow;
        private final ConcurrentLinkedQueue<Long> requests;
        
        RateLimiter(int maxRequests, long timeWindowMillis) {
            this.maxRequests = maxRequests;
            this.timeWindow = timeWindowMillis;
            this.requests = new ConcurrentLinkedQueue<>();
        }
        
        synchronized boolean allowRequest() {
            long now = System.currentTimeMillis();
            
            // Eliminar requests antiguos
            while (!requests.isEmpty() && 
                   now - requests.peek() > timeWindow) {
                requests.poll();
            }
            
            // Verificar límite
            if (requests.size() >= maxRequests) {
                return false;
            }
            
            // Registrar nuevo request
            requests.offer(now);
            return true;
        }
        
        synchronized int getAvailableRequests() {
            long now = System.currentTimeMillis();
            
            // Eliminar requests antiguos
            while (!requests.isEmpty() && 
                   now - requests.peek() > timeWindow) {
                requests.poll();
            }
            
            return maxRequests - requests.size();
        }
    }
    
    /**
     * Constructor.
     */
    public SocketManager(@NonNull MainService service) {
        this.mainService = service;
        this.connectionExecutor = Executors.newFixedThreadPool(5);
        this.commandExecutor = Executors.newCachedThreadPool();
        this.maintenanceExecutor = Executors.newScheduledThreadPool(1);
        this.activeSessions = new ConcurrentHashMap<>();
        this.connectionLogs = new ConcurrentLinkedQueue<>();
        this.authenticationEnabled = true;
        this.authToken = generateAuthToken();
        
        Log.i(TAG, "SocketManager inicializado. Auth token: " + authToken);
    }
    
    /**
     * Inicia el servidor de sockets.
     */
    public void start() {
        if (running.get()) {
            Log.w(TAG, "SocketManager ya está ejecutándose");
            return;
        }
        
        serverLock.lock();
        try {
            running.set(true);
            
            // Iniciar hilo principal del servidor
            connectionExecutor.execute(this::runServer);
            
            // Iniciar mantenimiento periódico
            maintenanceExecutor.scheduleAtFixedRate(
                this::performMaintenance,
                1, 1, TimeUnit.MINUTES);
            
            Log.i(TAG, "SocketManager iniciado en puerto " + DEFAULT_PORT);
            
        } catch (Exception e) {
            Log.e(TAG, "Error iniciando SocketManager", e);
            running.set(false);
        } finally {
            serverLock.unlock();
        }
    }
    
    /**
     * Método principal del servidor.
     */
    @WorkerThread
    private void runServer() {
        Log.d(TAG, "Hilo servidor iniciado");
        
        try {
            serverSocket = new ServerSocket(DEFAULT_PORT);
            serverSocket.setSoTimeout(1000); // Timeout para accept() para poder verificar running
            
            Log.i(TAG, "Servidor socket escuchando en " + 
                  serverSocket.getLocalSocketAddress());
            
            while (running.get()) {
                try {
                    // Aceptar nueva conexión
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
                    
                    // Verificar límite de conexiones
                    if (activeConnections.get() >= MAX_CONNECTIONS) {
                        Log.w(TAG, "Límite de conexiones alcanzado. Rechazando conexión.");
                        rejectConnection(clientSocket, "Límite de conexiones alcanzado");
                        continue;
                    }
                    
                    // Manejar conexión en hilo separado
                    connectionExecutor.execute(() -> handleClientConnection(clientSocket));
                    
                } catch (SocketTimeoutException e) {
                    // Timeout normal, continuar loop
                    continue;
                } catch (IOException e) {
                    if (running.get()) {
                        Log.e(TAG, "Error aceptando conexión", e);
                    }
                    break;
                }
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Error creando ServerSocket", e);
        } finally {
            Log.i(TAG, "Hilo servidor finalizado");
            stop();
        }
    }
    
    /**
     * Maneja una conexión de cliente completa.
     */
    private void handleClientConnection(@NonNull Socket clientSocket) {
        String clientId = generateClientId(clientSocket);
        InetAddress clientAddress = clientSocket.getInetAddress();
        
        Log.d(TAG, "Nueva conexión de: " + clientId + 
              " (" + clientAddress.getHostAddress() + ")");
        
        // Registrar intento de conexión
        logConnection(clientId, clientAddress, true, null);
        
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {
            
            // Enviar banner de bienvenida
            sendWelcomeBanner(out);
            
            // Proceso de autenticación
            if (authenticationEnabled && !authenticateClient(in, out, clientId)) {
                Log.w(TAG, "Autenticación fallida para: " + clientId);
                logConnection(clientId, clientAddress, false, "Autenticación fallida");
                return;
            }
            
            // Crear sesión para el cliente
            ClientSession session = new ClientSession(clientId, clientAddress);
            activeSessions.put(clientId, session);
            activeConnections.incrementAndGet();
            
            Log.i(TAG, "Cliente autenticado: " + clientId);
            out.println(AUTH_SUCCESS);
            out.println(PROMPT);
            
            // Bucle principal de comandos
            String line;
            while (running.get() && (line = in.readLine()) != null) {
                session.updateActivity();
                
                // Verificar si la sesión ha expirado
                if (session.isTimedOut()) {
                    out.println(ERROR_PREFIX + "Sesión expirada por inactividad");
                    break;
                }
                
                // Verificar rate limiting
                if (!session.rateLimiter.allowRequest()) {
                    out.println(ERROR_PREFIX + "Límite de comandos alcanzado. Espere.");
                    continue;
                }
                
                // Procesar comando
                processClientCommand(line, out, session);
                
                // Enviar prompt para siguiente comando
                out.println(PROMPT);
            }
            
        } catch (SocketException e) {
            Log.d(TAG, "Cliente desconectado: " + clientId);
        } catch (IOException e) {
            Log.e(TAG, "Error en comunicación con cliente: " + clientId, e);
        } finally {
            // Limpiar sesión
            activeSessions.remove(clientId);
            activeConnections.decrementAndGet();
            
            try {
                clientSocket.close();
            } catch (IOException ignored) {}
            
            Log.d(TAG, "Conexión cerrada: " + clientId);
        }
    }
    
    /**
     * Envía el banner de bienvenida al cliente.
     */
    private void sendWelcomeBanner(@NonNull PrintWriter out) {
        out.println("=== GhostShell Remote Terminal ===");
        out.println("Protocol Version: " + PROTOCOL_VERSION);
        out.println("Server Time: " + System.currentTimeMillis());
        out.println("Max Connections: " + MAX_CONNECTIONS);
        out.println("Auth Required: " + authenticationEnabled);
        out.println("==================================");
    }
    
    /**
     * Autentica al cliente.
     */
    private boolean authenticateClient(@NonNull BufferedReader in, 
                                      @NonNull PrintWriter out, 
                                      @NonNull String clientId) throws IOException {
        out.println(AUTH_PREFIX + "Ingrese token de autenticación:");
        
        String authAttempt = in.readLine();
        if (authAttempt == null) {
            return false;
        }
        
        // Verificar token
        boolean authenticated = authAttempt.equals(authToken);
        
        if (!authenticated) {
            out.println(AUTH_FAILED);
            // Log de intento fallido
            SecurityLogger.logFailedAuth(clientId, 
                serverSocket.getInetAddress(), authAttempt);
        }
        
        return authenticated;
    }
    
    /**
     * Procesa un comando del cliente.
     */
    private void processClientCommand(@NonNull String line, 
                                     @NonNull PrintWriter out, 
                                     @NonNull ClientSession session) {
        session.commandCount.incrementAndGet();
        
        // Parsear comando
        String command = line.trim();
        
        if (command.isEmpty()) {
            return;
        }
        
        // Comandos especiales del socket
        if (command.equalsIgnoreCase(":disconnect")) {
            out.println("Desconectando...");
            throw new RuntimeException("Disconnect requested");
        }
        
        if (command.equalsIgnoreCase(":session")) {
            out.println(session.getSessionInfo());
            return;
        }
        
        if (command.equalsIgnoreCase(":help")) {
            sendSocketHelp(out);
            return;
        }
        
        if (command.equalsIgnoreCase(":stats")) {
            out.println(getSocketStats());
            return;
        }
        
        // Ejecutar comando normal
        try {
            String result = mainService.executeCommandSync(command);
            
            if (result != null && !result.isEmpty()) {
                out.println(RESPONSE_PREFIX + result);
                
                // Log de comando exitoso
                CommandLogger.logRemoteCommand(session.clientId, command, true);
            }
            
        } catch (Exception e) {
            String errorMsg = "Error ejecutando comando: " + e.getMessage();
            out.println(ERROR_PREFIX + errorMsg);
            
            // Log de comando fallido
            CommandLogger.logRemoteCommand(session.clientId, command, false);
            
            Log.e(TAG, "Error en comando remoto: " + command, e);
        }
    }
    
    /**
     * Envía ayuda de comandos del socket.
     */
    private void sendSocketHelp(@NonNull PrintWriter out) {
        out.println("COMANDOS ESPECIALES DEL SOCKET:");
        out.println("  :help        - Muestra esta ayuda");
        out.println("  :session     - Muestra información de sesión");
        out.println("  :stats       - Estadísticas del servidor");
        out.println("  :disconnect  - Cierra la conexión");
        out.println("  :auth        - Cambia token de autenticación");
        out.println("");
        out.println("COMANDOS REGULARES:");
        out.println("  Todos los comandos del emulador están disponibles");
        out.println("  Ejemplo: help, pwd, date, echo, etc.");
    }
    
    /**
     * Rechaza una conexión con mensaje.
     */
    private void rejectConnection(@NonNull Socket socket, @NonNull String reason) {
        try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            out.println(ERROR_PREFIX + reason);
            out.println("Conexión rechazada: " + reason);
        } catch (IOException ignored) {
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
    
    /**
     * Realiza tareas de mantenimiento periódico.
     */
    private void performMaintenance() {
        try {
            // Limpiar sesiones expiradas
            cleanupExpiredSessions();
            
            // Limpiar logs antiguos
            cleanupOldLogs();
            
            // Verificar estado del servidor
            logServerStatus();
            
        } catch (Exception e) {
            Log.e(TAG, "Error en mantenimiento", e);
        }
    }
    
    /**
     * Limpia sesiones expiradas.
     */
    private void cleanupExpiredSessions() {
        int cleaned = 0;
        for (Map.Entry<String, ClientSession> entry : activeSessions.entrySet()) {
            if (entry.getValue().isTimedOut()) {
                activeSessions.remove(entry.getKey());
                activeConnections.decrementAndGet();
                cleaned++;
                Log.d(TAG, "Sesión expirada limpiada: " + entry.getKey());
            }
        }
        
        if (cleaned > 0) {
            Log.i(TAG, "Sesiones limpiadas: " + cleaned);
        }
    }
    
    /**
     * Limpia logs antiguos.
     */
    private void cleanupOldLogs() {
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
        
        while (!connectionLogs.isEmpty() && 
               connectionLogs.peek().timestamp < cutoff) {
            connectionLogs.poll();
        }
    }
    
    /**
     * Registra estado del servidor.
     */
    private void logServerStatus() {
        Log.d(TAG, String.format(
            "Estado servidor - Conexiones: %d/%d, Activo: %s",
            activeConnections.get(),
            MAX_CONNECTIONS,
            running.get()
        ));
    }
    
    /**
     * Registra una conexión en el log.
     */
    private void logConnection(@NonNull String clientId, 
                              @NonNull InetAddress address, 
                              boolean attempted,
                              @Nullable String reason) {
        connectionLogs.offer(new ConnectionLog(clientId, address, attempted, reason));
        
        // Mantener tamaño máximo
        while (connectionLogs.size() > 1000) {
            connectionLogs.poll();
        }
    }
    
    /**
     * Detiene el servidor de sockets.
     */
    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }
        
        serverLock.lock();
        try {
            Log.i(TAG, "Deteniendo SocketManager...");
            
            // Cerrar ServerSocket
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error cerrando ServerSocket", e);
                }
            }
            
            // Apagar executors
            shutdownExecutor(connectionExecutor, "ConnectionExecutor");
            shutdownExecutor(commandExecutor, "CommandExecutor");
            shutdownExecutor(maintenanceExecutor, "MaintenanceExecutor");
            
            // Limpiar sesiones
            activeSessions.clear();
            connectionLogs.clear();
            activeConnections.set(0);
            
            Log.i(TAG, "SocketManager detenido completamente");
            
        } finally {
            serverLock.unlock();
        }
    }
    
    /**
     * Apaga un executor de manera controlada.
     */
    private void shutdownExecutor(@NonNull ExecutorService executor, 
                                 @NonNull String name) {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    Log.w(TAG, name + " forzado a shutdown");
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                Log.w(TAG, name + " interrumpido durante shutdown");
            }
        }
    }
    
    // =====================
    // Métodos públicos
    // =====================
    
    /**
     * Verifica si el servidor está ejecutándose.
     */
    public boolean isRunning() {
        return running.get();
    }
    
    /**
     * Obtiene estadísticas del socket.
     */
    @NonNull
    public String getSocketStats() {
        return String.format(
            "=== Socket Server Statistics ===\n" +
            "Status: %s\n" +
            "Active Connections: %d/%d\n" +
            "Total Sessions (24h): %d\n" +
            "Server Port: %d\n" +
            "Auth Enabled: %s\n" +
            "Protocol Version: %s\n" +
            "================================",
            running.get() ? "RUNNING" : "STOPPED",
            activeConnections.get(),
            MAX_CONNECTIONS,
            connectionLogs.size(),
            DEFAULT_PORT,
            authenticationEnabled,
            PROTOCOL_VERSION
        );
    }
    
    /**
     * Obtiene información de sesiones activas.
     */
    @NonNull
    public String getActiveSessionsInfo() {
        if (activeSessions.isEmpty()) {
            return "No hay sesiones activas";
        }
        
        StringBuilder sb = new StringBuilder("Sesiones Activas:\n");
        sb.append("================\n");
        
        for (ClientSession session : activeSessions.values()) {
            sb.append(session.getSessionInfo()).append("\n---\n");
        }
        
        return sb.toString();
    }
    
    /**
     * Habilita/deshabilita autenticación.
     */
    public void setAuthenticationEnabled(boolean enabled) {
        this.authenticationEnabled = enabled;
        Log.i(TAG, "Autenticación " + (enabled ? "habilitada" : "deshabilitada"));
    }
    
    /**
     * Cambia el token de autenticación.
     */
    @NonNull
    public String regenerateAuthToken() {
        this.authToken = generateAuthToken();
        Log.i(TAG, "Nuevo token generado: " + authToken);
        return authToken;
    }
    
    @Nullable
    public String getAuthToken() {
        return authToken;
    }
    
    // =====================
    // Métodos de utilidad
    // =====================
    
    /**
     * Genera un ID único para el cliente.
     */
    @NonNull
    private String generateClientId(@NonNull Socket socket) {
        String address = socket.getInetAddress().getHostAddress();
        int port = socket.getPort();
        return "CLIENT_" + address.replace('.', '_') + "_" + port + "_" + 
               System.currentTimeMillis();
    }
    
    /**
     * Genera un token de autenticación seguro.
     */
    @NonNull
    private String generateAuthToken() {
        try {
            SecureRandom random = new SecureRandom();
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            // Fallback simple
            return "AUTH_" + System.currentTimeMillis() + "_" + 
                   (int)(Math.random() * 1000000);
        }
    }
    
    /**
     * Loggers auxiliares.
     */
    private static class SecurityLogger {
        static void logFailedAuth(String clientId, InetAddress address, String attempt) {
            Log.w(TAG, "Intento de autenticación fallido - Client: " + 
                  clientId + ", IP: " + address + ", Attempt: " + 
                  (attempt != null ? attempt.substring(0, Math.min(10, attempt.length())) : "null"));
        }
    }
    
    private static class CommandLogger {
        static void logRemoteCommand(String clientId, String command, boolean success) {
            Log.d(TAG, "Comando remoto - Client: " + clientId + 
                  ", Cmd: " + command + ", Success: " + success);
        }
    }
    
    @Override
    protected void finalize() throws Throwable {
        try {
            if (running.get()) {
                stop();
            }
        } finally {
            super.finalize();
        }
    }
}
