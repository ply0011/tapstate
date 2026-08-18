package io.tapstate.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.tapstate.core.model.Resource;
import io.tapstate.core.model.SourceResource;
import org.junit.jupiter.api.Test;

/**
 * The managed state store is registered by the deployment at startup, not applied by whoever is using it.
 */
class ViewStoreSeedRunnerTest {

    @Test
    void the_store_is_there_without_anyone_applying_it() {
        InMemoryStorePort store = new InMemoryStorePort();

        new ViewStoreSeedRunner(store.artifacts(), "mongodb://mongo:27017/tapstate").run(null);

        Resource seeded = store.artifacts().get(ViewTargetResolver.STATE_STORE_SOURCE_ID).orElseThrow();
        assertThat(seeded).isInstanceOf(SourceResource.class);
        SourceResource source = (SourceResource) seeded;
        assertThat(source.connector()).isEqualTo("mongodb");
        // A connection supplier, not something to read: a resource under this id that declares capture
        // settings is refused as an authored source, so seeding one would break the deployment it serves.
        assertThat(source.mode()).as("no read mode").isNull();
        assertThat(source.tables()).as("no tables to read").isNull();
    }

    @Test
    void an_author_who_declared_their_own_store_keeps_it() {
        // The seed runs on every boot, so overwriting would silently undo a deliberate change on restart --
        // the kind of loss whose cause is a week away from its effect.
        InMemoryStorePort store = new InMemoryStorePort();
        SourceResource mine = new SourceResource(
                ViewTargetResolver.STATE_STORE_SOURCE_ID, null, "mongodb",
                java.util.Map.of("isUri", true, "uri", "mongodb://elsewhere:27017/mine"),
                null, null, null, null, null);
        store.artifacts().create(mine);

        new ViewStoreSeedRunner(store.artifacts(), "mongodb://mongo:27017/tapstate").run(null);

        SourceResource kept = (SourceResource)
                store.artifacts().get(ViewTargetResolver.STATE_STORE_SOURCE_ID).orElseThrow();
        assertThat(kept.config().get("uri")).isEqualTo("mongodb://elsewhere:27017/mine");
    }

    @Test
    void the_views_database_is_its_own_but_reached_the_same_way() {
        // Only the database is rewritten. Credentials, host list and options are what it took to reach the
        // server at all, so dropping any of them would leave a URI that addresses the right database on a
        // server it can no longer log in to -- and each of these shapes is one a real deployment has.
        assertThat(ViewStoreSeedRunner.viewsUri("mongodb://mongo:27017/tapstate"))
                .isEqualTo("mongodb://mongo:27017/views");
        assertThat(ViewStoreSeedRunner.viewsUri("mongodb://mongo:27017/tapstate?directConnection=true"))
                .as("options are carried, not dropped")
                .isEqualTo("mongodb://mongo:27017/views?directConnection=true");
        assertThat(ViewStoreSeedRunner.viewsUri("mongodb://user:pw@a:27017,b:27017/tapstate?replicaSet=rs0"))
                .as("credentials and every member of the set survive")
                .isEqualTo("mongodb://user:pw@a:27017,b:27017/views?replicaSet=rs0");
        assertThat(ViewStoreSeedRunner.viewsUri("mongodb://mongo:27017"))
                .as("a URI that names no database still gets one")
                .isEqualTo("mongodb://mongo:27017/views");
        assertThat(ViewStoreSeedRunner.viewsUri("mongodb://mongo:27017?directConnection=true"))
                .as("no database, but options")
                .isEqualTo("mongodb://mongo:27017/views?directConnection=true");
    }
}
