package com.example.commandemulator;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MainService - Servicio en segundo plano del Emulador de Comandos.
 * 
 * Características:
 * - Ejecución de comandos asíncrona
 * - Gestión de hilos optimizada
 * - Sistema de historial de comandos
 * - Timeout para comandos largos
 * - Notificaciones de estado
 * - Manejo de lifecycle robusto
 */
public class MainService extends Service {
    
    private static final String TAG = "CommandEmulatorService";
    private static final int COMMAND_TIMEOUT_SECONDS = 10;
    private static final int MAX_HISTORY_SIZE = 100;
    
    // Binder para comunicación con actividades
    private final IBinder binder = new LocalBinder();
    
    // Estado del servicio
    private final AtomicBoolean isActive = new AtomicBoolean(false);
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    
    // Gestión de hilos
    private ExecutorService commandExecutor;
    private final ConcurrentLinkedQueue<CommandTask> pendingCommands = new ConcurrentLinkedQueue<>();
    
    // Historial de comandos
    private final ConcurrentLinkedQueue<CommandHistoryItem> commandHistory = new ConcurrentLinkedQueue<>();
    
    // Interfaz para callbacks de ejecución
    public interface CommandExecutionCallback {
        void onCommandExecuted(@NonNull String command, @NonNull String result);
        void onCommandError(@NonNull String command, @NonNull String error);
    }
    
    /**
     * Binder personalizado para comunicación con Activities.
     */
    public class LocalBinder extends Binder {
        @NonNull
        public MainService getService() {
            return MainService.this;
        }
    }
    
    /**
     * Representa una tarea de comando para ejecución asíncrona.
     */
    private static class CommandTask {
        final String command;
        final CommandExecutionCallback callback;
        final long timestamp;
        
        CommandTask(@NonNull String command, @NonNull CommandExecutionCallback callback) {
            this.command = command;
            this.callback = callback;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    /**
     * Elemento del historial de comandos.
     */
    public static class CommandHistoryItem {
        private final String command;
        private final String result;
        private final long timestamp;
        private final boolean successful;
        
        public CommandHistoryItem(@NonNull String command, @NonNull String result, 
                                 boolean successful) {
            this.command = command;
            this.result = result;
            this.timestamp = System.currentTimeMillis();
            this.successful = successful;
        }
        
        @NonNull
        public String getCommand() { return command; }
        @NonNull
        public String getResult() { return result; }
        public long getTimestamp() { return timestamp; }
        public boolean isSuccessful() { return successful; }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        // Inicializar servicio
        initializeService();
        
        Log.i(TAG, "MainService creado y configurado");
    }
    
    /**
     * Inicializa todos los componentes del servicio.
     */
    private void initializeService() {
        // Inicializar executor de comandos (thread pool)
        commandExecutor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors()
        );
        
        // Establecer estado activo
        isActive.set(true);
        isShuttingDown.set(false);
        
        // Limpiar historial
        commandHistory.clear();
        pendingCommands.clear();
        
        // Registrar inicio en el historial
        addToHistory("SERVICE_START", "Servicio iniciado correctamente", true);
    }
    
    @Override
    public int onStartCommand(@NonNull Intent intent, int flags, int startId) {
        Log.i(TAG, "Servicio en ejecución (START_STICKY)");
        
        // Manejar comandos desde intent si existen
        if (intent != null && intent.hasExtra("command")) {
            String command = intent.getStringExtra("command");
            if (command != null && !command.trim().isEmpty()) {
                executeCommandAsync(command, new LoggingCallback());
            }
        }
        
        // Mantener servicio activo incluso si la app se cierra
        return START_STICKY;
    }
    
    @Nullable
    @Override
    public IBinder onBind(@NonNull Intent intent) {
        Log.d(TAG, "Cliente conectado al servicio");
        return binder;
    }
    
    @Override
    public boolean onUnbind(@NonNull Intent intent) {
        Log.d(TAG, "Cliente desconectado del servicio");
        return true; // Llama a onRebind si nuevos clientes se conectan
    }
    
    @Override
    public void onRebind(@NonNull Intent intent) {
        super.onRebind(intent);
        Log.d(TAG, "Cliente reconectado al servicio");
    }
    
    @Override
    public void onDestroy() {
        Log.i(TAG, "Destruyendo MainService...");
        
        // Establecer estado de apagado
        isShuttingDown.set(true);
        isActive.set(false);
        
        // Apagar el executor de comandos
        shutdownExecutor();
        
        // Limpiar colas
        pendingCommands.clear();
        
        // Guardar historial si es necesario
        saveHistoryIfNeeded();
        
        super.onDestroy();
        Log.i(TAG, "MainService destruido completamente");
    }
    
