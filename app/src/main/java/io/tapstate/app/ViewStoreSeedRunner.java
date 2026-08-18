package io.tapstate.app;

import io.tapstate.core.model.SourceResource;
import io.tapstate.spi.store.ArtifactMutation;
import io.tapstate.spi.store.ArtifactStore;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Registers the managed state store views materialize into, once at startup, so a deployment has one
 * without anyone declaring it.
 *
 * <p>It used to be a file the demo script wrote and the user applied, which made the store look like part
 * of the workspace an author owns. It is not: it is the deployment's, the same store the server already
 * keeps its own state in, and requiring a person to hand it back to the server was a step that existed
 * only because nothing else created it.
 *
 * <p>The connection is derived from the server's own store URI rather than configured separately. There is
 * nothing else it could point at in this release -- the bundled store is one instance shared by the
 * control plane and the views, which is a documented boundary of the preview rather than an oversight --
 * and a second setting would be one more thing to get wrong for a deployment that has no second instance
 * to name.
 *
 * <p>Seeding never overwrites. The insert is the store's atomic create, so an author who has declared
 * their own resource under this id keeps it, and the seed reports that it found one rather than replacing
 * what somebody meant to put there.
 */
final class ViewStoreSeedRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ViewStoreSeedRunner.class);

    /** The connector the bundled store speaks; the only shape the view sink writes today. */
    private static final String CONNECTOR = "mongodb";

    private final ArtifactStore artifacts;
    private final String serverStoreUri;

    ViewStoreSeedRunner(ArtifactStore artifacts, String serverStoreUri) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.serverStoreUri = Objects.requireNonNull(serverStoreUri, "serverStoreUri");
    }

    @Override
    public void run(ApplicationArguments args) {
        String id = ViewTargetResolver.STATE_STORE_SOURCE_ID;
        SourceResource store = new SourceResource(
                id, null, CONNECTOR,
                Map.of("isUri", true, "uri", viewsUri(serverStoreUri)),
                // No mode and no tables: this is a connection supplier, not something to read from. The
                // distinction is load-bearing -- a resource under this id that declares capture settings
                // is refused as an authored source rather than written into.
                null, null, null, null, null);
        ArtifactMutation outcome = artifacts.create(store);
        if (outcome == ArtifactMutation.CREATED) {
            LOG.info("Registered the managed state store '{}' for views to materialize into", id);
        } else {
            LOG.info("The managed state store '{}' is already declared; leaving it as it is", id);
        }
    }

    /**
     * The server's store URI with its database swapped for the view store's.
     *
     * <p>Views are kept in their own database rather than beside the control plane's collections: the two
     * are the deployment's and the user's data respectively, and a listing of one should not enumerate the
     * other. Only the path is rewritten -- host, credentials and every option are carried across, because
     * whatever it took to reach the one database is what it takes to reach the other on the same server.
     */
    static String viewsUri(String serverStoreUri) {
        String database = ViewTargetResolver.STATE_STORE_SOURCE_ID;
        int scheme = serverStoreUri.indexOf("://");
        int authorityStart = scheme < 0 ? 0 : scheme + 3;
        int query = serverStoreUri.indexOf('?', authorityStart);
        String beforeQuery = query < 0 ? serverStoreUri : serverStoreUri.substring(0, query);
        String fromQuery = query < 0 ? "" : serverStoreUri.substring(query);
        int path = beforeQuery.indexOf('/', authorityStart);
        String authority = path < 0 ? beforeQuery : beforeQuery.substring(0, path);
        return authority + "/" + database + fromQuery;
    }
}
