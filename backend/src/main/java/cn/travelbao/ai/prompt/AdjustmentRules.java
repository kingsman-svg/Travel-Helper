package cn.travelbao.ai.prompt;

import cn.travelbao.common.exception.ServiceProblem;
import cn.travelbao.trip.dto.Itinerary;
import java.util.ArrayList;
import java.util.regex.Pattern;

/** Protects unchanged days for the supported “第 N 天少走路” adjustment. */
public final class AdjustmentRules {
    private static final Pattern DAY = Pattern.compile("第([一二三四五六七八九十\\d]{1,3})天");
    private static final Pattern LIMIT = Pattern.compile("(?:不超过|降低到|降到|最多|控制在)\\s*(\\d{1,4})\\s*分钟");
    private AdjustmentRules() {}
    public static Itinerary apply(Itinerary previous, Itinerary proposed, String message) {
        if (previous == null || !(message.contains("少走路") || message.contains("减少步行"))) return proposed;
        var matcher = DAY.matcher(message);
        var targets = new java.util.HashSet<Integer>();
        while (matcher.find()) targets.add(dayIndex(matcher.group(1)));
        // Repeating the same day is still a single-day request.
        if (targets.size() != 1) return proposed;
        int index = targets.iterator().next();
        if (index < 0 || index >= previous.days().size() || proposed.days().size() != previous.days().size()) return proposed;
        var replacement = proposed.days().get(index);
        int before = walking(previous.days().get(index)), after = walking(replacement);
        if (before > 0 && after >= before) throw invalid("第" + (index+1) + "天需减少步行，原估计" + before + "分钟，本次" + after + "分钟");
        var limit = LIMIT.matcher(message);
        if (limit.find() && after > Integer.parseInt(limit.group(1))) throw invalid("第" + (index+1) + "天步行总量" + after + "分钟，超过用户指定的" + limit.group(1) + "分钟");
        var days = new ArrayList<>(previous.days()); days.set(index,replacement);
        return new Itinerary(previous.title(),proposed.summary(),days,previous.extraCosts(),proposed.sources(),proposed.pendingConfirmations());
    }
    private static int dayIndex(String value) {
        if (value.matches("\\d+")) return Integer.parseInt(value)-1;
        String numbers = "一二三四五六七八九";
        if (value.length() == 1) return value.equals("十") ? 9 : numbers.indexOf(value);
        if (value.startsWith("十") && value.length() == 2) return 10 + numbers.indexOf(value.charAt(1));
        return -1;
    }
    private static int walking(Itinerary.Day day) { return day.activities().stream().mapToInt(Itinerary.Activity::walkingMinutes).sum(); }
    private static ServiceProblem invalid(String message) { return new ServiceProblem("INVALID_ITINERARY",message); }
}
