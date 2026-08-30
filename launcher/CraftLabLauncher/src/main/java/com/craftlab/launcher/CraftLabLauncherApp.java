package com.craftlab.launcher;

import com.craftlab.launcher.auth.AuthSession;
import com.craftlab.launcher.auth.OfflineAuthProvider;
import com.craftlab.launcher.config.LauncherConfig;
import com.craftlab.launcher.forge.ForgeRuntimeValidator;
import com.craftlab.launcher.instance.InstanceManager;
import com.craftlab.launcher.instance.InstancePaths;
import com.craftlab.launcher.launch.MinecraftLauncher;
import com.craftlab.launcher.launch.ServerConnectionTarget;
import com.craftlab.launcher.log.LauncherLog;
import com.craftlab.launcher.modpack.HttpModPackProvider;
import com.craftlab.launcher.modpack.LocalFileModPackProvider;
import com.craftlab.launcher.modpack.ModPackProvider;
import com.craftlab.launcher.modpack.RemoteModPack;
import com.craftlab.launcher.state.LauncherState;
import com.craftlab.launcher.sync.SyncManager;
import com.craftlab.launcher.version.VersionManifestResolver;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Interface volontairement simple : titre, statut, liste des mods, barre de progression,
 * deux boutons, un journal. Toute la logique lourde (réseau, disque, sous-processus) tourne
 * sur un exécuteur dédié, jamais sur le thread JavaFX ; les mises à jour d'interface
 * repassent explicitement par Platform.runLater(...).
 */
public class CraftLabLauncherApp extends Application {

    /**
     * Identifiant de build à comparer explicitement contre celui annoncé lors de chaque
     * livraison de correctif — permet de confirmer sans ambiguïté que le code réellement
     * exécuté correspond bien à la source la plus récente, avant même de cliquer sur Jouer.
     */
    private static final String BUILD_ID = "2026-08-30-fix-forge-patched-client-validation";

    private InstancePaths paths;
    private LauncherConfig config;
    private LauncherLog log;
    private ModPackProvider provider;
    private SyncManager syncManager;
    private InstanceManager instanceManager;
    private ExecutorService executor;

    private Label statusLabel;
    private ListView<String> modListView;
    private ProgressBar progressBar;
    private TextArea logArea;
    private Button playButton;
    private Button checkButton;

    @Override
    public void init() {
        paths = InstancePaths.resolveDefault();
        config = new LauncherConfig(paths.root());
        config.load();
        log = new LauncherLog(paths.logsDir());

        provider = createProvider(config.getModPackUrl());

        syncManager = new SyncManager(provider, paths);
        instanceManager = new InstanceManager(paths);
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CraftLab-Sync");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void start(Stage stage) {
        Label title = new Label("CraftLab");
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");

        Label serverLabel = new Label("Serveur CraftLab — " + config.getServerAddress() + ":" + config.getServerPort());

        statusLabel = new Label("● Non vérifié");

        modListView = new ListView<>();
        modListView.setPrefHeight(160);

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);

        checkButton = new Button("Vérifier les mises à jour");
        checkButton.setOnAction(e -> runSync());

        playButton = new Button("Jouer");
        playButton.setDisable(true);
        playButton.setOnAction(e -> runLaunch());

        HBox buttons = new HBox(10, checkButton, playButton);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefHeight(160);
        log.setUiListener(line -> Platform.runLater(() -> logArea.appendText(line + "\n")));

        VBox root = new VBox(12,
            title, serverLabel, statusLabel, modListView, progressBar, buttons,
            new Label("Journal :"), logArea
        );
        root.setPadding(new Insets(16));

        stage.setTitle("CraftLab Launcher");
        stage.setScene(new Scene(root, 480, 660));
        stage.show();

