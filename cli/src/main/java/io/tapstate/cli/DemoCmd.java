package io.tapstate.cli;

import io.tapstate.core.common.TapstateException;
import io.tapstate.messages.MessageCatalog;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * {@code demo} — writes the demo workspace, in one command.
 *
 * <p>The walkthrough teaches this workspace by having you write it: three resources, typed out, each
 * explained. That is the right way to meet it once and the wrong way to meet it a second time - someone
 * who has just watched the demo and wants to run it themselves is not learning the DSL yet, they are
 * deciding whether this is worth their afternoon, and asking them to transcribe three files first is
 * where that decision gets made against us.
 *
 * <p><b>The same three files, not a fourth copy of them.</b> They are carried as resources beside this
 * class and held byte-for-byte to the ones the quickstart writes, by a test that says why. A demo command
 * that generated its own variant would have re-created, in the same release, the drift that the
 * quickstart and the end-to-end case were just wired together to prevent.
 *
 * <p><b>It writes a workspace and nothing else.</b> It starts no containers, opens no ports and reaches
 * no network: bringing the stack up belongs to the quickstart script, which already does it, and doing
 * it here too would mean two things that must agree about what the demo stack is. So the failure this
 * command can actually have is a workspace already holding these files - refused with a code rather
 * than overwritten, because those files are the user's the moment they have edited one.
 *
 * <p>{@code --print-steps} prints the walkthrough instead of writing anything. The seven steps are what
 * the recording follows and what teaches the shape; this command is the shortcut for people who want
 * the result first, never a replacement for them.
 */
@Command(name = "demo", mixinStandardHelpOptions = true,
        description = {
                "Write the demo workspace: two sources on different engines, and the pipeline that"
                        + " assembles them into one object.",
                "Writes files only - bring the stack up with the quickstart script."})
final class DemoCmd implements Callable<Integer> {

    /** Exit code when a coded diagnostic is reported, as the other offline verbs spell it. */
    static final int EXIT_DIAGNOSTIC = 1;

    /** The resources this writes, as {@code <kind directory>/<file>}, in the order a reader meets them. */
    static final List<String> RESOURCES =
            List.of("source/orders_db.tap.yml", "source/fulfillment_db.tap.yml",
                    "pipeline/order_pipeline.tap.yml");

    /**
     * The read-shell calls step 4 shows, each with the note printed beside it.
     *
     * <p>Held apart from the prose rather than written into it, so that a test can parse the command
     * exactly as printed. This is not tidiness: a wrong command here is indistinguishable from a right
     * one to anybody reading the file, and it reaches a stranger as an error on the first thing they
     * were told to type. One shipped that way - the watched row was written as a {@code find} call on
     * the collection, which the shell reads as a collection whose name contains the call.
     */
    static final List<String[]> READS = List.of(
            new String[] {"show collections", "one collection: views.order_state"},
            new String[] {"views.order_state.find({id:1})", "one order, with its shipments inside it"},
            new String[] {"watch views.order_state {id:1}", "the same object, redrawn as it changes"});

    /** Column the notes beside step 4's calls start at, so they line up under each other. */
    private static final int NOTE_COLUMN = 38;

    /**
     * The walkthrough, in the order the recording follows it. Kept here rather than in a document
     * because this is the copy a user can ask for at any moment, on the machine they are on.
     */
    private static final List<String> STEPS = List.of(
            "1. Install and bring up the stack (databases, server and store, seeded):",
            "     curl -sSL https://install.tapstate.dev | sh",
            "2. Write the demo workspace - orders in MySQL, shipments in PostgreSQL:",
            "     tapstate demo -w work",
            "3. Go online, register the connectors this demo reads, and apply it:",
            "     tapstate -w work   then: connect http://127.0.0.1:8080 ; login admin",
            "     register ../mysql-connector.jar ; register ../postgres-connector.jar",
            "     register ../mongodb-connector.jar",
            "     apply source/orders_db.tap.yml ; apply source/fulfillment_db.tap.yml",
            "     discover-schema orders_db ; discover-schema fulfillment_db ; apply",
            "     start order_pipeline",
            "4. Look at what was assembled:",
            "5. Change either database and watch that object follow:",
            "     docker compose exec postgres psql -U postgres -d appdb \\",
            "       -c \"INSERT INTO shipments VALUES (7,1,'ups','pending');\"",
            "     docker compose exec mysql mysql -uroot -psecret appdb \\",
            "       -e \"UPDATE orders SET customer='alicia' WHERE id=1;\"",
            "6. What you are looking at is a materialized view assembled from two engines that cannot",
            "   see each other - no SQL view and no join can produce it.",
            "7. And an agent can read the same object over MCP:",
            "     data_browser_collections / data_browser_find");

    @Spec
    CommandSpec spec;

    @Mixin
    WorkspaceOption workspace;

    @Option(names = "--print-steps",
            description = "Print the walkthrough these files belong to, and write nothing.")
    boolean printSteps;

