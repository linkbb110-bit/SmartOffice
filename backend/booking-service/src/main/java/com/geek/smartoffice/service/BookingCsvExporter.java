package com.geek.smartoffice.service;
import com.geek.smartoffice.model.vo.BookingVO;
import java.util.List;

public final class BookingCsvExporter {
    private BookingCsvExporter(){}
    // 按传入列表顺序生成完整 CSV，开头的 UTF-8 BOM 便于常见表格软件识别中文，行尾使用 CRLF。
    // 每个字段统一交给 cell 处理，主题中的逗号、双引号和换行不能直接用字符串拼接作为裸字段。
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
        // stripLeading 只用于识别前导空白后的公式标记，输出仍保留原文本；控制前缀则单独判断。
        // 对这些内容加单引号，减少表格软件把主题当公式执行的风险；这一步与 CSV 格式转义是两回事。
        String leading=text.stripLeading();
        if(!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0))>=0
                || text.startsWith("\t") || text.startsWith("\r") || text.startsWith("\n")) text="'"+text;
        // 字段内的双引号写成两个双引号，再用一对双引号包围整个字段，兼容逗号和换行。
        return "\""+text.replace("\"","\"\"")+"\"";
    }
}
