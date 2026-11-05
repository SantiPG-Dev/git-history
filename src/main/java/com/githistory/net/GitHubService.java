package com.githistory.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cliente mínimo de la API REST de GitHub para listar pull requests e issues
 * abiertas de un repositorio público. Sin autenticación (límite de 60
 * peticiones/hora por IP, suficiente para refrescos manuales). Todas las
 * llamadas son bloqueantes: usar desde hilos de fondo.
 */
public class GitHubService {

    public record PullRequest(int number, String title, String author, String url) {}

    public record Issue(int number, String title, String author, String url) {}

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Extrae [owner, repo] de una URL de remoto de GitHub, o null si no es de
     * GitHub. Soporta git@github.com:owner/repo.git y
     * https://github.com/owner/repo(.git).
     */
    public static String[] ownerRepo(String remoteUrl) {
        if (remoteUrl == null) {
            return null;
        }
        String path = null;
        if (remoteUrl.startsWith("git@github.com:")) {
            path = remoteUrl.substring("git@github.com:".length());
        } else if (remoteUrl.startsWith("https://github.com/")) {
            path = remoteUrl.substring("https://github.com/".length());
        } else if (remoteUrl.startsWith("ssh://git@github.com/")) {
            path = remoteUrl.substring("ssh://git@github.com/".length());
        }
        if (path == null) {
            return null;
        }
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        String[] parts = path.split("/");
        return parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()
                ? parts : null;
    }

    public List<PullRequest> listOpenPullRequests(String owner, String repo) throws IOException {
        List<Object> data = getArray("https://api.github.com/repos/"
                + owner + "/" + repo + "/pulls?state=open&per_page=50");
        List<PullRequest> out = new ArrayList<>();
        for (Object item : data) {
            Map<String, Object> pr = cast(item);
            Map<String, Object> user = cast(pr.get("user"));
            out.add(new PullRequest(
                    ((Double) pr.get("number")).intValue(),
                    (String) pr.get("title"),
                    user != null ? (String) user.get("login") : "?",
                    (String) pr.get("html_url")));
        }
        return out;
    }

    public List<Issue> listOpenIssues(String owner, String repo) throws IOException {
        List<Object> data = getArray("https://api.github.com/repos/"
                + owner + "/" + repo + "/issues?state=open&per_page=50");
        List<Issue> out = new ArrayList<>();
        for (Object item : data) {
            Map<String, Object> issue = cast(item);
            // El endpoint de issues también devuelve PRs: se filtran.
            if (issue.containsKey("pull_request")) {
                continue;
            }
            Map<String, Object> user = cast(issue.get("user"));
            out.add(new Issue(
                    ((Double) issue.get("number")).intValue(),
                    (String) issue.get("title"),
                    user != null ? (String) user.get("login") : "?",
                    (String) issue.get("html_url")));
        }
        return out;
    }

    private List<Object> getArray(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "git-history-client")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Petición interrumpida", e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("GitHub respondió " + response.statusCode() + " para " + url);
        }
        return Json.parseArray(response.body());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }
}
