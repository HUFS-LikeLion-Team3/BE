package com.finsight.market;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:marketExpansion;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
    "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "auth.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    "market.data.twelve-data.api-key="
})
class MarketTargetExpansionMigrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test void addsFxGoldWtiOnceAndAlignsRateLabelsWithoutTouchingOtherTargets() {
        jdbc.update("delete from market_targets");
        // 운영 DB의 11/14 등록 상태(NASDAQ100·US10Y)를 재현한다.
        jdbc.update("""
            insert into market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
            values (X'2f1cc2d89505497789b56d88e75414df','NASDAQ100','NASDAQ 100','us_equity','index','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','point','XNAS','America/New_York','NDX',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """);
        jdbc.update("""
            insert into market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
            values (X'99999999999949998999999999999999','US10Y','미국 10년물 국채 수익률 (재무부 일일 기준)','rate','yield','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','%','USTY','America/New_York','BC_10YEAR',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """);
        jdbc.update("update market_targets set display_name='기존 이름' where code='NASDAQ100'");

        var migration = new ResourceDatabasePopulator(
                new FileSystemResource("docs/migrations/20261125_market_targets_fx_gold_wti.sql"));
        migration.execute(dataSource);
        migration.execute(dataSource);

        assertEquals(5, jdbc.queryForObject("select count(*) from market_targets", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from market_targets where code='USDKRW'", Integer.class));
        assertEquals("TDFX", jdbc.queryForObject("select calendar_code from market_targets where code='USDKRW'", String.class));
        assertEquals("TDGD", jdbc.queryForObject("select calendar_code from market_targets where code='GOLD'", String.class));
        assertEquals("TDWT", jdbc.queryForObject("select calendar_code from market_targets where code='WTI'", String.class));
        assertEquals("WTI/USD", jdbc.queryForObject("select data_symbol from market_targets where code='WTI'", String.class));
        assertTrue(labels("USDKRW").contains("환율 상승(원화 약세)"));
        assertTrue(labels("USDKRW").contains("환율 하락(원화 강세)"));
        assertTrue(labels("US10Y").contains("금리 상승"));
        assertTrue(labels("US10Y").contains("금리 하락"));
        assertEquals("기존 이름", jdbc.queryForObject("select display_name from market_targets where code='NASDAQ100'", String.class));
        assertEquals(3, jdbc.queryForObject(
                "select count(*) from market_targets where code in ('USDKRW','GOLD','WTI') and is_active=true", Integer.class));
    }

    private String labels(String code) {
        return jdbc.queryForObject("select cast(direction_labels as varchar) from market_targets where code=?", String.class, code);
    }
}
