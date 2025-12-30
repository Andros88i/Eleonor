package com.example.commandemulator;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MainApplication - Núcleo global del Emulador de Comandos.
 * 
 * Patrones aplicados:
 * - Singleton (para acceso global controlado)
 * - Repository (para gestión de entorno y sesión)
 * - Inmutabilidad donde sea posible
 */
public class MainApplication extends Application {
    
    private static final String TAG = "CommandEmulator";
    
    // Singleton instance - volatile para thread safety en doble verificación
    private static volatile MainApplication instance;
    
    // Contexto de aplicación (inmutable)
    private final Context appContext;
    
    // Repositorios para gestión separada de responsabilidades
    private EnvironmentRepository environmentRepository;
    private SessionRepository sessionRepository;
    
    // Estado de la aplicación
    private AppState appState;
    
    public MainApplication() {
        super();
        this.appContext = this;
    }
    
    /**
     * Obtiene la instancia singleton de la aplicación.
     * Usa doble verificación para thread safety.
     */
    @NonNull
    public static MainApplication getInstance() {
        if (instance == null) {
            synchronized (MainApplication.class) {
                if (instance == null) {
                    throw new IllegalStateException(
                        "MainApplication no ha sido inicializado. " +
                        "Asegúrate de declararla en AndroidManifest.xml"
                    );
                }
            }
        }
        return instance;
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        // Inicializar singleton
        instance = this;
        
        // Inicializar componentes
        initializeComponents();
        
        Log.i(TAG, "MainApplication iniciado correctamente");
    }
    
    /**
     * Inicializa todos los componentes de la aplicación.
     * Separado de onCreate() para mejor testabilidad.
     */
    private void initializeComponents() {
        // Inicializar repositorios
        environmentRepository = new EnvironmentRepository();
        sessionRepository = new SessionRepository();
        
        // Inicializar estado de la aplicación
        appState = new AppState(true);
        
        // Cargar configuración inicial
        loadDefaultConfiguration();
    }
    
    /**
     * Carga la configuración por defecto del emulador.
     */
    private void loadDefaultConfiguration() {
        // Inicializar entorno por defecto
        environmentRepository.initializeDefaultEnvironment();
        
        // Inicializar sesión por defecto
        sessionRepository.initializeDefaultSession();
    }
    
    // =====================
    // Métodos públicos de acceso
    // =====================
    
    @NonNull
    public Context getAppContext() {
        return appContext;
    }
    
    @NonNull
    public EnvironmentRepository getEnvironmentRepository() {
        return environmentRepository;
    }
    
    @NonNull
    public SessionRepository getSessionRepository() {
        return sessionRepository;
    }
    
    @NonNull
    public AppState getAppState() {
        return appState;
    }
    
    // =====================
    // Clases internas para separación de responsabilidades
    // =====================
    
    /**
     * Repository para gestión de variables de entorno.
     * Patrón Repository para encapsular el acceso a datos.
     */
    public static class EnvironmentRepository {
        private final Map<String, String> environmentVariables;
        
        public EnvironmentRepository() {
            // ConcurrentHashMap para thread safety
            this.environmentVariables = new ConcurrentHashMap<>();
        }
        
        /**
         * Inicializa el entorno por defecto.
         */
        public void initializeDefaultEnvironment() {
            environmentVariables.clear();
            environmentVariables.put("OS", "Android");
            environmentVariables.put("SHELL", "ghostsh");
            environmentVariables.put("PATH", "/system/bin:/data/local/bin");
            environmentVariables.put("LANG", "es_MX");
            environmentVariables.put("TERM", "ghost-terminal");
            environmentVariables.put("HOME", "/home/guest");
            environmentVariables.put("USER", "guest");
            environmentVariables.put("PWD", "/home/guest");
        }
        
        @NonNull
        public Map<String, String> getAllEnvironmentVariables() {
            // Devolver copia defensiva para evitar modificaciones externas
            return new HashMap<>(environmentVariables);
        }
        
        @NonNull
        public String getEnvironmentVariable(@NonNull String key) {
            String value = environmentVariables.get(key);
            return value != null ? value : "";
        }
        
