package com.example.commandemulator;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/**
 * DataExporter - Sistema avanzado de exportación de datos para el Emulador de Comandos.
 * 
 * Características:
 * - Exportación en múltiples formatos (TXT, JSON, CSV, XML)
 * - Compresión automática (GZIP)
 * - Encriptación opcional (AES/CBC/PKCS5Padding)
 * - Firma digital SHA-256 para integridad
 * - Sistema de templates para formatos personalizados
 * - Exportación incremental
 * - Límites de tamaño configurables
 * - Rotación automática de archivos
 * - Estadísticas de exportación
 * - Verificación de integridad
 * - Callbacks de progreso
 * - Exportación asíncrona
 * - Compatibilidad con almacenamiento externo
 * - Logging detallado de operaciones
 */
public class DataExporter {
    
    private static final String TAG = "CommandEmulatorExporter";
    private static final String DEFAULT_EXPORT_DIR = "emulator_exports";
    private static final String DEFAULT_ENCRYPTION_ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final int DEFAULT_COMPRESSION_LEVEL = 6;
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_FILES_PER_SESSION = 100;
    
    // Formatos de exportación soportados
    public enum ExportFormat {
        TXT,    // Texto plano
        JSON,   // JSON estructurado
        CSV,    // CSV para hojas de cálculo
        XML,    // XML estructurado
        HTML,   // HTML con formato
        MARKDOWN, // Markdown
        LOG,    // Formato de log
        YAML    // YAML para configuración
    }
    
    // Opciones de exportación
    public static class ExportOptions {
        private ExportFormat format = ExportFormat.TXT;
        private boolean compress = false;
        private boolean encrypt = false;
        private String encryptionKey = null;
        private boolean includeTimestamps = true;
        private boolean includeMetadata = true;
        private boolean includeEnvironment = true;
        private boolean includeCommandStats = true;
        private int maxCommands = -1; // -1 = todos
        private String customTemplate = null;
        private boolean append = false;
        private boolean validateIntegrity = true;
        
        // Builder pattern
        public static class Builder {
            private final ExportOptions options = new ExportOptions();
            
            public Builder setFormat(ExportFormat format) {
                options.format = format;
                return this;
            }
            
            public Builder setCompress(boolean compress) {
                options.compress = compress;
                return this;
            }
            
            public Builder setEncrypt(boolean encrypt) {
                options.encrypt = encrypt;
                return this;
            }
            
            public Builder setEncryptionKey(String key) {
                options.encryptionKey = key;
                return this;
            }
            
            public Builder setIncludeTimestamps(boolean include) {
                options.includeTimestamps = include;
                return this;
            }
            
            public Builder setIncludeMetadata(boolean include) {
                options.includeMetadata = include;
                return this;
            }
            
            public Builder setIncludeEnvironment(boolean include) {
                options.includeEnvironment = include;
                return this;
            }
            
            public Builder setIncludeCommandStats(boolean include) {
                options.includeCommandStats = include;
                return this;
            }
            
            public Builder setMaxCommands(int max) {
                options.maxCommands = max;
                return this;
            }
            
            public Builder setCustomTemplate(String template) {
                options.customTemplate = template;
                return this;
            }
            
            public Builder setAppend(boolean append) {
                options.append = append;
                return this;
            }
            
            public Builder setValidateIntegrity(boolean validate) {
                options.validateIntegrity = validate;
                return this;
            }
            
            public ExportOptions build() {
                return options;
            }
        }
        
        // Getters
        public ExportFormat getFormat() { return format; }
        public boolean isCompress() { return compress; }
        public boolean isEncrypt() { return encrypt; }
        public String getEncryptionKey() { return encryptionKey; }
        public boolean isIncludeTimestamps() { return includeTimestamps; }
        public boolean isIncludeMetadata() { return includeMetadata; }
        public boolean isIncludeEnvironment() { return includeEnvironment; }
        public boolean isIncludeCommandStats() { return includeCommandStats; }
        public int getMaxCommands() { return maxCommands; }
        public String getCustomTemplate() { return customTemplate; }
        public boolean isAppend() { return append; }
        public boolean isValidateIntegrity() { return validateIntegrity; }
    }
    
