package com.alus.tools.multithreaded.vo.config;

import lombok.Builder;
import lombok.Value;

/**
 * Config
 *
 * @author Oleksii Usatov
 */
@Value
@Builder
public class Config {

    @Builder.Default
    int valuesDequeSize = 200;

    @Builder.Default
    int tasksDequeSize = 200;

    @Builder.Default
    int eventProcessingParallelism = 20;

    @Builder.Default
    int logForRecordCount = 100;

    @Builder.Default
    int waitTimeForAllTasksFinishedMinute = 30;
}
