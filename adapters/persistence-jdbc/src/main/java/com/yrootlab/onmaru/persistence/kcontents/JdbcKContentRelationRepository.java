package com.yrootlab.onmaru.persistence.kcontents;

import com.yrootlab.onmaru.kcontents.domain.KContentRelationRepository;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcKContentRelationRepository implements KContentRelationRepository {
    private final DataSource dataSource;

    public JdbcKContentRelationRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<PublicRelation> findPublicByPlace(UUID placeId) {
        return find("place_id", "k_content_id", placeId);
    }

    @Override
    public List<PublicRelation> findPublicByWork(UUID workId) {
        return find("k_content_id", "place_id", workId);
    }

    private List<PublicRelation> find(String key, String order, UUID id) {
        var sql = "SELECT id, place_id, k_content_id, relation_type, verified_at "
                + "FROM onmaru.k_content_public_relations WHERE " + key + " = ? ORDER BY " + order + ", id";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (var result = statement.executeQuery()) {
                var found = new ArrayList<PublicRelation>();
                while (result.next()) {
                    var verified = result.getTimestamp("verified_at");
                    found.add(new PublicRelation(result.getObject("id", UUID.class),
                            result.getObject("place_id", UUID.class),
                            result.getObject("k_content_id", UUID.class),
                            result.getString("relation_type"),
                            verified == null ? null : verified.toInstant()));
                }
                return found;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to read public K-Contents relations", exception);
        }
    }
}