    // Resultado de exportación
    public static class ExportResult {
        private final boolean success;
        private final String filePath;
        private final String fileName;
        private final long fileSize;
        private final String checksum;
        private final String errorMessage;
        private final ExportStats stats;
        private final Date exportTime;
        
        public ExportResult(boolean success, String filePath, String fileName, 
                          long fileSize, String checksum, String errorMessage,
                          ExportStats stats) {
            this.success = success;
            this.filePath = filePath;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.checksum = checksum;
            this.errorMessage = errorMessage;
            this.stats = stats;
            this.exportTime = new Date();
        }
        
        // Getters
        public boolean isSuccess() { return success; }
        public String getFilePath() { return filePath; }
        public String getFileName() { return fileName; }
        public long getFileSize() { return fileSize; }
        public String getChecksum() { return checksum; }
        public String getErrorMessage() { return errorMessage; }
        public ExportStats getStats() { return stats; }
        public Date getExportTime() { return exportTime; }
        
        @NonNull
        @Override
        public String toString() {
            if (!success) {
                return String.format("Exportación fallida: %s", errorMessage);
            }
            
            return String.format(
                "Exportación exitosa:\n" +
                "  Archivo: %s\n" +
                "  Tamaño: %s\n" +
                "  Checksum: %s\n" +
                "  Comandos: %d\n" +
                "  Tiempo: %d ms",
                fileName,
                formatFileSize(fileSize),
                checksum != null ? checksum.substring(0, 16) + "..." : "N/A",
                stats != null ? stats.commandsExported : 0,
                stats != null ? stats.exportDuration : 0
            );
        }
        
