package cn.travelbao.ai.tools;

import cn.travelbao.trip.dto.*;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class ItineraryTools {
    public record BudgetResult(BigDecimal total, BigDecimal allEstimatedCosts, BigDecimal remaining,
                               boolean overBudget, String currency, Map<ExpenseCategory, BigDecimal> byCategory) {}
    public record ScheduleResult(boolean valid, List<String> conflicts, Map<String, Integer> walkingMinutesByDate) {}

    @Tool(name="calculate_budget", description="按全体旅客的人民币费用估算求和；只将预算包含项计入预算，使用精确小数。")
    public BudgetResult calculateBudget(List<Itinerary.Cost> costs, BigDecimal totalBudget, List<ExpenseCategory> budgetIncludes) {
        if (costs == null || totalBudget == null || totalBudget.signum() < 0 || budgetIncludes == null)
            throw new IllegalArgumentException("预算参数不正确");
        var categories = new EnumMap<ExpenseCategory, BigDecimal>(ExpenseCategory.class);
        for (var category : ExpenseCategory.values()) categories.put(category, new BigDecimal("0.00"));
        for (var cost : costs) {
            if (cost == null || cost.category() == null || cost.amount() == null || cost.amount().signum() < 0
                    || cost.amount().scale() > 2) throw new IllegalArgumentException("费用必须是非负金额，最多两位小数");
            categories.merge(cost.category(), cost.amount(), BigDecimal::add);
        }
        BigDecimal all = categories.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = categories.entrySet().stream().filter(e -> budgetIncludes.contains(e.getKey()))
                .map(Map.Entry::getValue).reduce(new BigDecimal("0.00"), BigDecimal::add);
        return new BudgetResult(total, all, totalBudget.subtract(total), total.compareTo(totalBudget) > 0, "CNY", categories);
    }

    @Tool(name="check_schedule", description="检查每日活动开始结束时间和重叠；汇总估计步行分钟。无法验证真实路程或开放时间。")
    public ScheduleResult checkSchedule(List<Itinerary.Day> days) {
        var conflicts = new ArrayList<String>();
        var walking = new LinkedHashMap<String, Integer>();
        for (var day : days) {
            LocalTime previousEnd = null;
            int minutes = 0;
            for (var activity : day.activities()) {
                LocalTime start = LocalTime.parse(activity.startTime()), end = LocalTime.parse(activity.endTime());
                if (!end.isAfter(start)) conflicts.add(day.date() + "：" + activity.title() + "结束时间必须晚于开始时间");
                if (previousEnd != null && start.isBefore(previousEnd)) conflicts.add(day.date() + "：" + activity.title() + "与上一活动重叠或未按时间排序");
                previousEnd = end;
                minutes += activity.walkingMinutes();
            }
            walking.put(day.date().toString(), minutes);
        }
        return new ScheduleResult(conflicts.isEmpty(), List.copyOf(conflicts), walking);
    }
}
