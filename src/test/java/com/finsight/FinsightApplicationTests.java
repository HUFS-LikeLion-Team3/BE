package com.finsight;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        // Keep this context smoke test isolated from a developer's real MySQL DB.
        "spring.datasource.url=jdbc:h2:mem:finsight_context;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        // Test-only 32-byte Base64 key; never used for application tokens.
        "auth.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
class FinsightApplicationTests {

	@Test
	void contextLoads() {
	}

}
