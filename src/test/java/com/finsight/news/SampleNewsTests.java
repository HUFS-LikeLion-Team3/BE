package com.finsight.news;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("sample")
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:sample;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "auth.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
class SampleNewsTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Test void sampleDataLoadsAndCanBeLoadedAgain() {
        new ResourceDatabasePopulator(new ClassPathResource("sample-news.sql")).execute(dataSource);
        assertEquals(4, jdbc.queryForObject("select count(*) from news", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from news where status='published' and content_type='live'", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from source_documents", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from news_sources", Integer.class));
        assertEquals(2, jdbc.queryForObject("select count(*) from news_facts", Integer.class));
    }
}
