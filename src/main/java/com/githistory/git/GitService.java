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
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
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
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        git.commit().setMessage(message).call();
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
        cmd.call();
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
