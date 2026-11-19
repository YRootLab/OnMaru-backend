package com.yrootlab.onmaru.persistence.mapcollab;

import com.yrootlab.onmaru.mapcollab.port.MemberProfilePort;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class JdbcMemberProfileAdapter implements MemberProfilePort {

    private final DataSource dataSource;

    public JdbcMemberProfileAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Map<UUID, String> findDisplayNames(Set<UUID> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> result = new HashMap<>();
        String sql = """
                SELECT member_id, display_name
                FROM onmaru.identity_member_profiles
                WHERE member_id = ANY (?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            Array array = conn.createArrayOf("uuid", memberIds.toArray());
            ps.setArray(1, array);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put((UUID) rs.getObject("member_id"), rs.getString("display_name"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query member display names", e);
        }
        return result;
    }
}
