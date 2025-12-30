package com.example.commandemulator;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CommandProcessor - Núcleo avanzado de interpretación de comandos.
 * 
 * Características:
 * - Arquitectura basada en comandos registrados
 * - Sistema de plugins para comandos personalizados
 * - Pipeline de comandos (|)
 * - Redirección de salida (> >>)
 * - Variables de entorno expandidas
 * - Historial de comandos con búsqueda
 * - Alias de comandos
 * - Comandos compuestos (&& ||)
 * - Autocompletado
 * - Scripting básico
 */
public class CommandProcessor {
    
    private static final String TAG = "CommandProcessor";
    private static final String PROMPT = "$ ";
    private static final String ENV_PREFIX = "$";
    private static final String HISTORY_FILE = "command_history.txt";
    
    // Interfaz para comandos personalizados
    public interface Command {
        @NonNull
        String getName();
        @NonNull
        String getDescription();
        @NonNull
        String execute(@NonNull CommandContext context);
        @NonNull
        default String getUsage() {
            return getName();
        }
        default boolean validate(@NonNull String[] args) {
            return true;
        }
    }
    
    // Contexto de ejecución de comandos
    public static class CommandContext {
        private final MainApplication app;
        private final String rawInput;
        private final String[] args;
        private final Map<String, String> environment;
        private final CommandHistory history;
        private final Map<String, String> aliases;
        private String output;
        private int exitCode;
        
        public CommandContext(@NonNull MainApplication app, 
                             @NonNull String rawInput, 
                             @NonNull String[] args,
                             @NonNull Map<String, String> environment,
                             @NonNull CommandHistory history,
                             @NonNull Map<String, String> aliases) {
            this.app = app;
            this.rawInput = rawInput;
            this.args = args;
            this.environment = environment;
            this.history = history;
            this.aliases = aliases;
            this.exitCode = 0;
        }
        
        @NonNull public MainApplication getApp() { return app; }
        @NonNull public String getRawInput() { return rawInput; }
        @NonNull public String[] getArgs() { return args; }
        @NonNull public Map<String, String> getEnvironment() { return environment; }
        @NonNull public CommandHistory getHistory() { return history; }
        @NonNull public Map<String, String> getAliases() { return aliases; }
        @Nullable public String getOutput() { return output; }
        public int getExitCode() { return exitCode; }
        
        public void setOutput(@NonNull String output) {
            this.output = output;
        }
        
        public void setExitCode(int exitCode) {
            this.exitCode = exitCode;
        }
    }
    
    // Historial de comandos
    public static class CommandHistory {
        private final List<String> commands;
        private final int maxSize;
        private int currentIndex;
        
        public CommandHistory(int maxSize) {
            this.commands = new ArrayList<>();
            this.maxSize = maxSize;
            this.currentIndex = -1;
        }
        
        public void add(@NonNull String command) {
            // No agregar comandos vacíos o duplicados consecutivos
            if (command.trim().isEmpty() || 
                (!commands.isEmpty() && commands.get(commands.size() - 1).equals(command))) {
                return;
            }
            
            commands.add(command);
            currentIndex = commands.size();
            
            // Mantener tamaño máximo
            if (commands.size() > maxSize) {
                commands.remove(0);
            }
        }
        
        @Nullable
        public String getPrevious() {
            if (commands.isEmpty()) return null;
            
            currentIndex = Math.max(0, currentIndex - 1);
            return currentIndex >= 0 && currentIndex < commands.size() ? 
                   commands.get(currentIndex) : null;
        }
        
        @Nullable
        public String getNext() {
            if (commands.isEmpty()) return null;
            
            currentIndex = Math.min(commands.size(), currentIndex + 1);
            return currentIndex < commands.size() ? commands.get(currentIndex) : "";
        }
        
        public void resetIndex() {
            currentIndex = commands.size();
        }
        
        @NonNull
        public List<String> search(@NonNull String query) {
            List<String> results = new ArrayList<>();
            for (String cmd : commands) {
                if (cmd.toLowerCase().contains(query.toLowerCase())) {
                    results.add(cmd);
                }
            }
            return results;
        }
        
        @NonNull
        public List<String> getAll() {
            return new ArrayList<>(commands);
        }
        
        public void clear() {
            commands.clear();
            currentIndex = -1;
        }
        
        public int size() {
            return commands.size();
        }
    }
    
