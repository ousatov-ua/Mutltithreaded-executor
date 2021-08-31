package com.alus.tools.multithreaded;

import com.alus.tools.multithreaded.manager.AbstractTaskManager;
import com.alus.tools.multithreaded.vo.WorkUnit;
import com.alus.tools.multithreaded.vo.config.Config;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit test for {@link AbstractTaskManager}
 *
 * @author Oleksii Usatov
 */
@Slf4j
public class AbstractTaskManagerTest {

    @Value
    @Builder
    static class CustomWorkOfUnit implements WorkUnit {
        int data;

        public static final CustomWorkOfUnit LAST_VALUE = new CustomWorkOfUnit(-1);

        @Override
        public WorkUnit getLastUnit() {
            return LAST_VALUE;
        }

        @Override
        public String getType() {
            return "SomeType";
        }
    }

    @Value
    @Builder
    static class Result {
        int sourceData;
        boolean ok;
    }

    @Test
    public void testManaging() throws InterruptedException {

        // Executor for taskManager
        final var taskManagerExecutor = Executors.newFixedThreadPool(1);

        // Contain results
        final var proceededUnits = new ConcurrentHashMap<Result, Integer>();
        final var config = Config.builder()
                .eventProcessingParallelism(2)
                .tasksDequeSize(10)
                .valuesDequeSize(10)
                .waitTimeForAllTasksFinishedMinute(1)
                .build();

        // Implementation of our TaskManager
        final var taskManager = new AbstractTaskManager<CustomWorkOfUnit, Result>(config, (t) -> {
            log.info("Proceeded {}", t);
            try {
                if (t.getData() == 3) {
                    TimeUnit.SECONDS.sleep(2);
                } else {
                    TimeUnit.SECONDS.sleep(1);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException("Exeception during sleep", e);
            }
            var result = Result.builder()
                    .ok(t.getData() != 3)
                    .sourceData(t.getData())
                    .build();
            proceededUnits.put(result, t.getData());
            return result;
        }) {

            // Define if it is in error
            @Override
            protected boolean isInError(Result result) {
                return result.ok != Boolean.TRUE;
            }
        };

        // Run task manager
        taskManagerExecutor.execute(taskManager);

        // Total number of tasks
        final int tasks = 20;
        for (int i = 0; i < tasks; i++) {
            var unit = CustomWorkOfUnit.builder()
                    .data(i)
                    .build();
            try {
                log.info("Put next task = {}", unit);
                taskManager.put(unit);
            } catch (InterruptedException ine) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Could not put value to queue, unit=" + unit, ine);
            }
        }

        // Notify taskManager that we'll not have more tasks and wait for having all sbmitted proceeded
        taskManager.waitForFinish(CustomWorkOfUnit.LAST_VALUE);

        // Shutdown task manager
        taskManagerExecutor.shutdown();
        var terminated = taskManagerExecutor.awaitTermination(10, TimeUnit.SECONDS);
        log.info("TaskManagerExecutor is terminated={}", terminated);

        // Log final statistics
        taskManager.logStatistics();

        // Assertions
        assertEquals(tasks, proceededUnits.size());
        var okResults = proceededUnits.keySet()
                .stream()
                .filter(Result::isOk)
                .collect(Collectors.toList());
        assertEquals(tasks - 1, okResults.size());
        var failedResult = proceededUnits.keySet()
                .stream()
                .filter(r -> !r.isOk())
                .collect(Collectors.toList());
        assertEquals(1, failedResult.size());
        assertEquals(3, failedResult.get(0).getSourceData());
    }
}
