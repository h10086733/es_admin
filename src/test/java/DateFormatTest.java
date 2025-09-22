import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DateFormatTest {
    
    public static void main(String[] args) {
        System.out.println("=== 测试日期格式转换 ===");
        
        // 测试常见的日期格式
        String[] testDates = {
            "2025-09-20 16:33:47.0",
            "2025-09-20 15:58:00.0", 
            "2025-09-20 16:01:41.0",
            "2025-09-20 16:23:43.0"
        };
        
        for (String dateStr : testDates) {
            String result = formatDateValue("start_date", dateStr);
            System.out.println("原值: " + dateStr + " -> 转换后: " + result);
        }
    }
    
    public static String formatDateValue(String fieldName, String value) {
        if (value == null) return null;
        
        String valueStr = value.trim();
        
        // 检查是否是日期格式的字符串
        if (valueStr.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+") ||
            valueStr.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}") ||
            (isSystemField(fieldName) && valueStr.matches("\\d{4}-\\d{2}-\\d{2}.*"))) {
            
            try {
                // 处理形如 "2025-09-20 16:33:47.0" 的格式
                if (valueStr.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+")) {
                    // 移除最后的毫秒部分，转换为LocalDateTime再转为ISO格式
                    String cleanDateStr = valueStr.replaceAll("\\.\\d+$", "");
                    LocalDateTime dateTime = LocalDateTime.parse(cleanDateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    return dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } else if (valueStr.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) {
                    // 处理形如 "2025-09-20 16:33:47" 的格式
                    LocalDateTime dateTime = LocalDateTime.parse(valueStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    return dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                } else if (valueStr.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    // 处理形如 "2025-09-20" 的格式
                    java.time.LocalDate date = java.time.LocalDate.parse(valueStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                    return date.atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                }
            } catch (Exception e) {
                System.out.println("日期字符串转换失败: " + valueStr + " (字段: " + fieldName + ") - " + e.getMessage());
                return valueStr;
            }
        }
        
        return valueStr;
    }
    
    public static boolean isSystemField(String fieldName) {
        return fieldName != null && (fieldName.equals("start_date") || fieldName.equals("modify_date") || 
               fieldName.equals("start_member_id") || fieldName.equals("modify_member_id") ||
               fieldName.equals("approve_member_id") || fieldName.equals("ratify_member_id"));
    }
}