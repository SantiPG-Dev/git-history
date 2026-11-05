package com.githistory.git;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Pila de acciones deshacibles/rehacibles de un repositorio. Cada acción
 * guarda su operación inversa y su operación de rehacer.
 *
 * La pila se manipula solo desde el hilo de JavaFX: la UI consulta la acción
 * con {@link #peekUndo()}/{@link #peekRedo()}, ejecuta su {@link GitAction}
 * en el executor del repo y, si tiene éxito, confirma el movimiento con
 * {@link #confirmUndo()}/{@link #confirmRedo()}. Si la acción falla, la pila
 * queda intacta para poder reintentarla.
 */
public class UndoManager {

    @FunctionalInterface
    public interface GitAction {
        void run(GitService git) throws Exception;
    }

    public record Action(String label, GitAction undo, GitAction redo) {}

    private final Deque<Action> undoStack = new ArrayDeque<>();
    private final Deque<Action> redoStack = new ArrayDeque<>();

    /** Registra una acción nueva del usuario; invalida la pila de rehacer. */
    public void push(Action action) {
        undoStack.push(action);
        redoStack.clear();
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public String undoLabel() {
        Action a = undoStack.peek();
        return a == null ? null : a.label();
    }

    public String redoLabel() {
        Action a = redoStack.peek();
        return a == null ? null : a.label();
    }

    public Action peekUndo() {
        return undoStack.peek();
    }

    public Action peekRedo() {
        return redoStack.peek();
    }

    /** Marca como deshecha la acción cuya inversa ya se ejecutó con éxito. */
    public void confirmUndo() {
        Action a = undoStack.pop();
        redoStack.push(a);
    }

    /** Marca como rehecha la acción cuyo redo ya se ejecutó con éxito. */
    public void confirmRedo() {
        Action a = redoStack.pop();
        undoStack.push(a);
    }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }
}
