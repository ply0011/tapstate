package io.tapstate.archtests;

import io.tapstate.spi.store.DataBrowserQuery;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A read of business data may only reach the database its source's connection resolved to. Nothing in
 * the request says which database that is, and that absence is the whole guarantee: a request with no
 * word for a database cannot ask for another one, whatever a caller writes down.
 *
 * <p>The absence is guarded at the boundary already — a body naming a database is refused rather than
 * bound, and there is an end-to-end witness for it. What is guarded here is the other half, the one a
 * boundary test cannot see: the shape of the request types themselves. A slot added to either of these
 * records would compile, and every caller that does not pass it would keep compiling too if the change
 * came with a constructor for the old arity. Nothing would be red, and the read face would have grown a
 * way to name a database — upstream of the only line of defence there is.
 *
 * <p>So the shapes are written down and compared. The rule is not "these lists must never change": it is
 * that changing one is a decision somebody made on purpose, rather than a slot that arrived with a
 * feature and was never read as what it is. Adding a genuinely needed component means editing the list
 * here, in a file whose whole subject is why the list is short.
 *
 * <p>Two types, because a read is written down twice on its way in and either spelling could grow the
 * slot: the body a caller sends, and the value the drive is given. The path between them carries the
 * collection, which is why the body has no component for it.
 */
class DataBrowserReadShapeGatesTest {

    /** The body of a read, as every surface sends it. Package-private, so it is named rather than imported. */
    private static final String FIND_REQUEST = "io.tapstate.control.restapi.DataBrowserFindRequest";

    @Test
    @DisplayName("the value handed to the drive names a collection, rows, an order and a size - and nothing else")
    void theSeamsReadRequestHasNoRoomToNameADatabaseOrACommand() {
        assertThat(componentsOf(DataBrowserQuery.class))
                .as("the components of the read request the drive is given")
                .containsExactly("collection", "filter", "sort", "limit");
    }

    @Test
    @DisplayName("the body a caller sends carries the same read and no more of one")
    void theBodyOfAReadHasNoRoomToNameADatabaseOrACommandEither() throws ClassNotFoundException {
        assertThat(componentsOf(Class.forName(FIND_REQUEST)))
                .as("the components of the request body, whose collection comes from the path instead")
                .containsExactly("filter", "sort", "limit");
    }

    private static List<String> componentsOf(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        assertThat(components)
                .as("%s is a record, so its shape is the list of its components", type.getSimpleName())
                .isNotNull();
        return Arrays.stream(components).map(RecordComponent::getName).toList();
    }
}
