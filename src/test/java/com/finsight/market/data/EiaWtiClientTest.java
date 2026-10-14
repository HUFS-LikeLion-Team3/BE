package com.finsight.market.data;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class EiaWtiClientTest {

    @Test
    void parsesDailyPricesWithoutInventingMissingDays() {
        String html = """
                <html><body>
                Cushing, OK WTI Spot Price FOB
                <table>
                <tr>
                    <td>2026 Oct- 5 to Oct- 9</td>
                    <td>96.13</td>
                    <td>96.24</td>
                    <td></td>
                    <td>97.12</td>
                    <td>95.80</td>
                </tr>
                </table>
                </body></html>
                """;

        var values = EiaWtiClient.parseHtml(html);

        assertEquals(4, values.size());

        assertEquals(
                LocalDate.parse("2026-10-05"),
                values.getFirst().sessionDate()
        );

        assertEquals(
                0,
                new BigDecimal("96.13").compareTo(
                        values.getFirst().value()
                )
        );

        assertEquals(
                LocalDate.parse("2026-10-09"),
                values.getLast().sessionDate()
        );

        assertTrue(values.stream().noneMatch(
                o -> o.sessionDate().equals(
                        LocalDate.parse("2026-10-07")
                )
        ));

        assertEquals(
                "2026-10-06",
                values.getFirst().asOfAt().toLocalDate().toString()
        );
    }
}