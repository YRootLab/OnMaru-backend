package com.yrootlab.onmaru.persistence.admin;

import com.yrootlab.onmaru.admin.users.AdminMember;
import com.yrootlab.onmaru.admin.users.AdminMemberStore;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.yrootlab.onmaru.catalog.application.pagination.AdminCursor;
import com.yrootlab.onmaru.catalog.application.pagination.AdminPage;

public final class JdbcAdminMemberStore implements AdminMemberStore {

    private final DataSource dataSource;

    public JdbcAdminMemberStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<AdminMember> find(String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT member.id, member.status::text, member.created_at, count(review.id) AS review_count
                FROM onmaru.identity_members member
                LEFT JOIN onmaru.community_visit_reviews review ON review.member_id = member.id
                """);
        if (status != null && !status.isBlank()) {
            sql.append(" WHERE member.status::text = ?");
        }
        sql.append(" GROUP BY member.id, member.status, member.created_at ORDER BY member.created_at DESC LIMIT ?");
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            if (status != null && !status.isBlank()) {
                statement.setString(index++, status.toUpperCase());
            }
            statement.setInt(index, limit);
            try (var result = statement.executeQuery()) {
                List<AdminMember> members = new ArrayList<>();
                while (result.next()) {
                    members.add(new AdminMember(
                            result.getObject("id", java.util.UUID.class),
                            result.getString("status"),
                            result.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                            result.getLong("review_count")));
                }
                return members;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load admin members", exception);
        }
    }

    @Override
    public AdminPage<AdminMember> findPage(String status, int limit, AdminCursor cursor) {
        String normalizedStatus = status == null || status.isBlank() ? null : status.toUpperCase();
        StringBuilder sql = new StringBuilder("""
                WITH page_members AS (
                    SELECT member.id, member.status::text AS status, member.created_at
                    FROM onmaru.identity_members member
                    WHERE 1=1
                """);
        if (normalizedStatus != null) sql.append(" AND member.status::text = ?");
        if (cursor != null) sql.append(" AND (member.created_at, member.id) < (?, ?)");
        sql.append(" ORDER BY member.created_at DESC, member.id DESC LIMIT ?)");
        sql.append("""
                 SELECT page_members.id, page_members.status, page_members.created_at,
                        (SELECT count(*) FROM onmaru.community_visit_reviews review
                         WHERE review.member_id = page_members.id) AS review_count
                 FROM page_members
                 ORDER BY page_members.created_at DESC, page_members.id DESC
                """);
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            if (normalizedStatus != null) statement.setString(index++, normalizedStatus);
            if (cursor != null) {
                statement.setObject(index++, java.time.OffsetDateTime.ofInstant(cursor.timestamp(), java.time.ZoneOffset.UTC));
                statement.setObject(index++, cursor.id());
            }
            statement.setInt(index, limit + 1);
            try (var result = statement.executeQuery()) {
                List<AdminMember> members = new ArrayList<>();
                while (result.next()) {
                    members.add(new AdminMember(result.getObject("id", UUID.class), result.getString("status"),
                            result.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                            result.getLong("review_count")));
                }
                boolean hasNext = members.size() > limit;
                return new AdminPage<>(members.subList(0, Math.min(limit, members.size())), hasNext);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load admin member page", exception);
        }
    }
}
