package com.example.commandemulator;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IAController - Sistema avanzado de inteligencia artificial local
 * para el Emulador de Comandos.
 * 
 * Características:
 * - Análisis de comandos con NLP básico
 * - Sistema de aprendizaje y memoria contextual
 * - Sugerencias inteligentes de comandos
 * - Corrección automática de errores
 * - Análisis de patrones de uso
 * - Respuestas conversacionales contextuales
 * - Sistema de consejos y tips
 * - Detección de intenciones del usuario
 * - Gestión de contexto de sesión
 * - Análisis de métricas y estadísticas
 * - Sistema de plugins para funcionalidades adicionales
 */
public class IAController {
    
    private static final String TAG = "CommandEmulatorIA";
    private static final int MEMORY_CAPACITY = 1000;
    private static final int LEARNING_THRESHOLD = 3;
    
    // Tipos de intenciones detectadas
    public enum Intent {
        COMMAND_EXECUTION,
        COMMAND_HELP,
        SYSTEM_INFO,
        FILE_OPERATION,
        NETWORK_OPERATION,
        CONFIGURATION,
        TROUBLESHOOTING,
        CONVERSATION,
        UNKNOWN
    }
    
    // Niveles de confianza en análisis
    public enum Confidence {
        HIGH(0.8f),
        MEDIUM(0.5f),
        LOW(0.3f),
        VERY_LOW(0.1f);
        
        private final float threshold;
        
        Confidence(float threshold) {
            this.threshold = threshold;
        }
        
        public float getThreshold() {
            return threshold;
        }
    }
    
    // Resultado de análisis IA
    public static class AnalysisResult {
        private final Intent intent;
        private final Confidence confidence;
        private final String suggestion;
        private final String correction;
        private final List<String> alternatives;
        private final Map<String, Object> metadata;
        private final long timestamp;
        
        public AnalysisResult(Intent intent, Confidence confidence, 
                             String suggestion, String correction,
                             List<String> alternatives, Map<String, Object> metadata) {
            this.intent = intent;
            this.confidence = confidence;
            this.suggestion = suggestion;
            this.correction = correction;
            this.alternatives = alternatives != null ? alternatives : new ArrayList<>();
            this.metadata = metadata != null ? metadata : new HashMap<>();
            this.timestamp = System.currentTimeMillis();
        }
        
        // Getters
        public Intent getIntent() { return intent; }
        public Confidence getConfidence() { return confidence; }
        public String getSuggestion() { return suggestion; }
        public String getCorrection() { return correction; }
        public List<String> getAlternatives() { return new ArrayList<>(alternatives); }
        public Map<String, Object> getMetadata() { return new HashMap<>(metadata); }
        public long getTimestamp() { return timestamp; }
        
        public boolean hasSuggestion() {
            return suggestion != null && !suggestion.isEmpty();
        }
        
        public boolean hasCorrection() {
            return correction != null && !correction.isEmpty();
        }
        
        @NonNull
        @Override
        public String toString() {
            return String.format(
                "AnalysisResult{intent=%s, confidence=%s, suggestion=%s, correction=%s}",
                intent, confidence, 
                suggestion != null ? suggestion.substring(0, Math.min(20, suggestion.length())) : "null",
                correction != null ? correction.substring(0, Math.min(20, correction.length())) : "null"
            );
        }
    }
    
    // Memoria de contexto de sesión
    public static class SessionMemory {
        private final Map<String, Integer> commandFrequency;
        private final List<String> recentCommands;
        private final Map<String, List<String>> commandPatterns;
        private final Map<String, String> userPreferences;
        private final List<String> conversationHistory;
        private final Map<String, Object> sessionData;
        
        public SessionMemory() {
            this.commandFrequency = new ConcurrentHashMap<>();
            this.recentCommands = Collections.synchronizedList(new ArrayList<>());
            this.commandPatterns = new ConcurrentHashMap<>();
            this.userPreferences = new ConcurrentHashMap<>();
            this.conversationHistory = Collections.synchronizedList(new ArrayList<>());
            this.sessionData = new ConcurrentHashMap<>();
        }
        
        public void recordCommand(String command) {
            if (command == null || command.trim().isEmpty()) return;
            
            String normalized = normalizeCommand(command);
            
            // Actualizar frecuencia
            commandFrequency.put(normalized, 
                commandFrequency.getOrDefault(normalized, 0) + 1);
            
            // Mantener comandos recientes
            recentCommands.add(normalized);
            if (recentCommands.size() > 50) {
                recentCommands.remove(0);
            }
            
            // Detectar patrones (comandos frecuentemente usados en secuencia)
            if (recentCommands.size() >= 3) {
                String lastThree = String.join(" -> ", 
                    recentCommands.subList(Math.max(0, recentCommands.size() - 3), recentCommands.size()));
                List<String> patterns = commandPatterns.getOrDefault(lastThree, new ArrayList<>());
                patterns.add(normalized);
                commandPatterns.put(lastThree, patterns);
            }
        }
        
