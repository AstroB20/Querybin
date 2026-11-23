package com.example.apibuilder.builder;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The builder's own storage (published APIs and their history) lives in a separate embedded database,
 * so nothing is ever written to the target database and the metadata cannot be reached through a
 * published API. The main {@code spring.datasource} is the target database.
 */
@Configuration
class MetadataConfig {

    static final String METADATA = "metadata";

    /** Not a default candidate, so Spring Boot still auto-configures the target datasource. */
    @Bean(defaultCandidate = false)
    @Qualifier(METADATA)
    DataSource metadataDataSource(@Value("${app.metadata.url}") String url,
                                  @Value("${app.metadata.username:sa}") String username,
                                  @Value("${app.metadata.password:}") String password) {
        DataSource dataSource = DataSourceBuilder.create().url(url).username(username).password(password).build();
        DatabasePopulatorUtils.execute(new ResourceDatabasePopulator(new ClassPathResource("builder-schema.sql")),
                dataSource);
        return dataSource;
    }

    @Bean(defaultCandidate = false)
    @Qualifier(METADATA)
    JdbcClient metadataJdbcClient(@Qualifier(METADATA) DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    /** Publishing is the only transactional work, and it only touches the metadata database. */
    @Bean
    PlatformTransactionManager transactionManager(@Qualifier(METADATA) DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