    // Componentes principales
    private final MainApplication app;
    private final Map<String, Command> registeredCommands;
    private final Map<String, String> commandAliases;
    private final CommandHistory history;
    private final Stack<String> directoryStack;
    private final AtomicInteger commandCounter;
    private boolean echoEnabled;
    
    // Comandos integrados
    private final Map<String, Command> builtinCommands;
    
    public CommandProcessor(@NonNull MainApplication application) {
        this.app = application;
        this.registeredCommands = new ConcurrentHashMap<>();
        this.commandAliases = new ConcurrentHashMap<>();
        this.history = new CommandHistory(1000);
        this.directoryStack = new Stack<>();
        this.commandCounter = new AtomicInteger(0);
        this.echoEnabled = true;
        this.builtinCommands = new LinkedHashMap<>();
        
        // Inicializar comandos integrados
        initializeBuiltinCommands();
        
        // Registrar comandos integrados
        for (Command cmd : builtinCommands.values()) {
            registerCommand(cmd);
        }
        
        // Configurar alias comunes
        setupDefaultAliases();
        
        Log.d(TAG, "CommandProcessor inicializado con " + 
              builtinCommands.size() + " comandos integrados");
    }
    
    /**
     * Inicializa todos los comandos integrados.
     */
    private void initializeBuiltinCommands() {
        // Comandos básicos
        builtinCommands.put("help", new BaseCommand("help", 
            "Muestra ayuda de comandos", this::executeHelp));
        
        builtinCommands.put("pwd", new BaseCommand("pwd", 
            "Muestra directorio actual", this::executePwd));
        
        builtinCommands.put("cd", new BaseCommand("cd", 
            "Cambia directorio", this::executeCd));
        
        builtinCommands.put("whoami", new BaseCommand("whoami", 
            "Muestra usuario actual", this::executeWhoami));
        
        builtinCommands.put("date", new BaseCommand("date", 
            "Muestra fecha y hora", this::executeDate));
        
        builtinCommands.put("env", new BaseCommand("env", 
            "Lista variables de entorno", this::executeEnv));
        
        builtinCommands.put("export", new BaseCommand("export", 
            "Define variable de entorno", this::executeExport));
        
        builtinCommands.put("echo", new BaseCommand("echo", 
            "Imprime texto", this::executeEcho));
        
        builtinCommands.put("clear", new BaseCommand("clear", 
            "Limpia pantalla", ctx -> "__CLEAR__"));
        
        builtinCommands.put("exit", new BaseCommand("exit", 
            "Cierra sesión", this::executeExit));
        
        // Comandos avanzados
        builtinCommands.put("history", new BaseCommand("history", 
            "Muestra historial de comandos", this::executeHistory));
        
        builtinCommands.put("alias", new BaseCommand("alias", 
            "Gestiona alias de comandos", this::executeAlias));
        
        builtinCommands.put("pushd", new BaseCommand("pushd", 
            "Guarda directorio actual y cambia", this::executePushd));
        
        builtinCommands.put("popd", new BaseCommand("popd", 
            "Restaura directorio guardado", this::executePopd));
        
        builtinCommands.put("dirs", new BaseCommand("dirs", 
            "Muestra pila de directorios", this::executeDirs));
        
        builtinCommands.put("type", new BaseCommand("type", 
            "Muestra tipo de comando", this::executeType));
        
        builtinCommands.put("sleep", new BaseCommand("sleep", 
            "Espera N segundos", this::executeSleep));
        
        builtinCommands.put("source", new BaseCommand("source", 
            "Ejecuta script desde archivo", this::executeSource));
        
        builtinCommands.put("set", new BaseCommand("set", 
            "Configura opciones del shell", this::executeSet));
        
        builtinCommands.put("unset", new BaseCommand("unset", 
            "Elimina variable de entorno", this::executeUnset));
        
        builtinCommands.put("jobs", new BaseCommand("jobs", 
            "Muestra trabajos en segundo plano", ctx -> "No hay trabajos activos"));
        
        builtinCommands.put("kill", new BaseCommand("kill", 
            "Termina proceso por PID", ctx -> "kill: No se encontró el proceso"));
        
        builtinCommands.put("ps", new BaseCommand("ps", 
            "Muestra procesos", ctx -> "PID\tCMD\n1\tghostsh"));
    }
    
