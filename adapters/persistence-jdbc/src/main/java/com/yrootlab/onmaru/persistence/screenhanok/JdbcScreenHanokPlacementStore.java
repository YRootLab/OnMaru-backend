package com.yrootlab.onmaru.persistence.screenhanok;
import com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokMediaType;
import com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokPlacement;
import com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokPlacementStore;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** PostgreSQL-backed, atomically replaced screen-hanok publication snapshot. */
public final class JdbcScreenHanokPlacementStore implements ScreenHanokPlacementStore {

    private final DataSource dataSource;

    public JdbcScreenHanokPlacementStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void publish(List<ScreenHanokPlacement> placements) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var delete = connection.prepareStatement("DELETE FROM onmaru.catalog_screen_hanok_placements")) {
                    delete.executeUpdate();
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO onmaru.catalog_screen_hanok_placements (
                            place_id, media_type, work_title, subtitle, tags, source_url, source_title, published_at
                        ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                        """)) {
                    for (var placement : placements) {
                        insert.setString(1, placement.placeId());
                        insert.setString(2, placement.mediaType().name());
                        insert.setString(3, placement.workTitle());
                        insert.setString(4, placement.subtitle());
                        insert.setString(5, tagsJson(placement.tags()));
                        insert.setString(6, placement.sourceUrl());
                        insert.setString(7, placement.sourceTitle());
                        insert.setObject(8, OffsetDateTime.ofInstant(placement.publishedAt(), ZoneOffset.UTC));
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw databaseFailure(exception);
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    @Override
    public List<ScreenHanokPlacement> current() {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT place_id, media_type, work_title, subtitle, tags, source_url, source_title, published_at
                     FROM onmaru.catalog_screen_hanok_placements
                     ORDER BY published_at DESC, place_id, media_type, work_title
                     """);
             var result = statement.executeQuery()) {
            var placements = new ArrayList<ScreenHanokPlacement>();
            while (result.next()) {
                placements.add(new ScreenHanokPlacement(
                        result.getString("place_id"),
                        ScreenHanokMediaType.valueOf(result.getString("media_type")),
                        result.getString("work_title"),
                        result.getString("subtitle"),
                        tags(result.getString("tags")),
                        result.getString("source_url"),
                        result.getString("source_title"),
                        result.getObject("published_at", OffsetDateTime.class).toInstant()));
            }
            return List.copyOf(placements);
        } catch (Exception exception) {
            throw databaseFailure(exception);
        }
    }

    private IllegalStateException databaseFailure(Exception exception) {
        return new IllegalStateException("Screen-hanok publication snapshot database operation failed", exception);
    }

    private String tagsJson(List<String> tags) {
        var json = new StringBuilder("[");
        for (int index = 0; index < tags.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('"');
            for (char character : tags.get(index).toCharArray()) {
                switch (character) {
                    case '"' -> json.append("\\\"");
                    case '\\' -> json.append("\\\\");
                    case '\b' -> json.append("\\b");
                    case '\f' -> json.append("\\f");
                    case '\n' -> json.append("\\n");
                    case '\r' -> json.append("\\r");
                    case '\t' -> json.append("\\t");
                    default -> {
                        if (character < 0x20) {
                            json.append(String.format("\\u%04x", (int) character));
                        } else {
                            json.append(character);
                        }
                    }
                }
            }
            json.append('"');
        }
        return json.append(']').toString();
    }

    private List<String> tags(String json) {
        var values = new ArrayList<String>();
        int index = skipWhitespace(json, 0);
        if (index >= json.length() || json.charAt(index++) != '[') {
            throw new IllegalStateException("Screen-hanok tags must be a JSON array");
        }
        index = skipWhitespace(json, index);
        while (index < json.length() && json.charAt(index) != ']') {
            if (json.charAt(index++) != '"') {
                throw new IllegalStateException("Screen-hanok tags must contain JSON strings");
            }
            var value = new StringBuilder();
            while (index < json.length() && json.charAt(index) != '"') {
                char character = json.charAt(index++);
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                if (index >= json.length()) {
                    throw new IllegalStateException("Invalid screen-hanok tag escape");
                }
                char escaped = json.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        if (index + 4 > json.length()) {
                            throw new IllegalStateException("Invalid screen-hanok unicode escape");
                        }
                        value.append((char) Integer.parseInt(json.substring(index, index + 4), 16));
                        index += 4;
                    }
                    default -> throw new IllegalStateException("Invalid screen-hanok tag escape");
                }
            }
            if (index >= json.length()) {
                throw new IllegalStateException("Unterminated screen-hanok tag");
            }
            values.add(value.toString());
            index = skipWhitespace(json, index + 1);
            if (index < json.length() && json.charAt(index) == ',') {
                index = skipWhitespace(json, index + 1);
            } else if (index >= json.length() || json.charAt(index) != ']') {
                throw new IllegalStateException("Invalid screen-hanok tags array");
            }
        }
        if (index >= json.length() || json.charAt(index) != ']') {
            throw new IllegalStateException("Invalid screen-hanok tags array");
        }
        return List.copyOf(values);
    }

    private int skipWhitespace(String value, int index) {
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }
}
