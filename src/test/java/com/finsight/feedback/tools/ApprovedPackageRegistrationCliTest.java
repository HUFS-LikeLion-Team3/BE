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

    private String issue36Fixture(String filename) throws Exception {
        try (var stream = getClass().getResourceAsStream("/issue36/" + filename)) {
            if (stream == null) throw new IllegalStateException("Missing issue36 fixture: " + filename);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void keepsPrimaryOfficialTierZeroMapping() throws Exception {
        var pkg = ApprovedPackageRegistrationCli.validate(issue36Fixture("tier0-official-package.json"));
        assertEquals("primary_official", pkg.documents().get(0).selectionTier());
        assertEquals("government_release", pkg.documents().get(0).sourceType());
    }

    @Test
    void mapsTierTwoResearchReportToTrustedResearchWithoutRewritingPackage() throws Exception {
        var pkg = ApprovedPackageRegistrationCli.validate(issue36Fixture("tier2-research-package.json"));
        var doc = pkg.documents().get(0);
        assertEquals("trusted_research", doc.selectionTier());
        assertEquals("research_report", doc.sourceType());
        assertEquals("dd78bd77074d70fe364f9c337b3dcf9b0c3012d0bef9523a57b16db0b58d1f04", doc.contentHash());
        assertEquals("sha256:d24597edd825006029aec9d1684cba7c3bb1d09588663b15146771a8f0de2b75", pkg.id());
        assertEquals(pkg.id(), ApprovedPackageRegistrationCli.inventory(pkg).get("packageId"));
        assertEquals(1, doc.chunks().size());
    }

    @Test
    void rejectsUnsupportedOrMismatchedTiersEvenWithValidCanonicalHash() throws Exception {
        var mismatchedResearch = assertThrows(IllegalStateException.class,
                () -> ApprovedPackageRegistrationCli.validate(issue36Fixture("tier2-invalid-source-package.json")));
        assertEquals("trusted_research tier mismatch", mismatchedResearch.getMessage());
        var mismatchedArticle = assertThrows(IllegalStateException.class,
                () -> ApprovedPackageRegistrationCli.validate(issue36Fixture("tier1-invalid-source-package.json")));
        assertEquals("original_article tier mismatch", mismatchedArticle.getMessage());
        var unknownTier = assertThrows(IllegalStateException.class,
                () -> ApprovedPackageRegistrationCli.validate(issue36Fixture("tier3-unsupported-package.json")));
        assertEquals("unconfirmed selection_tier mapping: 3", unknownTier.getMessage());
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
