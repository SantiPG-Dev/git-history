package com.githistory.git;

import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.IllegalTodoFileModification;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.RebaseTodoLine;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Envoltorio sobre JGit con todas las operaciones que usa la interfaz.
 * Todos los métodos son bloqueantes: la UI debe llamarlos desde hilos de fondo.
 */
public class GitService implements AutoCloseable {

    static {
        // Registra el soporte nativo de LFS (el módulo org.eclipse.jgit.lfs
        // no se auto-registra: hay que invocarlo explícitamente).
        org.eclipse.jgit.lfs.BuiltinLFS.register();
    }

    public record StatusEntry(String path, String state) {}

    public record WorkingTreeStatus(List<StatusEntry> staged, List<StatusEntry> unstaged) {}

    public record BranchInfo(String name, String shortName, boolean remote,
                             boolean current, int ahead, int behind) {}

    public record DiffFile(String path, String changeType, String diffText) {}

    private Repository repo;
    private Git git;

    public boolean isOpen() {
        return git != null;
    }

    public void open(Path dir) throws IOException {
        close();
        FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(dir.toFile());
        if (builder.getGitDir() == null) {
            throw new IOException("No se encontró un repositorio Git en " + dir);
        }
        repo = builder.build();
        git = new Git(repo);
    }

    @Override
    public void close() {
        if (git != null) {
            git.close();
            git = null;
            repo = null;
        }
    }

    public String currentBranch() throws IOException {
        String branch = repo.getBranch();
        if (branch == null) {
            return "(repositorio vacío)";
        }
        if (ObjectId.isId(branch)) {
            return "HEAD separado @ " + branch.substring(0, 7);
        }
        return branch;
    }

    public List<CommitInfo> loadHistory(int max) throws IOException {
        List<CommitInfo> commits = new ArrayList<>();
        ObjectId head = repo.resolve(Constants.HEAD);
        if (head == null) {
            return commits;
        }
        Map<String, List<CommitInfo.RefLabel>> labels = refLabels();
        try (RevWalk walk = new RevWalk(repo)) {
            walk.sort(RevSort.TOPO);
            walk.sort(RevSort.COMMIT_TIME_DESC);
            walk.markStart(walk.parseCommit(head));
            for (Ref ref : repo.getRefDatabase().getRefs()) {
                try {
                    RevObject obj = walk.parseAny(ref.getObjectId());
                    while (obj instanceof RevTag tag) {
                        obj = walk.parseAny(tag.getObject().getId());
                    }
                    if (obj instanceof RevCommit rc) {
                        walk.markStart(rc);
                    }
                } catch (Exception ignored) {
                    // Referencia rota o no apuntando a un commit: se ignora.
                }
            }
            int count = 0;
            for (RevCommit c : walk) {
                List<String> parents = new ArrayList<>();
                for (RevCommit parent : c.getParents()) {
                    parents.add(parent.getId().getName());
                }
                PersonIdent author = c.getAuthorIdent();
                commits.add(new CommitInfo(
                        c.getId().getName(),
                        c.getId().getName().substring(0, 7),
                        c.getShortMessage(),
                        c.getFullMessage(),
                        author.getName(),
                        author.getEmailAddress(),
                        author.getWhen().toInstant(),
                        parents,
                        labels.getOrDefault(c.getId().getName(), List.of())));
                if (++count >= max) {
                    break;
                }
            }
        }
        return commits;
    }

    private Map<String, List<CommitInfo.RefLabel>> refLabels() throws IOException {
        Map<String, List<CommitInfo.RefLabel>> map = new LinkedHashMap<>();
        String head = repo.getBranch();
        for (Ref ref : repo.getRefDatabase().getRefs()) {
            String name = ref.getName();
            String commitId = ref.getObjectId().getName();
            CommitInfo.RefLabel.Type type;
            if (name.startsWith(Constants.R_HEADS)) {
                type = CommitInfo.RefLabel.Type.LOCAL;
            } else if (name.startsWith(Constants.R_REMOTES)) {
                type = CommitInfo.RefLabel.Type.REMOTE;
            } else if (name.startsWith(Constants.R_TAGS)) {
                type = CommitInfo.RefLabel.Type.TAG;
                Ref peeled = repo.getRefDatabase().peel(ref);
                if (peeled.getPeeledObjectId() != null) {
                    commitId = peeled.getPeeledObjectId().getName();
                }
            } else {
                continue;
            }
            String shortName = Repository.shortenRefName(name);
            boolean current = type == CommitInfo.RefLabel.Type.LOCAL && shortName.equals(head);
            map.computeIfAbsent(commitId, k -> new ArrayList<>())
                    .add(new CommitInfo.RefLabel(shortName, type, current));
        }
        return map;
    }

    public WorkingTreeStatus status() throws GitAPIException {
        Status s = git.status().call();
        List<StatusEntry> staged = new ArrayList<>();
        s.getAdded().forEach(p -> staged.add(new StatusEntry(p, "Nuevo")));
        s.getChanged().forEach(p -> staged.add(new StatusEntry(p, "Modificado")));
        s.getRemoved().forEach(p -> staged.add(new StatusEntry(p, "Eliminado")));
        List<StatusEntry> unstaged = new ArrayList<>();
        s.getModified().forEach(p -> unstaged.add(new StatusEntry(p, "Modificado")));
        s.getMissing().forEach(p -> unstaged.add(new StatusEntry(p, "Eliminado")));
        s.getUntracked().forEach(p -> unstaged.add(new StatusEntry(p, "Nuevo")));
        s.getConflicting().forEach(p -> unstaged.add(new StatusEntry(p, "Conflicto")));
        staged.sort(Comparator.comparing(StatusEntry::path));
        unstaged.sort(Comparator.comparing(StatusEntry::path));
        return new WorkingTreeStatus(staged, unstaged);
    }

    public void stage(String path, boolean deleted) throws GitAPIException {
        if (deleted) {
            git.rm().addFilepattern(path).call();
        } else {
            git.add().addFilepattern(path).call();
        }
    }

    public void stageAll() throws GitAPIException {
        git.add().addFilepattern(".").call();
        git.add().addFilepattern(".").setUpdate(true).call();
    }

    public void unstage(String path) throws GitAPIException {
        git.reset().addPath(path).call();
    }

    public void unstageAll() throws GitAPIException {
        git.reset().call();
    }

    public void commit(String message) throws GitAPIException {
        applySigning(git.commit().setMessage(message)).call();
    }

    /** Aplica la firma GPG si commit.gpgsign está activo (local o global). */
    private org.eclipse.jgit.api.CommitCommand applySigning(
            org.eclipse.jgit.api.CommitCommand cmd) {
        StoredConfig config = repo.getConfig();
        if (config.getBoolean("commit", "gpgsign", false)) {
            cmd.setSign(true);
            String key = config.getString("user", null, "signingkey");
            if (key != null) {
                cmd.setSigningKey(key);
            }
        }
        return cmd;
    }

