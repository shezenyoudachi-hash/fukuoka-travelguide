package com.fukuokatabi.plan;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanResponse(
        TripType type,
        List<String> chips,
        List<Day> days,
        List<String> tips,
        InterpretationSummary interpretation,
        Providers providers) {

    public record TripType(String mark, String title, String subtitle) {}

    public record Day(int dayNumber, String date, String headline, List<Item> items) {}

    /**
     * kind: spot / lunch / dinner / night / move
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String kind, String time, String name, String area, String note, String why,
                       String warning, Double lat, Double lng, String spotId, Integer minutes) {

        static Item move(String text, int minutes) {
            return new Item("move", null, text, null, null, null, null, null, null, null, minutes);
        }
    }

    public record InterpretationSummary(String source, List<String> notes) {}

    /** どの実装で作ったか（rule/claude, estimate/google）。開発時の確認用 */
    public record Providers(String interpreter, String travel) {}
}