        public void recordConversation(String userInput, String aiResponse) {
            if (userInput != null) {
                conversationHistory.add("Usuario: " + userInput);
            }
            if (aiResponse != null) {
                conversationHistory.add("Asistente: " + aiResponse);
            }
            
            // Mantener límite de historial
            if (conversationHistory.size() > 100) {
                conversationHistory.subList(0, conversationHistory.size() - 100).clear();
            }
        }
        
        public void setPreference(String key, String value) {
            userPreferences.put(key, value);
        }
        
        public String getPreference(String key) {
            return userPreferences.get(key);
        }
        
        public void setSessionData(String key, Object value) {
            sessionData.put(key, value);
        }
        
        public Object getSessionData(String key) {
            return sessionData.get(key);
        }
        
        public int getCommandFrequency(String command) {
            return commandFrequency.getOrDefault(command, 0);
        }
        
        public List<String> getRecentCommands(int count) {
            int start = Math.max(0, recentCommands.size() - count);
            return new ArrayList<>(recentCommands.subList(start, recentCommands.size()));
        }
        
        public List<String> getCommandPatterns() {
            return new ArrayList<>(commandPatterns.keySet());
        }
        
        public List<String> getConversationHistory() {
            return new ArrayList<>(conversationHistory);
        }
        
        public Map<String, Integer> getTopCommands(int topN) {
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(commandFrequency.entrySet());
            entries.sort((a, b) -> b.getValue().compareTo(a.getValue()));
            
            Map<String, Integer> topCommands = new LinkedHashMap<>();
            for (int i = 0; i < Math.min(topN, entries.size()); i++) {
                topCommands.put(entries.get(i).getKey(), entries.get(i).getValue());
            }
            
            return topCommands;
        }
        
        private String normalizeCommand(String command) {
            return command.trim().toLowerCase(Locale.getDefault());
        }
    }
    
    // Plugin de IA para funcionalidades específicas
    public interface IAPlugin {
        String getName();
        boolean canHandle(String input, Intent intent);
        AnalysisResult analyze(String input, SessionMemory memory);
        float getConfidence(String input);
    }
    
    // Componentes principales
    private final MainApplication app;
    private final SessionMemory sessionMemory;
    private final ExecutorService analysisExecutor;
    private final List<IAPlugin> plugins;
    private final Random random;
    private final Pattern patternCache;
    
    // Estadísticas
    private int totalAnalyses;
    private int suggestionsProvided;
    private int correctionsProvided;
    
    public IAController(MainApplication application) {
        this.app = application;
        this.sessionMemory = new SessionMemory();
        this.analysisExecutor = Executors.newFixedThreadPool(2);
        this.plugins = new ArrayList<>();
        this.random = new Random();
        this.patternCache = Pattern.compile("[^a-zA-Z0-9\\s]");
        
        this.totalAnalyses = 0;
        this.suggestionsProvided = 0;
        this.correctionsProvided = 0;
        
        // Registrar plugins por defecto
        registerDefaultPlugins();
        
        Log.i(TAG, "IAController inicializado con " + plugins.size() + " plugins");
    }
    
    /**
     * Registra plugins por defecto.
     */
    private void registerDefaultPlugins() {
        plugins.add(new CommandCorrectionPlugin());
        plugins.add(new SuggestionPlugin());
        plugins.add(new ConversationPlugin());
        plugins.add(new SystemAnalysisPlugin());
        plugins.add(new LearningPlugin());
        plugins.add(new PatternRecognitionPlugin());
    }
    
    /**
     * Registra un plugin personalizado.
     */
    public void registerPlugin(IAPlugin plugin) {
        if (plugin != null && !plugins.contains(plugin)) {
            plugins.add(plugin);
            Log.d(TAG, "Plugin registrado: " + plugin.getName());
        }
    }
    