    /**
     * Apaga el executor de comandos de manera controlada.
     */
    private void shutdownExecutor() {
        if (commandExecutor != null && !commandExecutor.isShutdown()) {
            commandExecutor.shutdown();
            try {
                if (!commandExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    commandExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                commandExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    // =====================
    // Métodos públicos para Activities
    // =====================
    
    /**
     * Verifica si el servicio está activo y funcionando.
     */
    public boolean isServiceActive() {
        return isActive.get() && !isShuttingDown.get();
    }
    
    /**
     * Ejecuta un comando de forma asíncrona.
     */
    public void executeCommandAsync(@NonNull String command, 
                                   @NonNull CommandExecutionCallback callback) {
        if (!isServiceActive()) {
            callback.onCommandError(command, "Servicio no disponible");
            return;
        }
        
        if (command.trim().isEmpty()) {
            callback.onCommandError(command, "Comando vacío");
            return;
        }
        
        // Crear y encolar tarea
        CommandTask task = new CommandTask(command, callback);
        pendingCommands.offer(task);
        
        // Ejecutar en el thread pool
        commandExecutor.submit(() -> executeCommandInternal(task));
    }
    
    /**
     * Ejecuta un comando de forma síncrona (para comandos rápidos).
     */
    @NonNull
    public String executeCommandSync(@NonNull String command) {
        if (!isServiceActive()) {
            return "[ERROR] Servicio no disponible";
        }
        
        if (command.trim().isEmpty()) {
            return "[ERROR] Comando vacío";
        }
        
        try {
            return processCommand(command.trim());
        } catch (Exception e) {
            Log.e(TAG, "Error ejecutando comando: " + command, e);
            return "[ERROR] " + e.getMessage();
        }
    }
    
    /**
     * Obtiene el historial de comandos.
     */
    @NonNull
    public ConcurrentLinkedQueue<CommandHistoryItem> getCommandHistory() {
        return new ConcurrentLinkedQueue<>(commandHistory);
    }
    
    /**
     * Limpia el historial de comandos.
     */
    public void clearCommandHistory() {
        commandHistory.clear();
    }
    
    /**
     * Obtiene el último comando ejecutado.
     */
    @Nullable
    public CommandHistoryItem getLastCommand() {
        return commandHistory.peek();
    }
    
    /**
     * Obtiene estadísticas del servicio.
     */
    @NonNull
    public String getServiceStats() {
        return String.format(Locale.getDefault(),
            "Servicio: %s\n" +
            "Comandos en cola: %d\n" +
            "Historial: %d comandos\n" +
            "Estado: %s",
            isActive.get() ? "ACTIVO" : "INACTIVO",
            pendingCommands.size(),
            commandHistory.size(),
            isShuttingDown.get() ? "APAGANDO" : "NORMAL"
        );
    }
    
    // =====================
    // Métodos internos de ejecución
    // =====================
    
    /**
     * Ejecuta internamente un comando (en thread separado).
     */
    private void executeCommandInternal(@NonNull CommandTask task) {
        String result;
        boolean successful = true;
        
        try {
            // Ejecutar con timeout
            Future<String> future = commandExecutor.submit(() -> processCommand(task.command));
            
            result = future.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
        } catch (Exception e) {
            result = "[ERROR] " + e.getMessage();
            successful = false;
            Log.e(TAG, "Error ejecutando comando: " + task.command, e);
        }
        
        // Actualizar historial
        addToHistory(task.command, result, successful);
        
        // Notificar resultado
        if (successful) {
            task.callback.onCommandExecuted(task.command, result);
        } else {
            task.callback.onCommandError(task.command, result);
        }
        
        // Eliminar de pendientes
        pendingCommands.remove(task);
    }
    
    /**
     * Procesa un comando específico.
     */
    @NonNull
    private String processCommand(@NonNull String command) {
        MainApplication app = MainApplication.getInstance();
        
        if (app == null || !app.isValidState()) {
            return "[ERROR] Aplicación no inicializada correctamente";
        }
        
        String[] parts = command.split("\\s+", 2);
        String baseCommand = parts[0].toLowerCase(Locale.getDefault());
        String arguments = parts.length > 1 ? parts[1] : "";
        
        try {
            return switch (baseCommand) {
                case "help" -> getHelp();
                case "pwd" -> getCurrentDirectory(app);
                case "whoami" -> getCurrentUser(app);
                case "date" -> getCurrentDate();
                case "env" -> getEnvironmentVariables(app);
                case "cd" -> changeDirectory(app, arguments);
                case "echo" -> echoCommand(arguments);
                case "exit" -> exitService();
                case "history" -> getCommandHistoryList();
                case "stats" -> getServiceStats();
                case "clear" -> clearHistory();
                default -> "Comando no encontrado: " + baseCommand;
            };
        } catch (Exception e) {
            return "[ERROR] " + e.getMessage();
        }
    }
    
    /**
     * Agrega un comando al historial.
     */
    private void addToHistory(@NonNull String command, @NonNull String result, 
                             boolean successful) {
        CommandHistoryItem item = new CommandHistoryItem(command, result, successful);
        commandHistory.offer(item);
        
        // Mantener tamaño máximo
        while (commandHistory.size() > MAX_HISTORY_SIZE) {
            commandHistory.poll();
        }
    }
    
    /**
     * Guarda el historial si es necesario (punto de extensión).
     */
    private void saveHistoryIfNeeded() {
        // Aquí se podría implementar persistencia a base de datos o archivo
        Log.d(TAG, "Historial contiene " + commandHistory.size() + " comandos");
    }
    
    // =====================
    // Implementaciones de comandos específicos
    // =====================
    
    @NonNull
    private String getHelp() {
        return "COMANDOS DISPONIBLES:\n" +
               "help      - Muestra esta ayuda\n" +
               "pwd       - Directorio actual\n" +
               "cd <dir>  - Cambia directorio\n" +
               "whoami    - Usuario actual\n" +
               "date      - Fecha y hora\n" +
               "env       - Variables de entorno\n" +
               "echo <txt>- Repite texto\n" +
               "history   - Historial de comandos\n" +
               "stats     - Estadísticas del servicio\n" +
               "clear     - Limpia historial\n" +
               "exit      - Cierra el servicio\n";
    }
    
    @NonNull
    private String getCurrentDirectory(@NonNull MainApplication app) {
        return app.getSessionRepository().getCurrentDirectory();
    }
    
    @NonNull
    private String getCurrentUser(@NonNull MainApplication app) {
        return app.getSessionRepository().getCurrentUser();
    }
    
    @NonNull
    private String getCurrentDate() {
        SimpleDateFormat sdf = new SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss zzz", 
            Locale.getDefault()
        );
        return sdf.format(new Date());
    }
    
    @NonNull
    private String getEnvironmentVariables(@NonNull MainApplication app) {
        StringBuilder sb = new StringBuilder("VARIABLES DE ENTORNO:\n");
        Map<String, String> env = app.getEnvironmentRepository().getAllEnvironmentVariables();
        
        for (Map.Entry<String, String> entry : env.entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
        }
        
        return sb.toString();
    }
    
    @NonNull
    private String changeDirectory(@NonNull MainApplication app, @NonNull String dir) {
        if (dir.isEmpty()) {
            return "Uso: cd <directorio>";
        }
        
        try {
            app.getSessionRepository().setCurrentDirectory(dir);
            return "Directorio cambiado a: " + dir;
        } catch (IllegalArgumentException e) {
            return "[ERROR] " + e.getMessage();
        }
    }
    
    @NonNull
    private String echoCommand(@NonNull String text) {
        if (text.isEmpty()) {
            return "Uso: echo <texto>";
        }
        return text;
    }
    
    @NonNull
    private String exitService() {
        isActive.set(false);
        stopSelf();
        return "Servicio finalizado. Sesión terminada.";
    }
    
    @NonNull
    private String getCommandHistoryList() {
        if (commandHistory.isEmpty()) {
            return "Historial vacío";
        }
        
        StringBuilder sb = new StringBuilder("HISTORIAL DE COMANDOS:\n");
        int index = 1;
        
        for (CommandHistoryItem item : commandHistory) {
            String status = item.isSuccessful() ? "✓" : "✗";
            sb.append(index++).append(". [").append(status).append("] ")
              .append(item.getCommand()).append("\n");
        }
        
        return sb.toString();
    }
    
    @NonNull
    private String clearHistory() {
        int count = commandHistory.size();
        commandHistory.clear();
        return "Historial limpiado (" + count + " comandos eliminados)";
    }
    
    /**
     * Callback para logging interno.
     */
    private class LoggingCallback implements CommandExecutionCallback {
        @Override
        public void onCommandExecuted(@NonNull String command, @NonNull String result) {
            Log.d(TAG, "Comando ejecutado: " + command);
        }
        
        @Override
        public void onCommandError(@NonNull String command, @NonNull String error) {
            Log.e(TAG, "Error en comando: " + command + " - " + error);
        }
    }
}