        public void setEnvironmentVariable(@NonNull String key, @NonNull String value) {
            if (key.trim().isEmpty()) {
                throw new IllegalArgumentException("La clave no puede estar vacía");
            }
            environmentVariables.put(key, value);
        }
        
        public boolean removeEnvironmentVariable(@NonNull String key) {
            return environmentVariables.remove(key) != null;
        }
        
        public void clearEnvironment() {
            environmentVariables.clear();
        }
        
        public boolean containsKey(@NonNull String key) {
            return environmentVariables.containsKey(key);
        }
    }
    
    /**
     * Repository para gestión de sesión del usuario.
     */
    public static class SessionRepository {
        private String currentUser;
        private String currentDirectory;
        private long sessionStartTime;
        
        public SessionRepository() {
            this.sessionStartTime = System.currentTimeMillis();
        }
        
        /**
         * Inicializa la sesión por defecto.
         */
        public void initializeDefaultSession() {
            this.currentUser = "guest";
            this.currentDirectory = "/home/guest";
        }
        
        @NonNull
        public String getCurrentUser() {
            return currentUser != null ? currentUser : "unknown";
        }
        
        public void setCurrentUser(@NonNull String user) {
            if (user.trim().isEmpty()) {
                throw new IllegalArgumentException("El usuario no puede estar vacío");
            }
            this.currentUser = user;
        }
        
        @NonNull
        public String getCurrentDirectory() {
            return currentDirectory != null ? currentDirectory : "/";
        }
        
        public void setCurrentDirectory(@NonNull String directory) {
            if (directory.trim().isEmpty()) {
                throw new IllegalArgumentException("El directorio no puede estar vacío");
            }
            this.currentDirectory = directory;
        }
        
        public long getSessionDuration() {
            return System.currentTimeMillis() - sessionStartTime;
        }
        
        @NonNull
        public String getPrompt() {
            return String.format("%s@android:%s$ ", getCurrentUser(), getCurrentDirectory());
        }
        
        public void resetSession() {
            initializeDefaultSession();
            sessionStartTime = System.currentTimeMillis();
        }
    }
    
    /**
     * Clase inmutable para el estado global de la aplicación.
     */
    public static class AppState {
        private volatile boolean isRunning;
        private volatile boolean isInitialized;
        private final long appStartTime;
        
        public AppState(boolean isRunning) {
            this.isRunning = isRunning;
            this.isInitialized = true;
            this.appStartTime = System.currentTimeMillis();
        }
        
        public boolean isRunning() {
            return isRunning;
        }
        
        public void setRunning(boolean running) {
            this.isRunning = running;
        }
        
        public boolean isInitialized() {
            return isInitialized;
        }
        
        public long getAppUptime() {
            return System.currentTimeMillis() - appStartTime;
        }
        
        public void shutdown() {
            this.isRunning = false;
            Log.i(TAG, "Aplicación apagada. Uptime: " + getAppUptime() + "ms");
        }
    }
    
    // =====================
    // Métodos de utilidad
    // =====================
    
    /**
     * Verifica si la aplicación está en estado válido.
     */
    public boolean isValidState() {
        return appState != null && 
               appState.isInitialized() && 
               environmentRepository != null && 
               sessionRepository != null;
    }
    
    /**
     * Reinicia completamente el emulador.
     */
    public void restartEmulator() {
        Log.i(TAG, "Reiniciando emulador...");
        
        // Reiniciar repositorios
        environmentRepository.initializeDefaultEnvironment();
        sessionRepository.resetSession();
        appState.setRunning(true);
        
        Log.i(TAG, "Emulador reiniciado correctamente");
    }
    
    /**
     * Limpia todos los recursos antes de la destrucción.
     */
    @Override
    public void onTerminate() {
        // Guardar estado si es necesario
        if (appState != null) {
            appState.shutdown();
        }
        
        // Limpiar referencias
        environmentRepository = null;
        sessionRepository = null;
        appState = null;
        
        super.onTerminate();
    }
    
    /**
     * Método de conveniencia para logging consistente.
     */
    public static void logDebug(String message) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message);
        }
    }
    
    public static void logError(String message, Throwable throwable) {
        Log.e(TAG, message, throwable);
    }
}
