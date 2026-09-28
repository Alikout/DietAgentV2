package com.diet.common.model.web;
import com.diet.common.enums.ClarifyAction;
import com.diet.common.enums.Intent;
import com.diet.common.model.domain.SlotBundle;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
@Data
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@Accessors(fluent = true)
@AllArgsConstructor
@NoArgsConstructor
public class TraceLabelRequest {
    private Intent expectedIntent;
    private SlotBundle expectedSlots;
    private ClarifyAction expectedClarifyAction;
    private String labelNote;
}
