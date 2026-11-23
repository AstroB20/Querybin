package com.example.apibuilder.builder;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.example.apibuilder.shared.QueryDefinition;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.json.JsonMapper;

/**
 * Stores published APIs. Every publish adds an immutable row to API_VERSION; nothing is ever updated in
 * place, which is what makes the time machine possible.
 */
@Repository
class ApiRepository {

    record Endpoint(String slug, int currentVersion, Instant createdAt, String description, Instant updatedAt) {
    }

    record Version(String slug, int version, String description, QueryDefinition definition, String note,
                   Instant createdAt) {
    }

    private static final String VERSION_COLUMNS = "SLUG, VERSION, DESCRIPTION, DEFINITION, NOTE, CREATED_AT";

    private static final String ENDPOINTS = """
            SELECT e.SLUG, e.CURRENT_VERSION, e.CREATED_AT, v.DESCRIPTION, v.CREATED_AT AS UPDATED_AT
            FROM BUILDER.API_ENDPOINT e
            JOIN BUILDER.API_VERSION v ON v.SLUG = e.SLUG AND v.VERSION = e.CURRENT_VERSION
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    ApiRepository(@Qualifier(MetadataConfig.METADATA) JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<Endpoint> findEndpoints() {
        return jdbc.sql(ENDPOINTS + "ORDER BY e.SLUG").query(ApiRepository::endpoint).list();
    }

    Optional<Endpoint> findEndpoint(String slug) {
        return jdbc.sql(ENDPOINTS + "WHERE e.SLUG = ?").param(slug).query(ApiRepository::endpoint).optional();
    }

    void insertEndpoint(String slug) {
        jdbc.sql("INSERT INTO BUILDER.API_ENDPOINT (SLUG, CURRENT_VERSION) VALUES (?, 1)").param(slug).update();
    }

    /** Claims the next version number, locking the endpoint row so concurrent publishes cannot collide. */
    Optional<Integer> nextVersion(String slug) {
        int updated = jdbc.sql("UPDATE BUILDER.API_ENDPOINT SET CURRENT_VERSION = CURRENT_VERSION + 1 WHERE SLUG = ?")
                .param(slug).update();
        if (updated == 0) {
            return Optional.empty();
        }
        return Optional.of(jdbc.sql("SELECT CURRENT_VERSION FROM BUILDER.API_ENDPOINT WHERE SLUG = ?")
                .param(slug).query(Integer.class).single());
    }

    void insertVersion(String slug, int version, String description, QueryDefinition definition, String note) {
        jdbc.sql("INSERT INTO BUILDER.API_VERSION (SLUG, VERSION, DESCRIPTION, DEFINITION, NOTE) VALUES (?, ?, ?, ?, ?)")
                .params(slug, version, description, json.writeValueAsString(definition), note)
                .update();
    }

    Optional<Version> findVersion(String slug, int version) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM BUILDER.API_VERSION WHERE SLUG = ? AND VERSION = ?")
                .params(slug, version).query(this::version).optional();
    }

    List<Version> findVersions(String slug) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM BUILDER.API_VERSION WHERE SLUG = ? ORDER BY VERSION DESC")
                .param(slug).query(this::version).list();
    }

    boolean delete(String slug) {
        return jdbc.sql("DELETE FROM BUILDER.API_ENDPOINT WHERE SLUG = ?").param(slug).update() > 0;
    }

    private static Endpoint endpoint(ResultSet rs, int rowNum) throws SQLException {
        return new Endpoint(rs.getString("SLUG"), rs.getInt("CURRENT_VERSION"), instant(rs, "CREATED_AT"),
                rs.getString("DESCRIPTION"), instant(rs, "UPDATED_AT"));
    }

    private Version version(ResultSet rs, int rowNum) throws SQLException {
        return new Version(rs.getString("SLUG"), rs.getInt("VERSION"), rs.getString("DESCRIPTION"),
                json.readValue(rs.getString("DEFINITION"), QueryDefinition.class), rs.getString("NOTE"),
                instant(rs, "CREATED_AT"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
