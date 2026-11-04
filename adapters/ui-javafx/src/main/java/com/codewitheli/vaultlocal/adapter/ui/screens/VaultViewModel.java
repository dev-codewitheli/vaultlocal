package com.codewitheli.vaultlocal.adapter.ui.screens;

import com.codewitheli.vaultlocal.domain.entry.Entry;
import com.codewitheli.vaultlocal.domain.entry.EntryId;
import com.codewitheli.vaultlocal.domain.entry.Platform;
import com.codewitheli.vaultlocal.domain.policy.StalenessPolicy;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Predicate;

/** S8 state: rows, search/platform/favorites filtering, favorites-first sorting, stale checks. */
public final class VaultViewModel {

    /** {@code null} platform = "All platforms". */
    private final ObjectProperty<Platform> platformFilter = new SimpleObjectProperty<>();
    private final StringProperty search = new SimpleStringProperty("");
    private final BooleanProperty favoritesOnly = new SimpleBooleanProperty(false);
    private final ObservableList<EntryRow> rows = FXCollections.observableArrayList(
            r -> new javafx.beans.Observable[]{r.entryProperty()});
    private final FilteredList<EntryRow> filtered = new FilteredList<>(rows);
    private final SortedList<EntryRow> sorted = new SortedList<>(filtered);
    private final ObservableList<Platform> platforms = FXCollections.observableArrayList();

    public VaultViewModel() {
        filtered.predicateProperty().bind(Bindings.createObjectBinding(this::predicate,
                platformFilter, search, favoritesOnly));
    }

    private Predicate<EntryRow> predicate() {
        String q = search.get() == null ? "" : search.get().strip().toLowerCase(Locale.ROOT);
        Platform p = platformFilter.get();
        boolean favs = favoritesOnly.get();
        return row -> {
            Entry e = row.entry();
            if (favs && !e.favorite()) {
                return false;
            }
            if (p != null && !p.equals(e.platform())) {
                return false;
            }
            return q.isEmpty()
                    || e.platform().displayName().toLowerCase(Locale.ROOT).contains(q)
                    || e.accountLabel().toLowerCase(Locale.ROOT).contains(q)
                    || e.username().toLowerCase(Locale.ROOT).contains(q);
        };
    }

    /** Favorites first, then whatever the table's column sort says (label A–Z by default). */
    public static Comparator<EntryRow> favoritesFirst(Comparator<EntryRow> columnOrder) {
        Comparator<EntryRow> fav = Comparator.comparing(r -> !r.entry().favorite());
        Comparator<EntryRow> fallback = Comparator.comparing(r -> r.entry().accountLabel(), String.CASE_INSENSITIVE_ORDER);
        return fav.thenComparing(columnOrder != null ? columnOrder : fallback);
    }

    public void setEntries(List<Entry> entries, Map<EntryId, Integer> scores) {
        rows.forEach(EntryRow::mask);
        rows.setAll(entries.stream().map(e -> {
            EntryRow r = new EntryRow(e);
            r.strengthProperty().set(scores.get(e.id()));
            return r;
        }).toList());
        TreeSet<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<Platform> distinct = entries.stream().map(Entry::platform)
                .filter(p -> seen.add(p.encode()))
                .sorted(Comparator.comparing(Platform::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        Platform selected = platformFilter.get();
        platforms.setAll(distinct);
        if (selected != null && !distinct.contains(selected)) {
            platformFilter.set(null);
        }
    }

    public void replace(Entry updated, Integer score) {
        for (EntryRow r : rows) {
            if (r.entry().id().equals(updated.id())) {
                r.mask();
                r.entryProperty().set(updated);
                if (score != null) {
                    r.strengthProperty().set(score);
                }
                return;
            }
        }
    }

    public static boolean isStale(Entry e, Instant now, int days) {
        return StalenessPolicy.isStale(e.passwordChangedAt(), now, days);
    }

    public void maskAll() {
        rows.forEach(EntryRow::mask);
    }

    /** Wipes everything (on lock). */
    public void clear() {
        maskAll();
        rows.clear();
        platforms.clear();
    }

    public ObservableList<EntryRow> rows() {
        return rows;
    }

    public SortedList<EntryRow> sorted() {
        return sorted;
    }

    public int visibleCount() {
        return filtered.size();
    }

    public FilteredList<EntryRow> filtered() {
        return filtered;
    }

    public ObservableList<Platform> platforms() {
        return platforms;
    }

    public ObjectProperty<Platform> platformFilterProperty() {
        return platformFilter;
    }

    public StringProperty searchProperty() {
        return search;
    }

    public BooleanProperty favoritesOnlyProperty() {
        return favoritesOnly;
    }

    public EntryRow find(EntryId id) {
        return rows.stream().filter(r -> Objects.equals(r.entry().id(), id)).findFirst().orElse(null);
    }
}
