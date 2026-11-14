package com.hpg.payment.provider.simulated;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Where the simulated provider keeps its orders, payments and disputes. */
public interface SimulatorStore {

    void put(String kind, String id, String ref, Object value);

    <T> Optional<T> get(String kind, String id, Class<T> type);

    <T> List<T> byRef(String kind, String ref, Class<T> type);

    /** In memory: for unit tests. Lost on restart and not shared between instances. */
    static SimulatorStore inMemory() {
        Map<String, Object[]> data = new ConcurrentHashMap<>();
        return new SimulatorStore() {
            @Override
            public void put(String kind, String id, String ref, Object value) {
                data.put(kind + "/" + id, new Object[] {ref, value});
            }

            @Override
            public <T> Optional<T> get(String kind, String id, Class<T> type) {
                return Optional.ofNullable(data.get(kind + "/" + id)).map(v -> type.cast(v[1]));
            }

            @Override
            public <T> List<T> byRef(String kind, String ref, Class<T> type) {
                return data.entrySet().stream().filter(e -> e.getKey().startsWith(kind + "/") && ref.equals(e.getValue()[0]))
                        .map(e -> type.cast(e.getValue()[1])).toList();
            }
        };
    }

    /** In PostgreSQL: shared by all replicas and kept across restarts, like a real provider's state. */
    static SimulatorStore jdbc(JdbcTemplate jdbc, ObjectMapper json) {
        return new SimulatorStore() {
            @Override
            public void put(String kind, String id, String ref, Object value) {
                jdbc.update("""
                        INSERT INTO simulated_provider_objects (kind, id, ref, data, updated_at) VALUES (?, ?, ?, ?::jsonb, ?)
                        ON CONFLICT (kind, id) DO UPDATE SET data = EXCLUDED.data, ref = EXCLUDED.ref, updated_at = EXCLUDED.updated_at""",
                        kind, id, ref, write(value), Timestamp.from(Instant.now()));
            }

            @Override
            public <T> Optional<T> get(String kind, String id, Class<T> type) {
                return jdbc.query("SELECT data::text FROM simulated_provider_objects WHERE kind = ? AND id = ?",
                        (rs, i) -> read(rs.getString(1), type), kind, id).stream().findFirst();
            }

            @Override
            public <T> List<T> byRef(String kind, String ref, Class<T> type) {
                return jdbc.query("SELECT data::text FROM simulated_provider_objects WHERE kind = ? AND ref = ? ORDER BY updated_at",
                        (rs, i) -> read(rs.getString(1), type), kind, ref);
            }

            private String write(Object value) {
                try {
                    return json.writeValueAsString(value);
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            }

            private <T> T read(String text, Class<T> type) {
                try {
                    return json.readValue(text, type);
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }
}
