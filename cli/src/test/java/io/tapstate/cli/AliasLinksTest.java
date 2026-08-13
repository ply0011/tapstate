package io.tapstate.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tapstate.core.common.TapstateException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The optional {@code tap} shortcut, managed after installation. {@code tapstate} is the only real
 * command; this exists so a user who declined the shortcut at install time - or whose machine had the
 * name taken - can change their mind without reinstalling.
 *
 * <p>Every case here is about a link rather than a copy. A copy would be correct the day it is made
 * and wrong at the next upgrade, which is exactly the failure a shortcut is least likely to have
 * anyone watching for.
 */
class AliasLinksTest {

    @Test
    void installLinksTheShortcutToTheRealCommand(@TempDir Path binDir) throws IOException {
        Files.createFile(binDir.resolve("tapstate"));

        AliasLinks.install(binDir);

        Path alias = binDir.resolve("tap");
        assertThat(Files.isSymbolicLink(alias)).isTrue();
        assertThat(alias.toRealPath()).isEqualTo(binDir.resolve("tapstate").toRealPath());
    }

    @Test
    void installIsRepeatableRatherThanRefusingItsOwnLink(@TempDir Path binDir) throws IOException {
        // Re-running is how a user re-points the shortcut after an upgrade, so its own link is not a
        // conflict. Only a `tap` that belongs to something else is.
        Files.createFile(binDir.resolve("tapstate"));
        AliasLinks.install(binDir);

        AliasLinks.install(binDir);

        assertThat(binDir.resolve("tap").toRealPath()).isEqualTo(binDir.resolve("tapstate").toRealPath());
    }

    @Test
    void uninstallRemovesOnlyTheShortcut(@TempDir Path binDir) throws IOException {
        Files.createFile(binDir.resolve("tapstate"));
        AliasLinks.install(binDir);

        AliasLinks.uninstall(binDir);

        assertThat(Files.exists(binDir.resolve("tap"), java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(Files.exists(binDir.resolve("tapstate"))).isTrue();
    }

    @Test
    void aNameThatBelongsToSomethingElseIsRefusedAndLeftUntouched(@TempDir Path binDir) throws IOException {
        // node-tap ships a `tap`. Overwriting it would remove a working command from the user's machine
        // to install a convenience they can live without, so the refusal is the whole point - and the
        // assertion has to prove the file survived, not merely that a throw happened.
        Files.createFile(binDir.resolve("tapstate"));
        Path foreign = binDir.resolve("tap");
        Files.writeString(foreign, "#!/bin/sh\necho not tapstate\n");

        assertThatThrownBy(() -> AliasLinks.install(binDir))
                .isInstanceOf(TapstateException.class)
                .satisfies(e -> assertThat(((TapstateException) e).code().code())
                        .isEqualTo("cli.alias-name-taken"));

        assertThat(Files.isSymbolicLink(foreign)).isFalse();
        assertThat(Files.readString(foreign)).contains("not tapstate");
    }

    @Test
    void uninstallLeavesAForeignNameAlone(@TempDir Path binDir) throws IOException {
        // Symmetry with install: uninstall removes our shortcut, and refuses to delete a command it
        // never created. Removing it would be the same harm as overwriting it.
        Files.createFile(binDir.resolve("tapstate"));
        Path foreign = binDir.resolve("tap");
        Files.writeString(foreign, "#!/bin/sh\necho not tapstate\n");

        assertThatThrownBy(() -> AliasLinks.uninstall(binDir))
                .isInstanceOf(TapstateException.class)
                .satisfies(e -> assertThat(((TapstateException) e).code().code())
                        .isEqualTo("cli.alias-name-taken"));

        assertThat(Files.readString(foreign)).contains("not tapstate");
    }

    @Test
    void uninstallingWhatIsNotThereIsNotAnError(@TempDir Path binDir) throws IOException {
        // The end state the caller asked for already holds. Refusing here would make `uninstall` fail
        // on exactly the machines where the installer had already skipped the alias.
        Files.createFile(binDir.resolve("tapstate"));

        AliasLinks.uninstall(binDir);

        assertThat(Files.exists(binDir.resolve("tap"), java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse();
    }
}
