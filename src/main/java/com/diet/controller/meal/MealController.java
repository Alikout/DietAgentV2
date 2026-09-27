package com.diet.controller.meal;

import com.diet.model.web.MealRequest;
import com.diet.model.web.MealResponse;
import com.diet.security.CurrentUser;
import com.diet.service.meal.MealService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/diet/meals")
public class MealController {
    private final MealService mealService;

    public MealController(MealService mealService) {
        this.mealService = mealService;
    }

    /** 身份取自 JWT subject（第四周鉴权），个人餐食的属主校验由 SQL 条件完成。 */
    @GetMapping("/personal")
    public List<MealResponse> findPersonal() {
        return mealService.findPersonalMeals(CurrentUser.id()).stream().map(MealResponse::from).toList();
    }

    @PostMapping("/personal")
    public MealResponse createPersonal(@RequestBody MealRequest request) {
        return MealResponse.from(mealService.createPersonalMeal(CurrentUser.id(), request));
    }

    @PutMapping("/personal/{mealId}")
    public MealResponse updatePersonal(@PathVariable Long mealId, @RequestBody MealRequest request) {
        return MealResponse.from(mealService.updatePersonalMeal(CurrentUser.id(), mealId, request));
    }

    @DeleteMapping("/personal/{mealId}")
    public void deletePersonal(@PathVariable Long mealId) {
        mealService.deletePersonalMeal(CurrentUser.id(), mealId);
    }

    @GetMapping("/public")
    public List<MealResponse> findPublic() {
        return mealService.findPublicMeals().stream().map(MealResponse::from).toList();
    }

    // ================= 管理员公共餐食库维护（ADMIN，SecurityConfig 路径级限定） =================

    /** POST /meals/public — 新增公共餐食。 */
    @PostMapping("/public")
    public MealResponse createPublic(@RequestBody MealRequest request) {
        return MealResponse.from(mealService.createPublicMeal(request));
    }

    /** PUT /meals/public/{mealId} — 修改公共餐食。 */
    @PutMapping("/public/{mealId}")
    public MealResponse updatePublic(@PathVariable Long mealId, @RequestBody MealRequest request) {
        return MealResponse.from(mealService.updatePublicMeal(mealId, request));
    }

    /** DELETE /meals/public/{mealId} — 删除公共餐食。 */
    @DeleteMapping("/public/{mealId}")
    public void deletePublic(@PathVariable Long mealId) {
        mealService.deletePublicMeal(mealId);
    }
}
