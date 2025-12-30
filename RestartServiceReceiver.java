package com.example.commandemulator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RestartServiceReceiver - Receptor para reiniciar el MainService
 * cuando es detenido por el sistema o por condiciones externas.
 * 
 * Características:
 * - Prevención de bucles infinitos de reinicio
 * - Backoff exponencial para intentos fallidos
 * - Verificación de condiciones del sistema
 * - Sistema de cuotas para evitar abusos
 * - Logging detallado de eventos de reinicio
 * - Configuración personalizable por usuario
 */
public class RestartServiceReceiver extends BroadcastReceiver {
    
    private static final String TAG = "CommandEmulatorRestart";
    private static final String CHANNEL_ID = "service_restart_channel";
    private static final int NOTIFICATION_ID = 1002;
    
    // Acciones que pueden disparar un reinicio
    private static final String[] RESTART_TRIGGERS = {
        Intent.ACTION_BOOT_COMPLETED,
        "android.intent.action.ACTION_POWER_CONNECTED",
        "android.intent.action.ACTION_POWER_DISCONNECTED",
        "android.intent.action.USER_PRESENT",
        "android.intent.action.SCREEN_ON",
        "com.example.commandemulator.RESTART_SERVICE",
        "com.example.commandemulator.SERVICE_CRASHED"
    };
    
    // Sistema de cuotas para evitar reinicios excesivos
    private static final class RestartQuota {
        private static final long QUOTA_WINDOW_MS = TimeUnit.HOURS.toMillis(1);
        private static final int MAX_RESTARTS_PER_HOUR = 10;
        private static final long MIN_RESTART_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30);
        
        private static final AtomicInteger restartCount = new AtomicInteger(0);
        private static volatile long lastRestartTime = 0;
        private static volatile long quotaWindowStart = System.currentTimeMillis();
        
        /**
         * Verifica si se permite un nuevo reinicio.
         */
        static synchronized boolean canRestart() {
            long now = System.currentTimeMillis();
            
            // Reiniciar ventana de cuota si ha pasado una hora
            if (now - quotaWindowStart > QUOTA_WINDOW_MS) {
                restartCount.set(0);
                quotaWindowStart = now;
                Log.d(TAG, "Ventana de cuota reiniciada");
            }
            
            // Verificar límite por hora
            if (restartCount.get() >= MAX_RESTARTS_PER_HOUR) {
                Log.w(TAG, "Cuota de reinicios excedida: " + restartCount.get() + 
                      " en la última hora (máximo: " + MAX_RESTARTS_PER_HOUR + ")");
                return false;
            }
            
            // Verificar intervalo mínimo entre reinicios
            if (now - lastRestartTime < MIN_RESTART_INTERVAL_MS) {
                Log.w(TAG, "Reinicio demasiado rápido. Esperar: " + 
                      (MIN_RESTART_INTERVAL_MS - (now - lastRestartTime)) + "ms");
                return false;
            }
            
            return true;
        }
        
        /**
         * Registra un intento de reinicio exitoso.
         */
        static synchronized void recordRestart() {
            restartCount.incrementAndGet();
            lastRestartTime = System.currentTimeMillis();
            Log.d(TAG, "Reinicio registrado. Total esta hora: " + restartCount.get());
        }
        