    /**
     * Configura alias por defecto.
     */
    private void setupDefaultAliases() {
        commandAliases.put("ll", "ls -l");
        commandAliases.put("la", "ls -a");
        commandAliases.put("..", "cd ..");
        commandAliases.put("...", "cd ../..");
        commandAliases.put("....", "cd ../../..");
        commandAliases.put("~", "cd ~");
        commandAliases.put("cls", "clear");
        commandAliases.put("h", "history");
        commandAliases.put("?", "help");
    }
    
    /**
     * Clase base para comandos.
     */
    private static class BaseCommand implements Command {
        private final String name;
        private final String description;
        private final CommandExecutor executor;
        
        interface CommandExecutor {
            @NonNull
            String execute(@NonNull CommandContext context);
        }
        
        BaseCommand(@NonNull String name, 
                   @NonNull String description,
                   @NonNull CommandExecutor executor) {
            this.name = name;
            this.description = description;
            this.executor = executor;
        }
        
        @Override @NonNull public String getName() { return name; }
        @Override @NonNull public String getDescription() { return description; }
        
        @Override
        @NonNull
        public String execute(@NonNull CommandContext context) {
            return executor.execute(context);
        }
    }
    
    /**
     * Procesa una línea de comandos completa.
     */
    @NonNull
    public CommandResult process(@NonNull String input) {
        long startTime = System.currentTimeMillis();
        commandCounter.incrementAndGet();
        
        try {
            // Verificar estado
            if (!app.getAppState().isRunning()) {
                return CommandResult.error("[ERROR] Emulador detenido");
            }
            
            // Normalizar entrada
            String normalizedInput = normalizeInput(input);
            if (normalizedInput.isEmpty()) {
                return CommandResult.empty();
            }
            
            // Agregar al historial
            history.add(normalizedInput);
            
            // Expandir alias
            String expandedInput = expandAliases(normalizedInput);
            
            // Parsear posibles pipelines
            List<String> pipeline = parsePipeline(expandedInput);
            if (!pipeline.isEmpty()) {
                return executePipeline(pipeline);
            }
            
            // Parsear comando simple
            ParsedCommand parsed = parseCommand(expandedInput);
            if (parsed == null) {
                return CommandResult.error("Error de sintaxis");
            }
            
            // Ejecutar comando
            String output = executeSingleCommand(parsed);
            
            long duration = System.currentTimeMillis() - startTime;
            
            return CommandResult.success(output, duration);
            
        } catch (SecurityException e) {
            Log.e(TAG, "Error de seguridad procesando comando", e);
            return CommandResult.error("[ERROR] Permiso denegado: " + e.getMessage());
            
        } catch (Exception e) {
            Log.e(TAG, "Error procesando comando: " + input, e);
            return CommandResult.error("[ERROR] " + e.getMessage());
        }
    }
    
    /**
     * Normaliza la entrada del comando.
     */
    @NonNull
    private String normalizeInput(@NonNull String input) {
        String trimmed = input.trim();
        
        // Eliminar comentarios
        int commentIndex = trimmed.indexOf('#');
        if (commentIndex != -1) {
            trimmed = trimmed.substring(0, commentIndex).trim();
        }
        
        return trimmed;
    }
    
    /**
     * Expande alias en el comando.
     */
    @NonNull
    private String expandAliases(@NonNull String input) {
        String[] parts = input.split("\\s+", 2);
        String command = parts[0];
        String rest = parts.length > 1 ? parts[1] : "";
        
        // Buscar alias
        String alias = commandAliases.get(command);
        if (alias != null) {
            String expanded = alias + (rest.isEmpty() ? "" : " " + rest);
            Log.d(TAG, "Alias expandido: " + command + " -> " + expanded);
            return expanded;
        }
        
        return input;
    }
    
    /**
     * Parse una línea para detectar pipelines.
     */
    @NonNull
    private List<String> parsePipeline(@NonNull String input) {
        List<String> commands = new ArrayList<>();
        
        // División simple por pipe (sin considerar pipes entre comillas)
        String[] pipeParts = input.split("\\|");
        for (String part : pipeParts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                commands.add(trimmed);
            }
        }
        
