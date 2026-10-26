package cn.travelbao;

import cn.travelbao.ai.tools.ItineraryTools;
import cn.travelbao.trip.dto.*;
import cn.travelbao.trip.service.ItineraryValidator;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class Day2BusinessTest {
    final ItineraryTools tools = new ItineraryTools();
    final ItineraryValidator validator = new ItineraryValidator(Validation.buildDefaultValidatorFactory().getValidator(), tools);
    static TravelRequest request() {
        return new TravelRequest("上海", "杭州", LocalDate.of(2026,11,1), LocalDate.of(2026,11,3), 2,
                new BigDecimal("3000.00"), List.of(ExpenseCategory.values()), "少走路，喜欢美食");
    }
    static Itinerary plan() {
        var days = new ArrayList<Itinerary.Day>();
        for (int i=0; i<3; i++) days.add(new Itinerary.Day(request().startDate().plusDays(i), "湖边慢游",
                List.of(activity("09:00", "10:00", new BigDecimal("100.10")), activity("11:00", "12:00", new BigDecimal("200.20")))));
        return new Itinerary("杭州三日", "轻松安排", days,
                List.of(new Itinerary.Cost(ExpenseCategory.ACCOMMODATION, new BigDecimal("800.00"), "全体两晚")), List.of(), List.of("费用为估计"));
    }
    static Itinerary.Activity activity(String start, String end, BigDecimal cost) {
        return new Itinerary.Activity(start,end,"游览","杭州","公交，预留30分钟",20,
                List.of(new Itinerary.Cost(ExpenseCategory.FOOD,cost,"全体费用")));
    }
    @Test void budgetUsesExactDecimalsAndOnlyCountsIncludedCategories() {
        var costs = List.of(new Itinerary.Cost(ExpenseCategory.FOOD,new BigDecimal("0.10"),"一"),
                new Itinerary.Cost(ExpenseCategory.FOOD,new BigDecimal("0.20"),"二"),
                new Itinerary.Cost(ExpenseCategory.TRANSPORT,new BigDecimal("500.00"),"预算外"));
        var result = tools.calculateBudget(costs,new BigDecimal("0.25"),List.of(ExpenseCategory.FOOD));
        assertThat(result.total()).isEqualByComparingTo("0.30");
        assertThat(result.allEstimatedCosts()).isEqualByComparingTo("500.30");
        assertThat(result.remaining()).isEqualByComparingTo("-0.05");
        assertThat(result.overBudget()).isTrue();
    }
    @Test void validPlanProducesRealChecksAndIndependentSum() {
        var result = validator.check(request(),plan(),"v2");
        assertThat(result.budget().total()).isEqualByComparingTo("1700.90");
        assertThat(result.schedule().walkingMinutesByDate()).containsEntry("2026-11-02",40);
        assertThat(result.toolExecutions()).extracting(TripDraft.ToolExecution::name).containsExactly("check_schedule","calculate_budget");
    }
    @Test void overlappingOrBackwardsActivitiesAreRejected() {
        assertThat(tools.checkSchedule(List.of(new Itinerary.Day(request().startDate(),"冲突",List.of(
                activity("09:00","10:00",BigDecimal.ZERO),activity("09:30","09:00",BigDecimal.ZERO))))).valid()).isFalse();
    }
    @Test void wholeDayWalkingLimitIsEnforcedAsADailyTotal() {
        var r=request();var limited=new TravelRequest(r.origin(),r.destination(),r.startDate(),r.endDate(),r.people(),r.totalBudget(),r.budgetIncludes(),"全天步行不超过30分钟");
        assertThatThrownBy(()->validator.check(limited,plan(),"v4")).hasMessageContaining("超过每天步行不超过30分钟");
    }
    @Test void transportInSharedCostsAndAccommodationInActivitiesAreRejected() {
        var good = plan();
        assertThatThrownBy(() -> validator.check(request(),new Itinerary("x","x",good.days(),
                List.of(new Itinerary.Cost(ExpenseCategory.TRANSPORT,new BigDecimal("100"),"往返票")),List.of(),List.of()),"v2"))
                .hasMessageContaining("共同费用");
        var invalid = new Itinerary.Activity("09:00","10:00","入住","杭州","公交",5,
                List.of(new Itinerary.Cost(ExpenseCategory.ACCOMMODATION,new BigDecimal("100"),"房费")));
        var days = new ArrayList<>(good.days());
        days.set(0,new Itinerary.Day(request().startDate(),"入住",List.of(invalid)));
        assertThatThrownBy(() -> validator.check(request(),new Itinerary("x","x",days,List.of(),List.of(),List.of()),"v2"))
                .hasMessageContaining("住宿费用");
    }
    @Test void missingFieldsNullItemsNegativeMoneyAndDateGapsAreRejected() {
        var good = plan();
        assertThatThrownBy(() -> validator.check(request(),new Itinerary("","x",good.days(),List.of(),List.of(),List.of()),"v2"))
                .hasMessageContaining("title");
        assertThatThrownBy(() -> validator.check(request(),new Itinerary("x","x",Arrays.asList((Itinerary.Day)null),List.of(),List.of(),List.of()),"v2"))
                .isInstanceOf(cn.travelbao.common.exception.ServiceProblem.class);
        assertThatThrownBy(() -> validator.check(request(),new Itinerary("x","x",good.days(),
                List.of(new Itinerary.Cost(ExpenseCategory.FOOD,new BigDecimal("-1.00"),"错误")),List.of(),List.of()),"v2"))
                .hasMessageContaining("extraCosts");
        assertThatThrownBy(() -> validator.check(request(),new Itinerary("x","x",List.of(good.days().get(1),good.days().get(0),good.days().get(2)),List.of(),List.of(),List.of()),"v2"))
                .hasMessageContaining("日期");
    }
}