    /**
     * Procesa un comando de entrada con análisis IA.
     */
    @Nullable
    public AnalysisResult analyzeInput(@NonNull String input) {
        totalAnalyses++;
        
        try {
            // Detectar intención principal
            Intent intent = detectIntent(input);
            
            // Consultar plugins para análisis
            List<AnalysisResult> pluginResults = new ArrayList<>();
            for (IAPlugin plugin : plugins) {
                if (plugin.canHandle(input, intent)) {
                    AnalysisResult result = plugin.analyze(input, sessionMemory);
                    if (result != null) {
                        pluginResults.add(result);
                    }
                }
            }
            
            // Combinar resultados de plugins
            AnalysisResult combinedResult = combineResults(pluginResults, input, intent);
            
            // Actualizar memoria de sesión
            sessionMemory.recordCommand(input);
            
            // Registrar estadísticas
            if (combinedResult.hasSuggestion()) suggestionsProvided++;
            if (combinedResult.hasCorrection()) correctionsProvided++;
            
            Log.d(TAG, "Análisis completado: " + combinedResult);
            return combinedResult;
            
        } catch (Exception e) {
            Log.e(TAG, "Error en análisis IA: " + input, e);
            return null;
        }
    }
    
    /**
     * Procesa la salida de un comando con análisis IA.
     */
    @Nullable
    public String analyzeOutput(@NonNull String input, @Nullable String output) {
        if (output == null) return null;
        
        // Análisis simple de salida
        if (output.contains("Comando no encontrado") || 
            output.contains("command not found")) {
            return suggestAlternativeCommand(input);
        }
        
        if (output.contains("Permiso denegado") || 
            output.contains("permission denied")) {
            return "⚠️ Permisos insuficientes. ¿Necesitas usar otro usuario o directorio?";
        }
        
        if (output.contains("No such file or directory")) {
            return "📁 Archivo o directorio no encontrado. Verifica la ruta.";
        }
        
        if (output.toLowerCase().contains("error")) {
            return analyzeErrorPattern(output);
        }
        
        // Si la salida está vacía pero se esperaba algo
        if (output.trim().isEmpty() && isCommandExpectingOutput(input)) {
            return "💡 El comando se ejecutó, pero no produjo salida. ¿Esperabas ver algo?";
        }
        
        return null;
    }
    
    /**
     * Genera una respuesta conversacional inteligente.
     */
    @Nullable
    public String generateConversationalResponse(@NonNull String input) {
        // Registrar en historial de conversación
        sessionMemory.recordConversation(input, null);
        
        // Detectar saludo
        if (isGreeting(input)) {
            String[] greetings = {
                "¡Hola! 👋 ¿En qué puedo ayudarte hoy?",
                "¡Hola! Listo para ayudarte con la terminal.",
                "¡Buen día! ¿Qué comandos necesitas ejecutar?"
            };
            String response = greetings[random.nextInt(greetings.length)];
            sessionMemory.recordConversation(null, response);
            return response;
        }
        
        // Detectar despedida
        if (isFarewell(input)) {
            String[] farewells = {
                "¡Hasta luego! 👋 ¡Vuelve cuando quieras!",
                "Adiós. Recuerda que puedes usar 'history' para ver tus comandos anteriores.",
                "¡Nos vemos! La terminal estará aquí cuando regreses."
            };
            String response = farewells[random.nextInt(farewells)];
            sessionMemory.recordConversation(null, response);
            return response;
        }
        
        // Detectar pregunta sobre el sistema
        if (isSystemQuestion(input)) {
            String response = generateSystemInfoResponse();
            sessionMemory.recordConversation(null, response);
            return response;
        }
        
        // Detectar solicitud de ayuda
        if (isHelpRequest(input)) {
            String response = generateHelpResponse(input);
            sessionMemory.recordConversation(null, response);
            return response;
        }
        
        // Respuesta por defecto usando NLP básico
        String response = generateDefaultResponse(input);
        if (response != null) {
            sessionMemory.recordConversation(null, response);
        }
        
        return response;
    }
    
    /**
     * Proporciona sugerencias proactivas basadas en patrones de uso.
     */
    @Nullable
    public String provideProactiveSuggestion() {
        // Basado en comandos recientes
        List<String> recent = sessionMemory.getRecentCommands(5);
        if (recent.size() >= 3) {
            // Detectar si el usuario está explorando directorios
            long cdCount = recent.stream().filter(cmd -> cmd.startsWith("cd")).count();
            if (cdCount >= 2 && !recent.contains("ls") && !recent.contains("dir")) {
                return "💡 Parece que estás navegando por directorios. " +
                       "¿Quieres listar el contenido con 'ls' o 'dir'?";
            }
            
            // Detectar si el usuario está configurando variables
            long exportCount = recent.stream().filter(cmd -> cmd.startsWith("export")).count();
            if (exportCount >= 2 && !recent.contains("env") && !recent.contains("printenv")) {
                return "💡 Has estado configurando variables. " +
                       "Usa 'env' para ver todas las variables de entorno.";
            }
        }
        
        // Basado en hora del día (sugerencias contextuales)
        int hour = new Date().getHours();
        if (hour >= 8 && hour <= 12 && sessionMemory.getCommandFrequency("date") == 0) {
            return "☀️ Buenos días. ¿Quieres ver la hora actual? Prueba 'date'.";
        }
        
        return null;
    }
    
