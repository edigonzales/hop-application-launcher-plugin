package ch.so.agi.hop.launcher;

import java.nio.file.Path;
import java.util.Map;
import org.apache.hop.core.Const;
import org.apache.hop.core.config.HopConfig;

/** Local bootstrap configuration: it must be available before cloning the catalog. */
public record LauncherSettings(String repository, String branch, Path checkout) {
  public static Path stateDirectory() {
    return Path.of(Const.HOP_CONFIG_FOLDER).resolve("application-launcher");
  }

  public static LauncherSettings load() {
    return new LauncherSettings(
        HopConfig.readOptionString(
            "applicationLauncher.repository",
            "https://github.com/sogis/datenportal-themenrepo.git"),
        HopConfig.readOptionString("applicationLauncher.branch", "main"),
        Path.of(
            HopConfig.readOptionString(
                "applicationLauncher.checkout", stateDirectory().resolve("checkout").toString())));
  }

  public void save() {
    HopConfig.saveOptions(
        Map.of(
            "applicationLauncher.repository",
            repository,
            "applicationLauncher.branch",
            branch,
            "applicationLauncher.checkout",
            checkout.toAbsolutePath().toString()));
  }
}
