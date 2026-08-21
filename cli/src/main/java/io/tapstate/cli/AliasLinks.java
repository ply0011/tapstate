package io.tapstate.cli;

import io.tapstate.core.common.TapstateException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

/**
 * The optional {@code tap} shortcut, managed after installation.
 *
 * <p>{@code tapstate} is the only real command: every document, message and completion says that name,
 * and this exists to save keystrokes. It is created as a link to {@code tapstate} in the same
 * directory, never as a copy - a copy is correct on the day it is made and stale at the next upgrade,
 * which is the failure a convenience shortcut is least likely to have anyone watching for.
 *
 * <p>A {@code tap} that belongs to something else (node-tap ships one) is refused rather than
 * replaced, in both directions. Overwriting it would remove a working command from the machine to
 * install a convenience the user can live without; deleting it on {@code uninstall} would do the same
 * harm by the other route. Only a link this command could have made is ever touched.
 */
final class AliasLinks {

    /** The shortcut's name. The real command is never anything but {@code tapstate}. */
    static final String ALIAS = "tap";

    private static final String COMMAND = "tapstate";

    private AliasLinks() {
    }

    /**
     * Points the shortcut at {@code tapstate} in the given directory.
     *
     * <p>Repeatable on purpose: re-running is how a user re-points the shortcut, so finding this
     * command's own link is not a conflict. The link is written to a temporary name and moved into
     * place, so an interrupted run leaves either the old link or the new one - never a half-written
     * name where a command used to be.
     */
    static void install(Path binDir) {
        Path alias = binDir.resolve(ALIAS);
        requireOursOrAbsent(alias);
        Path staged = binDir.resolve("." + ALIAS + "." + ProcessHandle.current().pid());
        try {
            Files.deleteIfExists(staged);
            Files.createSymbolicLink(staged, Path.of(COMMAND));
            Files.move(staged, alias, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            quietlyDelete(staged);
            throw linkFailed(alias, e);
        }
    }

    /**
     * Removes the shortcut. An absent one is success rather than a refusal: the end state the caller
     * asked for already holds, and failing here would break exactly the machines where the installer
     * had already skipped the alias because the name was taken.
     */
    static void uninstall(Path binDir) {
        Path alias = binDir.resolve(ALIAS);
        if (!Files.exists(alias, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        requireOursOrAbsent(alias);
        try {
            Files.delete(alias);
        } catch (IOException e) {
            throw linkFailed(alias, e);
        }
    }

    /**
     * Refuses a name that exists and is not a link this command could have made. A regular file, a
     * directory, or a link pointing somewhere else all belong to someone else.
     */
    /**
     * Refuses unless the name is free or already holds this installation's own link.
     *
     * <p>Ownership is decided by where the link points, not by what the target is called. Matching the
     * file name alone accepted any link ending in {@code tapstate}, including one into a directory this
     * installation does not own -- which {@code install} would then replace and {@code uninstall} would
     * delete, silently, on something somebody else put there.
     *
     * <p>Two link shapes are ours and both must stay accepted: {@code tapstate}, written here, and
     * {@code versions/<v>/bin/tapstate}, written by the installer. Both resolve inside the install
     * directory, which is what the test below actually is.
     */
    private static void requireOursOrAbsent(Path alias) {
        if (!Files.exists(alias, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(alias)) {
            try {
                Path binDir = alias.toAbsolutePath().normalize().getParent();
                Path target = Files.readSymbolicLink(alias);
                Path resolved = binDir.resolve(target).normalize();
                if (resolved.getFileName().toString().equals(COMMAND) && resolved.startsWith(binDir)) {
                    return;
                }
            } catch (IOException e) {
                throw linkFailed(alias, e);
            }
        }
        throw new TapstateException(
                CliError.ALIAS_NAME_TAKEN, Map.of("path", alias.toString()), null);
    }

    /**
     * The shortcut could not be written or removed. These are the filesystem's ordinary refusals -- a
     * directory that is not there, one this user cannot write into, a filesystem with no symbolic links
     * -- and each of them belongs to the user to act on, so they leave the same way the name-taken
     * refusal one branch away does. What the filesystem said is carried in {@code reason}: without it
     * the message names a path and no cause, which is the half the reader already had.
     */
    private static TapstateException linkFailed(Path alias, IOException cause) {
        return new TapstateException(CliError.ALIAS_LINK_FAILED,
                Map.of("path", alias.toString(), "reason", describe(cause)), cause);
    }

    /** What the filesystem said, in one line, never empty. */
    private static String describe(IOException cause) {
        String said = cause.getMessage();
        return said == null || said.isBlank()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + said;
    }

    private static void quietlyDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The staged name is ours and temporary; failing to clean it up must not mask the real cause.
        }
    }
}
