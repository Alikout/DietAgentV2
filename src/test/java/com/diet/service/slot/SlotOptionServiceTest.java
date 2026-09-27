package com.diet.service.slot;

import com.diet.mapper.SlotOptionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SlotOptionService 单元测试：验证槽位字典的 Caffeine 缓存行为。
 * 同一 slotName 的重复查询必须命中缓存，Mapper 只被调用一次。
 */
class SlotOptionServiceTest {

    /** 被 mock 的槽位字典 Mapper，记录实际 DB 查询次数。 */
    private SlotOptionMapper slotOptionMapper;

    /** 被测服务（TTL 10 分钟，测试生命周期内不会过期）。 */
    private SlotOptionService slotOptionService;

    /** 每个用例前重置 mock 与被测服务。 */
    @BeforeEach
    void setUp() {
        slotOptionMapper = mock(SlotOptionMapper.class);
        slotOptionService = new SlotOptionService(slotOptionMapper, 10L);
    }

    /** 连续两次 findAllOptions 只触发一轮 DB 查询（7 个槽位各 1 次），第二次全部命中缓存。 */
    @Test
    void findAllOptionsCachesPerSlotName() {
        when(slotOptionMapper.findEnabledValues("taste")).thenReturn(List.of("清淡", "重口"));

        Map<String, List<String>> first = slotOptionService.findAllOptions();
        Map<String, List<String>> second = slotOptionService.findAllOptions();

        // 7 个槽位维度，每个只查一次 DB：两次调用共 7 次 Mapper 调用
        verify(slotOptionMapper, times(SlotOptionService.SLOT_NAMES.size())).findEnabledValues(anyString());
        // 返回结构保持 7 维不变，且值来自 Mapper
        assertEquals(SlotOptionService.SLOT_NAMES.size(), first.size());
        assertTrue(second.containsKey("taste"));
        assertEquals(List.of("清淡", "重口"), second.get("taste"));
    }

    /** 不同 slotName 各自独立加载，字典内容按维度正确分组。 */
    @Test
    void findAllOptionsLoadsEachDimensionIndependently() {
        when(slotOptionMapper.findEnabledValues("mealTime")).thenReturn(List.of("早餐"));
        when(slotOptionMapper.findEnabledValues("mood")).thenReturn(List.of("开心"));

        Map<String, List<String>> options = slotOptionService.findAllOptions();

        assertEquals(List.of("早餐"), options.get("mealTime"));
        assertEquals(List.of("开心"), options.get("mood"));
        verify(slotOptionMapper, times(1)).findEnabledValues("mealTime");
        verify(slotOptionMapper, times(1)).findEnabledValues("mood");
    }
}
