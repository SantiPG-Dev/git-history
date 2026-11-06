package com.githistory.git;

import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.DiffCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.RebaseCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawText;
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

    public record StatusEntry(String path, String state) {}

    public record WorkingTreeStatus(List<StatusEntry> staged, List<StatusEntry> unstaged) {}

    public record BranchInfo(String name, String shortName, boolean remote, boolean current) {}

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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DiffCommand cmd = git.diff().setOutputStream(out).setCached(staged);
        if (path != null) {
            cmd.setPathFilter(PathFilter.create(path));
        }
        cmd.call();
        return out.toString(StandardCharsets.UTF_8);
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
            out.add(new BranchInfo(ref.getName(), shortName, remote, !remote && shortName.equals(current)));
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
        git.push().setPushTags().setCredentialsProvider(credentials)
                .setProgressMonitor(monitor(progress)).call();
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

    public void push(CredentialsProvider credentials, BiConsumer<String, Double> progress) throws GitAPIException {
        git.push().setCredentialsProvider(credentials).setProgressMonitor(monitor(progress)).call();
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
        git.push().setRemote(remote)
                .setRefSpecs(new org.eclipse.jgit.transport.RefSpec(ref + ":" + ref))
                .setCredentialsProvider(credentials)
                .setProgressMonitor(monitor(progress)).call();
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