        /**
         * Obtiene estadísticas de reinicio.
         */
        static String getStats() {
            long now = System.currentTimeMillis();
            long remainingInWindow = QUOTA_WINDOW_MS - (now - quotaWindowStart);
            return String.format(
                "Reinicios esta hora: %d/%d\n" +
                "Último reinicio: %s\n" +
                "Ventana restante: %d segundos",
                restartCount.get(),
                MAX_RESTARTS_PER_HOUR,
                lastRestartTime > 0 ? 
                    TimeUnit.MILLISECONDS.toSeconds(now - lastRestartTime) + "s ago" : "Nunca",
                TimeUnit.MILLISECONDS.toSeconds(Math.max(0, remainingInWindow))
            );
        }
    }
    
    /**
     * Manager para manejar el proceso de reinicio.
     */
    private static class RestartManager {
        
        /**
         * Intenta reiniciar el servicio con todas las verificaciones.
         */
        static void attemptServiceRestart(@NonNull Context context, @NonNull String triggerAction) {
            try {
                Log.i(TAG, "Intentando reinicio del servicio. Disparador: " + triggerAction);
                
                // 1. Verificar cuotas
                if (!RestartQuota.canRestart()) {
                    Log.w(TAG, "Reinicio bloqueado por cuota o intervalo");
                    showNotification(context,
                        "Reinicio del servicio bloqueado",
                        "Demasiados reinicios recientemente",
                        NotificationCompat.PRIORITY_MIN,
                        true);
                    return;
                }
                
                // 2. Verificar condiciones del sistema
                if (!checkSystemConditions(context, triggerAction)) {
                    Log.w(TAG, "Condiciones del sistema no adecuadas para reinicio");
                    return;
                }
                
                // 3. Verificar si el servicio ya está ejecutándose
                if (isServiceAlreadyRunning(context)) {
                    Log.i(TAG, "Servicio ya está ejecutándose, omitiendo reinicio");
                    return;
                }
                
                // 4. Calcular retardo basado en intentos fallidos anteriores
                long delay = calculateRestartDelay(context, triggerAction);
                
                if (delay > 0) {
                    Log.d(TAG, "Programando reinicio con retardo de " + delay + "ms");
                    scheduleDelayedRestart(context, delay, triggerAction);
                } else {
                    performImmediateRestart(context, triggerAction);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error en attemptServiceRestart", e);
                EventLogger.logRestartEvent(context, false, triggerAction, e.getMessage());
            }
        }
        
        /**
         * Verifica condiciones del sistema antes de reiniciar.
         */
        private static boolean checkSystemConditions(@NonNull Context context, 
                                                    @NonNull String triggerAction) {
            // 1. Verificar si el dispositivo está en modo de bajo consumo
            if (isDeviceInBatterySaverMode(context)) {
                Log.w(TAG, "Modo ahorro de energía activado, omitiendo reinicio");
                if (isCriticalRestart(triggerAction)) {
                    Log.i(TAG, "Pero es crítico, procediendo de todos modos");
                } else {
                    return false;
                }
            }
            
            // 2. Verificar si el sistema está en proceso de apagado
            if (isSystemShuttingDown(context)) {
                Log.w(TAG, "Sistema apagándose, omitiendo reinicio");
                return false;
            }
            
            // 3. Verificar almacenamiento disponible
            if (isLowStorage(context)) {
                Log.w(TAG, "Almacenamiento bajo, omitiendo reinicio");
                return false;
            }
            
            return true;
        }
        
        private static boolean isDeviceInBatterySaverMode(@NonNull Context context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                return powerManager != null && powerManager.isPowerSaveMode();
            }
            return false;
        }
        
        private static boolean isSystemShuttingDown(@NonNull Context context) {
            // Verificar si hay un apagado en progreso
            // Esto es una implementación simplificada
            return false;
        }
        
        private static boolean isLowStorage(@NonNull Context context) {
            // Verificar almacenamiento disponible
            // Implementación simplificada
            return false;
        }
        
        private static boolean isCriticalRestart(@NonNull String triggerAction) {
            // Algunas acciones son críticas y deben ejecutarse de todos modos
            return triggerAction.equals("com.example.commandemulator.SERVICE_CRASHED") ||
                   triggerAction.equals(Intent.ACTION_BOOT_COMPLETED);
        }
        
        /**
         * Verifica si el servicio ya está ejecutándose.
         */
        private static boolean isServiceAlreadyRunning(@NonNull Context context) {
            // Implementación simplificada
            // En una app real, usarías ActivityManager o un sistema de estado compartido
            return false;
        }
        
        /**
         * Calcula el retardo para el reinicio (backoff exponencial).
         */
        private static long calculateRestartDelay(@NonNull Context context, 
                                                 @NonNull String triggerAction) {
            // Obtener conteo de intentos fallidos recientes
            int failedAttempts = getFailedRestartAttempts(context);
            
            // Backoff exponencial: 2^intentos * baseDelay
            long baseDelay = getBaseDelayForTrigger(triggerAction);
            long delay = (long) (baseDelay * Math.pow(2, Math.min(failedAttempts, 5)));
            
            // Límite máximo de retardo
            long maxDelay = TimeUnit.MINUTES.toMillis(5);
            delay = Math.min(delay, maxDelay);
            
            if (delay > 0) {
                Log.d(TAG, "Backoff calculado: " + delay + "ms (intentos fallidos: " + 
                      failedAttempts + ", base: " + baseDelay + "ms)");
            }
            
            return delay;
        }
        
        private static long getBaseDelayForTrigger(@NonNull String triggerAction) {
            switch (triggerAction) {
                case "com.example.commandemulator.SERVICE_CRASHED":
                    return 1000; // 1 segundo para crashes
                case Intent.ACTION_BOOT_COMPLETED:
                    return 5000; // 5 segundos para boot
                case Intent.ACTION_USER_PRESENT:
                    return 2000; // 2 segundos para usuario presente
                default:
                    return 3000; // 3 segundos por defecto
            }
        }
        
        private static int getFailedRestartAttempts(@NonNull Context context) {
            // Leer de SharedPreferences
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                "restart_stats", Context.MODE_PRIVATE);
            return prefs.getInt("failed_attempts", 0);
        }
        
        private static void incrementFailedAttempts(@NonNull Context context) {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                "restart_stats", Context.MODE_PRIVATE);
            int attempts = prefs.getInt("failed_attempts", 0) + 1;
            prefs.edit().putInt("failed_attempts", attempts).apply();
            Log.d(TAG, "Intento fallido #" + attempts + " registrado");
        }
        
        private static void resetFailedAttempts(@NonNull Context context) {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                "restart_stats", Context.MODE_PRIVATE);
            prefs.edit().putInt("failed_attempts", 0).apply();
            Log.d(TAG, "Intentos fallidos reiniciados a 0");
        }
        
        /**
         * Programa un reinicio con retardo.
         */
        private static void scheduleDelayedRestart(@NonNull Context context, 
                                                  long delay,
                                                  @NonNull String triggerAction) {
            android.os.Handler handler = new android.os.Handler();
            handler.postDelayed(() -> {
                Log.d(TAG, "Ejecutando reinicio programado (retardo: " + delay + "ms)");
                performImmediateRestart(context, triggerAction);
            }, delay);
        }
        
        /**
         * Realiza el reinicio inmediato del servicio.
         */
        private static void performImmediateRestart(@NonNull Context context, 
                                                   @NonNull String triggerAction) {
            try {
                Log.i(TAG, "Iniciando reinicio inmediato del servicio");
                
                Intent serviceIntent = new Intent(context, MainService.class);
                serviceIntent.putExtra("restart_reason", triggerAction);
                serviceIntent.putExtra("restart_timestamp", System.currentTimeMillis());
                serviceIntent.putExtra("attempt_number", RestartQuota.getRestartCount());
                
                // Iniciar servicio según versión de Android
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundServiceSafely(context, serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                
                // Registrar éxito
                RestartQuota.recordRestart();
                EventLogger.logRestartEvent(context, true, triggerAction, null);
                resetFailedAttempts(context);
                
                // Mostrar notificación
                showNotification(context,
                    "Servicio reiniciado",
                    "Emulador de Comandos está nuevamente activo",
                    NotificationCompat.PRIORITY_DEFAULT,
                    false);
                
                Log.i(TAG, "Reinicio del servicio completado exitosamente");
                
            } catch (SecurityException e) {
                Log.e(TAG, "Permiso denegado para reiniciar servicio", e);
                EventLogger.logRestartEvent(context, false, triggerAction, "SECURITY_EXCEPTION");
                incrementFailedAttempts(context);
                
            } catch (IllegalStateException e) {
                Log.e(TAG, "Estado ilegal para reiniciar servicio", e);
                EventLogger.logRestartEvent(context, false, triggerAction, "ILLEGAL_STATE");
                incrementFailedAttempts(context);
                
            } catch (Exception e) {
                Log.e(TAG, "Error inesperado reiniciando servicio", e);
                EventLogger.logRestartEvent(context, false, triggerAction, "UNKNOWN_ERROR");
                incrementFailedAttempts(context);
            }
        }
        
        @RequiresApi(api = Build.VERSION_CODES.O)
        private static void startForegroundServiceSafely(@NonNull Context context, 
                                                        @NonNull Intent serviceIntent) {
            try {
                context.startForegroundService(serviceIntent);
            } catch (IllegalStateException e) {
                // Fallback: intentar como servicio normal
                Log.w(TAG, "Fallback a startService()");
                context.startService(serviceIntent);
            }
        }
        
        /**
         * Muestra una notificación al usuario.
         */
        private static void showNotification(@NonNull Context context,
                                           @NonNull String title,
                                           @NonNull String message,
                                           int priority,
                                           boolean isWarning) {
            try {
                NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(isWarning ? 
                        R.drawable.ic_warning : R.drawable.ic_terminal)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setPriority(priority)
                    .setAutoCancel(true);
                
                NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
                notificationManager.notify(NOTIFICATION_ID, builder.build());
                
            } catch (Exception e) {
                Log.e(TAG, "Error mostrando notificación", e);
            }
        }
    }
    
    /**
     * Logger de eventos de reinicio.
     */
    private static class EventLogger {
        
        private static final String PREFS_NAME = "restart_events";
        private static final String KEY_LAST_RESTART = "last_restart";
        private static final String KEY_TOTAL_RESTARTS = "total_restarts";
        private static final String KEY_LAST_ERROR = "last_restart_error";
        
        static void logRestartEvent(@NonNull Context context, 
                                   boolean success,
                                   @NonNull String trigger,
                                   String error) {
            try {
                android.content.SharedPreferences prefs = context.getSharedPreferences(
                    PREFS_NAME, Context.MODE_PRIVATE);
                
                android.content.SharedPreferences.Editor editor = prefs.edit();
                editor.putLong(KEY_LAST_RESTART, System.currentTimeMillis());
                
                if (success) {
                    int total = prefs.getInt(KEY_TOTAL_RESTARTS, 0);
                    editor.putInt(KEY_TOTAL_RESTARTS, total + 1);
                    editor.remove(KEY_LAST_ERROR);
                    Log.d(TAG, "Reinicio exitoso registrado. Total: " + (total + 1));
                } else {
                    editor.putString(KEY_LAST_ERROR, 
                        trigger + ": " + (error != null ? error : "Unknown"));
                    Log.w(TAG, "Reinicio fallido registrado: " + trigger + " - " + error);
                }
                
                editor.apply();
                
            } catch (Exception e) {
                Log.e(TAG, "Error registrando evento de reinicio", e);
            }
        }
        
        static String getRestartStats(@NonNull Context context) {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);
            
            long lastRestart = prefs.getLong(KEY_LAST_RESTART, 0);
            int totalRestarts = prefs.getInt(KEY_TOTAL_RESTARTS, 0);
            String lastError = prefs.getString(KEY_LAST_ERROR, "Ninguno");
            
            long ago = lastRestart > 0 ? 
                TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - lastRestart) : 0;
            
            return String.format(
                "Reinicios totales: %d\n" +
                "Último reinicio: %s\n" +
                "Último error: %s\n" +
                "Cuota actual: %s",
                totalRestarts,
                lastRestart > 0 ? ago + " segundos atrás" : "Nunca",
                lastError,
                RestartQuota.getStats()
            );
        }
    }
    
    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        if (intent == null || intent.getAction() == null) {
            Log.w(TAG, "Intent o acción nula recibida");
            return;
        }
        
        String action = intent.getAction();
        Log.d(TAG, "Broadcast recibido: " + action);
        
        // Verificar si este receiver debe manejar esta acción
        if (!shouldHandleAction(action)) {
            Log.d(TAG, "Acción no manejada: " + action);
            return;
        }
        
        // Log detallado
        logIntentDetails(intent);
        
        // Verificar configuración del usuario
        if (!isAutoRestartEnabled(context)) {
            Log.i(TAG, "Reinicio automático deshabilitado por el usuario");
            return;
        }
        
        // Verificar permisos
        if (!hasRequiredPermissions(context)) {
            Log.w(TAG, "Permisos insuficientes para reinicio");
            EventLogger.logRestartEvent(context, false, action, "INSUFFICIENT_PERMISSIONS");
            return;
        }
        
        // Delegar al RestartManager
        RestartManager.attemptServiceRestart(context, action);
    }
    
    /**
     * Determina si debemos manejar esta acción.
     */
    private boolean shouldHandleAction(@NonNull String action) {
        for (String trigger : RESTART_TRIGGERS) {
            if (trigger.equals(action)) {
                return true;
            }
        }
        
        // También manejar acciones explícitas de nuestro paquete
        return action.startsWith("com.example.commandemulator.");
    }
    
    /**
     * Log de detalles del intent.
     */
    private void logIntentDetails(@NonNull Intent intent) {
        StringBuilder sb = new StringBuilder("Detalles del intent:\n");
        sb.append("  Acción: ").append(intent.getAction()).append("\n");
        
        if (intent.getExtras() != null) {
            for (String key : intent.getExtras().keySet()) {
                sb.append("  ").append(key).append(": ")
                  .append(intent.getExtras().get(key)).append("\n");
            }
        }
        
        Log.d(TAG, sb.toString());
    }
    
    /**
     * Verifica si el usuario ha habilitado el reinicio automático.
     */
    private boolean isAutoRestartEnabled(@NonNull Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(
            "app_preferences", Context.MODE_PRIVATE);
        return prefs.getBoolean("auto_restart_enabled", true);
    }
    
    /**
     * Verifica permisos requeridos.
     */
    private boolean hasRequiredPermissions(@NonNull Context context) {
        // Permiso básico para recibir broadcasts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return context.checkCallingOrSelfPermission(
                android.Manifest.permission.FOREGROUND_SERVICE) == 
                android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }
    
    /**
     * Métodos de utilidad pública.
     */
    
    /**
     * Solicita un reinicio manual del servicio.
     */
    public static void requestManualRestart(@NonNull Context context) {
        Log.i(TAG, "Reinicio manual solicitado");
        
        Intent restartIntent = new Intent("com.example.commandemulator.MANUAL_RESTART");
        restartIntent.setClass(context, RestartServiceReceiver.class);
        context.sendBroadcast(restartIntent);
    }
    
    /**
     * Obtiene estadísticas de reinicio.
     */
    public static String getRestartStatistics(@NonNull Context context) {
        return EventLogger.getRestartStats(context);
    }
    
    /**
     * Resetea las estadísticas de reinicio.
     */
    public static void resetRestartStatistics(@NonNull Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(
            "restart_events", Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
        
        android.content.SharedPreferences statsPrefs = context.getSharedPreferences(
            "restart_stats", Context.MODE_PRIVATE);
        statsPrefs.edit().clear().apply();
        
        Log.i(TAG, "Estadísticas de reinicio reseteadas");
    }
    
    /**
     * Habilita/deshabilita el reinicio automático.
     */
    public static void setAutoRestartEnabled(@NonNull Context context, boolean enabled) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(
            "app_preferences", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("auto_restart_enabled", enabled).apply();
        
        Log.i(TAG, "Reinicio automático " + (enabled ? "habilitado" : "deshabilitado"));
    }
}
