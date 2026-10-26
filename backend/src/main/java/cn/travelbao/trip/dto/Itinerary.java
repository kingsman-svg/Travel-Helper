package cn.travelbao.trip.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Costs are CNY estimates for the entire group, never per-person amounts. */
public record Itinerary(
        @NotBlank @Size(max=100) String title,
        @NotBlank @Size(max=1500) String summary,
        @NotEmpty @Size(max=14) List<@NotNull @Valid Day> days,
        @NotNull @Size(max=30) List<@NotNull @Valid Cost> extraCosts,
        @NotNull @Size(max=10) List<@NotNull @Valid Source> sources,
        @NotNull @Size(max=20) List<@NotBlank @Size(max=300) String> pendingConfirmations) {
    public record Day(@NotNull LocalDate date, @NotBlank @Size(max=100) String theme,
                      @NotEmpty @Size(max=12) List<@NotNull @Valid Activity> activities) {}
    public record Activity(
            @NotBlank @Pattern(regexp="(?:[01]\\d|2[0-3]):[0-5]\\d") String startTime,
            @NotBlank @Pattern(regexp="(?:[01]\\d|2[0-3]):[0-5]\\d") String endTime,
            @NotBlank @Size(max=100) String title,
            @NotBlank @Size(max=150) String location,
            @NotBlank @Size(max=300) String transport,
            @NotNull @Min(0) @Max(720) Integer walkingMinutes,
            @NotNull @Size(max=10) List<@NotNull @Valid Cost> costs) {}
    public record Cost(@NotNull ExpenseCategory category,
                       @NotNull @DecimalMin("0") @DecimalMax("10000000") @Digits(integer=8, fraction=2) BigDecimal amount,
                       @NotBlank @Size(max=200) String note) {}
    public record Source(@NotBlank @Size(max=150) String title,
                         @NotNull @Size(max=500) String url,
                         @NotBlank @Size(max=300) String note,
                         @Size(max=100) String documentId,
                         @Size(max=140) String chunkId,
                         @Size(max=10) String verifiedAt,
                         @Size(max=30) String origin) {
        public Source(String title,String url,String note) { this(title,url,note,null,null,null,null); }
    }
}
