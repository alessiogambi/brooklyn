package brooklyn.management;

import brooklyn.management.Task;

/**
 * Interface for something which is not a task, but which is closely linked to one, ie. it has a task.
 * 
 * @since 0.6.0
 */
public interface TaskWrapper<T> extends TaskAdaptable<T> {
    Task<T> getTask();
}
