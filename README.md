# Multithreaded executor

The primary objective is to manage the processing of numerous tasks concurrently through multithreading. This involves controlling the submission of tasks to prevent potential OutOfMemoryExceptions and other errors. The TaskManager serves as a logging mechanism to monitor the current state of queues and other relevant information.

There are two queues:

1) Queue to which we submit `WorkUnit`
2) Queue which is used by ThreadPoolExecutor to proceed submitted `WorkUnits`.

Both queues are blocking queues with configurable size. Queue for WorkUnit is limited by `Config.workUnitsDequeSize`
Queue for tasks is limited by `Config.tasksDequeSize`

Usual use ('all code' example can be found `AbstractTaskManagerTest`):

```java
        var taskManager = new AbstractTaskManager<...,...>{...};  // create a taskManager
        
        var workUnit = new WorkUnit(){...};  // create WorkUnit
                
         while(stopSubmit){
             taskManager.submit(workUnit)  // submit WorkUnit to taskManager to execute it
         }
        
        // Notify taskManager that we'll not have more tasks and wait for having all submitted tasks to be proceeded
        taskManager.wait(CustomWorkOfUnit.LAST_VALUE);

        // Log final statistics
        taskManager.logStatistics();
```

We can submit `workUnits` until size of its dequeue is less or equal to`Config.workUnitsDequeSize`. The submit process will be
blocked until at least one `workOfUnit` will go to `tasksDeque` (so a place for one more `workUnit` is freed).

TaskManager will take `workUnit` from `workUnit's` queue and will submit the task to execute this `workUnit` using his
own thread pool executor with `LimitedQueue` as reservoir.
