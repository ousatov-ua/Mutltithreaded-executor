package com.alus.tools.multithreaded.manager;

import com.alus.tools.multithreaded.vo.StatUnit;
import com.alus.tools.multithreaded.vo.WorkUnit;
import com.alus.tools.multithreaded.vo.config.Config;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * TaskManager
 *
 * <p>
 * Manages processing of all units of work. Built on base of limited queue.
 *
 * @author Oleksii Usatov
 */
@Slf4j
public class AbstractTaskManager<T extends WorkUnit, R> implements Runnable, Closeable {
    private final Config config;
    private final LinkedBlockingDeque<T> workUnitsDeque;
    private final LimitedQueue<Runnable> tasksDeque;
    private final Function<T, R> function;
    private final ExecutorService processorsPool;
    private final ScheduledExecutorService statusExecutor;
    private final ExecutorService taskManagerExecutor;
    private final AtomicLong totalUnitsOfWorkSubmitted = new AtomicLong();
    private final Map<String, StatUnit> recordsSubmitted = new LinkedHashMap<>();
    private volatile boolean finished;

    public AbstractTaskManager(Config config, Function<T, R> function) {
        this.config = config;
        this.function = function;
        final int unitsOfWorkDequeSize = config.getValuesDequeSize();
        final int tasksDequeSize = config.getTasksDequeSize();
        final var nThreads = config.getEventProcessingParallelism();
        this.workUnitsDeque = new LinkedBlockingDeque<>(unitsOfWorkDequeSize);
        this.tasksDeque = new LimitedQueue<>(tasksDequeSize);
        this.processorsPool = new ThreadPoolExecutor(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, tasksDeque);
        this.statusExecutor = Executors.newScheduledThreadPool(1);
        statusExecutor.scheduleAtFixedRate(() ->
                log.info("Total values submitted={}, workUnitsDeque size={}, tasksDeque size={}", totalUnitsOfWorkSubmitted.get(),
                        workUnitsDeque.size(), tasksDeque.size()), 1, 1, TimeUnit.MINUTES);
        this.taskManagerExecutor = Executors.newFixedThreadPool(1);
        this.taskManagerExecutor.execute(this);
    }

    @Override
    public void run() {
        while (true) {
            T workUnit = null;
            try {
                workUnit = workUnitsDeque.take();
                if (workUnit == workUnit.getLastUnit()) {
                    log.info("Last task is reached, workUnitsDeque is empty={}", workUnitsDeque.isEmpty());
                    finished = true;
                    break;
                }
                final var finalWorkUnit = workUnit;
                processorsPool.submit(() -> logStatistics(finalWorkUnit, function.apply(finalWorkUnit)));
            } catch (Exception ine) {
                log.error("Could not process workUnit={}", workUnit, ine);
            }
        }
    }

    public boolean isFinished() {
        return finished && workUnitsDeque.isEmpty() && tasksDeque.isEmpty();
    }

    /**
     * Log statistic for taken workUnit
     *
     * @param workUnit {@link WorkUnit}
     */
    private void logStatistics(WorkUnit workUnit, R result) {
        totalUnitsOfWorkSubmitted.incrementAndGet();

        synchronized (this) {
            var statValue = recordsSubmitted.getOrDefault(workUnit.getType(), StatUnit.builder().build());
            long currentCount = statValue.getCurrentCount() + 1;
            long totalCount = statValue.getTotalCount() + 1;
            long totalErrorCount = statValue.getTotalErrorCount();
            if (isInError(result)) {
                totalErrorCount = totalErrorCount + 1;
            }
            if (currentCount >= config.getLogForRecordCount()) {
                log.info("Processed total={}, in error={} records for type={}", totalCount, totalErrorCount,
                        workUnit.getType());
                currentCount = 0;
            }
            recordsSubmitted.put(workUnit.getType(), StatUnit.builder()
                    .currentCount(currentCount)
                    .totalCount(totalCount)
                    .totalErrorCount(totalErrorCount)
                    .build()
            );
        }
    }

    /**
     * Check if processing was marked as failed
     *
     * @param result R
     * @return true if is in error state
     */
    protected boolean isInError(R result) {
        return false;
    }

    /**
     * Submit task
     *
     * @param workUnit T
     * @throws InterruptedException exception
     */
    public void submit(T workUnit) throws InterruptedException {
        workUnitsDeque.put(workUnit);
    }

    /**
     * Put the last value to the queue and wait all submitted tasks finished
     *
     * @param lastWorkUnit last unit of work
     */
    public void wait(T lastWorkUnit) {
        try {
            workUnitsDeque.put(lastWorkUnit);
            while (!isFinished()) {
                log.info("Waiting for all tasks left the queue");
                TimeUnit.SECONDS.sleep(10);
            }
            log.info("All tasks are executed");
        } catch (Exception ine) {
            log.error("Exception during finishing", ine);
        }
    }

    /**
     * Log statistics
     */
    public void logStatistics() {
        recordsSubmitted.forEach((key, value) -> log.info("Statistics: total={}, in error={} records for type={}",
                value.getTotalCount(), value.getTotalErrorCount(), key));
        log.info("Statistics: Total values submitted={}", totalUnitsOfWorkSubmitted.get());
    }

    @Override
    public void close() throws IOException {
        try {
            log.info("Waiting for all submitted tasks finished");
            processorsPool.shutdown();
            var terminated = processorsPool.awaitTermination(
                    config.getWaitTimeForAllTasksFinishedMinute(), TimeUnit.MINUTES);
            log.info("ProcessorsPool is terminated={}", terminated);
            log.info("All submitted tasks finished");
            statusExecutor.shutdownNow();
            terminated = statusExecutor.awaitTermination(1, TimeUnit.MINUTES);
            log.info("StatusExecutor is finished={}", terminated);
            log.info("All tasks left the queue");
            log.info("Shutdown taskManager...");
            taskManagerExecutor.shutdownNow();
            terminated = taskManagerExecutor.awaitTermination(1, TimeUnit.MINUTES);
            log.info("Task manager is terminated={}", terminated);
        } catch (InterruptedException e) {
            throw new IOException("Cannot close resources", e);
        }
    }

    /**
     * Needed for ThreadPoolExecutor
     * </p>
     * Limited queue: executor will wait to submit runnable if queue is full
     *
     * @author Oleksii Usatov
     */
    private static class LimitedQueue<E> extends LinkedBlockingQueue<E> {
        public LimitedQueue(int maxSize) {
            super(maxSize);
        }

        @Override
        public boolean offer(E e) {

            // Turn offer() and add() into a blocking calls
            try {
                put(e);
                return true;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
