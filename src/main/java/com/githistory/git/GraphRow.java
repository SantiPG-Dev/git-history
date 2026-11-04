package com.githistory.git;

import java.util.List;

/**
 * Una fila del grafo de commits con toda la información necesaria para dibujarla:
 * en qué carril (columna) cae el nodo, qué carriles lo atraviesan en vertical
 * y qué conexiones diagonales salen hacia los carriles de sus padres.
 */
public record GraphRow(
        CommitInfo commit,
        int column,
        List<Integer> fullVerticals,
        List<Integer> topVerticals,
        List<Integer> bottomVerticals,
        List<Diagonal> diagonals) {

    public record Diagonal(int fromLane, int toLane, boolean toBottom) {}
}