    /** Enmienda el último commit; con message null conserva el mensaje original. */
    public void amend(String message) throws GitAPIException, IOException {
        var cmd = git.commit().setAmend(true);
        if (message != null && !message.isBlank()) {
            cmd.setMessage(message);
        } else {
            // JGit exige mensaje incluso en amend: se reutiliza el original.
            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit head = walk.parseCommit(repo.resolve(Constants.HEAD));
                cmd.setMessage(head.getFullMessage());
            }
        }
        applySigning(cmd).call();
    }

    /** Descarta los cambios de un archivo con seguimiento (checkout -- path). */
    public void discardChanges(String path) throws GitAPIException {
        git.checkout().addPath(path).call();
    }

    /**
     * Contenido de un archivo en un commit. Devuelve null si no existe en él
     * o si es binario (contiene NUL en los primeros 8 KB).
     */
    public String fileContent(String sha, String path) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(sha));
            try (TreeWalk tw = TreeWalk.forPath(repo, path, commit.getTree())) {
                if (tw == null) {
                    return null;
                }
                byte[] bytes = repo.open(tw.getObjectId(0)).getBytes();
                for (int i = 0; i < Math.min(bytes.length, 8192); i++) {
                    if (bytes[i] == 0) {
                        return null;
                    }
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * Restaura un archivo del worktree a su versión en un commit
     * (checkout &lt;sha&gt; -- &lt;path&gt;) y lo desprepara para que
     * aparezca como cambio sin preparar, descartable con discardChanges.
     */
    public void checkoutFileFromCommit(String sha, String path)
            throws GitAPIException {
        git.checkout().setStartPoint(sha).addPath(path).call();
        git.reset().addPath(path).call();
    }

    /**
     * Elimina un archivo sin seguimiento (descartar un "Nuevo" = borrarlo),
     * con guarda anti path traversal.
     */
    public void deleteUntracked(String path) throws IOException {
        Path root = repo.getWorkTree().toPath().toAbsolutePath().normalize();
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Ruta fuera del repositorio: " + path);
        }
        Files.deleteIfExists(resolved);
    }

    /** Descarta TODOS los cambios: reset --hard y borra archivos sin seguimiento. */
    public void discardAll() throws GitAPIException {
        git.reset().setMode(ResetCommand.ResetType.HARD).call();
        git.clean().setCleanDirectories(true).call();
    }

    /**
     * Añade un patrón al .gitignore de la raíz (lo crea si no existe y evita
     * duplicados). Devuelve false si el patrón ya estaba.
     */
    public boolean addGitignorePattern(String pattern) throws IOException {
        Path gitignore = repo.getWorkTree().toPath().resolve(".gitignore");
        List<String> lines = Files.isRegularFile(gitignore)
                ? new ArrayList<>(Files.readAllLines(gitignore))
                : new ArrayList<>();
        for (String line : lines) {
            if (line.trim().equals(pattern)) {
                return false;
            }
        }
        lines.add(pattern);
        Files.write(gitignore, lines);
        return true;
    }

    /** SHA del commit al que apunta HEAD, o null si la rama aún no nació. */
    public String headSha() throws IOException {
        ObjectId id = repo.resolve(Constants.HEAD);
        return id == null ? null : id.getName();
    }

    /** Nombre de la rama actual, o el SHA de HEAD si está separado. */
    public String headRef() throws IOException {
        return repo.getBranch();
    }

    /** Reset suave a un commit: mueve HEAD y deja los cambios preparados. */
    public void resetSoft(String sha) throws GitAPIException {
        git.reset().setMode(ResetCommand.ResetType.SOFT).setRef(sha).call();
    }

    /** Reset duro a un commit: mueve HEAD y descarta índice y árbol de trabajo. */
    public void resetHard(String sha) throws GitAPIException {
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(sha).call();
    }

    /**
     * Vista previa del mensaje combinado al fusionar (squash) HEAD con su
     * padre, o null si HEAD no es fusionable (sin padre, merge o separado).
     */
    public String squashPreview() throws IOException {
        ObjectId headId = repo.resolve(Constants.HEAD);
        String branch = repo.getBranch();
        if (headId == null || branch == null) {
            return null;
        }
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit head = walk.parseCommit(headId);
            if (head.getParentCount() != 1) {
                return null;
            }
            RevCommit parent = walk.parseCommit(head.getParent(0).getId());
            return parent.getFullMessage().trim() + "\n\n" + head.getFullMessage().trim();
        }
    }

    /**
     * Fusiona HEAD con su padre: nuevo commit con el árbol de HEAD, los
     * padres del padre y el mensaje indicado; la rama se mueve a él.
     *
     * @return SHA del commit anterior (para deshacer).
     */
    public String squashHead(String message) throws IOException {
        ObjectId headId = repo.resolve(Constants.HEAD);
        String branch = repo.getBranch();
        if (headId == null || branch == null) {
            throw new IllegalStateException("HEAD separado: no se puede hacer squash");
        }
        ObjectId newId;
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit head = walk.parseCommit(headId);
            if (head.getParentCount() != 1) {
                throw new IllegalStateException("HEAD debe tener exactamente un padre");
            }
            RevCommit parent = walk.parseCommit(head.getParent(0).getId());
            org.eclipse.jgit.lib.CommitBuilder cb = new org.eclipse.jgit.lib.CommitBuilder();
            cb.setTreeId(head.getTree());
            cb.setParentIds(parent.getParents());
            cb.setAuthor(parent.getAuthorIdent());
            cb.setCommitter(new PersonIdent(repo));
            cb.setMessage(message.endsWith("\n") ? message : message + "\n");
            ObjectInserter inserter = repo.newObjectInserter();
            newId = inserter.insert(cb);
            inserter.flush();
            inserter.close();
        }
        org.eclipse.jgit.lib.RefUpdate ru = repo.updateRef(Constants.R_HEADS + branch);
        ru.setNewObjectId(newId);
        ru.setRefLogMessage("squash: " + headId.getName().substring(0, 7), false);
        ru.forceUpdate();
        return headId.getName();
    }

    /**
     * Cambia el mensaje de un commit de la rama actual (reword), reescribiendo
     * la historia desde él hasta HEAD mediante rebase interactivo. El commit
     * debe tener exactamente un padre (ni raíz ni merge).
     *
     * @return SHA de HEAD anterior (para deshacer con resetHard).
     */
    public String rewordCommit(String sha, String newMessage)
            throws IOException, GitAPIException {
        ObjectId headId = repo.resolve(Constants.HEAD);
        String branch = repo.getBranch();
        if (headId == null || branch == null) {
            throw new IllegalStateException("HEAD separado: no se puede reescribir");
        }
        RevCommit target;
        try (RevWalk walk = new RevWalk(repo)) {
            target = walk.parseCommit(ObjectId.fromString(sha));
            if (!walk.isMergedInto(target, walk.parseCommit(headId))
                    && !target.getId().equals(headId)) {
                throw new IllegalStateException(
                        "El commit no es un ancestro de HEAD");
            }
            if (target.getParentCount() == 0) {
                throw new IllegalStateException(
                        "No se puede reescribir el commit raíz");
            }
            if (target.getParentCount() > 1) {
                throw new IllegalStateException(
                        "No se puede reescribir un commit de merge");
            }
        }
        String message = newMessage.endsWith("\n") ? newMessage : newMessage + "\n";
        if (target.getId().equals(headId)) {
            // Reescritura directa de HEAD: mismo árbol, autores y padres.
            ObjectId newId;
            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit head = walk.parseCommit(headId);
                org.eclipse.jgit.lib.CommitBuilder cb =
                        new org.eclipse.jgit.lib.CommitBuilder();
                cb.setTreeId(head.getTree());
                cb.setParentIds(head.getParents());
                cb.setAuthor(head.getAuthorIdent());
                cb.setCommitter(new PersonIdent(repo));
                cb.setMessage(message);
                ObjectInserter inserter = repo.newObjectInserter();
                newId = inserter.insert(cb);
                inserter.flush();
                inserter.close();
            }
            org.eclipse.jgit.lib.RefUpdate ru =
                    repo.updateRef(Constants.R_HEADS + branch);
            ru.setNewObjectId(newId);
            ru.setRefLogMessage("reword: " + headId.getName().substring(0, 7), false);
            ru.forceUpdate();
            return headId.getName();
        }
        ObjectId targetId = target.getId();
        ObjectId upstream = target.getParent(0).getId();
        boolean[] consumed = {false};
        RebaseResult result = git.rebase().setUpstream(upstream).runInteractively(
                new RebaseCommand.InteractiveHandler() {
                    @Override
                    public void prepareSteps(List<RebaseTodoLine> steps) {
                        for (RebaseTodoLine step : steps) {
                            if (step.getCommit().prefixCompare(targetId) == 0) {
                                try {
                                    step.setAction(RebaseTodoLine.Action.REWORD);
                                } catch (IllegalTodoFileModification e) {
                                    throw new IllegalStateException(
                                            "No se puede marcar reword", e);
                                }
                            }
                        }
                    }

                    @Override
                    public String modifyCommitMessage(String oldMessage) {
                        if (!consumed[0]) {
                            consumed[0] = true;
                            return message;
                        }
                        return oldMessage;
                    }
                }).call();
        if (!result.getStatus().isSuccessful()) {
            throw new IllegalStateException(
                    "El rebase para reword no completó: " + result.getStatus());
        }
        return headId.getName();
    }

    /**
     * Commits entre {@code upstreamSha} (excluido) y HEAD, de más nuevo a
     * más antiguo, para planificar un rebase interactivo.
     */
    public List<CommitInfo> rebaseRange(String upstreamSha)
            throws IOException, GitAPIException {
        List<CommitInfo> out = new ArrayList<>();
        ObjectId headId = repo.resolve(Constants.HEAD);
        ObjectId upstream = ObjectId.fromString(upstreamSha);
        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(headId));
            walk.markUninteresting(walk.parseCommit(upstream));
            for (RevCommit c : walk) {
                List<String> parents = new ArrayList<>();
                for (RevCommit parent : c.getParents()) {
                    parents.add(parent.getId().getName());
                }
                PersonIdent author = c.getAuthorIdent();
                out.add(new CommitInfo(
                        c.getId().getName(),
                        c.getId().getName().substring(0, 7),
                        c.getShortMessage(),
                        c.getFullMessage(),
                        author.getName(),
                        author.getEmailAddress(),
                        author.getWhen().toInstant(),
                        parents,
                        List.of()));
            }
        }
        return out;
    }

    public record RebasePlanEntry(String sha, String action, String message) {}

    /**
     * Rebase interactivo: reescribe los commits entre {@code upstreamSha}
     * (excluido) y HEAD según el plan, dado en orden de aplicación (el
     * commit más antiguo primero). Acciones: "pick", "reword" (usa el
     * mensaje de la entrada), "squash" (fusiona con el anterior) y "drop"
     * (elimina el commit).
     *
     * @return SHA de HEAD anterior (para deshacer con resetHard), o null si
     *         el rebase quedó detenido por conflictos.
     */
    public String interactiveRebase(String upstreamSha, List<RebasePlanEntry> plan)
            throws IOException, GitAPIException {
        ObjectId headId = repo.resolve(Constants.HEAD);
        String branch = repo.getBranch();
        if (headId == null || branch == null) {
            throw new IllegalStateException("HEAD separado: no se puede rebasar");
        }
        String oldHead = headId.getName();
        ObjectId upstream = ObjectId.fromString(upstreamSha);
        // Mensajes originales de los commits con reword: el handler recibe
        // solo el mensaje (no el sha), así que emparejamos por contenido.
        // JGit también llama a modifyCommitMessage al cerrar un squash, y un
        // amend con mensaje idéntico falla con NO_CHANGE: por eso el reword
        // sin cambios se degrada a pick.
        Map<String, String> effectiveAction = new LinkedHashMap<>();
        java.util.ArrayDeque<String[]> rewordMessages = new java.util.ArrayDeque<>();
        try (RevWalk walk = new RevWalk(repo)) {
            for (RebasePlanEntry entry : plan) {
                String action = entry.action();
                if ("reword".equals(action)) {
                    String original = walk.parseCommit(
                            ObjectId.fromString(entry.sha())).getFullMessage().trim();
                    String wanted = entry.message() == null
                            ? "" : entry.message().trim();
                    if (wanted.isEmpty() || wanted.equals(original)) {
                        action = "pick";
                    } else {
                        String m = wanted.endsWith("\n") ? wanted : wanted + "\n";
                        rewordMessages.add(new String[]{original, m});
                    }
                }
                effectiveAction.put(entry.sha(), action);
            }
        }
        RebaseResult result = git.rebase().setUpstream(upstream).runInteractively(
                new RebaseCommand.InteractiveHandler() {
                    @Override
                    public void prepareSteps(List<RebaseTodoLine> steps) {
                        Map<String, RebaseTodoLine> remaining = new LinkedHashMap<>();
                        for (RebaseTodoLine step : steps) {
                            remaining.put(step.getCommit().name(), step);
                        }
                        List<RebaseTodoLine> ordered = new ArrayList<>();
                        for (RebasePlanEntry entry : plan) {
                            String key = remaining.keySet().stream()
                                    .filter(k -> entry.sha().startsWith(k))
                                    .findFirst().orElse(null);
                            if (key == null) {
                                continue;
                            }
                            RebaseTodoLine step = remaining.remove(key);
                            try {
                                switch (effectiveAction.get(entry.sha())) {
                                    case "reword" ->
                                        step.setAction(RebaseTodoLine.Action.REWORD);
                                    case "squash" ->
                                        step.setAction(RebaseTodoLine.Action.SQUASH);
                                    case "drop" -> {
                                        // No se añade a la lista: se elimina.
                                        continue;
                                    }
                                    default ->
                                        step.setAction(RebaseTodoLine.Action.PICK);
                                }
                            } catch (IllegalTodoFileModification e) {
                                throw new IllegalStateException(
                                        "No se puede aplicar la acción del plan", e);
                            }
                            ordered.add(step);
                        }
                        ordered.addAll(remaining.values());
                        steps.clear();
                        steps.addAll(ordered);
                    }

                    @Override
                    public String modifyCommitMessage(String oldMessage) {
                        String[] head = rewordMessages.peek();
                        if (head != null && oldMessage.trim().equals(head[0])) {
                            rewordMessages.poll();
                            return head[1];
                        }
                        return oldMessage;
                    }
                }).call();
        if (result.getStatus() == RebaseResult.Status.STOPPED) {
            return null; // Conflictos: la UI ofrece resolverlos o abortar.
        }
        if (!result.getStatus().isSuccessful()) {
            throw new IllegalStateException(
                    "El rebase interactivo no completó: " + result.getStatus());
        }
        return oldHead;
    }

    /** true si no hay cambios sin commitear (índice ni árbol de trabajo). */
    public boolean isClean() throws GitAPIException {
        return git.status().call().isClean();
    }

    /** Aplica un commit sobre HEAD (cherry-pick). Lanza excepción si hay conflicto. */
    public void cherryPick(String sha) throws GitAPIException {
        git.cherryPick().include(ObjectId.fromString(sha)).call();
    }

    /** Crea un commit que revierte los cambios del commit dado. */
    public void revert(String sha) throws GitAPIException {
        git.revert().include(ObjectId.fromString(sha)).call();
    }

    /**
     * Mergea una rama en la actual.
     * @return estado del merge (FAST_FORWARD, MERGED, CONFLICTING, …)
     */
    public String merge(String branchName) throws GitAPIException, IOException {
        ObjectId id = repo.resolve(branchName);
        if (id == null) {
            throw new IOException("No se encontró la rama: " + branchName);
        }
        return git.merge().include(id).call().getMergeStatus().name();
    }

    /** true si hay un merge (o cherry-pick/revert) en curso. */
    public boolean isMerging() throws IOException {
        return repo.findRef(Constants.MERGE_HEAD) != null
                || repo.findRef(Constants.CHERRY_PICK_HEAD) != null
                || repo.findRef(Constants.REVERT_HEAD) != null;
    }

    /** true si hay un rebase en curso. */
    public boolean isRebasing() {
        return repo.getRepositoryState().name().startsWith("REBASING");
    }

    /** true si hay cualquier operación (merge, rebase, cherry-pick…) en curso. */
    public boolean isOperationInProgress() throws IOException {
        return isMerging() || isRebasing();
    }

    /**
     * Rebasa la rama actual sobre otra.
     * @return estado (OK, FAST_FORWARD, UP_TO_DATE, STOPPED=conflicto, …)
     */
    public String rebase(String upstream) throws GitAPIException {
        return git.rebase().setUpstream(upstream).call().getStatus().name();
    }

    /** Aborta la operación en curso: rebase --abort o merge --abort. */
    public void abortOperation() throws GitAPIException {
        if (isRebasing()) {
            git.rebase().setOperation(RebaseCommand.Operation.ABORT).call();
        } else {
            git.reset().setMode(ResetCommand.ResetType.HARD).call();
        }
    }

    /**
     * Continúa la operación en curso: rebase --continue o crea el commit de
     * merge. Solo válido cuando no quedan conflictos.
     */
    public void continueOperation(String message) throws GitAPIException {
        if (isRebasing()) {
            Status s = git.status().call();
            boolean nothingStaged = s.getAdded().isEmpty() && s.getChanged().isEmpty()
                    && s.getRemoved().isEmpty();
            git.rebase().setOperation(nothingStaged
                    ? RebaseCommand.Operation.SKIP
                    : RebaseCommand.Operation.CONTINUE).call();
            return;
        }
        var cmd = git.commit();
        if (message != null && !message.isBlank()) {
            cmd.setMessage(message);
        }
        cmd.call();
    }

    /** Escribe el contenido resuelto de un archivo en conflicto y lo prepara. */
    public void resolveConflict(String path, String resolvedText)
            throws IOException, GitAPIException {
        Path root = repo.getWorkTree().toPath().toAbsolutePath().normalize();
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Ruta fuera del repositorio: " + path);
        }
        Files.writeString(resolved, resolvedText);
        git.add().addFilepattern(path).call();
    }

    /** Contenido actual (con marcadores de conflicto) de un archivo. */
    public String workingFileContent(String path) throws IOException {
        Path root = repo.getWorkTree().toPath().toAbsolutePath().normalize();
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Ruta fuera del repositorio: " + path);
        }
        return Files.readString(resolved);
    }

    /**
     * Resuelve automáticamente los marcadores de conflicto quedándose con un
     * lado: {@code ours} = la versión de HEAD, si no la de la rama mergeada.
     * Los bloques sin marcadores se conservan.
     */
    public static String resolveWithSide(String content, boolean ours) {
        StringBuilder out = new StringBuilder();
        String[] lines = content.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            if (lines[i].startsWith("<<<<<<<")) {
                StringBuilder our = new StringBuilder();
                StringBuilder their = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].startsWith("=======")) {
                    our.append(lines[i++]).append('\n');
                }
                i++; // salta =======
                while (i < lines.length && !lines[i].startsWith(">>>>>>>")) {
                    their.append(lines[i++]).append('\n');
                }
                i++; // salta >>>>>>>
                out.append(ours ? our : their);
            } else {
                out.append(lines[i]);
                if (i < lines.length - 1) {
                    out.append('\n');
                }
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Diff de un archivo con cambios sin commitear: contra el índice si
     * {@code staged} es falso (cambios sin preparar) o contra HEAD si es
     * verdadero (cambios preparados).
     */
    public String workingTreeDiff(String path, boolean staged) throws GitAPIException {
        return workingTreeDiff(path, staged, false);
    }

    /**
     * Igual que {@link #workingTreeDiff(String, boolean)}, pero con
     * {@code ignoreWs} omite los cambios que solo alteran espacios en blanco
     * (como {@code git diff -w}).
     */
    public String workingTreeDiff(String path, boolean staged, boolean ignoreWs)
            throws GitAPIException {
        try {
            ObjectId headId = repo.resolve(Constants.HEAD);
            try (ObjectReader reader = repo.newObjectReader()) {
                AbstractTreeIterator oldTree;
                if (staged) {
                    if (headId == null) {
                        oldTree = new EmptyTreeIterator();
                    } else {
                        try (RevWalk walk = new RevWalk(repo)) {
                            oldTree = new CanonicalTreeParser(null, reader,
                                    walk.parseCommit(headId).getTree().getId());
                        }
                    }
                } else {
                    oldTree = new DirCacheIterator(repo.readDirCache());
                }
                AbstractTreeIterator newTree = staged
                        ? new DirCacheIterator(repo.readDirCache())
                        : new FileTreeIterator(repo);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (DiffFormatter formatter = new DiffFormatter(out)) {
                    formatter.setRepository(repo);
                    if (path != null) {
                        formatter.setPathFilter(PathFilter.create(path));
                    }
                    if (ignoreWs) {
                        formatter.setDiffComparator(RawTextComparator.WS_IGNORE_ALL);
                    }
                    // El scan configura el content source del working tree;
                    // hay que reusar ESTE formatter para formatear luego.
                    for (DiffEntry entry : formatter.scan(oldTree, newTree)) {
                        // Con ignore-espacios, un cambio de solo whitespace
                        // produce un FileHeader sin hunks: se omite entero.
                        if (ignoreWs && formatter.toFileHeader(entry).toEditList().isEmpty()) {
                            continue;
                        }
                        formatter.format(entry);
                    }
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new GitAPIException("No se pudo calcular el diff: " + e.getMessage(), e) {};
        }
    }

    /** Contenido de un archivo sin seguimiento (los untracked no tienen diff). */
    public String untrackedContent(String path) throws IOException {
        Path root = repo.getWorkTree().toPath().toAbsolutePath().normalize();
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Ruta fuera del repositorio: " + path);
        }
        if (Files.size(resolved) > 1_000_000) {
            return "(archivo demasiado grande para mostrar)";
        }
        return Files.readString(resolved);
    }

    // ── Staging por hunks ────────────────────────────────────────────────

    /** Un hunk de diff: rangos de líneas en el lado viejo (A) y nuevo (B). */
    public record Hunk(int beginA, int endA, int beginB, int endB) {}

    /**
     * Hunks del diff de un archivo (HEAD→working tree, o HEAD→índice si
     * {@code staged}), en el mismo orden en que aparecen en el diff textual.
     */
    public List<Hunk> diffHunks(String path, boolean staged) throws GitAPIException, IOException {
        ObjectId headId = repo.resolve(Constants.HEAD);
        try (ObjectReader reader = repo.newObjectReader();
             DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            formatter.setRepository(repo);
            formatter.setPathFilter(PathFilter.create(path));
            AbstractTreeIterator oldTree;
            if (headId == null) {
                oldTree = new EmptyTreeIterator();
            } else {
                try (RevWalk walk = new RevWalk(repo)) {
                    oldTree = new CanonicalTreeParser(null, reader, walk.parseTree(headId).getId());
                }
            }
            AbstractTreeIterator newTree = staged
                    ? new DirCacheIterator(repo.readDirCache())
                    : new FileTreeIterator(repo);
            List<DiffEntry> entries = formatter.scan(oldTree, newTree);
            if (entries.isEmpty()) {
                return List.of();
            }
            // Agrupa edits en hunks como hace el diff textual: los edits se
            // fusionan si hay 2*contexto (6) líneas o menos entre ellos.
            List<Hunk> hunks = new ArrayList<>();
            int beginA = -1, endA = 0, beginB = -1, endB = 0;
            for (Edit edit : formatter.toFileHeader(entries.get(0)).toEditList()) {
                if (beginA >= 0 && edit.getBeginA() - endA <= 6) {
                    endA = Math.max(endA, edit.getEndA());
                    endB = Math.max(endB, edit.getEndB());
                    continue;
                }
                if (beginA >= 0) {
                    hunks.add(new Hunk(beginA, endA, beginB, endB));
                }
                beginA = edit.getBeginA();
                endA = edit.getEndA();
                beginB = edit.getBeginB();
                endB = edit.getEndB();
            }
            if (beginA >= 0) {
                hunks.add(new Hunk(beginA, endA, beginB, endB));
            }
            return hunks;
        }
    }

    /** Prepara solo los hunks indicados del archivo en el índice. */
    public void stageHunks(String path, List<Integer> hunkIndexes) throws GitAPIException, IOException {
        String head = headFileContent(path);
        String work = workingFileContent(path);
        List<Hunk> hunks = diffHunks(path, false);
        List<String> aLines = splitLines(head);
        List<String> bLines = splitLines(work);
        List<String> result = new ArrayList<>();
        int aPos = 0;
        for (int i = 0; i < hunks.size(); i++) {
            Hunk h = hunks.get(i);
            result.addAll(aLines.subList(aPos, h.beginA()));
            result.addAll(hunkIndexes.contains(i)
                    ? bLines.subList(h.beginB(), h.endB())
                    : aLines.subList(h.beginA(), h.endA()));
            aPos = h.endA();
        }
        result.addAll(aLines.subList(aPos, aLines.size()));
        setIndexContent(path, joinLines(result, work));
    }

    /** Retira del índice los hunks indicados (vuelven a ser cambios sin preparar). */
    public void unstageHunks(String path, List<Integer> hunkIndexes) throws GitAPIException, IOException {
        String head = headFileContent(path);
        String index = indexFileContent(path);
        if (index == null) {
            return;
        }
        List<Hunk> hunks = diffHunks(path, true);
        List<String> aLines = splitLines(head);
        List<String> bLines = splitLines(index);
        List<String> result = new ArrayList<>();
        int bPos = 0;
        for (int i = 0; i < hunks.size(); i++) {
            Hunk h = hunks.get(i);
            result.addAll(bLines.subList(bPos, h.beginB()));
            result.addAll(hunkIndexes.contains(i)
                    ? aLines.subList(h.beginA(), h.endA())
                    : bLines.subList(h.beginB(), h.endB()));
            bPos = h.endB();
        }
        result.addAll(bLines.subList(bPos, bLines.size()));
        setIndexContent(path, joinLines(result, index));
    }

    /** Contenido del archivo en HEAD, o null si no existe allí. */
    public String headFileContent(String path) throws IOException {
        HeadEntry head = headFileEntry(path);
        return head == null ? null : readBlob(head.id());
    }

    private record HeadEntry(ObjectId id, FileMode mode) {}

    /** Entrada del archivo en el árbol de HEAD, o null si no existe allí. */
    private HeadEntry headFileEntry(String path) throws IOException {
        ObjectId head = repo.resolve(Constants.HEAD);
        if (head == null) {
            return null;
        }
        try (RevWalk walk = new RevWalk(repo)) {
            RevTree tree = walk.parseTree(head);
            try (TreeWalk tw = TreeWalk.forPath(repo, path, tree)) {
                if (tw == null) {
                    return null;
                }
                return new HeadEntry(tw.getObjectId(0), tw.getFileMode(0));
            }
        }
    }

    private String readBlob(ObjectId id) throws IOException {
        return new String(repo.open(id).getBytes(), StandardCharsets.UTF_8);
    }

    /** Contenido del archivo en el índice, o null si no está. */
    private String indexFileContent(String path) throws IOException {
        DirCacheEntry entry = repo.readDirCache().getEntry(path);
        if (entry == null) {
            return null;
        }
        return readBlob(entry.getObjectId());
    }

    /**
     * Escribe contenido en el índice. Si coincide con HEAD restaura la entrada
     * de HEAD (o la borra si el archivo no existía en HEAD, p. ej. un nuevo).
     */
    private void setIndexContent(String path, String content) throws IOException {
        HeadEntry head = headFileEntry(path);
        String headContent = head == null ? null : readBlob(head.id());
        DirCache cache = repo.lockDirCache();
        try {
            DirCacheEditor editor = cache.editor();
            if (Objects.equals(content, headContent)) {
                if (head == null) {
                    editor.add(new DirCacheEditor.DeletePath(path));
                } else {
                    editor.add(new DirCacheEditor.PathEdit(path) {
                        @Override
                        public void apply(DirCacheEntry entry) {
                            entry.setObjectId(head.id());
                            entry.setFileMode(head.mode());
                        }
                    });
                }
            } else {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                ObjectInserter inserter = repo.newObjectInserter();
                ObjectId blob = inserter.insert(Constants.OBJ_BLOB, bytes);
                inserter.flush();
                inserter.close();
                editor.add(new DirCacheEditor.PathEdit(path) {
                    @Override
                    public void apply(DirCacheEntry entry) {
                        entry.setObjectId(blob);
                        entry.setFileMode(FileMode.REGULAR_FILE);
                        entry.setLength(bytes.length);
                    }
                });
            }
            if (!editor.commit()) {
                throw new IOException("No se pudo actualizar el índice para " + path);
            }
        } finally {
            cache.unlock();
        }
    }

    private static List<String> splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /** Une líneas conservando si el original terminaba (o no) en nueva línea. */
    private static String joinLines(List<String> lines, String original) {
        if (lines.isEmpty()) {
            return "";
        }
        String joined = String.join("\n", lines);
        if (original != null && original.endsWith("\n")) {
            return joined + "\n";
        }
        return joined;
    }

    public String identityName() {
        return repo.getConfig().getString("user", null, "name");
    }

    public String identityEmail() {
        return repo.getConfig().getString("user", null, "email");
    }

    public void setIdentity(String name, String email) throws IOException {
        StoredConfig config = repo.getConfig();
        config.setString("user", null, "name", name);
        config.setString("user", null, "email", email);
        config.save();
    }

    /** Quita la identidad propia del repo para que vuelva a usarse la global. */
    public void clearIdentity() throws IOException {
        StoredConfig config = repo.getConfig();
        config.unset("user", null, "name");
        config.unset("user", null, "email");
        config.save();
    }

    /** Identidad definida solo en .git/config (sin heredar la global), o null. */
    public String localIdentityName() {
        return localConfig().getString("user", null, "name");
    }

    public String localIdentityEmail() {
        return localConfig().getString("user", null, "email");
    }

    private org.eclipse.jgit.lib.Config localConfig() {
        try {
            org.eclipse.jgit.storage.file.FileBasedConfig config =
                    new org.eclipse.jgit.storage.file.FileBasedConfig(
                            new java.io.File(repo.getDirectory(), "config"),
                            repo.getFS());
            config.load();
            return config;
        } catch (IOException | org.eclipse.jgit.errors.ConfigInvalidException e) {
            return new org.eclipse.jgit.lib.Config();
        }
    }

    public record ReflogEntryInfo(String oldId, String newId, String shortNewId,
                                  String message, String author, Instant when) {}

    /** Reflog de HEAD, de más reciente a más antiguo (para recuperar commits). */
    public List<ReflogEntryInfo> reflog() throws GitAPIException {
        List<ReflogEntryInfo> out = new ArrayList<>();
        for (org.eclipse.jgit.lib.ReflogEntry entry : git.reflog().call()) {
            String newId = entry.getNewId().getName();
            PersonIdent who = entry.getWho();
            out.add(new ReflogEntryInfo(
                    entry.getOldId().getName(),
                    newId,
                    newId.substring(0, 7),
                    entry.getComment(),
                    who != null ? who.getName() : "",
                    who != null ? who.getWhen().toInstant() : null));
        }
        return out;
    }

    /** URL del remoto indicado (normalmente "origin"), o null si no existe. */
    public String remoteUrl(String remote) {
        return repo.getConfig().getString("remote", remote, "url");
    }

    public record RemoteInfo(String name, String url) {}

    /** Remotos configurados en el repo, ordenados por nombre. */
    public List<RemoteInfo> remotes() {
        List<RemoteInfo> out = new ArrayList<>();
        for (String name : repo.getConfig().getSubsections("remote")) {
            out.add(new RemoteInfo(name, repo.getConfig().getString("remote", name, "url")));
        }
        out.sort(Comparator.comparing(RemoteInfo::name));
        return out;
    }

    public void addRemote(String name, String url) throws IOException {
        StoredConfig config = repo.getConfig();
        config.setString("remote", name, "url", url);
        config.setString("remote", name, "fetch",
                "+refs/heads/*:refs/remotes/" + name + "/*");
        config.save();
    }

    /** Elimina el remoto y sus referencias de seguimiento (refs/remotes/name/*). */
    public void removeRemote(String name) throws IOException, GitAPIException {
        StoredConfig config = repo.getConfig();
        config.unsetSection("remote", name);
        String branch = repo.getBranch();
        if (branch != null && name.equals(config.getString("branch", branch, "remote"))) {
            config.unset("branch", branch, "remote");
            config.unset("branch", branch, "merge");
        }
        config.save();
        for (Ref ref : repo.getRefDatabase().getRefsByPrefix(Constants.R_REMOTES + name + "/")) {
            git.branchDelete().setBranchNames(ref.getName()).setForce(true).call();
        }
    }

    /** Inicializa un repositorio nuevo en el directorio indicado. */
    public static void initRepo(Path directory) throws GitAPIException {
        Git.init().setDirectory(directory.toFile()).call().close();
    }

    public List<BranchInfo> branches(boolean remote) throws GitAPIException, IOException {
        ListBranchCommand cmd = git.branchList();
        if (remote) {
            cmd.setListMode(ListBranchCommand.ListMode.REMOTE);
        }
        String current = repo.getBranch();
        List<BranchInfo> out = new ArrayList<>();
        for (Ref ref : cmd.call()) {
            String shortName = Repository.shortenRefName(ref.getName());
            int ahead = 0;
            int behind = 0;
            if (!remote) {
                String tracking = new org.eclipse.jgit.lib.BranchConfig(
                        repo.getConfig(), shortName).getTrackingBranch();
                if (tracking != null) {
                    ObjectId localId = ref.getObjectId();
                    ObjectId upstreamId = repo.resolve(tracking);
                    if (upstreamId != null && !upstreamId.equals(localId)) {
                        for (RevCommit ignored : git.log()
                                .addRange(upstreamId, localId).call()) {
                            ahead++;
                        }
                        for (RevCommit ignored : git.log()
                                .addRange(localId, upstreamId).call()) {
                            behind++;
                        }
                    }
                }
            }
            out.add(new BranchInfo(ref.getName(), shortName, remote,
                    !remote && shortName.equals(current), ahead, behind));
        }
        out.sort(Comparator.comparing(BranchInfo::shortName));
        return out;
    }

    public List<String> tags() throws GitAPIException {
        List<String> out = new ArrayList<>();
        for (Ref ref : git.tagList().call()) {
            out.add(Repository.shortenRefName(ref.getName()));
        }
        out.sort(String::compareTo);
        return out;
    }

    public boolean isValidTagName(String name) {
        return Repository.isValidRefName(Constants.R_TAGS + name);
    }

    /**
     * Crea una etiqueta en un commit. Si {@code message} es null o vacío es
     * ligera; si no, anotada.
     */
    public void createTag(String name, String sha, String message) throws IOException, GitAPIException {
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit target = walk.parseCommit(ObjectId.fromString(sha));
            var cmd = git.tag().setName(name).setObjectId(target);
            if (message != null && !message.isBlank()) {
                cmd.setMessage(message).setAnnotated(true);
            }
            cmd.call();
        }
    }

    public void deleteTag(String name) throws GitAPIException {
        git.tagDelete().setTags(name).call();
    }

    /** SHA del commit al que apunta una etiqueta (pelando las anotadas). */
    public String tagCommitSha(String name) throws IOException {
        Ref ref = repo.findRef(Constants.R_TAGS + name);
        if (ref == null) {
            throw new IOException("No se encontró la etiqueta: " + name);
        }
        Ref peeled = repo.getRefDatabase().peel(ref);
        ObjectId id = peeled.getPeeledObjectId() != null
                ? peeled.getPeeledObjectId() : ref.getObjectId();
        return id.getName();
    }

    /** Sube todas las etiquetas locales al remoto por defecto. */
    public void pushTags(CredentialsProvider credentials, BiConsumer<String, Double> progress)
            throws GitAPIException {
        checkPushResult(git.push().setPushTags().setCredentialsProvider(credentials)
                .setProgressMonitor(monitor(progress)).call());
    }

    public boolean isValidBranchName(String name) {
        return Repository.isValidRefName(Constants.R_HEADS + name);
    }

    public void createBranch(String name, boolean checkout) throws GitAPIException {
        git.branchCreate().setName(name).call();
        if (checkout) {
            git.checkout().setName(name).call();
        }
    }

    public void createBranchAt(String name, String startPoint) throws GitAPIException {
        git.branchCreate().setName(name).setStartPoint(startPoint).call();
    }

    public void checkout(String name) throws GitAPIException {
        git.checkout().setName(name).call();
    }

    public void checkoutRemote(String remoteShortName, String localName) throws GitAPIException {
        git.checkout()
                .setCreateBranch(true)
                .setName(localName)
                .setStartPoint(remoteShortName)
                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                .call();
    }

    public void deleteBranch(String shortName) throws GitAPIException {
        git.branchDelete().setBranchNames(shortName).setForce(true).call();
    }

    /** Renombra una rama local, llevándose su upstream si apuntaba a sí misma. */
    public void renameBranch(String oldName, String newName) throws GitAPIException, IOException {
        StoredConfig config = repo.getConfig();
        String remote = config.getString("branch", oldName, "remote");
        String merge = config.getString("branch", oldName, "merge");
        git.branchRename().setOldName(oldName).setNewName(newName).call();
        // JGit mueve la sección branch.<old> a branch.<new>, pero el upstream
        // sigue apuntando al nombre viejo si era local.
        if (merge != null && merge.equals(Constants.R_HEADS + oldName)) {
            config.setString("branch", newName, "merge", Constants.R_HEADS + newName);
            config.save();
        }
    }

    public record StashEntry(int index, String message) {}

    public List<StashEntry> stashList() throws GitAPIException {
        List<StashEntry> out = new ArrayList<>();
        int i = 0;
        for (RevCommit c : git.stashList().call()) {
            out.add(new StashEntry(i++, c.getShortMessage()));
        }
        return out;
    }

    // ── Submódulos ───────────────────────────────────────────────────────

    public record SubmoduleInfo(String name, String path, String url, boolean initialized) {}

    /** Submódulos declarados en el índice/.gitmodules del repo. */
    public List<SubmoduleInfo> submodules() throws IOException, org.eclipse.jgit.errors.ConfigInvalidException {
        List<SubmoduleInfo> out = new ArrayList<>();
        try (org.eclipse.jgit.submodule.SubmoduleWalk walk =
                     org.eclipse.jgit.submodule.SubmoduleWalk.forIndex(repo)) {
            while (walk.next()) {
                Path gitMarker = repo.getWorkTree().toPath()
                        .resolve(walk.getPath()).resolve(".git");
                out.add(new SubmoduleInfo(
                        walk.getModuleName(),
                        walk.getPath(),
                        walk.getRemoteUrl(),
                        Files.exists(gitMarker)));
            }
        }
        out.sort(Comparator.comparing(SubmoduleInfo::path));
        return out;
    }

    /** Añade un submódulo: lo clona en {@code path} y lo registra. */
    public void addSubmodule(String url, String path) throws GitAPIException {
        git.submoduleAdd().setURI(url).setPath(path).call().close();
    }

    /** Inicializa (si hace falta) y actualiza el submódulo a su commit fijado. */
    public void updateSubmodule(String path) throws GitAPIException, IOException {
        git.submoduleInit().addPath(path).call();
        // Tras un 'git submodule deinit' queda el repo en .git/modules/<path>
        // pero sin el puntero .git en el worktree; JGit intentaría clonar de
        // nuevo sobre un directorio ya ocupado. Recreamos el puntero.
        Path modulesDir = repo.getDirectory().toPath().resolve("modules").resolve(path);
        Path worktree = repo.getWorkTree().toPath().resolve(path);
        Path gitFile = worktree.resolve(".git");
        if (Files.isDirectory(modulesDir.resolve("objects")) && !Files.exists(gitFile)) {
            Files.createDirectories(worktree);
            Files.writeString(gitFile, "gitdir: " + worktree.relativize(modulesDir) + "\n");
        }
        git.submoduleUpdate().addPath(path).call();
    }

    // ── Worktrees (vía git CLI: JGit 7.1 no tiene API de worktrees) ─────

    public record WorktreeInfo(String path, String headSha, String branch,
                               boolean bare, boolean main) {}

    /** Ejecuta el git CLI en el worktree del repo. Devuelve stdout+stderr. */
    private String runGitCli(List<String> args)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(args);
        Process process = new ProcessBuilder(cmd)
                .directory(repo.getWorkTree())
                .redirectErrorStream(true)
                .start();
        String out = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        int code = process.waitFor();
        if (code != 0) {
            throw new IOException("git " + String.join(" ", args) + " falló ("
                    + code + "): " + out.trim());
        }
        return out;
    }

    /** Worktrees del repositorio (el primero es el principal). */
    public List<WorktreeInfo> worktrees()
            throws IOException, InterruptedException {
        String out = runGitCli(List.of("worktree", "list", "--porcelain"));
        List<WorktreeInfo> result = new ArrayList<>();
        String path = null;
        String sha = null;
        String branch = null;
        boolean bare = false;
        for (String line : out.split("\n")) {
            if (line.startsWith("worktree ")) {
                if (path != null) {
                    result.add(new WorktreeInfo(path, sha, branch, bare,
                            result.isEmpty()));
                }
                path = line.substring("worktree ".length());
                sha = null;
                branch = null;
                bare = false;
            } else if (line.startsWith("HEAD ")) {
                sha = line.substring(5);
            } else if (line.startsWith("branch ")) {
                branch = line.substring(7).replaceFirst("^refs/heads/", "");
            } else if (line.equals("bare")) {
                bare = true;
            }
        }
        if (path != null) {
            result.add(new WorktreeInfo(path, sha, branch, bare,
                    result.isEmpty()));
        }
        return result;
    }

    /** Crea un worktree en {@code path} con la rama dada (nueva o existente). */
    public void worktreeAdd(String path, String branch, boolean createBranch)
            throws IOException, InterruptedException {
        if (createBranch) {
            runGitCli(List.of("worktree", "add", "-b", branch, path));
        } else {
            runGitCli(List.of("worktree", "add", path, branch));
        }
    }

    /** Elimina un worktree (no el principal). */
    public void worktreeRemove(String path, boolean force)
            throws IOException, InterruptedException {
        if (force) {
            runGitCli(List.of("worktree", "remove", "--force", path));
        } else {
            runGitCli(List.of("worktree", "remove", path));
        }
    }

    // ── LFS ──────────────────────────────────────────────────────────────

    /** true si el módulo LFS de JGit está en el classpath (soporte nativo). */
    public boolean lfsAvailable() {
        return org.eclipse.jgit.util.LfsFactory.getInstance().isAvailable();
    }

    /** true si LFS está activado en este repositorio. */
    public boolean lfsEnabled() {
        return org.eclipse.jgit.util.LfsFactory.getInstance().isEnabled(repo);
    }

    /**
     * Activa LFS en el repositorio (filtros clean/smudge nativos de JGit y
     * hook de pre-push). Equivalente a `git lfs install --local`.
     */
    public void lfsInstall() throws Exception {
        var cmd = org.eclipse.jgit.util.LfsFactory.getInstance().getInstallCommand();
        if (cmd == null) {
            throw new IllegalStateException("El módulo LFS de JGit no está disponible");
        }
        cmd.setRepository(repo).call();
    }

    /**
     * Patrones con `filter=lfs` en el .gitattributes de la raíz del worktree
     * (o de info/attributes). Lista vacía si el repo no usa LFS.
     */
    public List<String> lfsPatterns() throws IOException {
        List<String> patterns = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        if (repo.getWorkTree() != null) {
            sources.add(repo.getWorkTree().toPath().resolve(".gitattributes"));
        }
        sources.add(repo.getDirectory().toPath()
                .resolve(Constants.INFO_ATTRIBUTES));
        for (Path source : sources) {
            if (!Files.isRegularFile(source)) {
                continue;
            }
            for (String line : Files.readAllLines(source)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 2 && trimmed.contains("filter=lfs")) {
                    patterns.add(parts[0]);
                }
            }
        }
        return patterns;
    }

    /** @return false si no había cambios que guardar. */
    public boolean stashCreate(String message) throws GitAPIException {
        var cmd = git.stashCreate();
        if (message != null && !message.isBlank()) {
            cmd.setWorkingDirectoryMessage(message);
        }
        return cmd.call() != null;
    }

    public void stashApply(int index) throws GitAPIException {
        git.stashApply().setStashRef("stash@{" + index + "}").call();
    }

    public void stashPop(int index) throws GitAPIException {
        stashApply(index);
        git.stashDrop().setStashRef(index).call();
    }

    public void stashDrop(int index) throws GitAPIException {
        git.stashDrop().setStashRef(index).call();
    }

    public void fetch(CredentialsProvider credentials, BiConsumer<String, Double> progress) throws GitAPIException {
        git.fetch().setCredentialsProvider(credentials).setProgressMonitor(monitor(progress)).call();
    }

    public void pull(CredentialsProvider credentials, BiConsumer<String, Double> progress) throws GitAPIException {
        git.pull().setCredentialsProvider(credentials).setProgressMonitor(monitor(progress)).call();
    }

    public void push(CredentialsProvider credentials, BiConsumer<String, Double> progress)
            throws GitAPIException, IOException {
        String branch = repo.getBranch();
        var cmd = git.push()
                .setCredentialsProvider(credentials)
                .setProgressMonitor(monitor(progress));
        if (branch != null) {
            // Sin refspec, JGit puede no empujar nada silenciosamente.
            cmd.setRefSpecs(new org.eclipse.jgit.transport.RefSpec(
                    Constants.R_HEADS + branch + ":" + Constants.R_HEADS + branch));
        }
        checkPushResult(cmd.call());
    }

    /** Push forzado de la rama actual (tras reescribir historia: squash, amend, rebase). */
    public void pushForce(CredentialsProvider credentials, BiConsumer<String, Double> progress)
            throws GitAPIException, IOException {
        String branch = repo.getBranch();
        if (branch == null) {
            throw new IllegalStateException("HEAD separado: no hay rama que empujar");
        }
        checkPushResult(git.push().setForce(true)
                .setRefSpecs(new org.eclipse.jgit.transport.RefSpec(
                        Constants.R_HEADS + branch + ":" + Constants.R_HEADS + branch))
                .setCredentialsProvider(credentials)
                .setProgressMonitor(monitor(progress)).call());
    }

    /** JGit no lanza excepción ante rechazos del remoto: se inspecciona el resultado. */
    private static void checkPushResult(Iterable<org.eclipse.jgit.transport.PushResult> results)
            throws GitAPIException {
        for (org.eclipse.jgit.transport.PushResult result : results) {
            for (org.eclipse.jgit.transport.RemoteRefUpdate update : result.getRemoteUpdates()) {
                var status = update.getStatus();
                if (status != org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK
                        && status != org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE) {
                    String detail = update.getMessage() != null
                            ? ": " + update.getMessage() : "";
                    throw new GitAPIException("Push rechazado ("
                            + update.getRemoteName() + "): " + status + detail) {};
                }
            }
        }
    }

    /** ¿La rama tiene upstream configurado (branch.<name>.remote/merge)? */
    public boolean hasUpstream(String branch) {
        StoredConfig config = repo.getConfig();
        return config.getString("branch", branch, "remote") != null
                && config.getString("branch", branch, "merge") != null;
    }

    /** Push de una rama al remoto y la configura como upstream (-u). */
    public void pushSetUpstream(String branch, String remote, CredentialsProvider credentials,
                                BiConsumer<String, Double> progress)
            throws GitAPIException, IOException {
        String ref = Constants.R_HEADS + branch;
        checkPushResult(git.push().setRemote(remote)
                .setRefSpecs(new org.eclipse.jgit.transport.RefSpec(ref + ":" + ref))
                .setCredentialsProvider(credentials)
                .setProgressMonitor(monitor(progress)).call());
        StoredConfig config = repo.getConfig();
        config.setString("branch", branch, "remote", remote);
        config.setString("branch", branch, "merge", ref);
        config.save();
    }

    public static void cloneRepo(String url, Path directory, CredentialsProvider credentials,
                                 BiConsumer<String, Double> progress) throws GitAPIException {
        try (Git ignored = Git.cloneRepository()
                .setURI(url)
                .setDirectory(directory.toFile())
                .setCredentialsProvider(credentials)
                .setProgressMonitor(monitor(progress))
                .call()) {
            // Se cierra el clon; la UI abrirá el repo de nuevo con open().
        }
    }

    public record BlameLine(String commitId, String shortId, String author,
                            int lineNumber, String text) {}

    /**
     * Blame de un archivo en un commit dado (o en HEAD si es null): para cada
     * línea, el commit y autor que la escribieron.
     */
    public List<BlameLine> blame(String path, String sha) throws GitAPIException, IOException {
        var cmd = git.blame().setFilePath(path);
        if (sha != null) {
            cmd.setStartCommit(ObjectId.fromString(sha));
        }
        BlameResult result = cmd.call();
        if (result == null) {
            throw new IOException("No se pudo hacer blame de: " + path);
        }
        RawText contents = result.getResultContents();
        List<BlameLine> lines = new ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            RevCommit source = result.getSourceCommit(i);
            PersonIdent author = result.getSourceAuthor(i);
            lines.add(new BlameLine(
                    source != null ? source.getId().getName() : "",
                    source != null ? source.getId().getName().substring(0, 7) : "???????",
                    author != null ? author.getName() : "?",
                    i + 1,
                    contents.getString(i)));
        }
        return lines;
    }

    public List<DiffFile> commitDetail(String commitId) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(commitId));
            RevTree newTree = commit.getTree();
            RevTree oldTree = commit.getParentCount() == 0
                    ? null
                    : walk.parseCommit(commit.getParent(0).getId()).getTree();
            return diffTrees(oldTree, newTree);
        }
    }

    /** Todos los archivos del árbol de un commit (para "ver todos los archivos"). */
    public List<String> treeFiles(String commitId) throws IOException {
        List<String> out = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            RevTree tree = walk.parseTree(ObjectId.fromString(commitId));
            try (TreeWalk tw = new TreeWalk(repo)) {
                tw.addTree(tree);
                tw.setRecursive(true);
                while (tw.next()) {
                    if (!tw.isSubtree()) {
                        out.add(tw.getPathString());
                    }
                }
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    /** Diff entre dos commits cualesquiera (de oldSha a newSha). */
    public List<DiffFile> diffBetween(String oldSha, String newSha) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            RevTree oldTree = walk.parseCommit(ObjectId.fromString(oldSha)).getTree();
            RevTree newTree = walk.parseCommit(ObjectId.fromString(newSha)).getTree();
            return diffTrees(oldTree, newTree);
        }
    }

    private List<DiffFile> diffTrees(RevTree oldTree, RevTree newTree) throws IOException {
        try (ObjectReader reader = repo.newObjectReader()) {
            AbstractTreeIterator oldIter = oldTree == null
                    ? new EmptyTreeIterator()
                    : new CanonicalTreeParser(null, reader, oldTree.getId());
            AbstractTreeIterator newIter = new CanonicalTreeParser(null, reader, newTree.getId());
            List<DiffEntry> entries;
            try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                df.setRepository(repo);
                df.setDetectRenames(true);
                entries = df.scan(oldIter, newIter);
            }
            List<DiffFile> files = new ArrayList<>();
            for (DiffEntry entry : entries) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (DiffFormatter df = new DiffFormatter(out)) {
                    df.setRepository(repo);
                    df.setDetectRenames(true);
                    df.format(entry);
                }
                String path = entry.getChangeType() == DiffEntry.ChangeType.DELETE
                        ? entry.getOldPath()
                        : entry.getNewPath();
                files.add(new DiffFile(path, changeTypeName(entry.getChangeType()),
                        out.toString(StandardCharsets.UTF_8)));
            }
            return files;
        }
    }

    private static String changeTypeName(DiffEntry.ChangeType type) {
        return switch (type) {
            case ADD -> "Nuevo";
            case MODIFY -> "Modificado";
            case DELETE -> "Eliminado";
            case RENAME -> "Renombrado";
            case COPY -> "Copiado";
        };
    }

    private static ProgressMonitor monitor(BiConsumer<String, Double> progress) {
        return new ProgressMonitor() {
            private String task;
            private int total;

            @Override
            public void start(int totalTasks) {}

            @Override
            public void beginTask(String title, int totalWork) {
                task = title;
                total = totalWork;
                progress.accept(title, totalWork > 0 ? 0.0 : -1.0);
            }

            @Override
            public void update(int completed) {
                if (total > 0) {
                    progress.accept(task, Math.min(1.0, (double) completed / total));
                }
            }

            @Override
            public void endTask() {}

            @Override
            public boolean isCancelled() {
                // Al cerrar la pestaña el executor interrumpe el hilo: JGit
                // consulta este método periódicamente y aborta la operación.
                return Thread.currentThread().isInterrupted();
            }

            @Override
            public void showDuration(boolean enabled) {}
        };
    }
}
