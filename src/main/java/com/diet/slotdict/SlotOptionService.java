package com.diet.slotdict;

import com.diet.exception.DietException;
import com.diet.mapper.SlotOptionMapper;
import com.diet.model.domain.SlotBundle;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 槽位字典服务：读取 diet_slot_option 表的合法标签候选值。
 * <p>
 * 字典是低频变化的只读参考数据，但调用频率极高——IntentAgentService 每轮对话都会
 * findAllOptions()（7 次 DB 查询），餐食录入校验也会再查 7 次。这里用 Caffeine
 * LoadingCache 按 slotName 缓存查询结果（TTL 默认 10 分钟，可配 diet.slot-cache.ttl-minutes），
 * 将每轮对话的 14 次 DB 查询降为 0。diet_slot_option 在代码库中为纯只读表（无任何写语句），
 * 因此 TTL 过期即可保证最终一致，无需主动失效钩子；管理员改库后最多延迟一个 TTL 生效。
 */
@Service
public class SlotOptionService {
    public static final List<String> SLOT_NAMES = List.of(
            "mealTime", "mood", "scene", "healthGoal", "cuisine", "taste", "convenience"
    );

    /** 缓存最大容量，7 个槽位维度远小于上限，仅作防御。 */
    private static final long MAX_CACHE_ENTRIES = 64;

    private final SlotOptionMapper slotOptionMapper;

    /** slotName → 启用标签列表的加载缓存，loader 直连 Mapper，TTL 由配置注入。 */
    private final LoadingCache<String, List<String>> optionCache;

    public SlotOptionService(
            SlotOptionMapper slotOptionMapper,
            @Value("${diet.slot-cache.ttl-minutes:10}") long ttlMinutes
    ) {
        this.slotOptionMapper = slotOptionMapper;
        this.optionCache = Caffeine.newBuilder()
                .maximumSize(MAX_CACHE_ENTRIES)
                .expireAfterWrite(Duration.ofMinutes(ttlMinutes))
                .build(slotOptionMapper::findEnabledValues);
    }

    public Map<String, List<String>> findAllOptions() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String slotName : SLOT_NAMES) {
            // 命中缓存直接返回，未命中时由 LoadingCache 调 findEnabledValues 加载
            result.put(slotName, optionCache.get(slotName));
        }
        return result;
    }

    public void validate(SlotBundle slots) {
        Map<String, List<String>> options = findAllOptions();
        validateSlot("mealTime", slots.mealTime(), options);
        validateSlot("mood", slots.mood(), options);
        validateSlot("scene", slots.scene(), options);
        validateSlot("healthGoal", slots.healthGoal(), options);
        validateSlot("cuisine", slots.cuisine(), options);
        validateSlot("taste", slots.taste(), options);
        validateSlot("convenience", slots.convenience(), options);
    }

    private void validateSlot(String slotName, List<String> values, Map<String, List<String>> options) {
        if (values == null || values.isEmpty()) {
            return;
        }
        Set<String> allowed = Set.copyOf(options.getOrDefault(slotName, List.of()));
        for (String value : values) {
            if (!allowed.contains(value)) {
                throw new DietException("非法槽位标签: " + slotName + "=" + value);
            }
        }
    }
}