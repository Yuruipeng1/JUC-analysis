## 1.为什么ConditionObject 中的firstWaiter和lastWaiter不用volatile修饰？

firstWaiter和lastWaiter分别代表条件队列的队头和队尾。 这是因为源码作者是考虑，使用者肯定是以获得锁的前提下来调用await() / signal()这些方法的，既然有了这个前提，那么对firstWaiter的读写肯定是无竞争的，既然没有竞争也就不需要 CAS+volatile 来实现一个乐观锁了。



## 2.同步队列和条件队列的区别

1.同步队列中的节点创建时，初始状态为0，条件队列中的节点初始状态为CONDITION（-2）

2.同步队列中如果有队头，那么头部是一个dummy node，条件队列不会有dummy node

3.同步队列中维护的是一个双向链表，每个节点由prev和next维护关系，条件队列维护的是一个单向链表，节点间由nextWaiter维护



## 3.isOnSyncQueue方法逻辑

```java
final boolean isOnSyncQueue(Node node) {
    //如果当前状态为CONDITION，那么肯定不在同步队列中，直接返回false
    //如果当前状态不为CONDITION，但是prev节点为null，那么肯定不在同步队列
    if (node.waitStatus == Node.CONDITION || node.prev == null)
        return false;
    //1.不为CONDITION，2.prev节点不为null

    //如果next节点也不为null，那么肯定在同步队列中，返回true
    if (node.next != null) // If has successor, it must be on queue
        return true;
    /*
     * node.prev can be non-null, but not yet on queue because
     * the CAS to place it on queue can fail. So we have to
     * traverse from tail to make sure it actually made it.  It
     * will always be near the tail in calls to this method, and
     * unless the CAS failed (which is unlikely), it will be
     * there, so we hardly ever traverse much.
     */
    //走到这说明 1.不为CONDITION，2.prev节点不为null ，3.next节点为null

    //有两种情况可能会走到这里
    //如果当前节点是队尾节点，那么走到这里， 但是还有一种情况，在enq方法中入队时，可能会有尾分叉问题，
    // （节点的prev节点指向尾节点，但是cas设置tail失败）那么此时也会出现这中情况
    //所以这时候需要从队尾向前开始遍历，看是否在同步队列中
    return findNodeFromTail(node);
}
```



## 4.transferForSignal和transferAfterCancelledWait的竞争关系

```java
final boolean transferAfterCancelledWait(Node node) {
    if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
        enq(node);
        return true;
    }
    /*
     * If we lost out to a signal(), then we can't proceed
     * until it finishes its enq().  Cancelling during an
     * incomplete transfer is both rare and transient, so just
     * spin.
     */
    while (!isOnSyncQueue(node))
        Thread.yield();
    return false;
}
```

```java
final boolean transferForSignal(Node node) {
    /*
     * If cannot change waitStatus, the node has been cancelled.
     */
    if (!compareAndSetWaitStatus(node, Node.CONDITION, 0))
        return false;

    /*
     * Splice onto queue and try to set waitStatus of predecessor to
     * indicate that thread is (probably) waiting. If cancelled or
     * attempt to set waitStatus fails, wake up to resync (in which
     * case the waitStatus can be transiently and harmlessly wrong).
     */
    Node p = enq(node);
    int ws = p.waitStatus;
    if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL))
        LockSupport.unpark(node.thread);
    return true;
}
```

在这两个方法中都有compareAndSetWaitStatus(node, Node.CONDITION, 0)这句代码，在多线程环境下就可能出现竞争，transferForSignal方法对应的是正常唤醒线程，transferAfterCancelledWait方法对应的是中断唤醒线程，那么只有一个线程能cas设置状态成功，并且cas成功的线程才会把当前node入同步队列，失败的线程返回false。

正常唤醒线程竞争成功后调用enq方法将当前node入队，并且拿到node的prev节点，如果prev节点为SIGNAL或者成功cas设置为SIGNAL那么此时就直接返回true，因为prev节点为SIGNAL那么prev就有义务唤醒当前节点，当前节点直接返回就行. 如果以上不成立，那么就唤醒当前node线程，唤醒后，当前node就会在await方法中醒来，然后去执行acquireQueued方法，尝试去拿锁，如果成功就获取锁，如果失败，最终也会在shouldParkAfterFailedAcquire方法中将前驱节点设置为SIGNAL，然后去阻塞。



中断唤醒的线程竞争成功后enq方法入队列然后返回true。
