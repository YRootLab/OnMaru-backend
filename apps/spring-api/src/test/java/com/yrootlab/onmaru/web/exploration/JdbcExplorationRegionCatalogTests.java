package com.yrootlab.onmaru.web.exploration;

import com.yrootlab.onmaru.persistence.journey.exploration.JdbcExplorationRegionCatalog;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcExplorationRegionCatalogTests {

    @Test
    void loadsActiveRegionsWithParentQualificationAndCachesForTtl() {
        var queryCount = new AtomicInteger();
        var now = new AtomicReference<>(Instant.parse("2026-11-19T00:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };

        List<Object[]> rows = List.of(
                new Object[]{"kr-datalab-51", "강원특별자치도", "SIDO", null},
                new Object[]{"kr-datalab-51820", "고성군", "SIGUNGU", "강원특별자치도"},
                new Object[]{"kr-datalab-48820", "고성군", "SIGUNGU", "경상남도"},
                new Object[]{"kr-datalab-51150", "강릉시", "SIGUNGU", "강원특별자치도"}
        );

        var catalog = new JdbcExplorationRegionCatalog(stubDataSource(rows, queryCount), clock, Duration.ofMinutes(5));

        assertEquals("kr-datalab-51150", catalog.resolveRegionCode("강릉 한옥 추천해줘").orElseThrow());
        assertEquals("kr-datalab-51820", catalog.resolveRegionCode("강원 고성 여행").orElseThrow());
        assertEquals("kr-datalab-48820", catalog.resolveRegionCode("경상남도 고성군 여행").orElseThrow());
        assertTrue(catalog.resolveRegionCode("고성 여행").isEmpty());
        assertEquals(1, queryCount.get());

        // Advance within TTL -> uses cache
        now.set(now.get().plus(Duration.ofMinutes(4)));
        assertEquals("kr-datalab-51150", catalog.resolveRegionCode("강릉시 산책").orElseThrow());
        assertEquals(1, queryCount.get());

        // Advance past TTL -> reloads from DB
        now.set(now.get().plus(Duration.ofMinutes(2)));
        assertEquals("kr-datalab-51150", catalog.resolveRegionCode("강릉시 산책").orElseThrow());
        assertEquals(2, queryCount.get());

        // Manual invalidation forces reload
        catalog.invalidate();
        assertEquals("kr-datalab-51150", catalog.resolveRegionCode("강릉시 산책").orElseThrow());
        assertEquals(3, queryCount.get());
    }

    private static DataSource stubDataSource(List<Object[]> rows, AtomicInteger queryCount) {
        return new DataSource() {
            @Override
            public Connection getConnection() {
                return proxyConnection(rows, queryCount);
            }

            @Override
            public Connection getConnection(String username, String password) {
                return getConnection();
            }

            @Override public PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(PrintWriter out) {}
            @Override public void setLoginTimeout(int seconds) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public Logger getParentLogger() { return Logger.getAnonymousLogger(); }
            @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }

    private static Connection proxyConnection(List<Object[]> rows, AtomicInteger queryCount) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> proxyStatement(rows, queryCount);
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static PreparedStatement proxyStatement(List<Object[]> rows, AtomicInteger queryCount) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "executeQuery" -> {
                        queryCount.incrementAndGet();
                        yield proxyResultSet(rows);
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ResultSet proxyResultSet(List<Object[]> rows) {
        var index = new AtomicInteger(-1);
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> index.incrementAndGet() < rows.size();
                    case "getString" -> {
                        Object[] row = rows.get(index.get());
                        yield switch ((String) args[0]) {
                            case "code" -> (String) row[0];
                            case "name" -> (String) row[1];
                            case "level" -> (String) row[2];
                            case "parent_name" -> (String) row[3];
                            default -> throw new IllegalArgumentException((String) args[0]);
                        };
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