    @Option(names = "--force", description = "Overwrite the demo files if the workspace already holds them.")
    boolean force;

    @Option(names = {"-o", "--output"}, paramLabel = "FORMAT",
            description = "Output format: text, json or yaml (default: text).",
            defaultValue = "text", completionCandidates = OutputFormat.Candidates.class)
    OutputFormat output;

    @Override
    public Integer call() {
        if (printSteps) {
            emitSteps();
            return 0;
        }
        Path root = workspace.root();
        try {
            List<Path> written = write(root);
            emitWritten(root, written);
            return 0;
        } catch (TapstateException coded) {
            return emitDiagnostic(coded);
        }
    }

    /**
     * Writes every resource, or none of them.
     *
     * <p>The existence check runs over the whole set before the first byte is written. A per-file check
     * would leave a workspace holding two of the three when the third is the one already there, which
     * is a state neither a re-run nor {@code --force} was designed around and which the user did not ask
     * for.
     */
    private List<Path> write(Path root) {
        if (!force) {
            for (String resource : RESOURCES) {
                Path target = root.resolve(resource);
                if (Files.exists(target)) {
                    throw new TapstateException(
                            CliError.DEMO_WORKSPACE_EXISTS, Map.of("path", target.toString()), null);
                }
            }
        }
        List<Path> written = new ArrayList<>();
        for (String resource : RESOURCES) {
            Path target = root.resolve(resource);
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(target, bundled(resource));
            } catch (IOException cannotWrite) {
                throw new UncheckedIOException("cannot write " + target, cannotWrite);
            }
            written.add(target);
        }
        return written;
    }

    /** One bundled resource. Absent means a broken build, not a user error, so it crashes bare. */
    static String bundled(String resource) {
        String name = "/demo/" + resource.substring(resource.indexOf('/') + 1);
        try (InputStream in = DemoCmd.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("the demo resource " + name + " is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read the bundled demo resource " + name, unreadable);
        }
    }

    /** The walkthrough as printed: the prose, with step 4's calls rendered under their heading. */
    static List<String> walkthrough() {
        List<String> lines = new ArrayList<>();
        for (String step : STEPS) {
            lines.add(step);
            if (step.startsWith("4. ")) {
                READS.forEach(read -> lines.add("     " + pad(read[0]) + read[1]));
            }
        }
        return lines;
    }

    private static String pad(String call) {
        return call.length() >= NOTE_COLUMN ? call + "  " : call + " ".repeat(NOTE_COLUMN - call.length());
    }

    private void emitSteps() {
        PrintWriter out = CliIo.out(spec);
        List<String> lines = walkthrough();
        switch (output) {
            case JSON -> out.println(JsonOut.write(Map.of("status", "ok", "steps", lines)));
            case YAML -> out.println(YamlOut.write(Map.of("status", "ok", "steps", lines)));
            default -> {
                out.println("The demo, end to end:");
                out.println();
                lines.forEach(out::println);
            }
        }
        out.flush();
    }

    private void emitWritten(Path root, List<Path> written) {
        PrintWriter out = CliIo.out(spec);
        switch (output) {
            case JSON -> out.println(JsonOut.write(envelope(root, written)));
            case YAML -> out.println(YamlOut.write(envelope(root, written)));
            default -> {
                out.println("Wrote the demo workspace to " + root + ":");
                written.forEach(path -> out.println("  " + root.relativize(path)));
                out.println();
                out.println("Next: bring the stack up, then apply it. `tapstate demo --print-steps`"
                        + " prints the whole walkthrough.");
            }
        }
        out.flush();
    }

    private static Map<String, Object> envelope(Path root, List<Path> written) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("status", "ok");
        env.put("workdir", root.toString());
        env.put("created", written.stream().map(path -> root.relativize(path).toString()).toList());
        return env;
    }

    private int emitDiagnostic(TapstateException e) {
        switch (output) {
            case JSON -> {
                PrintWriter o = CliIo.out(spec);
                o.println(JsonOut.write(diagnosticEnvelope(e)));
                o.flush();
            }
            case YAML -> {
                PrintWriter o = CliIo.out(spec);
                o.println(YamlOut.write(diagnosticEnvelope(e)));
                o.flush();
            }
            default -> {
                MessageCatalog.Rendered rendered = MessageCatalog.bundled().render(e.code(), e.args());
                PrintWriter err = CliIo.err(spec);
                err.println(Ansi.AUTO.string("@|bold,red error:|@") + " " + e.code().code());
                err.println("  " + rendered.message());
                if (rendered.solution() != null) {
                    err.println("  " + rendered.solution());
                }
                err.flush();
            }
        }
        return EXIT_DIAGNOSTIC;
    }

    private static Map<String, Object> diagnosticEnvelope(TapstateException e) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("status", "error");
        env.put("diagnostics", List.of(Diagnostics.map(e.code(), e.args(), null, 0, 0)));
        return env;
    }
}
