package com.alus.tools.multithreaded.vo;

/**
 * Unit of work
 *
 * @author Oleksii Usatov
 */
public interface WorkUnit {
    WorkUnit getLastUnit();

    String getType();
}
