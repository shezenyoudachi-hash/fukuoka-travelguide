package com.fukuokatabi.plan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;

public record PlanRequest(
        @NotNull @Valid Answers answers,
        @Size(max = 500) String freeText,
        LocalDate startDate) {

    public record Answers(
            @Min(1) @Max(3) int days,
            @NotBlank @Pattern(regexp = "solo|friends|couple|family") String who,
            @NotEmpty @Size(max = 2) List<@Pattern(regexp = "food|history|nature|shop|art") String> main,
            List<@Pattern(regexp = "ramen|motsu|mizutaki|yatai|seafood") String> eat,
            @NotBlank @Pattern(regexp = "slow|mid|fast") String pace,
            @NotBlank @Pattern(regexp = "train|car") String move,
            @NotBlank @Pattern(regexp = "yatai|bar|view|rest") String night,
            @NotBlank @Pattern(regexp = "city|near|far") String range) {

        public List<String> eatOrEmpty() {
            return eat == null ? List.of() : eat;
        }
    }
}