    /**
     * Obtiene estadísticas de uso de IA.
     */
    @NonNull
    public String getIAStatistics() {
        Map<String, Integer> topCommands = sessionMemory.getTopCommands(5);
        StringBuilder stats = new StringBuilder();
        
        stats.append("📊 Estadísticas de IA:\n");
        stats.append("─────────────────────\n");
        stats.append("Análisis realizados: ").append(totalAnalyses).append("\n");
        stats.append("Sugerencias dadas: ").append(suggestionsProvided).append("\n");
        stats.append("Correcciones: ").append(correctionsProvided).append("\n");
        stats.append("Comandos recientes: ").append(sessionMemory.getRecentCommands(5).size()).append("\n");
        stats.append("\n");
        stats.append("Comandos más usados:\n");
        
        for (Map.Entry<String, Integer> entry : topCommands.entrySet()) {
            stats.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append(" veces\n");
        }
        
        if (!sessionMemory.getCommandPatterns().isEmpty()) {
            stats.append("\nPatrones detectados: ").append(sessionMemory.getCommandPatterns().size());
        }
        
        return stats.toString();
    }
    
    /**
     * Obtiene consejos personalizados basados en patrones de uso.
     */
    @NonNull
    public String getPersonalizedTips() {
        List<String> tips = new ArrayList<>();
        Map<String, Integer> topCommands = sessionMemory.getTopCommands(3);
        
        // Consejo basado en comandos frecuentes
        for (String cmd : topCommands.keySet()) {
            if (cmd.startsWith("cd")) {
                tips.add("💡 Prueba 'pushd' y 'popd' para gestionar una pila de directorios.");
            } else if (cmd.startsWith("echo")) {
                tips.add("💡 Usa variables de entorno en echo: 'echo $PATH' muestra la variable PATH.");
            } else if (cmd.equals("pwd")) {
                tips.add("💡 Guarda rutas frecuentes como alias: 'alias proj=\"cd /path/to/project\"'.");
            }
        }
        
        // Consejos generales si no hay patrones específicos
        if (tips.isEmpty()) {
            tips.add("💡 Usa la tecla Tab para autocompletar comandos y rutas.");
            tips.add("💡 Prueba 'history' para ver todos los comandos ejecutados.");
            tips.add("💡 Usa 'clear' para limpiar la pantalla cuando esté muy llena.");
            tips.add("💡 Los comandos se pueden encadenar con '&&': 'cmd1 && cmd2'.");
        }
        
        // Seleccionar un consejo aleatorio
        return tips.get(random.nextInt(tips.size()));
    }
    
    // =====================
    // Métodos internos de análisis
    // =====================
    
    /**
     * Detecta la intención principal del usuario.
     */
    private Intent detectIntent(@NonNull String input) {
        String normalized = input.toLowerCase(Locale.getDefault());
        
        if (isConversational(normalized)) {
            return Intent.CONVERSATION;
        }
        
        if (normalized.contains("?") || 
            normalized.contains("cómo") || 
            normalized.contains("como") ||
            normalized.contains("qué es") ||
            normalized.contains("que es")) {
            return Intent.COMMAND_HELP;
        }
        
        if (normalized.startsWith("ls") || 
            normalized.startsWith("dir") ||
            normalized.contains("archivo") ||
            normalized.contains("carpeta")) {
            return Intent.FILE_OPERATION;
        }
        
        if (normalized.contains("ping") ||
            normalized.contains("red") ||
            normalized.contains("ip") ||
            normalized.contains("conexión") ||
            normalized.contains("conexion")) {
            return Intent.NETWORK_OPERATION;
        }
        
        if (normalized.contains("config") ||
            normalized.contains("ajust") ||
            normalized.contains("preferencia")) {
            return Intent.CONFIGURATION;
        }
        
        if (normalized.contains("error") ||
            normalized.contains("problema") ||
            normalized.contains("no funciona")) {
            return Intent.TROUBLESHOOTING;
        }
        
        if (normalized.contains("quién") ||
            normalized.contains("quien") ||
            normalized.contains("soy") ||
            normalized.contains("estado") ||
            normalized.contains("información") ||
            normalized.contains("informacion")) {
            return Intent.SYSTEM_INFO;
        }
        
        // Por defecto, asumir que es ejecución de comando
        return Intent.COMMAND_EXECUTION;
    }
    