        log.log("[CraftLab] Launcher build: " + BUILD_ID);
        log.log("CraftLab Launcher démarré. Instance : " + paths.instanceDir());
        runSync();
    }

    /**
     * Détermine si modpack_url désigne une source HTTP(S) ou un fichier local. Seul un préfixe
     * http:// ou https:// déclenche HttpModPackProvider — tout le reste (chemin Windows du type
     * "C:/Users/...", chemin Unix, ou préfixe "file:") est traité comme un fichier local. Une
     * simple absence du préfixe "file:" ne doit jamais faire passer un chemin local par erreur
     * dans le client HTTP (voir docs/launcher.md, section dépannage).
     */
    private ModPackProvider createProvider(String rawUrl) {
        if (isHttpUrl(rawUrl)) {
            log.log("Source du ModPack : HTTP(S) -> " + rawUrl);
            return new HttpModPackProvider(rawUrl);
        }

        Path localPath = resolveLocalPath(rawUrl);
        log.log("Source du ModPack : fichier local -> " + localPath.toAbsolutePath()
            + (java.nio.file.Files.exists(localPath) ? " (trouvé)" : " (INTROUVABLE)"));
        return new LocalFileModPackProvider(localPath);
    }

    private static boolean isHttpUrl(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static Path resolveLocalPath(String value) {
        String path = value.startsWith("file:") ? value.substring("file:".length()) : value;
        return Path.of(path);
    }

    private void runSync() {
        setBusy(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);

        CompletableFuture.runAsync(() -> syncManager.sync(new SyncManager.Listener() {
            @Override
            public void onStateChanged(LauncherState state) {
                Platform.runLater(() -> statusLabel.setText("● " + describeState(state)));
            }

            @Override
            public void onModProgress(String modId, long bytesWritten, long totalBytesHint) {
                if (totalBytesHint > 0) {
                    Platform.runLater(() -> progressBar.setProgress((double) bytesWritten / totalBytesHint));
                }
            }

            @Override
            public void onLog(String message) {
                log.log(message);
            }
        }).thenCompose(plan -> ensureInstanceReady())
          .whenComplete((v, throwable) -> Platform.runLater(() -> {
              // Aucune exception ne doit pouvoir disparaître silencieusement dans la console :
              // tout est capturé ici et systématiquement écrit dans le journal affiché à l'écran.
              try {
                  progressBar.setVisible(false);
                  setBusy(false);
                  if (throwable != null) {
                      statusLabel.setText("● Erreur");
                      log.log("Erreur : " + rootMessage(throwable));
                      playButton.setDisable(true);
                  } else {
                      statusLabel.setText("● ModPack à jour");
                      refreshModList();
                      playButton.setDisable(false);
                      log.log("[CraftLab] Prêt à jouer.");
                  }
              } catch (Exception e) {
                  log.log("[CraftLab] Erreur inattendue après synchronisation : " + rootMessage(e));
              }
          })), executor);
    }

    private CompletableFuture<Void> ensureInstanceReady() {
        return provider.getCurrentModPack().thenAccept(remote -> {
            try {
                instanceManager.ensureReady(remote.minecraftVersion(), remote.forgeVersion(), log::log);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    private void runLaunch() {
        setBusy(true);
        statusLabel.setText("● Lancement...");

        CompletableFuture.runAsync(() -> {
            try {
                RemoteModPack remote = provider.getCurrentModPack().get();
                String versionId = instanceManager.versionId(remote.minecraftVersion(), remote.forgeVersion());

                if (!validateRuntime(remote.minecraftVersion(), remote.forgeVersion(), versionId)) {
                    throw new java.io.IOException("Runtime Forge invalide : le client patché est manquant ou incomplet "
                        + "(voir diagnostics ci-dessus). Relance \"Vérifier les mises à jour\" pour réinstaller Forge.");
                }
                log.log("[CraftLab] Runtime Forge valide.");

                AuthSession session = new OfflineAuthProvider().authenticate(config.getUsername());

                log.log("[CraftLab] Lancement de Minecraft...");
                ServerConnectionTarget target = new ServerConnectionTarget(config.getServerAddress(), config.getServerPort());
                MinecraftLauncher launcher = new MinecraftLauncher(paths);
                Process process = launcher.launch(versionId, session, target,
                    config.getResolutionWidth(), config.getResolutionHeight(), log::log);

                // ProcessBuilder.start() ayant réussi ne prouve pas que Minecraft a réellement
                // démarré : l'état "En jeu" n'est affiché qu'après un court délai où le processus
                // est toujours vivant. Le code de sortie réel (loggé par MinecraftLauncher) reste
                // la source de vérité en cas d'échec, quelle que soit l'issue de cette vérification.
                Platform.runLater(() -> statusLabel.setText("● Minecraft démarré, surveillance en cours..."));

                CompletableFuture.runAsync(() -> {
                    if (process.isAlive()) {
                        Platform.runLater(() -> statusLabel.setText("● En jeu"));
                        log.log("[CraftLab] Minecraft semble toujours actif après quelques secondes.");
                    }
                }, CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS));

                process.onExit().thenAccept(finished -> Platform.runLater(() -> {
                    int exitCode = finished.exitValue();
                    statusLabel.setText(exitCode == 0 ? "● Minecraft fermé" : "● Erreur (voir journal)");
                }));
            } catch (Exception e) {
                log.log("[CraftLab] Erreur de lancement :");
                log.log(rootMessage(e));
                Platform.runLater(() -> statusLabel.setText("● Erreur de lancement"));
            } finally {
                Platform.runLater(() -> setBusy(false));
            }
        }, executor);
    }

    /**
     * Vérifie, juste avant de lancer le processus Minecraft/Forge, que le runtime Forge installé
     * est réellement utilisable — et pas seulement que son version.json existe (voir
     * ForgeRuntimeValidator pour le pourquoi). Distingue explicitement "Minecraft.class présent"
     * (le .class existe physiquement dans le jar client patché sur disque) de "visible depuis le
     * runtime" (ce même jar figure bien dans le classpath que VersionManifestResolver
     * construirait réellement pour ce lancement) : les deux peuvent diverger si le jar existe
     * mais que la résolution des bibliothèques (règles, coordonnées Maven) ne le sélectionne pas.
     */
    private boolean validateRuntime(String minecraftVersion, String forgeVersion, String versionId) {
        log.log("[CraftLab] Validation du runtime...");

        ForgeRuntimeValidator.Result result = new ForgeRuntimeValidator(paths).validate(minecraftVersion, forgeVersion);

        log.log("[CraftLab] Minecraft client JAR: " + result.minecraftClientJar
            + (result.minecraftClientJarPresent ? " (présent)" : " (ABSENT)"));

        log.log("[CraftLab] Forge version profile: " + result.forgeVersionProfile
            + (java.nio.file.Files.exists(result.forgeVersionProfile) ? " (présent)" : " (ABSENT)"));

        log.log("[CraftLab] Forge universal JAR: " + result.forgeUniversalJar
            + (result.forgeUniversalJarPresent ? " (présent)" : " (ABSENT)"));

        log.log("[CraftLab] Forge client patché (contient Minecraft.class): " + result.forgePatchedClientJar
            + (result.forgePatchedClientJarPresent ? " (présent)" : " (ABSENT)"));
        log.log("[CraftLab] Minecraft.class présent: " + (result.minecraftClassPresent ? "YES" : "NO"));

        boolean visibleToRuntime = false;
        if (result.forgePatchedClientJarPresent && result.minecraftClassPresent) {
            try {
                var resolved = new VersionManifestResolver(paths.versionsDir(), paths.librariesDir())
                    .resolve(versionId, java.util.Set.of("has_custom_resolution"));
                String patchedClientAbsolute = result.forgePatchedClientJar.toAbsolutePath().toString();
                visibleToRuntime = resolved.classpathEntries().contains(patchedClientAbsolute);
            } catch (Exception e) {
                visibleToRuntime = false;
            }
        }
        log.log("[CraftLab] Minecraft.class visible depuis le runtime (classpath résolu): " + (visibleToRuntime ? "YES" : "NO"));

        if (!result.missingLocalLibraries.isEmpty()) {
            for (java.nio.file.Path missing : result.missingLocalLibraries) {
                log.log("[CraftLab] Bibliothèque locale manquante : " + missing);
            }
        }

        boolean ready = result.isReady() && visibleToRuntime;
        log.log("[CraftLab] Forge runtime prêt: " + (ready ? "YES" : "NO"));
        return ready;
    }

    private void refreshModList() {
        provider.getCurrentModPack().thenAccept(remote -> Platform.runLater(() -> {
            modListView.getItems().clear();
            for (var entry : remote.mods()) {
                modListView.getItems().add(entry.name() + "   " + entry.version() + "   ✓");
            }
        }));
    }

    private void setBusy(boolean busy) {
        checkButton.setDisable(busy);
        playButton.setDisable(busy || modListView.getItems().isEmpty());
    }

    private String describeState(LauncherState state) {
        return switch (state) {
            case CHECKING -> "Vérification...";
            case DOWNLOADING -> "Téléchargement...";
            case VERIFYING -> "Vérification SHA-256...";
            case READY -> "ModPack à jour";
            case LAUNCHING -> "Lancement...";
            case ERROR -> "Erreur";
        };
    }

    private String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    @Override
    public void stop() {
        executor.shutdownNow();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
