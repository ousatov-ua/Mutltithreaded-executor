package com.alus.tools.multithreaded.vo;

import lombok.Builder;
import lombok.Value;

/**
 * Contains statistics information
 *
 * @author Oleksii Usatov
 */
@Value
@Builder
public class StatUnit {
    long currentCount;
    long totalCount;
    long totalErrorCount;
}