package cn.travelbao.ai.tools;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** A per-probe tool instance records whether the model actually requested execution. */
public class BudgetProbeTool {
    private final AtomicInteger calls = new AtomicInteger();
    private volatile BudgetResult lastResult;

    @Tool(name = "calculate_budget", description = "计算住宿、餐饮、交通的总预算，输入单位为人民币元")
    public BudgetResult calculate(
            @ToolParam(description = "住宿费用，整数元") int accommodation,
            @ToolParam(description = "餐饮费用，整数元") int food,
            @ToolParam(description = "交通费用，整数元") int transport) {
        if (accommodation < 0 || food < 0 || transport < 0) throw new IllegalArgumentException("费用不能为负数");
        long total = (long) accommodation + food + transport;
        lastResult = new BudgetResult(accommodation, food, transport, total, "CNY");
        calls.incrementAndGet();
        return lastResult;
    }

    public int calls() { return calls.get(); }
    public BudgetResult lastResult() { return lastResult; }

    public record BudgetResult(int accommodation, int food, int transport, long total, String currency) { }
}