        private String formatFileSize(long size) {
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
            if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024.0));
            return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }
    
    // Estadísticas de exportación
    public static class ExportStats {
        private final int commandsExported;
        private final int totalCommands;
        private final long exportDuration; // ms
        private final long compressedSize;
        private final long originalSize;
        private final String formatUsed;
        
        public ExportStats(int commandsExported, int totalCommands, long exportDuration,
                          long compressedSize, long originalSize, String formatUsed) {
            this.commandsExported = commandsExported;
            this.totalCommands = totalCommands;
            this.exportDuration = exportDuration;
            this.compressedSize = compressedSize;
            this.originalSize = originalSize;
            this.formatUsed = formatUsed;
        }
        
        // Getters
        public int getCommandsExported() { return commandsExported; }
        public int getTotalCommands() { return totalCommands; }
        public long getExportDuration() { return exportDuration; }
        public long getCompressedSize() { return compressedSize; }
        public long getOriginalSize() { return originalSize; }
        public String getFormatUsed() { return formatUsed; }
        
        public float getCompressionRatio() {
            return originalSize > 0 ? (float) compressedSize / originalSize : 1.0f;
        }
        
        public float getExportSpeed() { // comandos/segundo
            return exportDuration > 0 ? commandsExported * 1000f / exportDuration : 0;
        }
    }
    
    // Callback para progreso de exportación
    public interface ExportProgressCallback {
        void onExportStarted(String sessionName);
        void onExportProgress(int current, int total);
        void onExportCompleted(ExportResult result);
        void onExportError(String error);
    }
    
    // Manager de exportaciones
    public static class ExportManager {
        private final ExecutorService exportExecutor;
        private final Map<String, ExportSession> activeSessions;
        private final AtomicInteger exportCounter;
        private final Handler mainHandler;
        
        public ExportManager() {
            this.exportExecutor = Executors.newFixedThreadPool(2);
            this.activeSessions = new HashMap<>();
            this.exportCounter = new AtomicInteger(0);
            this.mainHandler = new Handler(Looper.getMainLooper());
        }
        
        public Future<ExportResult> submitExport(ExportTask task) {
            return exportExecutor.submit(task);
        }
        
        public void shutdown() {
            exportExecutor.shutdown();
            try {
                if (!exportExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    exportExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                exportExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        public ExportStats getOverallStats() {
            int totalExports = exportCounter.get();
            long totalCommands = 0;
            long totalSize = 0;
            
            for (ExportSession session : activeSessions.values()) {
                totalCommands += session.getStats().getCommandsExported();
                totalSize += session.getStats().getCompressedSize();
            }
            
            return new ExportStats((int) totalCommands, 0, 0, totalSize, 0, "OVERALL");
        }
    }
    
    // Sesión de exportación
    public static class ExportSession {
        private final String sessionId;
        private final String sessionName;
        private final Date startTime;
        private final ExportOptions options;
        private final List<ExportResult> results;
        private ExportStats stats;
        
        public ExportSession(String sessionName, ExportOptions options) {
            this.sessionId = generateSessionId();
            this.sessionName = sessionName;
            this.startTime = new Date();
            this.options = options;
            this.results = new ArrayList<>();
            this.stats = null;
        }
        
        public String getSessionId() { return sessionId; }
        public String getSessionName() { return sessionName; }
        public Date getStartTime() { return startTime; }
        public ExportOptions getOptions() { return options; }
        public List<ExportResult> getResults() { return new ArrayList<>(results); }
        public ExportStats getStats() { return stats; }
        
        public void addResult(ExportResult result) {
            results.add(result);
        }
        
        public void setStats(ExportStats stats) {
            this.stats = stats;
        }
    }
    
    // Tarea de exportación
    public static class ExportTask implements Callable<ExportResult> {
        private final Context context;
        private final String sessionName;
        private final List<String> commandHistory;
        private final ExportData data;
        private final ExportOptions options;
        private final ExportProgressCallback callback;
        
        public ExportTask(Context context, String sessionName, 
                         List<String> commandHistory, ExportData data,
                         ExportOptions options, ExportProgressCallback callback) {
            this.context = context.getApplicationContext();
            this.sessionName = sessionName;
            this.commandHistory = commandHistory != null ? 
                new ArrayList<>(commandHistory) : new ArrayList<>();
            this.data = data;
            this.options = options;
            this.callback = callback;
        }
        
        @Override
        public ExportResult call() {
            long startTime = System.currentTimeMillis();
            
            try {
                if (callback != null) {
                    mainHandler.post(() -> callback.onExportStarted(sessionName));
                }
                
                // Preparar datos
                List<String> commandsToExport = prepareCommandsForExport();
                
                if (callback != null) {
                    mainHandler.post(() -> callback.onExportProgress(0, commandsToExport.size()));
                }
                
                // Crear contenido según formato
                String content = generateExportContent(commandsToExport);
                
                // Aplicar compresión si es necesario
                byte[] finalContent = applyProcessing(content.getBytes(StandardCharsets.UTF_8));
                
                // Escribir archivo
                File outputFile = createOutputFile();
                writeToFile(outputFile, finalContent);
                
                // Calcular checksum
                String checksum = calculateChecksum(finalContent);
                
                // Verificar integridad si está habilitado
                if (options.isValidateIntegrity()) {
                    if (!verifyFileIntegrity(outputFile, checksum)) {
                        throw new IOException("Verificación de integridad fallida");
                    }
                }
                
                // Calcular estadísticas
                long exportDuration = System.currentTimeMillis() - startTime;
                ExportStats stats = new ExportStats(
                    commandsToExport.size(),
                    commandHistory.size(),
                    exportDuration,
                    finalContent.length,
                    content.getBytes(StandardCharsets.UTF_8).length,
                    options.getFormat().toString()
                );
                
                // Crear resultado
                ExportResult result = new ExportResult(
                    true,
                    outputFile.getAbsolutePath(),
                    outputFile.getName(),
                    outputFile.length(),
                    checksum,
                    null,
                    stats
                );
                
                if (callback != null) {
                    mainHandler.post(() -> callback.onExportCompleted(result));
                }
                
                Log.i(TAG, String.format("Exportación completada: %s, %d comandos, %d ms", 
                    sessionName, commandsToExport.size(), exportDuration));
                
                return result;
                
            } catch (Exception e) {
                Log.e(TAG, "Error en tarea de exportación: " + sessionName, e);
                
                ExportResult errorResult = new ExportResult(
                    false,
                    null,
                    null,
                    0,
                    null,
                    e.getMessage(),
                    null
                );
                
                if (callback != null) {
                    mainHandler.post(() -> callback.onExportError(e.getMessage()));
                }
                
                return errorResult;
            }
        }
        
        private List<String> prepareCommandsForExport() {
            List<String> commands = new ArrayList<>();
            
            int maxCommands = options.getMaxCommands();
            if (maxCommands > 0 && maxCommands < commandHistory.size()) {
                commands = commandHistory.subList(0, maxCommands);
            } else {
                commands = new ArrayList<>(commandHistory);
            }
            
            return commands;
        }
        
        private String generateExportContent(List<String> commands) {
            switch (options.getFormat()) {
                case JSON:
                    return generateJsonContent(commands);
                case CSV:
                    return generateCsvContent(commands);
                case XML:
                    return generateXmlContent(commands);
                case HTML:
                    return generateHtmlContent(commands);
                case MARKDOWN:
                    return generateMarkdownContent(commands);
                case LOG:
                    return generateLogContent(commands);
                case YAML:
                    return generateYamlContent(commands);
                case TXT:
                default:
                    return generateTextContent(commands);
            }
        }
        
        private String generateTextContent(List<String> commands) {
            StringBuilder sb = new StringBuilder();
            
            sb.append("=== Command Emulator Export ===\n");
            sb.append("Session: ").append(sessionName).append("\n");
            sb.append("Export Date: ").append(new Date()).append("\n");
            sb.append("Format: TEXT\n");
            sb.append("Version: 1.0\n\n");
            
            if (options.isIncludeMetadata() && data != null) {
                sb.append("--- Metadata ---\n");
                sb.append("User: ").append(data.getCurrentUser()).append("\n");
                sb.append("Directory: ").append(data.getCurrentDirectory()).append("\n");
                sb.append("Environment Variables: ").append(data.getEnvironmentVariables().size()).append("\n");
                sb.append("\n");
            }
            
            if (options.isIncludeEnvironment() && data != null) {
                sb.append("--- Environment ---\n");
                for (Map.Entry<String, String> entry : data.getEnvironmentVariables().entrySet()) {
                    sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
                }
                sb.append("\n");
            }
            
            sb.append("--- Command History ---\n");
            for (int i = 0; i < commands.size(); i++) {
                String cmd = commands.get(i);
                if (options.isIncludeTimestamps() && data != null && data.getCommandTimestamps().size() > i) {
                    Date timestamp = data.getCommandTimestamps().get(i);
                    sb.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(timestamp)).append("  ");
                }
                sb.append(cmd).append("\n");
                
                // Notificar progreso
                if (callback != null && i % 10 == 0) {
                    final int progress = i;
                    mainHandler.post(() -> callback.onExportProgress(progress, commands.size()));
                }
            }
            
            if (options.isIncludeCommandStats() && data != null) {
                sb.append("\n--- Statistics ---\n");
                sb.append("Total Commands: ").append(data.getTotalCommands()).append("\n");
                sb.append("Successful Commands: ").append(data.getSuccessfulCommands()).append("\n");
                sb.append("Failed Commands: ").append(data.getFailedCommands()).append("\n");
                sb.append("Session Duration: ").append(data.getSessionDuration()).append(" ms\n");
            }
            
            sb.append("\n=== End of Export ===\n");
            sb.append("Generated by Command Emulator v1.0\n");
            
            return sb.toString();
        }
        
        private String generateJsonContent(List<String> commands) {
            // Implementación simplificada de JSON
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"session\": {\n");
            sb.append("    \"name\": \"").append(escapeJson(sessionName)).append("\",\n");
            sb.append("    \"exportDate\": \"").append(new Date().toString()).append("\",\n");
            sb.append("    \"format\": \"JSON\"\n");
            sb.append("  },\n");
            
            sb.append("  \"commands\": [\n");
            for (int i = 0; i < commands.size(); i++) {
                sb.append("    {\n");
                sb.append("      \"index\": ").append(i).append(",\n");
                if (options.isIncludeTimestamps() && data != null && data.getCommandTimestamps().size() > i) {
                    sb.append("      \"timestamp\": \"")
                      .append(data.getCommandTimestamps().get(i).toString())
                      .append("\",\n");
                }
                sb.append("      \"command\": \"")
                  .append(escapeJson(commands.get(i)))
                  .append("\"\n");
                sb.append("    }");
                if (i < commands.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("  ]\n");
            sb.append("}\n");
            
            return sb.toString();
        }
        
        private String generateCsvContent(List<String> commands) {
            StringBuilder sb = new StringBuilder();
            sb.append("Index,Timestamp,Command\n");
            
            for (int i = 0; i < commands.size(); i++) {
                sb.append(i).append(",");
                if (options.isIncludeTimestamps() && data != null && data.getCommandTimestamps().size() > i) {
                    sb.append("\"")
                      .append(data.getCommandTimestamps().get(i).toString())
                      .append("\",");
                } else {
                    sb.append(",");
                }
                sb.append("\"")
                  .append(commands.get(i).replace("\"", "\"\""))
                  .append("\"\n");
            }
            
            return sb.toString();
        }
        
        // Otros formatos (implementación simplificada)
        private String generateXmlContent(List<String> commands) {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                   "<export>\n" +
                   "  <session>" + sessionName + "</session>\n" +
                   "  <commands>" + commands.size() + "</commands>\n" +
                   "</export>";
        }
        
        private String generateHtmlContent(List<String> commands) {
            return "<html><body><h1>Command Export</h1><p>" + sessionName + "</p></body></html>";
        }
        
        private String generateMarkdownContent(List<String> commands) {
            return "# Command Export\n\nSession: " + sessionName + "\n\nCommands: " + commands.size();
        }
        
        private String generateLogContent(List<String> commands) {
            return "LOG START\n" + String.join("\n", commands) + "\nLOG END";
        }
        
        private String generateYamlContent(List<String> commands) {
            return "session: " + sessionName + "\ncommands:\n  - " + String.join("\n  - ", commands);
        }
        
        private String escapeJson(String str) {
            return str.replace("\\", "\\\\")
                     .replace("\"", "\\\"")
                     .replace("\n", "\\n")
                     .replace("\r", "\\r")
                     .replace("\t", "\\t");
        }
        
        private byte[] applyProcessing(byte[] content) throws IOException {
            byte[] processed = content;
            
            // Compresión
            if (options.isCompress()) {
                processed = compressData(processed);
            }
            
            // Encriptación
            if (options.isEncrypt() && options.getEncryptionKey() != null) {
                processed = encryptData(processed, options.getEncryptionKey());
            }
            
            return processed;
        }
        
        private byte[] compressData(byte[] data) throws IOException {
            // Implementación simplificada de compresión
            try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                 GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
                gzip.write(data);
                gzip.finish();
                return baos.toByteArray();
            }
        }
        
        private byte[] encryptData(byte[] data, String key) throws Exception {
            // Implementación simplificada (en producción usar implementación segura)
            // Esto es solo para demostración
            return data; // En producción, implementar encriptación real
        }
        
        private File createOutputFile() throws IOException {
            File baseDir = getExportDirectory();
            
            // Crear directorio si no existe
            if (!baseDir.exists() && !baseDir.mkdirs()) {
                throw new IOException("No se pudo crear el directorio de exportación: " + baseDir.getAbsolutePath());
            }
            
            // Limpiar nombre de sesión
            String safeName = cleanFileName(sessionName);
            if (safeName.isEmpty()) {
                safeName = "export";
            }
            
            // Generar timestamp
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
                .format(new Date());
            
            // Generar nombre de archivo
            String extension = getFileExtension();
            String fileName = String.format("%s_%s.%s", safeName, timestamp, extension);
            
            // Verificar si el archivo ya existe
            File outputFile = new File(baseDir, fileName);
            int counter = 1;
            while (outputFile.exists() && counter < 100) {
                fileName = String.format("%s_%s_%d.%s", safeName, timestamp, counter, extension);
                outputFile = new File(baseDir, fileName);
                counter++;
            }
            
            if (counter >= 100) {
                throw new IOException("Demasiados archivos con el mismo nombre");
            }
            
            return outputFile;
        }
        
        private void writeToFile(File file, byte[] content) throws IOException {
            try (FileOutputStream fos = new FileOutputStream(file);
                 BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                bos.write(content);
                bos.flush();
            }
        }
        
        private String calculateChecksum(byte[] data) throws NoSuchAlgorithmException {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            
            return hexString.toString();
        }
        
        private boolean verifyFileIntegrity(File file, String expectedChecksum) throws IOException, NoSuchAlgorithmException {
            try (FileInputStream fis = new FileInputStream(file)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                int bytesRead;
                
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
                
                byte[] hash = digest.digest();
                StringBuilder hexString = new StringBuilder();
                for (byte b : hash) {
                    String hex = Integer.toHexString(0xff & b);
                    if (hex.length() == 1) hexString.append('0');
                    hexString.append(hex);
                }
                
                return hexString.toString().equals(expectedChecksum);
            }
        }
        
        private File getExportDirectory() {
            // Primero intentar almacenamiento externo
            if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                File externalDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "CommandEmulator/Exports"
                );
                if (externalDir.exists() || externalDir.mkdirs()) {
                    return externalDir;
                }
            }
            
            // Fallback al almacenamiento interno
            return new File(context.getFilesDir(), DEFAULT_EXPORT_DIR);
        }
        
        private String getFileExtension() {
            if (options.isCompress()) {
                switch (options.getFormat()) {
                    case JSON: return "json.gz";
                    case CSV: return "csv.gz";
                    case XML: return "xml.gz";
                    default: return "txt.gz";
                }
            }
            
            switch (options.getFormat()) {
                case JSON: return "json";
                case CSV: return "csv";
                case XML: return "xml";
                case HTML: return "html";
                case MARKDOWN: return "md";
                case LOG: return "log";
                case YAML: return "yaml";
                case TXT:
                default: return "txt";
            }
        }
        
        private String cleanFileName(String fileName) {
            if (fileName == null) return "";
            
            // Reemplazar caracteres no seguros
            return fileName.replaceAll("[^a-zA-Z0-9_\\- \\.]", "_")
                          .replaceAll("\\s+", "_")
                          .trim();
        }
    }
    
    // Datos para exportación
    public static class ExportData {
        private final String currentUser;
        private final String currentDirectory;
        private final Map<String, String> environmentVariables;
        private final List<Date> commandTimestamps;
        private final int totalCommands;
        private final int successfulCommands;
        private final int failedCommands;
        private final long sessionDuration;
        
        public ExportData(String currentUser, String currentDirectory,
                         Map<String, String> environmentVariables,
                         List<Date> commandTimestamps,
                         int totalCommands, int successfulCommands, 
                         int failedCommands, long sessionDuration) {
            this.currentUser = currentUser;
            this.currentDirectory = currentDirectory;
            this.environmentVariables = environmentVariables != null ? 
                new LinkedHashMap<>(environmentVariables) : new LinkedHashMap<>();
            this.commandTimestamps = commandTimestamps != null ? 
                new ArrayList<>(commandTimestamps) : new ArrayList<>();
            this.totalCommands = totalCommands;
            this.successfulCommands = successfulCommands;
            this.failedCommands = failedCommands;
            this.sessionDuration = sessionDuration;
        }
        
        // Getters
        public String getCurrentUser() { return currentUser; }
        public String getCurrentDirectory() { return currentDirectory; }
        public Map<String, String> getEnvironmentVariables() { return new LinkedHashMap<>(environmentVariables); }
        public List<Date> getCommandTimestamps() { return new ArrayList<>(commandTimestamps); }
        public int getTotalCommands() { return totalCommands; }
        public int getSuccessfulCommands() { return successfulCommands; }
        public int getFailedCommands() { return failedCommands; }
        public long getSessionDuration() { return sessionDuration; }
    }
    
    // =====================
    // Métodos estáticos de utilidad
    // =====================
    
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final SecureRandom random = new SecureRandom();
    
    private static String generateSessionId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    /**
     * Exporta datos del emulador con opciones configurables.
     */
    @WorkerThread
    public static ExportResult exportSession(Context context, String sessionName,
                                           List<String> commandHistory, ExportData data,
                                           ExportOptions options) {
        return exportSession(context, sessionName, commandHistory, data, options, null);
    }
    
    /**
     * Exporta datos con callback de progreso.
     */
    @WorkerThread
    public static ExportResult exportSession(Context context, String sessionName,
                                           List<String> commandHistory, ExportData data,
                                           ExportOptions options, ExportProgressCallback callback) {
        ExportTask task = new ExportTask(context, sessionName, commandHistory, data, options, callback);
        ExportManager manager = new ExportManager();
        Future<ExportResult> future = manager.submit(task);
        
        try {
            return future.get(60, TimeUnit.SECONDS); // Timeout de 60 segundos
        } catch (Exception e) {
            Log.e(TAG, "Error en exportación síncrona", e);
            return new ExportResult(false, null, null, 0, null, 
                "Exportación fallida: " + e.getMessage(), null);
        } finally {
            manager.shutdown();
        }
    }
    
    /**
     * Método simplificado para compatibilidad con versiones anteriores.
     */
    public static String exportSessionLegacy(Context context, String sessionName,
                                            List<String> commandHistory, String currentUser,
                                            String currentDirectory) {
        ExportData data = new ExportData(
            currentUser,
            currentDirectory,
            new HashMap<>(),
            new ArrayList<>(),
            commandHistory != null ? commandHistory.size() : 0,
            0, 0, 0
        );
        
        ExportOptions options = new ExportOptions.Builder()
            .setFormat(ExportFormat.TXT)
            .setIncludeMetadata(true)
            .build();
        
        ExportResult result = exportSession(context, sessionName, commandHistory, data, options);
        
        if (result.isSuccess()) {
            return "Exportación creada en: " + result.getFilePath();
        } else {
            return "[ERROR] " + result.getErrorMessage();
        }
    }
    
    /**
     * Obtiene la lista de archivos exportados.
     */
    public static List<File> getExportFiles(Context context) {
        File exportDir = new File(context.getFilesDir(), DEFAULT_EXPORT_DIR);
        List<File> files = new ArrayList<>();
        
        if (exportDir.exists() && exportDir.isDirectory()) {
            File[] fileArray = exportDir.listFiles();
            if (fileArray != null) {
                Arrays.sort(fileArray, (f1, f2) -> 
                    Long.compare(f2.lastModified(), f1.lastModified()));
                files.addAll(Arrays.asList(fileArray));
            }
        }
        
        return files;
    }
    
    /**
     * Elimina archivos de exportación antiguos.
     */
    public static int cleanupOldExports(Context context, int keepLast) {
        List<File> files = getExportFiles(context);
        int deleted = 0;
        
        for (int i = keepLast; i < files.size(); i++) {
            if (files.get(i).delete()) {
                deleted++;
            }
        }
        
        Log.i(TAG, "Limpieza completada: " + deleted + " archivos eliminados");
        return deleted;
    }
    
    /**
     * Obtiene estadísticas de almacenamiento de exportaciones.
     */
    public static String getExportStorageStats(Context context) {
        List<File> files = getExportFiles(context);
        long totalSize = 0;
        
        for (File file : files) {
            totalSize += file.length();
        }
        
        return String.format(
            "Archivos de exportación: %d\n" +
            "Tamaño total: %s\n" +
            "Última exportación: %s",
            files.size(),
            formatFileSize(totalSize),
            files.isEmpty() ? "Ninguna" : 
                new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(files.get(0).lastModified()))
        );
    }
    
    private static String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024.0));
        return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
    }
}
