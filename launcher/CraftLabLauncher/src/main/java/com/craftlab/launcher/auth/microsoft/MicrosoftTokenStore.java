package com.craftlab.launcher.auth.microsoft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * Persiste UNIQUEMENT le refresh token Microsoft (long-lived) — jamais les jetons Microsoft/
 * Xbox/XSTS/Minecraft à courte durée de vie, qu'il est plus simple et plus sûr de redemander à
 * chaque lancement via ce refresh token que d'essayer de les mettre en cache correctement (voir
 * docs/authentication.md, section "Pourquoi ne mettre en cache que le refresh token").
 *
 * Chiffré au repos via DPAPI (Data Protection API), le mécanisme natif Windows de protection de
 * secrets par utilisateur — le même que Windows Credential Manager utilise en interne. Pas de
 * dépendance native (JNA/JNI) : invoque PowerShell (`ConvertTo-SecureString`/
 * `ConvertFrom-SecureString`, sans `-Key`, donc lié à l'utilisateur Windows courant par DPAPI)
 * en sous-processus, en transmettant systématiquement le secret par stdin — jamais en argument
 * de ligne de commande, qui apparaîtrait autrement dans la liste des processus ou les journaux
 * système. Le fichier chiffré n'est lisible en clair que par le même compte Windows sur la même
 * machine : copié ailleurs (autre PC, autre utilisateur), il ne peut plus être déchiffré.
 */
public final class MicrosoftTokenStore {

    private static final String FILE_NAME = "msa-refresh-token.dat";
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(10);

    private final Path filePath;

    public MicrosoftTokenStore(Path craftLabRoot) {
        this.filePath = craftLabRoot.resolve(FILE_NAME);
    }

    public void store(String refreshToken) throws MicrosoftAuthException {
        String encrypted = runPowerShell(
            "$plain = [Console]::In.ReadToEnd(); "
                + "$secure = ConvertTo-SecureString -String $plain -AsPlainText -Force; "
                + "Write-Output (ConvertFrom-SecureString -SecureString $secure)",
            refreshToken);
        try {
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, encrypted, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new MicrosoftAuthException("Impossible d'enregistrer la session Microsoft localement : " + e.getMessage(), e);
        }
    }

    /** Vide si aucune session n'a jamais été enregistrée, ou si le fichier ne peut plus être déchiffré (voir la javadoc de la classe). */
    public Optional<String> load() {
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        try {
            String encrypted = Files.readString(filePath, StandardCharsets.US_ASCII);
            String plain = runPowerShell(
                "$encrypted = [Console]::In.ReadToEnd().Trim(); "
                    + "$secure = ConvertTo-SecureString -String $encrypted; "
                    + "$bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure); "
                    + "try { Write-Output ([System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)) } "
                    + "finally { [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }",
                encrypted);
            return Optional.of(plain);
        } catch (Exception e) {
            // Fichier absent/corrompu/illisible sur cette machine : traité comme "pas de session
            // enregistrée", jamais comme une erreur bloquante — l'utilisateur se reconnectera.
            return Optional.empty();
        }
    }

    /** Déconnexion explicite : plus aucune session Microsoft locale après cet appel. */
    public void clear() throws IOException {
        Files.deleteIfExists(filePath);
    }

    private static String runPowerShell(String script, String stdin) throws MicrosoftAuthException {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
            builder.redirectErrorStream(false);
            Process process = builder.start();

            try (var writer = process.getOutputStream()) {
                writer.write(stdin.getBytes(StandardCharsets.UTF_8));
                writer.flush();
            }

            boolean finished = process.waitFor(PROCESS_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new MicrosoftAuthException("Le chiffrement/déchiffrement local de la session Microsoft a expiré.");
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 || output.isEmpty()) {
                String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                throw new MicrosoftAuthException("Échec du stockage sécurisé local (DPAPI) : "
                    + (error.isEmpty() ? "sortie vide" : error));
            }
            return output;
        } catch (MicrosoftAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new MicrosoftAuthException("Impossible d'utiliser le stockage sécurisé Windows (DPAPI) : " + e.getMessage(), e);
        }
    }
}