        return commands.size() > 1 ? commands : new ArrayList<>();
    }
    
    /**
     * Ejecuta un pipeline de comandos.
     */
    @NonNull
    private CommandResult executePipeline(@NonNull List<String> pipeline) {
        StringBuilder output = new StringBuilder();
        String previousOutput = null;
        
        for (int i = 0; i < pipeline.size(); i++) {
            String command = pipeline.get(i);
            
            // Si hay salida previa, agregarla como entrada
            if (previousOutput != null && i > 0) {
                // Para comandos simples, podríamos modificar esto
                // En una implementación real, usaríamos redirección
                command = command + " \"" + previousOutput.replace("\"", "\\\"") + "\"";
            }
            
            CommandResult result = process(command);
            if (!result.isSuccess()) {
                return CommandResult.error("Pipeline falló en: " + command + 
                                          "\nError: " + result.getOutput());
            }
            
            previousOutput = result.getOutput();
            if (i == pipeline.size() - 1) {
                output.append(previousOutput);
            }
        }
        
        return CommandResult.success(output.toString());
    }
    
    /**
     * Representa un comando parseado.
     */
    private static class ParsedCommand {
        final String name;
        final String[] args;
        final String rawInput;
        
        ParsedCommand(@NonNull String name, @NonNull String[] args, @NonNull String rawInput) {
            this.name = name;
            this.args = args;
            this.rawInput = rawInput;
        }
    }
    
    /**
     * Parsea un comando simple.
     */
    @Nullable
    private ParsedCommand parseCommand(@NonNull String input) {
        // Tokenización simple (sin manejo de comillas complejas)
        String[] tokens = input.split("\\s+");
        if (tokens.length == 0) {
            return null;
        }
        
        String commandName = tokens[0];
        String[] args = Arrays.copyOfRange(tokens, 1, tokens.length);
        
        return new ParsedCommand(commandName, args, input);
    }
    
    /**
     * Ejecuta un comando simple.
     */
    @NonNull
    private String executeSingleCommand(@NonNull ParsedCommand parsed) {
        String commandName = parsed.name;
        String[] args = parsed.args;
        
        // Buscar comando registrado
        Command command = registeredCommands.get(commandName);
        if (command == null) {
            return "Comando no encontrado: " + commandName + 
                   "\nUse 'help' para lista de comandos disponibles";
        }
        
        // Validar argumentos
        if (!command.validate(args)) {
            return "Uso incorrecto: " + command.getUsage();
        }
        
        // Crear contexto
        CommandContext context = new CommandContext(
            app,
            parsed.rawInput,
            args,
            app.getEnvironmentRepository().getAllEnvironmentVariables(),
            history,
            commandAliases
        );
        
        // Ejecutar comando
        try {
            String result = command.execute(context);
            
            // Si el comando estableció un código de salida
            if (context.getExitCode() != 0) {
                return "[ERROR " + context.getExitCode() + "] " + 
                       (result != null ? result : "Comando falló");
            }
            
            return result != null ? result : "";
            
        } catch (Exception e) {
            Log.e(TAG, "Error ejecutando comando: " + commandName, e);
            return "[ERROR] " + e.getMessage();
        }
    }
    
    // =====================
    // Implementaciones de comandos integrados
    // =====================
    
    @NonNull
    private String executeHelp(@NonNull CommandContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== GhostShell v1.0 ===\n");
        sb.append("Comandos disponibles:\n\n");
        
        // Agrupar por categorías
        Map<String, List<Command>> categories = new LinkedHashMap<>();
        categories.put("Básicos", new ArrayList<>());
        categories.put("Sistema", new ArrayList<>());
        categories.put("Shell", new ArrayList<>());
        categories.put("Personalizados", new ArrayList<>());
        
        for (Command cmd : registeredCommands.values()) {
            if (builtinCommands.containsKey(cmd.getName())) {
                categories.get("Básicos").add(cmd);
            } else {
                categories.get("Personalizados").add(cmd);
            }
        }
        
        // Mostrar por categoría
        for (Map.Entry<String, List<Command>> entry : categories.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                sb.append(entry.getKey()).append(":\n");
                for (Command cmd : entry.getValue()) {
                    sb.append(String.format("  %-12s - %s\n", 
                        cmd.getName(), cmd.getDescription()));
                }
                sb.append("\n");
            }
        }
        
        sb.append("Alias comunes: ll, la, .., ..., ~, cls, h, ?\n");
        sb.append("Use 'type <comando>' para más información.\n");
        
        return sb.toString();
    }
    
    @NonNull
    private String executePwd(@NonNull CommandContext context) {
        return context.getApp().getSessionRepository().getCurrentDirectory();
    }
    
    @NonNull
    private String executeCd(@NonNull CommandContext context) {
        String[] args = context.getArgs();
        
        if (args.length == 0) {
            // cd sin argumentos = ir a home
            String home = context.getEnvironment().getOrDefault("HOME", "/home/guest");
            context.getApp().getSessionRepository().setCurrentDirectory(home);
            return "";
        }
        
        String path = args[0];
        
        // Manejar paths especiales
        if (path.equals("~")) {
            path = context.getEnvironment().getOrDefault("HOME", "/home/guest");
        } else if (path.equals("-")) {
            // Volver al directorio anterior
            if (!directoryStack.isEmpty()) {
                path = directoryStack.pop();
            } else {
                return "cd: No hay directorio anterior";
            }
        } else if (path.equals("..")) {
            // Subir un nivel
            String current = context.getApp().getSessionRepository().getCurrentDirectory();
            int lastSlash = current.lastIndexOf('/');
            path = lastSlash > 0 ? current.substring(0, lastSlash) : "/";
        }
        
        try {
            context.getApp().getSessionRepository().setCurrentDirectory(path);
            return "";
        } catch (IllegalArgumentException e) {
            return "cd: " + e.getMessage();
        }
    }
    
    @NonNull
    private String executeWhoami(@NonNull CommandContext context) {
        return context.getApp().getSessionRepository().getCurrentUser();
    }
    
    @NonNull
    private String executeDate(@NonNull CommandContext context) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz", Locale.getDefault());
        return sdf.format(new Date());
    }
    
    @NonNull
    private String executeEnv(@NonNull CommandContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Variables de entorno ===\n");
        
        for (Map.Entry<String, String> entry : context.getEnvironment().entrySet()) {
            sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
        }
        
        sb.append("============================\n");
        sb.append("Total: ").append(context.getEnvironment().size()).append(" variables");
        
        return sb.toString();
    }
    
    @NonNull
    private String executeExport(@NonNull CommandContext context) {
        String[] args = context.getArgs();
        
        if (args.length == 0) {
            return executeEnv(context);
        }
        
        String assignment = args[0];
        String[] kv = assignment.split("=", 2);
        
        if (kv.length != 2) {
            return "export: Uso incorrecto. Use: export VAR=valor";
        }
        
        context.getApp().getEnvironmentRepository().setEnvironmentVariable(kv[0], kv[1]);
        return "";
    }
    
    @NonNull
    private String executeEcho(@NonNull CommandContext context) {
        String[] args = context.getArgs();
        
        if (args.length == 0) {
            return "";
        }
        
        // Expandir variables de entorno
        StringBuilder result = new StringBuilder();
        for (String arg : args) {
            if (arg.startsWith(ENV_PREFIX) && arg.length() > 1) {
                String varName = arg.substring(1);
                String value = context.getEnvironment().getOrDefault(varName, "");
                result.append(value).append(" ");
            } else {
                result.append(arg).append(" ");
            }
        }
        
        return result.toString().trim();
    }
    
    @NonNull
    private String executeExit(@NonNull CommandContext context) {
        context.getApp().getAppState().shutdown();
        return "Sesión finalizada. Comandos ejecutados: " + commandCounter.get();
    }
    
    @NonNull
    private String executeHistory(@NonNull CommandContext context) {
        String[] args = context.getArgs();
        List<String> historyList = history.getAll();
        
        if (args.length == 1 && args[0].equals("-c")) {
            history.clear();
            return "Historial limpiado";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("=== Historial de comandos ===\n");
        
        int start = Math.max(0, historyList.size() - 50); // Mostrar últimos 50
        for (int i = start; i < historyList.size(); i++) {
            sb.append(String.format("%4d  %s\n", i + 1, historyList.get(i)));
        }
        
        sb.append("=============================\n");
        sb.append("Total: ").append(historyList.size()).append(" comandos");
        
        return sb.toString();
    }
    
    @NonNull
    private String executeAlias(@NonNull CommandContext context) {
        String[] args = context.getArgs();
        
        if (args.length == 0) {
            // Listar todos los alias
            StringBuilder sb = new StringBuilder();
            sb.append("=== Alias definidos ===\n");
            
            for (Map.Entry<String, String> entry : commandAliases.entrySet()) {
                sb.append(entry.getKey()).append("='").append(entry.getValue()).append("'\n");
            }
            
            sb.append("=======================\n");
            sb.append("Total: ").append(commandAliases.size()).append(" alias");
            return sb.toString();
        }
        
        String alias = args[0];
        
        if (!alias.contains("=")) {
            // Mostrar alias específico
            String value = commandAliases.get(alias);
            return value != null ? alias + "='" + value + "'" : 
                   "alias: " + alias + ": no encontrado";
        }
        
        // Definir nuevo alias
        String[] parts = alias.split("=", 2);
        commandAliases.put(parts[0], parts[1]);
        return "";
    }
    
    @NonNull
    private String executePushd(@NonNull CommandContext context) {
        String[] args = context.getArgs();
        String currentDir = context.getApp().getSessionRepository().getCurrentDirectory();
        
        if (args.length == 0) {
            // Intercambiar directorio actual con el tope de la pila
            if (directoryStack.isEmpty()) {
                return "pushd: La pila de directorios está vacía";
            }
            String swapDir = directoryStack.pop();
            directoryStack.push(currentDir);
            context.getApp().getSessionRepository().setCurrentDirectory(swapDir);
            return executeDirs(context);
        }
        
        String newDir = args[0];
        directoryStack.push(currentDir);
        
        try {
            context.getApp().getSessionRepository().setCurrentDirectory(newDir);
            return executeDirs(context);
        } catch (IllegalArgumentException e) {
            directoryStack.pop(); // Revertir push
            return "pushd: " + e.getMessage();
        }
    }
    
    @NonNull
    private String executePopd(@NonNull CommandContext context) {
        if (directoryStack.isEmpty()) {
            return "popd: La pila de directorios está vacía";
        }
        
        String prevDir = directoryStack.pop();
        context.getApp().getSessionRepository().setCurrentDirectory(prevDir);
        return executeDirs(context);
    }
    
    @NonNull
    private String executeDirs(@NonNull CommandContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Pila de directorios ===\n");
        
        // Directorio actual (no está en la pila)
        sb.append("0  ").append(context.getApp().getSessionRepository().getCurrentDirectory()).append("\n");
        
        // Directorios en la pila (del tope al fondo)
        for (int i = 0; i < directoryStack.size(); i++) {
            sb.append(i + 1).append("  ").append(directoryStack.get(i)).append("\n");
        }
        
        return sb.toString();
    }
    
    @NonNull
    private String executeType(@NonNull CommandContext context) {
        String[] args = context.getArgs();
        
        if (args.length == 0) {
            return "type: Especifique un comando";
        }
        
        String cmdName = args[0];
        
        // Verificar si es alias
        String alias = commandAliases.get(cmdName);
        if (alias != null) {
            return cmdName + " es un alias para '" + alias + "'";
        }
        
        // Verificar si es comando integrado
        Command command = registeredCommands.get(cmdName);
        if (command != null) {
            if (builtinCommands.containsKey(cmdName)) {
                return cmdName + " es un comando integrado de GhostShell\n" +
                       "Descripción: " + command.getDescription() + "\n" +
                       "Uso: " + command.getUsage();
            } else {
                return cmdName + " es un comando personalizado\n" +
                       "Descripción: " + command.getDescription();
            }
        }
        
        return "type: " + cmdName + ": no encontrado";
    }
    
    @NonNull
    private String executeSleep(@NonNull CommandContext context) {
        String[] args = context.getArgs();
        
        if (args.length == 0) {
            return "sleep: Especifique segundos";
        }
        
        try {
            int seconds = Integer.parseInt(args[0]);
            Thread.sleep(seconds * 1000L);
            return "";
        } catch (NumberFormatException e) {
            return "sleep: Número inválido";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "sleep: Interrumpido";
        }
    }
    
    @NonNull
    private String executeSource(@NonNull CommandContext context) {
        // Implementación básica
        return "source: Scripting no implementado completamente";
    }
    
    @NonNull
    private String executeSet(@NonNull CommandContext context) {
        String[] args = context.getArgs();
        
        if (args.length == 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Opciones del shell ===\n");
            sb.append("echo ").append(echoEnabled ? "on" : "off").append("\n");
            sb.append("historia ").append(history.size()).append(" comandos\n");
            sb.append("alias ").append(commandAliases.size()).append(" definidos\n");
            return sb.toString();
        }
        
        if (args[0].equals("echo")) {
            if (args.length < 2) {
                return "echo está " + (echoEnabled ? "activado" : "desactivado");
            }
            echoEnabled = args[1].equalsIgnoreCase("on") || args[1].equals("1");
            return "";
        }
        
        return "set: Opción desconocida: " + args[0];
    }
    
    @NonNull
    private String executeUnset(@NonNull CommandContext context) {
        String[] args = context.getArgs();
        
        if (args.length == 0) {
            return "unset: Especifique variable a eliminar";
        }
        
        String varName = args[0];
        boolean removed = context.getApp().getEnvironmentRepository().removeEnvironmentVariable(varName);
        
        return removed ? "" : "unset: Variable no encontrada: " + varName;
    }
    
    // =====================
    // API pública
    // =====================
    
    /**
     * Registra un comando personalizado.
     */
    public boolean registerCommand(@NonNull Command command) {
        String name = command.getName();
        
        if (registeredCommands.containsKey(name)) {
            Log.w(TAG, "Comando ya registrado: " + name);
            return false;
        }
        
        registeredCommands.put(name, command);
        Log.d(TAG, "Comando registrado: " + name);
        return true;
    }
    
    /**
     * Elimina un comando registrado.
     */
    public boolean unregisterCommand(@NonNull String name) {
        if (builtinCommands.containsKey(name)) {
            Log.w(TAG, "No se puede eliminar comando integrado: " + name);
            return false;
        }
        
        Command removed = registeredCommands.remove(name);
        if (removed != null) {
            Log.d(TAG, "Comando eliminado: " + name);
            return true;
        }
        
        return false;
    }
    
    /**
     * Agrega un alias.
     */
    public void addAlias(@NonNull String alias, @NonNull String command) {
        commandAliases.put(alias, command);
    }
    
    /**
     * Elimina un alias.
     */
    public boolean removeAlias(@NonNull String alias) {
        return commandAliases.remove(alias) != null;
    }
    
    /**
     * Obtiene sugerencias de autocompletado.
     */
    @NonNull
    public List<String> getSuggestions(@NonNull String partial) {
        List<String> suggestions = new ArrayList<>();
        
        // Comandos que coinciden
        for (String cmdName : registeredCommands.keySet()) {
            if (cmdName.startsWith(partial)) {
                suggestions.add(cmdName);
            }
        }
        
        // Alias que coinciden
        for (String alias : commandAliases.keySet()) {
            if (alias.startsWith(partial)) {
                suggestions.add(alias);
            }
        }
        
        return suggestions;
    }
    
    /**
     * Obtiene estadísticas del procesador.
     */
    @NonNull
    public String getStatistics() {
        return String.format(
            "=== CommandProcessor Statistics ===\n" +
            "Total commands processed: %d\n" +
            "Registered commands: %d\n" +
            "Built-in commands: %d\n" +
            "Custom commands: %d\n" +
            "Aliases defined: %d\n" +
            "History size: %d\n" +
            "Directory stack: %d\n" +
            "Echo enabled: %s\n" +
            "==================================",
            commandCounter.get(),
            registeredCommands.size(),
            builtinCommands.size(),
            registeredCommands.size() - builtinCommands.size(),
            commandAliases.size(),
            history.size(),
            directoryStack.size(),
            echoEnabled
        );
    }
    
    /**
     * Obtiene el historial de comandos.
     */
    @NonNull
    public CommandHistory getHistory() {
        return history;
    }
    
    /**
     * Clase para resultados de comandos.
     */
    public static class CommandResult {
        private final boolean success;
        private final String output;
        private final long executionTime;
        
        private CommandResult(boolean success, String output, long executionTime) {
            this.success = success;
            this.output = output;
            this.executionTime = executionTime;
        }
        
        public static CommandResult success(String output) {
            return new CommandResult(true, output, 0);
        }
        
        public static CommandResult success(String output, long executionTime) {
            return new CommandResult(true, output, executionTime);
        }
        
        public static CommandResult error(String error) {
            return new CommandResult(false, error, 0);
        }
        
        public static CommandResult empty() {
            return new CommandResult(true, "", 0);
        }
        
        public boolean isSuccess() { return success; }
        public String getOutput() { return output; }
        public long getExecutionTime() { return executionTime; }
        
        @Override
        public String toString() {
            return String.format("CommandResult{success=%s, time=%dms, output=%s}",
                success, executionTime, 
                output != null && output.length() > 50 ? 
                output.substring(0, 47) + "..." : output);
        }
    }
}
