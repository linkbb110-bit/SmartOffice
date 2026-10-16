package com.geek.smartoffice.service;
import com.geek.smartoffice.model.vo.BookingVO;
import java.util.List;

public final class BookingCsvExporter {
    private BookingCsvExporter(){}
    public static String export(List<BookingVO> rows) {
        StringBuilder csv=new StringBuilder("\uFEFFid,user_id,room_id,topic,start_time,end_time,status\r\n");
        for(var row:rows) {
            csv.append(cell(row.getId())).append(',').append(cell(row.getUserId())).append(',')
                    .append(cell(row.getRoomId())).append(',').append(cell(row.getTopic())).append(',')
                    .append(cell(row.getStartTime())).append(',').append(cell(row.getEndTime())).append(',')
                    .append(cell(row.getStatus())).append("\r\n");
        }
        return csv.toString();
    }
    private static String cell(Object value) {
        String text=value==null?"":value.toString();
        String leading=text.stripLeading();
        if(!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0))>=0
                || text.startsWith("\t") || text.startsWith("\r") || text.startsWith("\n")) text="'"+text;
        return "\""+text.replace("\"","\"\"")+"\"";
    }
}
