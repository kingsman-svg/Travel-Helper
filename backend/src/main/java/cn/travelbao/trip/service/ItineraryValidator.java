package cn.travelbao.trip.service;

import cn.travelbao.ai.tools.ItineraryTools;
import cn.travelbao.common.exception.ServiceProblem;
import cn.travelbao.trip.dto.*;
import jakarta.validation.Validator;
import java.net.URI;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ItineraryValidator {
    private final Validator validator;
    private final ItineraryTools tools;
    public ItineraryValidator(Validator validator, ItineraryTools tools) { this.validator = validator; this.tools = tools; }

    public void request(TravelRequest request) {
        validate(request);
        long days = ChronoUnit.DAYS.between(request.startDate(), request.endDate()) + 1;
        require(days >= 1 && days <= 14, "旅行日期需为连续 1–14 天");
        require(new HashSet<>(request.budgetIncludes()).size() == request.budgetIncludes().size(), "预算包含项不能重复");
    }

    public TripDraft check(TravelRequest request, Itinerary itinerary, String promptVersion) {
        request(request);
        validate(itinerary);
        for (var cost : itinerary.extraCosts()) require(cost.category() == ExpenseCategory.ACCOMMODATION || cost.category() == ExpenseCategory.OTHER,
                "共同费用只允许住宿与备用金；交通、餐饮和门票请分配到当天活动，避免重复计费");
        for (var day : itinerary.days()) for (var activity : day.activities()) for (var cost : activity.costs())
            require(cost.category() != ExpenseCategory.ACCOMMODATION, "住宿费用请统一列在共同费用中，避免重复计费");
        int count = (int) ChronoUnit.DAYS.between(request.startDate(), request.endDate()) + 1;
        require(itinerary.days().size() == count, "行程天数与旅行日期不一致");
        for (int i = 0; i < count; i++)
            require(itinerary.days().get(i).date().equals(request.startDate().plusDays(i)), "行程日期必须完整、连续且与需求一致");
        for (var source : itinerary.sources()) {
            if (source.url().isBlank()) continue;
            try {
                var uri = URI.create(source.url());
                require(Set.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null, "来源链接只支持 HTTP/HTTPS");
            } catch (IllegalArgumentException e) { throw problem("来源链接格式不正确"); }
        }
        long started = System.nanoTime();
        var schedule = tools.checkSchedule(itinerary.days());
        require(schedule.valid(), String.join("；", schedule.conflicts()));
        var walkingLimit = java.util.regex.Pattern.compile("(?:每天|每日|全天)\\s*步行(?:不超过|最多|控制在)?\\s*(\\d{1,4})\\s*分钟").matcher(request.preferences());
        if (walkingLimit.find()) {
            int max = Integer.parseInt(walkingLimit.group(1));
            schedule.walkingMinutesByDate().forEach((date,minutes) -> require(minutes <= max,
                    date + "估计步行" + minutes + "分钟，超过每天步行不超过" + max + "分钟的要求"));
        }
        var traces = new ArrayList<TripDraft.ToolExecution>();
        traces.add(new TripDraft.ToolExecution("check_schedule", "后端校验", "时间无重叠；步行分钟为估计值", elapsed(started)));
        var costs = new ArrayList<>(itinerary.extraCosts());
        itinerary.days().forEach(day -> day.activities().forEach(a -> costs.addAll(a.costs())));
        started = System.nanoTime();
        var budget = tools.calculateBudget(costs, request.totalBudget(), request.budgetIncludes());
        traces.add(new TripDraft.ToolExecution("calculate_budget", "后端校验", "包含项合计 ¥" + budget.total() + "；全部估算 ¥" + budget.allEstimatedCosts(), elapsed(started)));
        return new TripDraft(request, itinerary, budget, schedule, traces, promptVersion);
    }
    private void validate(Object value) {
        require(value != null, "行程内容不能为空");
        var errors = validator.validate(value);
        require(errors.isEmpty(), errors.stream().map(e -> e.getPropertyPath() + " " + e.getMessage()).sorted().findFirst().orElse("行程字段无效"));
    }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private static void require(boolean ok, String message) { if (!ok) throw problem(message); }
    private static ServiceProblem problem(String message) { return new ServiceProblem("INVALID_ITINERARY", message); }
}
