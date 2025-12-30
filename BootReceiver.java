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

/**
 * BootReceiver - Se ejecuta en eventos del sistema para mantener
 * el Emulador de Comandos siempre disponible.
 * 
 * Funcionalidades:
 * - Inicio automático al arranque del sistema
 * - Reinicio después de actualizaciones de la app
 * - Manejo de errores robusto
 * - Notificaciones informativas
 * - Verificación de permisos y condiciones
 */
public class BootReceiver extends BroadcastReceiver {
    
    private static final String TAG = "CommandEmulatorBoot";
    private static final String CHANNEL_ID = "command_emulator_boot_channel";
    private static final int NOTIFICATION_ID = 1001;
    
    // Acciones que este receiver maneja
    private static final String[] HANDLED_ACTIONS = {
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_PACKAGE_REPLACED,
        "android.intent.action.QUICKBOOT_POWERON", // Para algunos fabricantes
        "com.htc.intent.action.QUICKBOOT_POWERON"  // Para dispositivos HTC
    };
    
    /**
     * Clase de utilidad para manejar el inicio del servicio.
     */
    private static class ServiceStarter {
        
        /**
         * Inicia el servicio principal con todas las comprobaciones necesarias.
         */
        static void startMainService(@NonNull Context context) {
            try {
                // Verificar si el servicio ya está corriendo
                if (isServiceAlreadyRunning(context)) {
                    Log.i(TAG, "El servicio ya está en ejecución, omitiendo inicio");
                    showNotification(context, 
                        "Emulador de Comandos", 
                        "Servicio ya activo",
                        NotificationCompat.PRIORITY_MIN);
                    return;
                }
                
                // Verificar condiciones del dispositivo
                if (!checkDeviceConditions(context)) {
                    Log.w(TAG, "Condiciones del dispositivo no adecuadas para iniciar servicio");
                    return;
                }
                
                // Crear intent del servicio
                Intent serviceIntent = new Intent(context, MainService.class);
                serviceIntent.putExtra("start_reason", "boot_completed");
                serviceIntent.putExtra("timestamp", System.currentTimeMillis());
                
                // Iniciar servicio según versión de Android
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundServiceCompat(context, serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                
                Log.i(TAG, "MainService iniciado exitosamente desde BootReceiver");
                showNotification(context,
                    "Emulador de Comandos Iniciado",
                    "Servicio listo para comandos",
                    NotificationCompat.PRIORITY_DEFAULT);
                
                // Registrar evento de inicio
                EventLogger.logBootEvent(context, true);
                
            } catch (SecurityException e) {
                Log.e(TAG, "Permiso denegado para iniciar servicio", e);
                EventLogger.logBootEvent(context, false, "SECURITY_EXCEPTION");
                
            } catch (IllegalStateException e) {
                Log.e(TAG, "Estado ilegal para iniciar servicio", e);
                EventLogger.logBootEvent(context, false, "ILLEGAL_STATE");
                
            } catch (Exception e) {
                Log.e(TAG, "Error inesperado iniciando servicio", e);
                EventLogger.logBootEvent(context, false, "UNKNOWN_ERROR");
            }
        }
        
        /**
         * Verifica si el servicio ya está en ejecución.
         */
        private static boolean isServiceAlreadyRunning(@NonNull Context context) {
            // En una implementación real, aquí verificarías con ActivityManager
            // o usarías algún mecanismo de estado compartido
            
            // Por ahora, usamos un flag simple en SharedPreferences
            return false; // Implementación simplificada
        }
        
        /**
         * Verifica condiciones del dispositivo antes de iniciar.
         */
        private static boolean checkDeviceConditions(@NonNull Context context) {
            // 1. Verificar si hay batería suficiente
            if (isBatteryLow(context)) {
                Log.w(TAG, "Batería baja, omitiendo inicio automático");
                return false;
            }
            
            // 2. Verificar si el dispositivo está en modo Doze
            if (isDeviceInDozeMode(context)) {
                Log.w(TAG, "Dispositivo en modo Doze, esperando para iniciar");
                return false;
            }
            
            // 3. Verificar conectividad si es necesario
            if (requiresNetwork() && !isNetworkAvailable(context)) {
                Log.w(TAG, "Sin conectividad de red, omitiendo inicio");
                return false;
            }
            
            return true;
        }
        
        private static boolean isBatteryLow(@NonNull Context context) {
            // Implementación simplificada
            // En una app real, usarías BatteryManager
            return false;
        }
        
        @RequiresApi(api = Build.VERSION_CODES.M)
        private static boolean isDeviceInDozeMode(@NonNull Context context) {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return powerManager != null && !powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
        }
        
        private static boolean requiresNetwork() {
            // Determinar si el servicio requiere red
            return false; // El emulador básico no necesita red
        }
        
        private static boolean isNetworkAvailable(@NonNull Context context) {
            // Implementación simplificada
            return true;
        }
        
        /**
         * Inicia el servicio en primer plano (compatible con Android O+).
         */
        @RequiresApi(api = Build.VERSION_CODES.O)
        private static void startForegroundServiceCompat(@NonNull Context context, 
                                                        @NonNull Intent serviceIntent) {
            try {
                context.startForegroundService(serviceIntent);
                
                // Asegurar que el servicio muestra notificación rápidamente
                // (dentro de los 5 segundos requeridos por Android)
                scheduleForegroundNotification(context);
                
            } catch (IllegalStateException e) {
                Log.e(TAG, "No se puede iniciar servicio en primer plano", e);
                // Fallback: intentar como servicio normal
                try {
                    context.startService(serviceIntent);
                } catch (Exception ex) {
                    Log.e(TAG, "Fallback también falló", ex);
                }
            }
        }
        
        /**
         * Programa la notificación de primer plano.
         */
        private static void scheduleForegroundNotification(@NonNull Context context) {
            // El servicio debería mostrar su propia notificación
            // Esto es solo un backup
            android.os.Handler handler = new android.os.Handler();
            handler.postDelayed(() -> {
                showNotification(context,
                    "Emulador de Comandos",
                    "Ejecutándose en segundo plano",
                    NotificationCompat.PRIORITY_LOW);
            }, 1000);
        }
        
        /**
         * Muestra una notificación al usuario.
         */
        private static void showNotification(@NonNull Context context,
                                           @NonNull String title,
                                           @NonNull String message,
                                           int priority) {
            try {
                NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_terminal) // Asegúrate de tener este recurso
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
     * Logger de eventos para boot.
     */
    private static class EventLogger {
        
        private static final String PREFS_NAME = "boot_events";
        private static final String KEY_LAST_BOOT_TIME = "last_boot_time";
        private static final String KEY_BOOT_COUNT = "boot_count";
        private static final String KEY_LAST_ERROR = "last_error";
        
        static void logBootEvent(@NonNull Context context, boolean success) {
            logBootEvent(context, success, success ? "SUCCESS" : "UNKNOWN_ERROR");
        }
        
        static void logBootEvent(@NonNull Context context, boolean success, @NonNull String errorCode) {
            try {
                android.content.SharedPreferences prefs = context.getSharedPreferences(
                    PREFS_NAME, Context.MODE_PRIVATE);
                
                android.content.SharedPreferences.Editor editor = prefs.edit();
                editor.putLong(KEY_LAST_BOOT_TIME, System.currentTimeMillis());
                
                if (success) {
                    int count = prefs.getInt(KEY_BOOT_COUNT, 0);
                    editor.putInt(KEY_BOOT_COUNT, count + 1);
                    editor.remove(KEY_LAST_ERROR);
                } else {
                    editor.putString(KEY_LAST_ERROR, errorCode);
                }
                
                editor.apply();
                
                Log.d(TAG, String.format("Evento de boot registrado: éxito=%s, código=%s", 
                    success, errorCode));
                
            } catch (Exception e) {
                Log.e(TAG, "Error registrando evento de boot", e);
            }
        }
        
        static long getLastBootTime(@NonNull Context context) {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getLong(KEY_LAST_BOOT_TIME, 0);
        }
        
        static int getBootCount(@NonNull Context context) {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getInt(KEY_BOOT_COUNT, 0);
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
        
        // Verificar si manejamos esta acción
        if (!isActionHandled(action)) {
            Log.d(TAG, "Acción no manejada: " + action);
            return;
        }
        
        // Log detallado para debugging
        logIntentDetails(intent);
        
        // Verificar permisos básicos
        if (!hasRequiredPermissions(context)) {
            Log.w(TAG, "Permisos insuficientes para iniciar servicio");
            EventLogger.logBootEvent(context, false, "INSUFFICIENT_PERMISSIONS");
            return;
        }
        
        // Verificar si el usuario ha deshabilitado el inicio automático
        if (isAutoStartDisabledByUser(context)) {
            Log.i(TAG, "Inicio automático deshabilitado por el usuario");
            return;
        }
        
        // Iniciar servicio con retardo para evitar sobrecarga al arranque
        scheduleDelayedServiceStart(context, action);
    }
    
    /**
     * Verifica si manejamos esta acción específica.
     */
    private boolean isActionHandled(@NonNull String action) {
        for (String handledAction : HANDLED_ACTIONS) {
            if (handledAction.equals(action)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Registra detalles del intent para debugging.
     */
    private void logIntentDetails(@NonNull Intent intent) {
        Log.d(TAG, "Detalles del intent:");
        Log.d(TAG, "  Acción: " + intent.getAction());
        Log.d(TAG, "  Paquete: " + (intent.getData() != null ? intent.getData().toString() : "null"));
        Log.d(TAG, "  Flags: " + intent.getFlags());
        Log.d(TAG, "  Extras: " + (intent.getExtras() != null ? intent.getExtras().toString() : "sin extras"));
    }
    
    /**
     * Verifica permisos requeridos.
     */
    private boolean hasRequiredPermissions(@NonNull Context context) {
        // Verificar permiso RECEIVE_BOOT_COMPLETED
        if (context.checkCallingOrSelfPermission(android.Manifest.permission.RECEIVE_BOOT_COMPLETED) 
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Permiso RECEIVE_BOOT_COMPLETED no concedido");
            return false;
        }
        
        // Para Android O+, verificar permiso para foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (context.checkCallingOrSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE) 
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Permiso FOREGROUND_SERVICE no concedido");
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Verifica si el usuario deshabilitó el inicio automático.
     */
    private boolean isAutoStartDisabledByUser(@NonNull Context context) {
        // Podrías guardar esta preferencia en SharedPreferences
        android.content.SharedPreferences prefs = context.getSharedPreferences(
            "app_preferences", Context.MODE_PRIVATE);
        return !prefs.getBoolean("auto_start_enabled", true); // Por defecto true
    }
    
    /**
     * Programa el inicio del servicio con retardo.
     */
    private void scheduleDelayedServiceStart(@NonNull Context context, @NonNull String action) {
        // Calcular retardo basado en la acción
        long delay = calculateStartDelay(action);
        
        Log.d(TAG, "Programando inicio del servicio con retardo de " + delay + "ms");
        
        android.os.Handler handler = new android.os.Handler();
        handler.postDelayed(() -> {
            Log.d(TAG, "Ejecutando inicio programado del servicio");
            ServiceStarter.startMainService(context);
        }, delay);
    }
    
    /**
     * Calcula el retardo apropiado basado en la acción.
     */
    private long calculateStartDelay(@NonNull String action) {
        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
                // Esperar más al arranque del sistema
                return 30000; // 30 segundos
                
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                // Actualización de la app, esperar menos
                return 5000; // 5 segundos
                
            default:
                // Otros casos
                return 10000; // 10 segundos
        }
    }
    
    /**
     * Método de utilidad para verificar estado del receiver.
     */
    public static boolean isBootReceiverEnabled(@NonNull Context context) {
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            android.content.ComponentName component = new android.content.ComponentName(
                context, BootReceiver.class);
            
            int status = pm.getComponentEnabledSetting(component);
            return status == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                   status == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            
        } catch (Exception e) {
            Log.e(TAG, "Error verificando estado del receiver", e);
            return false;
        }
    }
    
    /**
     * Método de utilidad para habilitar/deshabilitar el receiver.
     */
    public static void setBootReceiverEnabled(@NonNull Context context, boolean enabled) {
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            android.content.ComponentName component = new android.content.ComponentName(
                context, BootReceiver.class);
            
            int newState = enabled ? 
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            
            pm.setComponentEnabledSetting(component, newState,
                android.content.pm.PackageManager.DONT_KILL_APP);
            
            Log.i(TAG, "BootReceiver " + (enabled ? "habilitado" : "deshabilitado"));
            
        } catch (Exception e) {
            Log.e(TAG, "Error cambiando estado del receiver", e);
        }
    }
}
