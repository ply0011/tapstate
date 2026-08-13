package io.tapstate.cli;

import io.tapstate.core.common.TapstateException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * {@code alias} — turns the optional {@code tap} shortcut on or off after installation.
 *
 * <p>The installer offers the shortcut at install time and skips it when the name is already taken,
 * so this is how a user changes that decision later without reinstalling. {@code tapstate} remains the
 * only real command either way: nothing here changes what documents, messages or scripts should say.
 *
 * <p>Offline, like the rest of the local verbs — it touches the install directory and never a server.
 */
@Command(name = "alias", mixinStandardHelpOptions = true,
        subcommands = {AliasCmd.Install.class, AliasCmd.Uninstall.class},
        description = {
                "Manage the optional `tap` shortcut for `tapstate`.",
                "The shortcut only saves keystrokes; `tapstate` is always the real command."})
final class AliasCmd {

    /**
     * Where the shortcut lives: beside the {@code tapstate} entry the installer wrote. Read from the
     * same variable the installer honours, so a non-default install directory is managed by the same
     * answer both halves already agree on rather than a second guess made here.
     */
    private static final String INSTALL_DIR_ENV = "TAPSTATE_INSTALL_DIR";

    private AliasCmd() {
    }

    @Command(name = "install", mixinStandardHelpOptions = true,
            description = "Create the `tap` shortcut next to `tapstate`.")
    static final class Install implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            Path binDir = binDir();
            AliasLinks.install(binDir);
            spec.commandLine().getOut().println(Ansi.AUTO.string(
                    "created the `tap` shortcut in " + binDir + " — `tapstate` is unchanged"));
            return 0;
        }
    }

    @Command(name = "uninstall", mixinStandardHelpOptions = true,
            description = "Remove the `tap` shortcut, leaving `tapstate` in place.")
    static final class Uninstall implements Callable<Integer> {

        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            Path binDir = binDir();
            AliasLinks.uninstall(binDir);
            spec.commandLine().getOut().println(Ansi.AUTO.string(
                    "the `tap` shortcut is not present in " + binDir + " — `tapstate` is unchanged"));
            return 0;
        }
    }

    /**
     * The directory holding the installed {@code tapstate} entry. An install directory that holds no
     * such entry is refused by the same code an occupied name is: in both cases this command has been
     * pointed at a directory it does not own, and guessing another one would manage a shortcut for an
     * installation the user did not mean.
     */
    private static Path binDir() {
        String configured = System.getenv(INSTALL_DIR_ENV);
        Path binDir = configured != null && !configured.isBlank()
                ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), ".tapstate", "bin");
        if (!Files.exists(binDir.resolve("tapstate"), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new TapstateException(CliError.ALIAS_INSTALL_DIR_UNKNOWN,
                    Map.of("path", binDir.toString()), null);
        }
        return binDir;
    }
}
