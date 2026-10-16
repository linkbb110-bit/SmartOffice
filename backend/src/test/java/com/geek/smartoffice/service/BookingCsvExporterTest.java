package com.geek.smartoffice.service;
import com.geek.smartoffice.model.vo.BookingVO;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class BookingCsvExporterTest {
    @Test void exportEscapesQuotesAndNeutralizesSpreadsheetFormulas() {
        var row=BookingVO.builder().id(1L).userId(2L).roomId(3L).status(1)
                .topic("=SUM(1,2)\"会议\"").startTime(LocalDateTime.of(2099,1,1,10,0))
                .endTime(LocalDateTime.of(2099,1,1,11,0)).build();
        String csv=BookingCsvExporter.export(List.of(row));
        assertTrue(csv.startsWith("\uFEFF"));
        assertTrue(csv.contains("\"'=SUM(1,2)\"\"会议\"\"\""));
        assertTrue(csv.contains("\"2099-01-01T10:00\""));
        assertEquals(3,csv.split("\r\n",-1).length);
    }
}