    private boolean isConversational(@NonNull String input) {
        String[] conversationalKeywords = {
            "hola", "hello", "hi", "hey",
            "adiós", "adios", "bye", "chao",
            "gracias", "thanks", "thank you",
            "por favor", "please",
            "cómo estás", "como estas", "how are you",
            "qué tal", "que tal"
        };
        
        for (String keyword : conversationalKeywords) {
            if (input.contains(keyword)) {
                return true;
            }
        }
        
        return false;
    }
    
    private boolean isGreeting(@NonNull String input) {
        String[] greetings = {"hola", "hello", "hi", "hey", "buenos días", "buenas tardes"};
        String normalized = input.toLowerCase(Locale.getDefault());
        for (String greeting : greetings) {
            if (normalized.contains(greeting)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean isFarewell(@NonNull String input) {
        String[] farewells = {"adiós", "adios", "bye", "chao", "nos vemos", "hasta luego"};
        String normalized = input.toLowerCase(Locale.getDefault());
        for (String farewell : farewells) {
            if (normalized.contains(farewell)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean isSystemQuestion(@NonNull String input) {
        String[] systemKeywords = {"quién eres", "quien eres", "qué eres", "que eres", 
                                  "información del sistema", "informacion del sistema",
                                  "estado", "versión", "version"};
        String normalized = input.toLowerCase(Locale.getDefault());
        for (String keyword : systemKeywords) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean isHelpRequest(@NonNull String input) {
        String[] helpKeywords = {"ayuda", "help", "qué puedo hacer", "que puedo hacer",
                                "cómo usar", "como usar", "tutorial", "guía", "guia"};
        String normalized = input.toLowerCase(Locale.getDefault());
        for (String keyword : helpKeywords) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
    
    private boolean isCommandExpectingOutput(@NonNull String command) {
        String[] outputCommands = {"ls", "dir", "cat", "echo", "date", "pwd", "whoami", "env"};
        String firstWord = command.split("\\s+")[0].toLowerCase();
        for (String cmd : outputCommands) {
            if (firstWord.equals(cmd)) {
                return true;
            }
        }
        return false;
    }
    
    @NonNull
    private String suggestAlternativeCommand(@NonNull String input) {
        String command = input.split("\\s+")[0].toLowerCase();
        
        Map<String, String> alternatives = new HashMap<>();
        alternatives.put("ls", "dir (si estás en Windows) o 'echo *' para listar archivos");
        alternatives.put("dir", "ls (si estás en Unix/Linux)");
        alternatives.put("cat", "type (Windows) o 'echo' para mostrar contenido");
        alternatives.put("type", "cat (Unix/Linux)");
        alternatives.put("copy", "cp");
        alternatives.put("move", "mv");
        alternatives.put("del", "rm");
        alternatives.put("rmdir", "rm -r");
        
        if (alternatives.containsKey(command)) {
            return "💡 Comando '" + command + "' no encontrado. " +
                   "¿Quizás quisiste usar: " + alternatives.get(command) + "?";
        }
        
        // Búsqueda fonética aproximada
        for (String knownCmd : Arrays.asList("help", "cd", "pwd", "date", "echo", "exit")) {
            if (calculateStringSimilarity(command, knownCmd) > 0.7) {
                return "💡 ¿Te refieres al comando '" + knownCmd + "'?";
            }
        }
        
        return "💡 Comando desconocido. Escribe 'help' para ver comandos disponibles.";
    }
    
    @NonNull
    private String analyzeErrorPattern(@NonNull String error) {
        String normalized = error.toLowerCase(Locale.getDefault());
        
        if (normalized.contains("no space left")) {
            return "🚨 Espacio en disco insuficiente. Considera liberar espacio.";
        }
        
        if (normalized.contains("connection refused") || 
            normalized.contains("timeout")) {
            return "🌐 Problema de conexión. Verifica la red o el host destino.";
        }
        
        if (normalized.contains("syntax error") || 
            normalized.contains("invalid argument")) {
            return "🔧 Error de sintaxis. Revisa la documentación del comando.";
        }
        
        if (normalized.contains("file exists")) {
            return "📁 El archivo o directorio ya existe.";
        }
        
        if (normalized.contains("read-only") || 
            normalized.contains("permission")) {
            return "🔒 Problema de permisos. Verifica los permisos del archivo/directorio.";
        }
        
        return "⚠️ Se produjo un error. Revisa los detalles arriba.";
    }
    
    @NonNull
    private String generateSystemInfoResponse() {
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        String date = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(new Date());
        
        return String.format(
            "🖥️ Sistema: Command Emulator v1.0\n" +
            "📅 Fecha: %s\n" +
            "⏰ Hora: %s\n" +
            "👤 Usuario: %s\n" +
            "📁 Directorio: %s\n" +
            "💡 Comandos hoy: %d",
            date, time,
            app.getSessionRepository().getCurrentUser(),
            app.getSessionRepository().getCurrentDirectory(),
            sessionMemory.getRecentCommands(100).size()
        );
    }
    
    @NonNull
    private String generateHelpResponse(@NonNull String input) {
        if (input.contains("comando") || input.contains("cmd")) {
            return "📚 Para ver todos los comandos disponibles, escribe 'help'.\n" +
                   "Para ayuda específica de un comando, prueba 'help <comando>'.";
        }
        
        if (input.contains("red") || input.contains("network")) {
            return "🌐 Comandos de red disponibles:\n" +
                   "  • ping <host> - Prueba conectividad\n" +
                   "  • netinfo - Información de red\n" +
                   "  • resolve <host> - Resolución DNS";
        }
        
        if (input.contains("archivo") || input.contains("file")) {
            return "📁 Comandos de archivos disponibles:\n" +
                   "  • pwd - Directorio actual\n" +
                   "  • cd <dir> - Cambiar directorio\n" +
                   "  • echo <texto> - Mostrar texto";
        }
        
        return "💡 Escribe 'help' para ver la lista completa de comandos.\n" +
               "También puedes usar 'man <comando>' para ayuda detallada (próximamente).";
    }
    
    @Nullable
    private String generateDefaultResponse(@NonNull String input) {
        String normalized = input.toLowerCase(Locale.getDefault());
        
        // Respuestas a preguntas comunes
        if (normalized.contains("qué hora es") || normalized.contains("que hora es")) {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            return "⏰ Son las " + time;
        }
        
        if (normalized.contains("qué día es") || normalized.contains("que dia es")) {
            String date = new SimpleDateFormat("EEEE, dd 'de' MMMM 'de' yyyy", Locale.getDefault()).format(new Date());
            return "📅 Hoy es " + date;
        }
        
        if (normalized.contains("dónde estoy") || normalized.contains("donde estoy")) {
            return "📁 Estás en: " + app.getSessionRepository().getCurrentDirectory();
        }
        
        if (normalized.contains("quién soy") || normalized.contains("quien soy")) {
            return "👤 Eres: " + app.getSessionRepository().getCurrentUser();
        }
        
        // Respuesta generativa básica
        if (normalized.contains("por qué") || normalized.contains("porque")) {
            return "🤔 Esa es una buena pregunta. ¿Has probado a consultar la documentación?";
        }
        
        if (normalized.endsWith("?")) {
            return "❓ Parece una pregunta. ¿Necesitas ayuda con algún comando específico?";
        }
        
        return null;
    }
    
    @NonNull
    private AnalysisResult combineResults(@NonNull List<AnalysisResult> pluginResults,
                                         @NonNull String input, 
                                         @NonNull Intent intent) {
        String suggestion = null;
        String correction = null;
        List<String> alternatives = new ArrayList<>();
        Map<String, Object> metadata = new HashMap<>();
        Confidence confidence = Confidence.VERY_LOW;
        
        for (AnalysisResult result : pluginResults) {
            if (result.getSuggestion() != null && suggestion == null) {
                suggestion = result.getSuggestion();
            }
            
            if (result.getCorrection() != null && correction == null) {
                correction = result.getCorrection();
            }
            
            alternatives.addAll(result.getAlternatives());
            
            if (result.getConfidence().getThreshold() > confidence.getThreshold()) {
                confidence = result.getConfidence();
            }
            
            metadata.putAll(result.getMetadata());
        }
        
        // Si no hay sugerencias específicas, agregar una genérica si es necesario
        if (suggestion == null && intent == Intent.COMMAND_EXECUTION) {
            suggestion = provideProactiveSuggestion();
        }
        
        return new AnalysisResult(intent, confidence, suggestion, correction, 
                                 alternatives, metadata);
    }
    
    private float calculateStringSimilarity(@NonNull String s1, @NonNull String s2) {
        // Implementación simple de similitud de cadenas (distancia de Levenshtein simplificada)
        if (s1.equals(s2)) return 1.0f;
        
        int maxLength = Math.max(s1.length(), s2.length());
        if (maxLength == 0) return 1.0f;
        
        int distance = 0;
        for (int i = 0; i < Math.min(s1.length(), s2.length()); i++) {
            if (s1.charAt(i) != s2.charAt(i)) {
                distance++;
            }
        }
        distance += Math.abs(s1.length() - s2.length());
        
        return 1.0f - ((float) distance / maxLength);
    }
    
    // =====================
    // Plugins de IA
    // =====================
    
    /**
     * Plugin para corrección de comandos.
     */
    private class CommandCorrectionPlugin implements IAPlugin {
        
        @Override
        public String getName() {
            return "CommandCorrectionPlugin";
        }
        
        @Override
        public boolean canHandle(String input, Intent intent) {
            return intent == Intent.COMMAND_EXECUTION || 
                   intent == Intent.TROUBLESHOOTING;
        }
        
        @Override
        public AnalysisResult analyze(String input, SessionMemory memory) {
            String[] parts = input.split("\\s+");
            if (parts.length == 0) return null;
            
            String command = parts[0].toLowerCase();
            String correction = null;
            
            // Correcciones comunes
            Map<String, String> corrections = new HashMap<>();
            corrections.put("cleear", "clear");
            corrections.put("clr", "clear");
            corrections.put("ext", "exit");
            corrections.put("exti", "exit");
            corrections.put("hel", "help");
            corrections.put("halp", "help");
            corrections.put("dat", "date");
            corrections.put("pawd", "pwd");
            corrections.put("woami", "whoami");
            corrections.put("ech", "echo");
            corrections.put("exho", "echo");
            
            if (corrections.containsKey(command)) {
                correction = corrections.get(command);
            } else {
                // Corrección fonética
                correction = suggestPhoneticCorrection(command);
            }
            
            if (correction != null) {
                return new AnalysisResult(
                    Intent.COMMAND_EXECUTION,
                    Confidence.MEDIUM,
                    "💡 ¿Quizás quisiste decir '" + correction + "'?",
                    correction,
                    Collections.singletonList(correction),
                    null
                );
            }
            
            return null;
        }
        
        @Override
        public float getConfidence(String input) {
            // Alta confianza para comandos muy cortos o con errores obvios
            if (input.length() <= 3) return 0.7f;
            
            // Baja confianza para comandos largos
            return 0.3f;
        }
        
        private String suggestPhoneticCorrection(String command) {
            String[] commonCommands = {"help", "clear", "exit", "cd", "pwd", 
                                      "date", "echo", "whoami", "env", "export"};
            
            for (String commonCmd : commonCommands) {
                if (calculateStringSimilarity(command, commonCmd) > 0.6) {
                    return commonCmd;
                }
            }
            
            return null;
        }
    }
    
    /**
     * Plugin para sugerencias.
     */
    private class SuggestionPlugin implements IAPlugin {
        
        @Override
        public String getName() {
            return "SuggestionPlugin";
        }
        
        @Override
        public boolean canHandle(String input, Intent intent) {
            return true; // Maneja todas las intenciones
        }
        
        @Override
        public AnalysisResult analyze(String input, SessionMemory memory) {
            String suggestion = null;
            List<String> alternatives = new ArrayList<>();
            
            // Sugerencias basadas en el comando
            if (input.startsWith("cd") && input.split(" ").length < 2) {
                suggestion = "💡 Usa 'cd <directorio>' para cambiar de carpeta. " +
                            "Prueba 'cd ..' para subir un nivel.";
                alternatives.add("cd ..");
                alternatives.add("cd ~");
            } else if (input.equals("sudo") || input.equals("su")) {
                suggestion = "🔒 Permisos elevados no disponibles en modo demostración. " +
                            "Usa 'whoami' para ver tu usuario actual.";
            } else if (input.equals("ls") || input.equals("dir")) {
                suggestion = "📁 Comando de listado de archivos no implementado completamente. " +
                            "Prueba 'echo *' para ver archivos en el directorio actual.";
                alternatives.add("echo *");
            } else if (input.contains(">>") || input.contains(">")) {
                suggestion = "💾 Redirección detectada. " +
                            "Usa '> archivo' para sobrescribir o '>> archivo' para añadir.";
            }
            
            if (suggestion != null) {
                return new AnalysisResult(
                    Intent.COMMAND_EXECUTION,
                    Confidence.HIGH,
                    suggestion,
                    null,
                    alternatives,
                    null
                );
            }
            
            return null;
        }
        
        @Override
        public float getConfidence(String input) {
            // Comandos específicos tienen alta confianza
            if (input.equals("sudo") || input.equals("ls") || input.equals("dir")) {
                return 0.9f;
            }
            
            // Comandos incompletos tienen media confianza
            if (input.startsWith("cd") && input.split(" ").length < 2) {
                return 0.7f;
            }
            
            return 0.3f;
        }
    }
    
    /**
     * Plugin para conversación.
     */
    private class ConversationPlugin implements IAPlugin {
        
        @Override
        public String getName() {
            return "ConversationPlugin";
        }
        
        @Override
        public boolean canHandle(String input, Intent intent) {
            return intent == Intent.CONVERSATION;
        }
        
        @Override
        public AnalysisResult analyze(String input, SessionMemory memory) {
            String response = generateConversationalResponse(input);
            
            if (response != null) {
                return new AnalysisResult(
                    Intent.CONVERSATION,
                    Confidence.HIGH,
                    response,
                    null,
                    null,
                    Collections.singletonMap("isConversation", true)
                );
            }
            
            return null;
        }
        
        @Override
        public float getConfidence(String input) {
            return isConversational(input.toLowerCase(Locale.getDefault())) ? 0.8f : 0.1f;
        }
    }
    
    /**
     * Plugin para análisis del sistema.
     */
    private class SystemAnalysisPlugin implements IAPlugin {
        
        @Override
        public String getName() {
            return "SystemAnalysisPlugin";
        }
        
        @Override
        public boolean canHandle(String input, Intent intent) {
            return intent == Intent.SYSTEM_INFO;
        }
        
        @Override
        public AnalysisResult analyze(String input, SessionMemory memory) {
            String suggestion = generateSystemInfoResponse();
            
            return new AnalysisResult(
                Intent.SYSTEM_INFO,
                Confidence.HIGH,
                suggestion,
                null,
                null,
                null
            );
        }
        
        @Override
        public float getConfidence(String input) {
            return isSystemQuestion(input) ? 0.9f : 0.2f;
        }
    }
    
    /**
     * Plugin de aprendizaje.
     */
    private class LearningPlugin implements IAPlugin {
        
        @Override
        public String getName() {
            return "LearningPlugin";
        }
        
        @Override
        public boolean canHandle(String input, Intent intent) {
            // Aprende de todos los comandos
            return intent == Intent.COMMAND_EXECUTION;
        }
        
        @Override
        public AnalysisResult analyze(String input, SessionMemory memory) {
            // Este plugin no genera sugerencias, solo aprende
            // El aprendizaje ya se hace en SessionMemory
            return null;
        }
        
        @Override
        public float getConfidence(String input) {
            return 0.1f; // Baja confianza para no interferir
        }
    }
    
    /**
     * Plugin de reconocimiento de patrones.
     */
    private class PatternRecognitionPlugin implements IAPlugin {
        
        @Override
        public String getName() {
            return "PatternRecognitionPlugin";
        }
        
        @Override
        public boolean canHandle(String input, Intent intent) {
            return intent == Intent.COMMAND_EXECUTION;
        }
        
        @Override
        public AnalysisResult analyze(String input, SessionMemory memory) {
            List<String> recent = memory.getRecentCommands(5);
            String suggestion = null;
            
            // Detectar patrones específicos
            if (recent.size() >= 3) {
                // Patrón: cd -> pwd -> cd (navegación)
                if (recent.get(0).startsWith("cd") && 
                    recent.get(1).equals("pwd") && 
                    recent.get(2).startsWith("cd")) {
                    suggestion = "💡 Parece que estás explorando directorios. " +
                                "Prueba 'pushd' para guardar directorios en una pila.";
                }
                
                // Patrón: echo -> echo -> echo (pruebas)
                if (recent.get(0).startsWith("echo") && 
                    recent.get(1).startsWith("echo") && 
                    recent.get(2).startsWith("echo")) {
                    suggestion = "💡 ¿Estás probando comandos? " +
                                "Recuerda que puedes usar variables: 'echo $USER'.";
                }
            }
            
            if (suggestion != null) {
                return new AnalysisResult(
                    Intent.COMMAND_EXECUTION,
                    Confidence.MEDIUM,
                    suggestion,
                    null,
                    null,
                    null
                );
            }
            
            return null;
        }
        
        @Override
        public float getConfidence(String input) {
            // Mayor confianza después de varios comandos
            if (sessionMemory.getRecentCommands(10).size() >= 5) {
                return 0.6f;
            }
            return 0.2f;
        }
    }
    
    // =====================
    // Métodos públicos simplificados para compatibilidad
    // =====================
    
    /**
     * Método simplificado para pre-procesamiento (compatibilidad).
     */
    @Nullable
    public String preProcess(String input) {
        AnalysisResult result = analyzeInput(input);
        return result != null && result.hasSuggestion() ? result.getSuggestion() : null;
    }
    
    /**
     * Método simplificado para post-procesamiento (compatibilidad).
     */
    @Nullable
    public String postProcess(String input, String output) {
        return analyzeOutput(input, output);
    }
    
    /**
     * Método simplificado para respuesta conversacional (compatibilidad).
     */
    @Nullable
    public String respond(String input) {
        return generateConversationalResponse(input);
    }
    
    /**
     * Limpia recursos.
     */
    public void shutdown() {
        analysisExecutor.shutdown();
        try {
            if (!analysisExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                analysisExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            analysisExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        Log.i(TAG, "IAController apagado");
    }
}
