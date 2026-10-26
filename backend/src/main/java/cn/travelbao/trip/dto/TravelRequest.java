package cn.travelbao.trip.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TravelRequest(
        @NotBlank @Size(max=60) String origin,
        @NotBlank @Size(max=60) String destination,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @Min(1) @Max(20) int people,
        @NotNull @DecimalMin("0.01") @DecimalMax("10000000") @Digits(integer=8, fraction=2) BigDecimal totalBudget,
        @NotEmpty @Size(max=5) List<@NotNull ExpenseCategory> budgetIncludes,
        @NotNull @Size(max=1000) String preferences) {}
