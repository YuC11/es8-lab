package com.eslab.levels.support;

import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 关卡与「复位」之间的互斥闸门。
 *
 * 关卡运行持读锁：多个关卡可以并行（它们靠 LabData 的每索引锁保护自己），
 * 复位/重建持写锁：执行期间不允许任何关卡在跑 —— 否则会出现「刚删到一半，某一关正在读它」。
 *
 * 进程内的锁管不到 sh/seed.sh（另一个 JVM），那条路径由 var/seed.lock + 8787 端口探测处理。
 */
@Component
public class LabGate {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public <T> T read(Callable<T> action) throws Exception {
        lock.readLock().lock();
        try {
            return action.call();
        } finally {
            lock.readLock().unlock();
        }
    }

    public <T> T write(Callable<T> action) throws Exception {
        lock.writeLock().lock();
        try {
            return action.call();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 当前是否有复位在跑（用于前端提示） */
    public boolean writing() {
        return lock.isWriteLocked();
    }

    public int readers() {
        return lock.getReadLockCount();
    }
}
