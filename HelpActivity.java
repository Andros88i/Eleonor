package com.example.commandemulator;

import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;

public class MainActivity extends AppCompatActivity {

    private static final String WELCOME_MESSAGE = "=== Emulador de Comandos ===";
    private static final String HELP_INSTRUCTION = "Escribe 'help' para ver los comandos disponibles.\n";
    
    private TextView terminalOutput;
    private EditText commandInput;
    private Button executeButton;
    private final Map<String, String> commandDescriptions = new LinkedHashMap<>();
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initializeViews();
        setupCommandDescriptions();
        configureTerminal();
        printWelcome();
        setupExecuteButton();
    }
    
    private void initializeViews() {
        terminalOutput = findViewById(R.id.terminalOutput);
        commandInput = findViewById(R.id.commandInput);
        executeButton = findViewById(R.id.executeButton);
    }
    
    private void setupCommandDescriptions() {
        commandDescriptions.put("help", "Muestra esta ayuda");
        commandDescriptions.put("clear", "Limpia la pantalla");
        commandDescriptions.put("date", "Muestra fecha y hora");
        commandDescriptions.put("echo", "Repite un mensaje");
        commandDescriptions.put("exit", "Cierra la app");
    }
    
    private void configureTerminal() {
        terminalOutput.setMovementMethod(new ScrollingMovementMethod());
    }
    
    private void printWelcome() {
        appendOutput(WELCOME_MESSAGE);
        appendOutput(HELP_INSTRUCTION);
    }
    
    private void setupExecuteButton() {
        executeButton.setOnClickListener(v -> {
            String command = commandInput.getText().toString().trim();
            if (!command.isEmpty()) {
                executeCommand(command);
                commandInput.setText("");
            }
        });
        
        // También permitir ejecutar con Enter en el teclado
        commandInput.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && 
                keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                executeButton.performClick();
                return true;
            }
            return false;
        });
    }
    
    private void executeCommand(String command) {
        appendOutput("> " + command);
        
        String commandLower = command.toLowerCase();
        
        if (commandLower.equals("help")) {
            showHelp();
        } else if (commandLower.equals("clear")) {
            clearTerminal();
        } else if (commandLower.equals("date")) {
            showDate();
        } else if (commandLower.equals("echo")) {
            appendOutput("Uso: echo <mensaje>");
        } else if (commandLower.equals("exit")) {
            exitApp();
        } else if (commandLower.startsWith("echo ")) {
            echoMessage(command.substring(5));
        } else {
            showUnrecognizedCommand(command);
        }
    }
    
    private void showHelp() {
        appendOutput("Comandos disponibles:");
        for (Map.Entry<String, String> entry : commandDescriptions.entrySet()) {
            appendOutput(entry.getKey() + " - " + entry.getValue());
        }
    }
    
    private void clearTerminal() {
        terminalOutput.setText("");
    }
    
    private void showDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String currentDateTime = sdf.format(new Date());
        appendOutput(currentDateTime);
    }
    
    private void echoMessage(String message) {
        if (!message.trim().isEmpty()) {
            appendOutput(message);
        } else {
            appendOutput("Error: El mensaje no puede estar vacío");
        }
    }
    
    private void exitApp() {
        appendOutput("Cerrando emulador...");
        finish();
    }
    
    private void showUnrecognizedCommand(String command) {
        appendOutput("Comando no reconocido: " + command);
        appendOutput("Escribe 'help' para ver los comandos disponibles.");
    }
    
    private void appendOutput(String text) {
        runOnUiThread(() -> {
            terminalOutput.append(text + "\n");
            scrollToBottom();
        });
    }
    
    private void scrollToBottom() {
        // Usar postDelayed para asegurar que el scroll ocurra después de que el texto se haya añadido
        terminalOutput.postDelayed(() -> {
            int scrollAmount = terminalOutput.getLayout() != null ? 
                terminalOutput.getLayout().getLineTop(terminalOutput.getLineCount()) - 
                terminalOutput.getHeight() : 0;
            
            if (scrollAmount > 0) {
                terminalOutput.scrollTo(0, scrollAmount);
            } else {
                terminalOutput.scrollTo(0, 0);
            }
        }, 100);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Limpiar recursos si es necesario
        commandDescriptions.clear();
    }
}
