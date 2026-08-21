package io.tapstate.core.model;

/**
 * Sink write mode: each value is one insert/update/delete disposal preset. The default is
 * {@code upsert}, and the default is written by leaving the field out.
 *
 * <p>Upsert depends on a key, and the key it depends on is the one the <em>source</em> table was
 * discovered to declare - not the target's. The two are easy to confuse and the difference is what a
 * reader acts on: adding a key to the table being written does not lift the refusal, because it is
 * not the table the refusal is about. The dependency is enforced where a discovered model exists
 * rather than offline, since whether a table declares a key is a property of the table and no
 * document carries it. Append is not judged, since it never matches a write to an existing row.
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
