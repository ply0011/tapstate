package io.tapstate.archtests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate behind the dependency table's own comment: <em>every new production module must be
 * registered here</em>. Until now that sentence was discipline, not a gate.
 *
 * <p>An unregistered module fails in the quietest way this repository has. Its classes are simply
 * absent from the arch-tests classpath, so every rule that names its package scans zero classes and
 * reports green — the same green a rule reports when it scanned the module and found nothing wrong.
 * Nothing distinguishes the two from the outside. Four production modules sat unscanned this way
 * (core-catalog, core-event, core-logging, catalog-assembler) and no run ever said a word.
 *
 * <p><strong>Exemptions are named one by one, each with its reason, and that is load-bearing.</strong>
 * The tempting shape is a category — "skip anything with packaging=pom" — and it works for exactly as
 * long as one category suffices. Add a second catch-all beside it ("skip test infrastructure", "skip
 * anything under tools/") and deleting a real module's registration stops turning this red, because
 * some category now absorbs it. Named, a new module is red by default: turning it green means either
 * registering it or writing its name and a reason into the list below, and both are a decision
 * somebody made on purpose. There is no silent third path.
 */
class ModuleRegistrationTest {

    /** Surefire runs a module from its own directory, so the repository root is one level up. */
    private static final Path REPOSITORY = Path.of("..");

    /** The dependency table this gate polices: the arch-tests module's own dependency block. */
    private static final Path DEPENDENCY_TABLE = REPOSITORY.resolve("arch-tests").resolve("pom.xml");

    /** The coordinate every module under scan shares; a third-party dependency is not a registration. */
    private static final String OUR_GROUP = "io.tapstate";

    /**
     * Reactor modules that are legitimately absent from the dependency table, keyed by artifactId,
     * each carrying the reason it is absent. Adding a row here is a deliberate act — see the class
     * javadoc for why this is a list of names rather than a rule about kinds.
     */
    private static final Map<String, String> EXEMPT = exemptions();

    private static Map<String, String> exemptions() {
        Map<String, String> exempt = new LinkedHashMap<>();
        // Aggregators: packaging=pom, so there is no bytecode for any rule to scan. Their members
        // register individually, which is where the scanning actually happens.
        exempt.put("tapstate", "the reactor root: an aggregator pom, no bytecode to scan");
        exempt.put("bom", "an aggregator pom: dependency management only, no bytecode to scan");
        exempt.put("core", "an aggregator pom: the core ring's members register individually");
        exempt.put("spi", "an aggregator pom: the spi ring's members register individually");
        exempt.put("adapters", "an aggregator pom: the adapters ring's members register individually");
        exempt.put("runtime", "an aggregator pom: the runtime ring's members register individually");
        exempt.put("control", "an aggregator pom: the control ring's members register individually");
        exempt.put("cli-bundle", "an aggregator pom: a release-only assembly of artifacts built elsewhere");
        // Not product code under guard.
        exempt.put("arch-tests", "the module the architecture rules themselves live in");
        exempt.put("e2e", "test infrastructure driving the product black-box, not guarded product code");
        exempt.put("test-support", "test scaffolding shared by other modules' suites, not guarded product code");
        return exempt;
    }

    @Test
    @DisplayName("every reactor module is either registered for scanning or named as an exemption")
    void everyReactorModuleIsEitherScannedOrNamedAsAnExemption() {
        Set<String> registered = registeredForScanning();
        List<String> unaccountedFor = new ArrayList<>();
        for (Module module : reactorModules()) {
            if (registered.contains(module.artifactId()) || EXEMPT.containsKey(module.artifactId())) {
                continue;
            }
            unaccountedFor.add(module.artifactId() + " (" + module.path() + ")");
        }
        assertThat(unaccountedFor)
                .as("a reactor module that is neither a test dependency of arch-tests nor a named "
                        + "exemption is invisible to every architecture rule, and its absence reports "
                        + "the same green as compliance — register it in arch-tests/pom.xml, or add it "
                        + "to EXEMPT with the reason it needs no scanning")
                .isEmpty();
    }

    @Test
    @DisplayName("the walk reached the reactor's leaves at every nesting depth")
    void theWalkReachedTheReactorsLeavesAtEveryNestingDepth() {
        // A gate over an enumeration is only as good as the enumeration. A walk that stopped at the
        // root pom, or that failed to descend into a nested aggregator, would find nothing
        // unaccounted for and report the same green as full coverage. These names are checked
        // individually and chosen for depth: one module declared directly by the root, one inside a
        // ring aggregator, and two under a two-segment path, so a walk that quietly stops descending
        // is red rather than vacuous.
        List<String> reached = reactorModules().stream().map(Module::artifactId).toList();
        assertThat(reached)
                .as("the reactor walk must reach every nesting depth the repository actually uses")
                .contains("tapstate", "app", "core-catalog", "catalog-assembler", "cli-bundle");
    }

    @Test
    @DisplayName("the dependency table was read, not merely opened")
    void theDependencyTableWasReadNotMerelyOpened() {
        // The twin vacuity: a table read that returned nothing would make every module look
        // unregistered, which is loud and self-correcting — but a read that returned the wrong
        // block (the dependencyManagement import, say) would return a set that is non-empty and
        // wrong. Naming modules known to be registered pins that the project-level block is the
        // one being read.
        assertThat(registeredForScanning())
                .as("the arch-tests dependency block must be the one read")
                .contains("app", "cli", "core-model")
                .doesNotContain("bom");
    }

    @Test
    @DisplayName("every named exemption still names a reactor module")
    void everyNamedExemptionStillNamesAReactorModule() {
        // An exemption for a module that no longer exists is a name nobody will ever question again.
        // Worse, it hides a rename: the renamed module goes red (correctly), and the obvious repair
        // is to add its new name here rather than ask whether it should now be scanned.
        List<String> reachable = reactorModules().stream().map(Module::artifactId).toList();
        assertThat(EXEMPT.keySet())
                .as("an exemption naming a module the reactor no longer has is dead weight")
                .allSatisfy(exempt -> assertThat(reachable).contains(exempt));
    }

    @Test
    @DisplayName("catalog-derive is outside the reactor, so this gate cannot see it — and must not claim to")
    void catalogDeriveIsOutsideTheReactorAndThereforeOutsideThisGate() {
        // catalog-derive is the PDK-touching segment of the catalog pipeline. It is deliberately kept
        // out of the default reactor so its transitive PDK tree never enters `mvn verify`; registering
        // it would trade a discipline problem for a heavy build. It is therefore structurally
        // invisible here, and its boundary is asserted from the PDK-free side instead (see
        // RingDependencyRulesTest). Adding it to EXEMPT would be the wrong repair — it would read as
        // "considered and waived" when what is true is "this gate never sees it".
        //
        // This pins both halves. Should catalog-derive ever join the reactor, the first test above
        // turns red and forces the decision to be made rather than absorbed by an exemption row.
        List<String> reached = reactorModules().stream().map(Module::artifactId).toList();
        // isNotEmpty first: doesNotContain over an empty list is true without looking at anything, so a
        // reactorModules() that stopped finding modules would satisfy this while seeing nothing at all.
        assertThat(reached)
                .as("catalog-derive is standalone by design: own coordinates, not under the root parent")
                .isNotEmpty()
                .doesNotContain("catalog-derive");
        assertThat(EXEMPT)
                .as("a module this gate cannot see must not be listed as a waiver it granted")
                .doesNotContainKey("catalog-derive");
    }

    /** A reactor module: the artifactId the dependency table would name it by, and where it lives. */
    private record Module(String artifactId, String path) {}

    private static List<Module> reactorModules() {
        List<Module> found = new ArrayList<>();
        collectFrom(REPOSITORY, found);
        return found;
    }

    private static void collectFrom(Path moduleDirectory, List<Module> into) {
        Element project = projectOf(moduleDirectory.resolve("pom.xml"));
        String path = REPOSITORY.relativize(moduleDirectory).toString();
        into.add(new Module(textOf(project, "artifactId"), path.isEmpty() ? "." : path));
        for (Element modules : childrenNamed(project, "modules")) {
            for (Element module : childrenNamed(modules, "module")) {
                collectFrom(moduleDirectory.resolve(module.getTextContent().trim()), into);
            }
        }
    }

    private static Set<String> registeredForScanning() {
        Element project = projectOf(DEPENDENCY_TABLE);
        Set<String> registered = new TreeSet<>();
        // Direct children only: the dependencyManagement block one level deeper imports the BOM,
        // which manages versions and puts no class on any classpath.
        for (Element dependencies : childrenNamed(project, "dependencies")) {
            for (Element dependency : childrenNamed(dependencies, "dependency")) {
                if (OUR_GROUP.equals(textOf(dependency, "groupId"))) {
                    registered.add(textOf(dependency, "artifactId"));
                }
            }
        }
        return registered;
    }

    private static Element projectOf(Path pom) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setExpandEntityReferences(false);
            try (var stream = Files.newInputStream(pom)) {
                Document document = factory.newDocumentBuilder().parse(stream);
                return document.getDocumentElement();
            }
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot read " + pom, cause);
        } catch (ParserConfigurationException | org.xml.sax.SAXException cause) {
            throw new IllegalStateException("cannot parse " + pom, cause);
        }
    }

    /**
     * Direct children by tag name. getElementsByTagName searches the whole subtree, which would read
     * a parent's artifactId as the module's own and the managed BOM import as a registration.
     */
    private static List<Element> childrenNamed(Element parent, String tag) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tag.equals(node.getNodeName())) {
                children.add((Element) node);
            }
        }
        return children;
    }

    private static String textOf(Element parent, String tag) {
        List<Element> matches = childrenNamed(parent, tag);
        return matches.isEmpty() ? "" : matches.get(0).getTextContent().trim();
    }
}
