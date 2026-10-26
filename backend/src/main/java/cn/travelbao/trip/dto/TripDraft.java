package cn.travelbao.trip.dto;

import cn.travelbao.ai.tools.ItineraryTools;
import java.util.List;

public record TripDraft(TravelRequest request, Itinerary itinerary,
                        ItineraryTools.BudgetResult budget, ItineraryTools.ScheduleResult schedule,
                        List<ToolExecution> toolExecutions, String promptVersion,String agentExecutionId) {
    public TripDraft(TravelRequest request,Itinerary itinerary,ItineraryTools.BudgetResult budget,ItineraryTools.ScheduleResult schedule,List<ToolExecution> tools,String version){this(request,itinerary,budget,schedule,tools,version,null);}
    public record Content(TravelRequest request, Itinerary itinerary,String promptVersion,String agentExecutionId) {
        public Content(TravelRequest request,Itinerary itinerary,String version){this(request,itinerary,version,null);}
        public Content(TravelRequest request,Itinerary itinerary) { this(request,itinerary,"v2",null); }
    }
    public record ToolExecution(String name, String trigger, String summary, long durationMs) {}
}
