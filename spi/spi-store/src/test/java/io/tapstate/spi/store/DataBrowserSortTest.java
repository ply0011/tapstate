package io.tapstate.spi.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tapstate.spi.store.DataBrowserSort.Direction;
import org.junit.jupiter.api.Test;

/** The order one read is returned in. */
class DataBrowserSortTest {

    @Test
    void refusesToBeBuiltWithoutAField() {
        // A half-built order is worse than none: it reaches the bridge, which asks it for a field name and
        // fails there instead - one layer away from the caller that got it wrong.
        assertThatThrownBy(() -> new DataBrowserSort(null, Direction.ASC))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("field");
    }

    @Test
    void refusesToBeBuiltWithoutADirection() {
        assertThatThrownBy(() -> new DataBrowserSort("status", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("direction");
    }

    @Test
    void carriesADottedPathAsTheFieldItOrdersOn() {
        // The documents being previewed are nested, so the field a caller wants to order on is often not
        // a top-level one. Nothing here splits or validates the path; it travels whole to the backend.
        DataBrowserSort sort = new DataBrowserSort("shipping.address.city", Direction.DESC);

        assertThat(sort.field()).isEqualTo("shipping.address.city");
        assertThat(sort.direction()).isEqualTo(Direction.DESC);
    }
}
