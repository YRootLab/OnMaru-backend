package com.yrootlab.onmaru.persistence.admin;

import com.yrootlab.onmaru.admin.users.AdminMember;
import com.yrootlab.onmaru.admin.users.AdminMemberStore;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

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
}
