package com.fukuokatabi.web;

import com.fukuokatabi.plan.PlanRequest;
import com.fukuokatabi.plan.PlanResponse;
import com.fukuokatabi.plan.PlanService;
import com.fukuokatabi.question.QuestionCatalog;
import com.fukuokatabi.spot.Spot;
import com.fukuokatabi.spot.SpotRepository;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;

/**
 * Web と iOS が共通で使う API。
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final QuestionCatalog questions;
    private final PlanService planService;
    private final SpotRepository spots;

    public ApiController(QuestionCatalog questions, PlanService planService, SpotRepository spots) {
        this.questions = questions;
        this.planService = planService;
        this.spots = spots;
    }

    @GetMapping("/questions")
    public List<QuestionCatalog.Question> questions() {
        return questions.all();
    }

    @PostMapping("/plans")
    public PlanResponse plan(@Valid @RequestBody PlanRequest request) {
        return planService.plan(request);
    }

    /** スポット一覧（「絶対に行きたい場所」の選択肢にも使う） */
    @GetMapping("/spots")
    public List<SpotView> spots() {
        return spots.findAll().stream()
                .sorted(Comparator.comparing(Spot::getCategory).thenComparing(Spot::getId))
                .map(SpotView::of)
                .toList();
    }

    public record SpotView(String id, String category, String name, String area, int farLevel, String note,
                           double lat, double lng,
                           String openTime, String closeTime, List<String> closedDays, boolean hoursVerified) {
        static SpotView of(Spot s) {
            return new SpotView(s.getId(), s.getCategory().name(), s.getName(), s.getArea(),
                    s.getFarLevel(), s.getNote(), s.getLatitude(), s.getLongitude(),
                    s.getOpenTime() == null ? null : s.getOpenTime().toString(),
                    s.getCloseTime() == null ? null : s.getCloseTime().toString(),
                    s.closedDaysOfWeek().stream().map(Enum::name).toList(),
                    s.isHoursVerified());
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalid(MethodArgumentNotValidException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("回答の内容を確認してください");
        pd.setDetail(e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((x, y) -> x + " / " + y).orElse(""));
        return pd;
    }
}
