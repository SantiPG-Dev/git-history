package com.githistory.ui;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Sesión de shell (bash) en el directorio del repositorio. Sin PTY: ejecuta
 * comandos de línea y vuelca stdout+stderr al callback. Los programas
 * interactivos (vim, top…) no funcionan; para eso está la terminal externa.
 */
public class TerminalSession implements AutoCloseable {

    private final Process process;
    private final BufferedWriter stdin;
    private final Thread reader;

    public TerminalSession(Path directory, Consumer<String> onOutput) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("bash");
        pb.directory(directory.toFile());
        pb.redirectErrorStream(true);
        pb.environment().put("TERM", "dumb");
        process = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));
        reader = new Thread(() -> {
            byte[] raw = new byte[4096];
            try {
                var in = process.getInputStream();
                int n;
                while ((n = in.read(raw)) != -1) {
                    onOutput.accept(new String(raw, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // Proceso terminado.
            }
        }, "terminal-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public void send(String command) throws IOException {
        stdin.write(command);
        stdin.newLine();
        stdin.flush();
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
