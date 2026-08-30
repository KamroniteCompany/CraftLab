package com.craftlab.launcher.launch;

import com.craftlab.launcher.auth.AuthSession;
import com.craftlab.launcher.instance.InstancePaths;
import com.craftlab.launcher.version.ArgumentSubstitutor;
import com.craftlab.launcher.version.ResolvedVersion;
import com.craftlab.launcher.version.VersionManifestResolver;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Construit la ligne de commande complète et lance Minecraft/Forge en tant que sous-processus,
 * en réutilisant la JVM du launcher lui-même. Capture séparément stdout et stderr de façon
 * asynchrone, journalise le code de sortie réel du processus, et persiste tout dans
 * instances/craftlab/logs/launcher-minecraft.log.
 *
 * Liste complète des placeholders ${...} gérés (correspond à ce que le profil Minecraft
 * 1.21.1 / Forge 52.1.0 réellement installé référence — vérifiable dans
 * instances/craftlab/versions/*.json) :
 *
 *   auth_player_name, version_name, game_directory, assets_root, assets_index_name,
 *   auth_uuid, auth_access_token, auth_session (format hérité), user_type, version_type,
 *   natives_directory, library_directory, classpath, launcher_name, launcher_version,
 *   resolution_width, resolution_height, clientid, auth_xuid.
 *
 * quickPlayPath/quickPlayMultiplayer ne sont volontairement PAS dans cette liste : leurs
 * features ne sont plus activées (voir ENABLED_FEATURES), VersionManifestResolver retire donc
 * entièrement ces arguments de la commande plutôt que de laisser des placeholders à résoudre.
 *
 * resolution_width/height viennent de launcher.properties (feature Mojang
 * "has_custom_resolution", explicitement activée ci-dessous). clientid/auth_xuid reçoivent
 * une valeur neutre "0" par sécurité : selon le profil réel, soit ils sont déjà filtrés par
 * une règle "features" que nous n'activons pas (mode hors-ligne, pas de télémétrie Microsoft),
 * soit ils sont inconditionnels et ont alors besoin d'une valeur non vide pour ne pas planter.
 *
 * Pas de connexion automatique — CraftLabTitleScreen (côté mod client, voir CraftLabCore)
 * s'affiche systématiquement au démarrage et déclenche lui-même la connexion, uniquement
 * lorsque le joueur clique sur "Jouer". Ce launcher ne rejoint donc plus jamais le serveur à
 * la place du joueur.
 *
 * Historique — pourquoi pas --server/--port ni Quick Play : --server/--port n'existent plus
 * dans les arguments déclarés par le profil 1.21.1 (vérifié dans le vrai version.json
 * téléchargé) — Minecraft les ignore silencieusement ("Completely ignored arguments"). Le
 * mécanisme "Quick Play" (introduit en 1.20, --quickPlayMultiplayer/--quickPlayPath) les a
 * remplacés, et A ÉTÉ utilisé ici dans une version précédente pour connecter automatiquement
 * le joueur dès le démarrage — precisément le comportement qu'on ne veut plus : Quick Play
 * réussi empêche Minecraft de créer un TitleScreen du tout (voir Minecraft.<init>), donc
 * CraftLabTitleScreen ne s'affichait jamais. QUICK_PLAY_FEATURES ci-dessous reste défini
 * (abstraction conservée pour une réutilisation future — ex. un mode "connexion directe"
 * optionnel) mais n'est plus passé à ENABLED_FEATURES par launch().
 *
 * server_address/server_port (ServerConnectionTarget) sont toujours utilisés ici, mais
 * seulement pour renseigner deux propriétés système (-Dcraftlab.server.address/port) que le
 * mod client relit quand le joueur clique sur "Jouer" dans CraftLabTitleScreen (voir
 * ServerTarget côté CraftLabCore) — jamais codés en dur côté client.
 */
public class MinecraftLauncher {

    /** Noms d'arguments dont la valeur ne doit jamais apparaître dans les journaux. */
    private static final Set<String> SENSITIVE_ARG_NAMES = Set.of("--accessToken", "--session");

    /**
     * Features Mojang activées pour un lancement CraftLab normal : uniquement la résolution
     * personnalisée. Ni Quick Play ni la démo ne sont activés, ce qui retire proprement leurs
     * arguments plutôt que de leur inventer une valeur (voir VersionManifestResolver.rulesAllow)
     * — Minecraft démarre donc toujours sur un TitleScreen classique, remplacé côté mod client
     * par CraftLabTitleScreen (voir ClientForgeEvents dans CraftLabCore).
     */
    private static final Set<String> ENABLED_FEATURES = Set.of("has_custom_resolution");

    /**
     * Conservé pour une réactivation future de la connexion automatique par Quick Play — NON
     * utilisé par launch() actuellement (voir le commentaire de classe ci-dessus pour le
     * pourquoi). Pour réactiver : passer ENABLED_FEATURES.plus(QUICK_PLAY_FEATURES) à
     * VersionManifestResolver.resolve(...) et fournir à nouveau les valeurs
     * quickPlayPath/quickPlayMultiplayer dans le Map de substitution ci-dessous.
     */
    private static final Set<String> QUICK_PLAY_FEATURES = Set.of("has_quick_plays_support", "is_quick_play_multiplayer");

    private final InstancePaths paths;

    public MinecraftLauncher(InstancePaths paths) {
        this.paths = paths;
    }

    public Process launch(String versionId, AuthSession session, ServerConnectionTarget target,
                           int resolutionWidth, int resolutionHeight, Consumer<String> onLog) throws IOException {
        onLog.accept("[CraftLab] Aucune connexion automatique : l'écran CraftLab s'affichera au démarrage.");
        onLog.accept("[CraftLab] Serveur configuré (utilisé par le bouton \"Jouer\" en jeu) : " + target);

        onLog.accept("[CraftLab] Résolution du profil de version " + versionId + "...");
        VersionManifestResolver resolver = new VersionManifestResolver(paths.versionsDir(), paths.librariesDir());
        ResolvedVersion version = resolver.resolve(versionId, ENABLED_FEATURES);

        if (version.mainClass() == null) {
            throw new IOException("Impossible de déterminer la classe principale à lancer pour " + versionId + ".");
        }
        if (version.classpathEntries().isEmpty()) {
            throw new IOException("Aucune bibliothèque trouvée pour " + versionId + " — l'installation Forge est-elle complète ?");
        }

        Path nativesDir = paths.nativesDir(versionId);
        Files.createDirectories(nativesDir);
        Files.createDirectories(paths.instanceDir());

        onLog.accept("[CraftLab] Résolution des bibliothèques (classpath)...");
        String classpath = String.join(File.pathSeparator, version.classpathEntries());
        String assetsRoot = paths.assetsDir().toAbsolutePath().toString();
        onLog.accept("[CraftLab] Classpath résolu : " + version.classpathEntries().size() + " entrées.");

        Map<String, String> values = new LinkedHashMap<>();
        values.put("auth_player_name", session.username());
        values.put("version_name", versionId);
        values.put("game_directory", paths.instanceDir().toAbsolutePath().toString());
        values.put("assets_root", assetsRoot);
        values.put("assets_index_name", version.assetsId() != null ? version.assetsId() : versionId);
        values.put("auth_uuid", session.uuid());
        values.put("auth_access_token", session.accessToken());
        values.put("auth_session", session.accessToken()); // format hérité (pré-1.13), gardé par robustesse
        values.put("user_type", session.userType());
        values.put("version_type", "CraftLab");
        values.put("natives_directory", nativesDir.toAbsolutePath().toString());
        values.put("library_directory", paths.librariesDir().toAbsolutePath().toString());
        values.put("launcher_name", "CraftLabLauncher");
        values.put("launcher_version", "0.1.0");
        values.put("classpath", classpath);
        values.put("resolution_width", String.valueOf(resolutionWidth));
        values.put("resolution_height", String.valueOf(resolutionHeight));
        values.put("clientid", "0");
        values.put("auth_xuid", "0");

        onLog.accept("[CraftLab] Construction des arguments JVM et jeu...");
        List<String> jvmArgs = ArgumentSubstitutor.substitute(version.jvmArguments(), values);
        List<String> gameArgs = ArgumentSubstitutor.substitute(version.gameArguments(), values);
        onLog.accept("[CraftLab] Arguments résolus.");

        // Validation obligatoire : un argument encore littéralement "${...}" indique un
        // placeholder que ni le profil (features) ni cette classe (values) ne couvrent — mieux
        // vaut échouer proprement ici qu'obtenir un crash Minecraft incompréhensible.
        List<String> unresolved = findUnresolvedPlaceholders(jvmArgs, gameArgs);
        if (!unresolved.isEmpty()) {
            for (String arg : unresolved) {
                onLog.accept("[CraftLab] Argument non résolu détecté :");
                onLog.accept(arg);
            }
            throw new IOException("Des placeholders non résolus subsistent dans les arguments de lancement : " + unresolved
                + " — voir la liste des placeholders gérés dans MinecraftLauncher.");
        }
        onLog.accept("[CraftLab] Aucun placeholder non résolu.");

        String javaExecutable = ProcessHandle.current().info().command().orElse("java");

        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        // Propriétés système lues par ServerTarget côté mod client (CraftLabCore) quand le
        // joueur clique sur "Jouer" dans CraftLabTitleScreen — server_address/server_port ne
        // sont donc jamais codés en dur côté client, sans pour autant passer par Quick Play.
        command.add("-Dcraftlab.server.address=" + target.address());
        command.add("-Dcraftlab.server.port=" + target.port());

        if (jvmArgs.isEmpty()) {
            command.add("-Djava.library.path=" + nativesDir.toAbsolutePath());
            command.add("-cp");
            command.add(classpath);
        } else {
            command.addAll(jvmArgs);
        }
        command.add(version.mainClass());
        command.addAll(gameArgs);

        logCommand(javaExecutable, version, classpath, jvmArgs, gameArgs, onLog);

        Path minecraftLogPath = paths.instanceDir().resolve("logs").resolve("launcher-minecraft.log");
        Files.createDirectories(minecraftLogPath.getParent());
        PrintWriter logWriter = new PrintWriter(Files.newBufferedWriter(minecraftLogPath, StandardCharsets.UTF_8), true);
        Object logLock = new Object();

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(paths.instanceDir().toFile());

        // Dernière vérification, au plus près possible de start() : la commande RÉELLEMENT
        // transmise à ProcessBuilder, pas une reconstruction séparée qui pourrait diverger.
        onLog.accept("[CraftLab] Commande finale (" + command.size() + " tokens) : "
            + String.join(" ", redactForLogging(command)));

        Process process = builder.start();

        onLog.accept("[CraftLab] Minecraft process started. (pid " + process.pid() + ")");
        synchronized (logLock) {
            logWriter.println("=== Minecraft process started (pid " + process.pid() + ") ===");
        }

        pumpStream(process.getInputStream(), "Minecraft", onLog, logWriter, logLock);
        pumpStream(process.getErrorStream(), "ERROR", onLog, logWriter, logLock);

        process.onExit().thenAccept(finished -> {
            int exitCode = finished.exitValue();
            onLog.accept("[CraftLab] Minecraft terminé. Exit code: " + exitCode);
            if (exitCode != 0) {
                onLog.accept("[CraftLab] Minecraft a échoué au démarrage.");
            }
            onLog.accept("[CraftLab] Minecraft log (launcher) : " + minecraftLogPath);

            Path vanillaLog = paths.instanceDir().resolve("logs").resolve("latest.log");
            if (Files.exists(vanillaLog)) {
                onLog.accept("[CraftLab] Minecraft log (jeu) : " + vanillaLog);
            }

            synchronized (logLock) {
                logWriter.println("=== Minecraft exited with code " + exitCode + " ===");
                logWriter.close();
            }
        });

        return process;
    }

    private List<String> findUnresolvedPlaceholders(List<String> jvmArgs, List<String> gameArgs) {
        List<String> unresolved = new ArrayList<>();
        for (String arg : jvmArgs) {
            if (arg.contains("${")) {
                unresolved.add(arg);
            }
        }
        for (String arg : gameArgs) {
            if (arg.contains("${")) {
                unresolved.add(arg);
            }
        }
        return unresolved;
    }

    private void logCommand(String javaExecutable, ResolvedVersion version, String classpath,
                             List<String> jvmArgs, List<String> gameArgs, Consumer<String> onLog) {
        onLog.accept("[CraftLab] JVM : " + javaExecutable);
        onLog.accept("[CraftLab] Working directory: " + paths.instanceDir().toAbsolutePath());
        onLog.accept("[CraftLab] Classe principale : " + version.mainClass());
        onLog.accept("[CraftLab] Classpath (" + version.classpathEntries().size() + " entrées) : " + classpath);
        onLog.accept("[CraftLab] Arguments JVM : " + String.join(" ", jvmArgs));
        onLog.accept("[CraftLab] Arguments Minecraft : " + String.join(" ", redactForLogging(gameArgs)));
    }

    /** Remplace la valeur des arguments sensibles (jeton d'accès, session) par <redacted> avant tout affichage/journalisation. */
    private List<String> redactForLogging(List<String> args) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            result.add(arg);
            if (SENSITIVE_ARG_NAMES.contains(arg) && i + 1 < args.size()) {
                result.add("<redacted>");
                i++;
            }
        }
        return result;
    }

    /** Lit un flux (stdout ou stderr) ligne à ligne sur un thread démon dédié, jamais sur le thread appelant. */
    private void pumpStream(InputStream stream, String prefix, Consumer<String> onLog, PrintWriter logWriter, Object logLock) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String formatted = "[CraftLab] [" + prefix + "] " + line;
                    onLog.accept(formatted);
                    synchronized (logLock) {
                        logWriter.println(formatted);
                        logWriter.flush();
                    }
                }
            } catch (IOException ignored) {
                // Flux fermé à la terminaison du processus : comportement normal, rien à signaler.
            }
        }, "CraftLab-Minecraft-" + prefix);
        thread.setDaemon(true);
        thread.start();
    }
}
