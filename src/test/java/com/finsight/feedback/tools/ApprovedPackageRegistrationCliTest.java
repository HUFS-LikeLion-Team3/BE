package com.finsight.feedback.tools;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovedPackageRegistrationCliTest {

    private String fixture() throws Exception {
        try (var stream = getClass().getResourceAsStream("/issue26/demo-package.json")) {
            if (stream == null) throw new IllegalStateException("Missing demo fixture");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void validatesExactUtf8PackageAndBuildsInventory() throws Exception {
        var pkg = ApprovedPackageRegistrationCli.validate(fixture());
        assertEquals("sha256:3f8f06b3d8c41b2b0035ecdcdb7c3a2de529431615984ac68d3091351f23226e", pkg.id());
        assertEquals(1, pkg.documents().size());
        assertEquals("original_article", pkg.documents().get(0).selectionTier());
        assertEquals(1, pkg.documents().get(0).chunks().size());
        assertEquals(UUID.fromString("c5d99dc5-0eff-5509-a1a9-1aa4be9750db"), pkg.marketIds().iterator().next());
        Map<String, Object> inv = ApprovedPackageRegistrationCli.inventory(pkg);
        assertEquals(pkg.id(), inv.get("packageId"));
        assertEquals(1, ((java.util.List<?>) inv.get("documents")).size());
    }

    @Test
    void alteredBodyOrChunkFailsBeforeDbOrAiRequest() throws Exception {
        String input = fixture();
        assertThrows(IllegalStateException.class,
                () -> ApprovedPackageRegistrationCli.validate(input.replace("FinSight local integration fixture.", "tampered body!")));
        assertThrows(IllegalStateException.class,
                () -> ApprovedPackageRegistrationCli.validate(input.replace("승인·인용 저장 연결 검증용 대역 본문입니다.", "변조된 텍스트")));
    }

    @Test
    void noSilentRebindingOrDataTypeGuess() throws Exception {
        String input = fixture();
        assertThrows(IllegalStateException.class,
                () -> ApprovedPackageRegistrationCli.validate(input.replace("\"selection_tier\": 1", "\"selection_tier\": 99")));
        assertThrows(IllegalStateException.class,
                () -> ApprovedPackageRegistrationCli.validate(input.replace("\"linked_news_ids\": []", "\"linked_news_ids\": [\"11111111-1111-4111-8111-111111111111\"]")));
        assertTrue(ApprovedPackageRegistrationCli.validate(input).documents().get(0).chunks().get(0).text().contains("승인·인용"));
    }
}
