package cn.travelbao;

import cn.travelbao.ai.prompt.*;
import cn.travelbao.trip.dto.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StructuredOutputTest {
    final ItineraryOutputParser parser = new ItineraryOutputParser(JsonMapper.builder().findAndAddModules().build());
    @Test void identicalRepeatedFieldsAreNormalizedButConflictingFieldsAreRejected() throws Exception {
        var json = JsonMapper.builder().findAndAddModules().build().writeValueAsString(Day2BusinessTest.plan());
        var duplicate = "{\"title\":\"杭州三日\"," + json.substring(1);
        assertThat(parser.parse(duplicate).title()).isEqualTo("杭州三日");
        assertThatThrownBy(() -> parser.parse("{\"title\":\"冲突标题\"," + json.substring(1))).hasMessageContaining("重复且值冲突");
        assertThatThrownBy(() -> parser.parse(json + "{}" )).hasMessageContaining("附加其他内容");
    }
    @Test void targetedWalkingAdjustmentProtectsOtherDaysAndChecksExplicitLimit() {
        var previous = Day2BusinessTest.plan();
        var days = new ArrayList<>(previous.days());
        days.set(0,new Itinerary.Day(previous.days().get(0).date(),"模型擅自改了第一天",previous.days().get(0).activities()));
        days.set(1,new Itinerary.Day(previous.days().get(1).date(),"第二天休息",List.of(Day2BusinessTest.activity("10:00","11:00",BigDecimal.ZERO))));
        var proposed = new Itinerary("新标题","减少步行",days,previous.extraCosts(),List.of(),List.of());
        var result = AdjustmentRules.apply(previous,proposed,"第二天少走路，降低到30分钟以内");
        assertThat(result.days().get(0)).isEqualTo(previous.days().get(0));
        assertThat(result.days().get(2)).isEqualTo(previous.days().get(2));
        assertThat(result.days().get(1).theme()).isEqualTo("第二天休息");
        assertThatThrownBy(() -> AdjustmentRules.apply(previous,proposed,"第二天少走路，降到10分钟以内")).hasMessageContaining("超过");
    }
    @Test void repeatedMentionOfTheSameDayStillEnforcesWalkingLimit() {
        var previous=Day2BusinessTest.plan();var days=new ArrayList<>(previous.days());
        var activity=new Itinerary.Activity("10:00","11:00","休息","杭州","公交",35,List.of());
        days.set(1,new Itinerary.Day(previous.days().get(1).date(),"休息",List.of(activity)));
        var proposed=new Itinerary("标题","简介",days,List.of(),List.of(),List.of());
        assertThatThrownBy(()->AdjustmentRules.apply(previous,proposed,"第二天少走路，请把第二天估计步行总量降低到30分钟以内，多休息；第一、三天不变。"))
                .hasMessageContaining("超过用户指定的30分钟");
    }
}
