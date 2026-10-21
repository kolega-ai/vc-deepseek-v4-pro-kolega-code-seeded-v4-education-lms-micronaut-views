package com.brightpath.channel;

import com.brightpath.channel.service.CsvUtil;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsvUtilTest {

    @Test
    void renderQuotesFieldsThatNeedIt() {
        String csv = CsvUtil.render(List.<String[]>of(
                new String[]{"plain", "with,comma", "with\"quote", "multi\nline", ""}));
        String[] lines = csv.split("\r\n");
        assertEquals(1, lines.length);
        assertEquals("plain,\"with,comma\",\"with\"\"quote\",\"multi\nline\",", lines[0]);
    }

    @Test
    void parseRoundTripsComplexCells() {
        List<String[]> rows = CsvUtil.parse("a,\"b,c\",\"d\"\"e\",\"line1\nline2\"\n1,2,3,4\n");
        assertEquals(2, rows.size());
        assertArrayEquals(new String[]{"a", "b,c", "d\"e", "line1\nline2"}, rows.get(0));
        assertArrayEquals(new String[]{"1", "2", "3", "4"}, rows.get(1));
    }

    @Test
    void rejectsUnterminatedQuotes() {
        assertThrows(RuntimeException.class, () -> CsvUtil.parse("\"unterminated, cell"));
    }
}
