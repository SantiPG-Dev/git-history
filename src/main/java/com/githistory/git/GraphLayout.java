package com.githistory.git;

import java.util.ArrayList;
import java.util.List;

/**
 * Asigna carriles (columnas) a los commits para dibujar el grafo de ramas.
 * Recorre los commits en orden topológico (hijos antes que padres) manteniendo
 * una lista de carriles activos, donde cada carril "espera" el commit que lo cerrará.
 */
public final class GraphLayout {

    public record Graph(List<GraphRow> rows, int maxLanes) {}

    private GraphLayout() {}

    public static Graph layout(List<CommitInfo> commits) {
        List<String> lanes = new ArrayList<>();
        List<GraphRow> rows = new ArrayList<>();
        int maxLanes = 0;

        for (CommitInfo commit : commits) {
            int col = lanes.indexOf(commit.id());
            boolean expected = col >= 0;
            if (!expected) {
                col = lanes.indexOf(null);
                if (col < 0) {
                    col = lanes.size();
                    lanes.add(null);
                }
            }
            lanes.set(col, null);

            boolean colReused = false;
            List<Integer> newLanes = new ArrayList<>();
            List<GraphRow.Diagonal> diagonals = new ArrayList<>();
            List<String> parents = commit.parents();
            for (int i = 0; i < parents.size(); i++) {
                String parent = parents.get(i);
                int existing = lanes.indexOf(parent);
                if (existing >= 0) {
                    diagonals.add(new GraphRow.Diagonal(col, existing, false));
                    continue;
                }
                int slot;
                if (i == 0) {
                    slot = col;
                    colReused = true;
                } else {
                    slot = lanes.indexOf(null);
                    if (slot < 0) {
                        slot = lanes.size();
                        lanes.add(null);
                    }
                    newLanes.add(slot);
                }
                lanes.set(slot, parent);
                if (slot != col) {
                    diagonals.add(new GraphRow.Diagonal(col, slot, true));
                }
            }

            List<Integer> full = new ArrayList<>();
            List<Integer> top = new ArrayList<>();
            List<Integer> bottom = new ArrayList<>();
            for (int j = 0; j < lanes.size(); j++) {
                if (lanes.get(j) == null) {
                    continue;
                }
                if (newLanes.contains(j) || (j == col && colReused && !expected)) {
                    bottom.add(j);
                } else {
                    full.add(j);
                }
            }
            if (expected && !colReused) {
                top.add(col);
            }

            while (!lanes.isEmpty() && lanes.get(lanes.size() - 1) == null) {
                lanes.remove(lanes.size() - 1);
            }
            maxLanes = Math.max(maxLanes, lanes.size());
            rows.add(new GraphRow(commit, col, full, top, bottom, diagonals));
        }
        return new Graph(rows, Math.max(maxLanes, 1));
    }
}
