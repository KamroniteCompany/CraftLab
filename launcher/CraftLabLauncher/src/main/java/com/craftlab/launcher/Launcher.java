package com.craftlab.launcher;

/**
 * Point d'entrée réel du jar/de l'exécutable jpackage — ne doit JAMAIS étendre
 * javafx.application.Application directement.
 *
 * Quand la classe Main-Class d'un jar (java -jar), ou la classe passée à -cp/--main-class
 * (java -cp, et donc l'exécutable généré par jpackage), est elle-même un sous-type
 * d'Application, le lanceur Java exige que JavaFX soit visible sur le MODULE-PATH avant même de
 * charger le code de l'application, et refuse de démarrer avec "Error: JavaFX runtime
 * components are missing, and are required to run this application" si JavaFX n'est disponible
 * que sur le classpath — exactement notre cas : JavaFX est une dépendance classpath ordinaire
 * (voir build.gradle), pas un module nommé sur un module-path. Reproduit et confirmé le
 * 2026-08-31 : `java -cp "<jars du classpath>" com.craftlab.launcher.CraftLabLauncherApp`
 * échoue avec ce message précis, indépendamment de jpackage — seule gradlew run fonctionnait,
 * parce que le plugin javafx-gradle ajoute --module-path/--add-modules pour cette tâche
 * spécifique, un traitement qui ne s'applique jamais à un jar ou un exécutable packagé.
 *
 * Cette classe intermédiaire, qui n'étend rien de JavaFX, contourne entièrement cette
 * vérification : une fois le contrôle passé à CraftLabLauncherApp.main() (qui appelle
 * Application.launch(args) comme avant, inchangé), les classes JavaFX se chargent normalement
 * depuis le classpath, sans jamais avoir besoin d'un module-path.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        CraftLabLauncherApp.main(args);
    }
}
