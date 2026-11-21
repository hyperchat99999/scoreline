package app.scoreline.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Provider tables retain their conference/group and the labels accompanying their values. */
public final class Standings {
    public final boolean supported;
    public final List<Group> groups;

    public Standings(boolean supported, List<Group> groups) {
        this.supported = supported;
        this.groups = immutable(groups);
    }

    public static final class Group {
        public final String name;
        public final List<Column> columns;
        public final List<Row> rows;

        public Group(String name, List<Column> columns, List<Row> rows) {
            this.name = name;
            this.columns = immutable(columns);
            this.rows = immutable(rows);
        }
    }

    public static final class Column {
        public final String name;
        public final String label;

        public Column(String name, String label) {
            this.name = name;
            this.label = label;
        }
    }

    public static final class Row {
        /** Empty when ESPN does not supply an explicit rank/seed. */
        public final String rank;
        public final Team team;
        public final List<String> values;

        public Row(String rank, Team team, List<String> values) {
            this.rank = rank;
            this.team = team;
            this.values = immutable(values);
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
