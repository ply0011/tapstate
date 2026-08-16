package io.tapstate.core.model;

/**
 * Sink write mode: each value is one insert/update/delete disposal preset. The default is
 * {@code upsert}, and the default is written by leaving the field out.
 *
 * <p>Upsert depends on the written table having a key, and that dependency is enforced where a
 * discovered model exists rather than offline, because whether a table declares a key is a property
 * of the table and no document carries it. An upsert into a table whose source declares no key is
 * refused there; append is not, since it never matches a write to an existing row.
 */
@Doc("How a sink applies inserts, updates and deletes to the target.")
public enum WriteMode {
    @Doc("Insert new rows and update existing ones by primary key; deletes remove the matching row.")
    UPSERT("upsert"),
    @Doc("Always insert rows without matching on a key; updates and deletes are not applied.")
    APPEND("append");

    private final String yaml;

    WriteMode(String yaml) {
        this.yaml = yaml;
    }

    public String yaml() {
        return yaml;
    }
}
