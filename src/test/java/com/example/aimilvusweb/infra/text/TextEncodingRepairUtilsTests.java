package com.example.aimilvusweb.infra.text;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TextEncodingRepairUtilsTests {

    @Test
    void shouldRepairWindows1252MojibakeChinese() {
        String repaired = TextEncodingRepairUtils.repairMojibake("ä¸œæ–¹è´¢å¯Œ");

        Assertions.assertEquals("东方财富", repaired);
    }

    @Test
    void shouldRepairMixedReportTitle() {
        String repaired = TextEncodingRepairUtils.repairMojibake("26Q1æ”¶å…¥ç«¯å¢žé•¿é“ä¸½");

        Assertions.assertEquals("26Q1收入端增长靓丽", repaired);
    }

    @Test
    void shouldKeepNormalTextUnchanged() {
        Assertions.assertEquals("东方财富", TextEncodingRepairUtils.repairMojibake("东方财富"));
        Assertions.assertEquals("CICC Research", TextEncodingRepairUtils.repairMojibake("CICC Research"));
    }
}
