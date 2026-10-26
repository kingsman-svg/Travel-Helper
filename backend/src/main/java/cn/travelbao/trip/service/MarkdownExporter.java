package cn.travelbao.trip.service;

import cn.travelbao.trip.dto.*;
import org.springframework.stereotype.Component;

@Component
public class MarkdownExporter {
    public String export(TripService.SavedTripView saved) {
        var draft = saved.draft(); var request = draft.request(); var plan = draft.itinerary();
        var out = new StringBuilder("# " + line(plan.title()) + "\n\n");
        out.append(line(plan.summary())).append("\n\n")
                .append("- 出发地：").append(line(request.origin())).append("\n- 目的地：").append(line(request.destination()))
                .append("\n- 日期：").append(request.startDate()).append(" 至 ").append(request.endDate())
                .append("\n- 人数：").append(request.people()).append("\n- 总预算：¥").append(request.totalBudget())
                .append("\n- 包含项：").append(request.budgetIncludes()).append("\n- 包含项估算合计：¥").append(draft.budget().total())
                .append("\n- 全部费用估算：¥").append(draft.budget().allEstimatedCosts())
                .append("\n- 预算余额：¥").append(draft.budget().remaining()).append("\n\n> 所有金额为全体旅客人民币估算；未经实时查询。\n");
        int day = 1;
        for (var daily : plan.days()) {
            out.append("\n## 第 ").append(day++).append(" 天 · ").append(daily.date()).append(" · ").append(line(daily.theme())).append("\n\n");
            for (var activity : daily.activities()) {
                out.append("- ").append(activity.startTime()).append("–").append(activity.endTime()).append(" ").append(line(activity.title()))
                        .append("（").append(line(activity.location())).append("）\n  - 交通：").append(line(activity.transport()))
                        .append("；估计步行 ").append(activity.walkingMinutes()).append(" 分钟\n");
                for (var cost : activity.costs()) out.append("  - ").append(cost.category()).append(" ¥").append(cost.amount()).append("：").append(line(cost.note())).append("\n");
            }
        }
        out.append("\n## 行程共同费用\n\n");
        for (var cost : plan.extraCosts()) out.append("- ").append(cost.category()).append(" ¥").append(cost.amount()).append("：").append(line(cost.note())).append("\n");
        out.append("\n## 待确认\n\n");
        for (var note : plan.pendingConfirmations()) out.append("- ").append(line(note)).append("\n");
        out.append("\n## 资料来源\n\n");
        if (plan.sources().isEmpty()) out.append("此行程暂无知识库引用；建议与估计需进一步核实。\n");
        for (var source : plan.sources()) {
            out.append("- ").append(line(source.title())).append(" ").append(line(source.url())).append("：").append(line(source.note())).append("\n");
            if (source.chunkId()!=null) out.append("  - 资料ID：").append(line(source.documentId())).append("；片段：").append(line(source.chunkId()))
                    .append("；核对日期：").append(line(source.verifiedAt())).append("；来源类型：").append(line(source.origin())).append("\n");
        }
        if(draft.agentExecutionId()!=null)out.append("\n智能体生成记录：").append(line(draft.agentExecutionId())).append("（后续编辑可能改变安排，工具记录保留生成时结果）。\n");
        return out.append("\n保存版本：").append(saved.version()).append(" · 保存时间：").append(saved.savedAt()).append("\n").toString();
    }
    private String line(String text) { return text.replace("\n", " ").replace("\r", " "); }
}
