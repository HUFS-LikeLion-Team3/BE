package com.finsight.market;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("market-data")
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:realMarketSeed;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "auth.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    "market.data.twelve-data.api-key="
})
class RealMarketTargetSeedTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test void actualAaplMetadataLoadsAndReloadDoesNotDuplicateOrOverwrite() {
        assertEquals("AAPL", jdbc.queryForObject("select data_symbol from market_targets where code='AAPL'", String.class));
        assertEquals("XNAS", jdbc.queryForObject("select calendar_code from market_targets where code='AAPL'", String.class));
        assertEquals("USD", jdbc.queryForObject("select unit from market_targets where code='AAPL'", String.class));
        assertEquals(6, jdbc.queryForObject("select count(*) from market_targets", Integer.class));
        assertEquals("point", jdbc.queryForObject("select unit from market_targets where code='KOSPI'", String.class));
        assertEquals("BC_10YEAR", jdbc.queryForObject("select data_symbol from market_targets where code='US10Y'", String.class));
        assertEquals("%", jdbc.queryForObject("select unit from market_targets where code='US10Y'", String.class));
        assertEquals("KRW", jdbc.queryForObject("select unit from market_targets where code='USDKRW'", String.class));
        assertEquals("USD/troy_oz", jdbc.queryForObject("select unit from market_targets where code='GOLD'", String.class));
        assertEquals("USD/barrel", jdbc.queryForObject("select unit from market_targets where code='WTI'", String.class));
        jdbc.update("update market_targets set display_name='기존 이름' where code='AAPL'");
        new ResourceDatabasePopulator(new ClassPathResource("market-targets-real.sql")).execute(dataSource);
        assertEquals(1, jdbc.queryForObject("select count(*) from market_targets where code='AAPL'", Integer.class));
        assertEquals("기존 이름", jdbc.queryForObject("select display_name from market_targets where code='AAPL'", String.class));
        assertEquals(6, jdbc.queryForObject("select count(*) from market_targets", Integer.class));
    }
}
