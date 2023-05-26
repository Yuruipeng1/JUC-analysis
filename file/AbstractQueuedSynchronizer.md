## 1.非公平独占锁加锁流程和公平独占锁的区别

首先在调用lock方法时，非公平锁在调用acquire方法前会先尝试cas把当前状态从0-->1,如果成功就把当前owner线程设为自己，表示加锁成功。而在公平锁的方法实现中是直接调用acquire方法。



其次，在acquire方法的tryAcquire方法逻辑中，公平锁在判断当前state为0后，它不会立马cas将state设为1。它会先调用hasQueuedPredecessors方法判断当前阻塞队列中还有没有等待的线程，如果有，就不会去cas加锁，如果没有，才会去加锁。而非公平锁的逻辑中不会调用这个方法，只要当前state为0那么就会去尝试加锁。



不管是公平锁还是非公平锁，只要进入了阻塞队列，那么只能等待别的线程唤醒自己了。（一朝入队，永远排队）



## 2.shouldParkAfterFailedAcquire方法逻辑

shouldParkAfterFailedAcquire的作用就是把当前节点的前一个节点的状态值设置为SIGNAL，当一个节点的状态值为SIGNAL，那么它释放锁时就会唤醒后继节点。

```java
private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
    int ws = pred.waitStatus;
    if (ws == Node.SIGNAL)
        /*
         * This node has already set status asking a release
         * to signal it, so it can safely park.
         */
    /*
     * 前驱节点已经设置了SIGNAL，闹钟已经设好，现在我可以安心睡觉（阻塞）了。
     * 如果前驱变成了head，并且head的代表线程exclusiveOwnerThread释放了锁，
     * 就会来根据这个SIGNAL来唤醒自己
     */

        return true;
    if (ws > 0) {
        /*
         * Predecessor was cancelled. Skip over predecessors and
         * indicate retry.
         */
        /*
         * 发现传入的前驱的状态大于0，即CANCELLED。说明前驱节点已经因为超时或响应了中断，
         * 而取消了自己。所以需要跨越掉这些CANCELLED节点，直到找到一个<=0的节点
         */
        do {
            node.prev = pred = pred.prev;
        } while (pred.waitStatus > 0);
        pred.next = node;
    } else {
        /*
         * waitStatus must be 0 or PROPAGATE.  Indicate that we
         * need a signal, but don't park yet.  Caller will need to
         * retry to make sure it cannot acquire before parking.
         */

        /*
         * 进入这个分支，ws只能是0或PROPAGATE。
         * CAS设置ws为SIGNAL
         */

        //为什么这里不直接返回true呢？
        //要确保当前线程不能获取锁再去park，可能在这段时间内，有人释放了锁，当前线程去获取锁是可以成功的

        compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
    }
    return false;
}
```



## 3.enq方法的尾分叉和prev的前有效性

```java
private Node enq(final Node node) {
    for (;;) {
        Node t = tail;
        if (t == null) { // Must initialize
            if (compareAndSetHead(new Node()))
                tail = head;
        } else {
            node.prev = t;
            if (compareAndSetTail(t, node)) {
                t.nacquireQueuedext = node;
                return t;
            }
        }
    }
}
```

尾分叉： 在调用enq方法时，当当前节点要插入到队列尾部，它会先执行node.prev=t，再cas设置tail为当前节点，所以某一时刻可能有多个线程同时执行node.prev=t，那么就会有多个节点同时指向tail节点的情况，但是设置tail是用cas保证安全的，所以最终只有一个节点能成功设置为tail。



前有效性：在上面刚刚设置新tail节点的那个状态，此时原来的tail的next节点还没有指向新的tail节点，此时如果另一个线程从头节点开始遍历队列，那么此时会把这个新tail给漏掉，所以，在AQS中遍历节点一般都是从tail节点开始，通过prev指针进行遍历，这就是prev的前有效性。



## 4.cancelAcquire方法在什么情况下执行？

首先在不响应中断的lock方法中调用的是acquireQueued方法，在它的逻辑中函数的唯一出口是return interrupted;，而它的前一句代码，就是failed = false; 在finally块中，failed要为true才会执行cancelAcquire方法，所以不响应中断的lock方法永远不会执行。



在响应中断的lockInterruptibly方法和超时的tryLock方法中，他们的出口还能通过被中断从而抛出异常，这样就可以不用走return的逻辑，也不会执行failed=false这段代码，从而可以执行cancelAcquire方法。



## 5.cancelAcquire方法逻辑

```java
private void cancelAcquire(Node node) {
    // Ignore if node doesn't exist
    // 避免空指针异常
    if (node == null)
        return;

    node.thread = null;

    // Skip cancelled predecessors
    Node pred = node.prev;
    while (pred.waitStatus > 0)// 循环用来跳过无效前驱
        node.prev = pred = pred.prev;
    // 执行完循环，pred会指向node的有效前驱。
    // 当然，如果node的前驱就是有效的。那么就不需要跳过了。

    // predNext is the apparent node to unsplice. CASes below will
    // fail if not, in which case, we lost race vs another cancel
    // or signal, so no further action is necessary.

    // pred的后继无论如何都需要取消，因为即使前面循环没有执行，
    // 现在pred的后继（肯定是参数node）也是一个马上取消掉的node。
    // 之后有些CAS操作会尝试修改pred的后继，如果CAS失败，那么说明有别的线程在做
    // 取消动作或通知动作，所以当前线程也不需要更多的动作了。

    Node predNext = pred.next;

    // Can use unconditional write instead of CAS here.
    // After this atomic step, other Nodes can skip past us.
    // Before, we are free of interference from other threads.

    // 这里直接使用赋值操作，而不是CAS操作。
    // 如果别的线程在执行这步之后，别的线程将会跳过这个node。
    // 如果别的线程在执行这步之前，别的线程还是会将这个node当作有效节点。

    node.waitStatus = Node.CANCELLED;

    // If we are the tail, remove ourselves.
    // 如果node是队尾，那简单。直接设置pred为队尾，然后设置pred的后继为null
    if (node == tail && compareAndSetTail(node, pred)) {
        compareAndSetNext(pred, predNext, null);
    } else {
        // If successor needs signal, try to set pred's next-link
        // so it will get one. Otherwise wake it up to propagate.
        int ws;

        /**
         * pred != head不成立，那么说明pred就是head，执行else分支。node的后继运气爆棚，因为node自己取消掉了，
         * node的后继便成为了等待队列中第一个线程（即成为了head后继），自然需要去唤醒它了（unparkSuccessor(node)）。
         *
         * pred != head成立，但(ws = pred.waitStatus) == Node.SIGNAL || (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))不成立，执行else分支。
         * 第一个条件成立，说明pred并不是head。
         * 第二个条件不成立，里面是||，说明是两个子条件都不成立。有下面情况：
         * 如果pred的状态是CANCELLED。这似乎有点矛盾，因为之前的for循环已经判断过pred的状态是 <= 0的了，但你要知道多线程环境下，什么都可能发生。
         * 很可能从for循环执行到这里的时候，pred又被取消掉了。考虑到pred如果是head后继的话，那么node后继就一下子成为了队列中第一个线程了，所以还是有必要执行unparkSuccessor(node)。
         * 如果pred的状态是 <= 0但还不是SIGNAL，但CAS设置pred的状态为SIGNAL却失败了。SIGNAL是作为后继节点被唤醒的标志而存在的，现在居然没有设置成功，所以很有必要执行unparkSuccessor(node)。
         *
         *
         * pred != head成立，(ws = pred.waitStatus) == Node.SIGNAL || (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))也成立，但pred.thread != null不成立，执行else分支。
         * 第一个条件成立，说明pred并不是head。
         * 第二个条件成立，里面是||，说明是两个子条件要么前真，要么前假后真。
         * 如果是前真，那么说明pred的状态已经是SIGNAL。
         * 如果是前假后真，那么说明pred的状态是0或PROPAGATE，且接下来的CAS操作也成功了，即成功设置为SIGNAL。
         * 第三个条件不成立，说明pred变成了一个dummy node了。要么pred是变成head了，要么pred突然被取消了（执行了node.thread = null）。这两种情况，前者必须执行unparkSuccessor(node)，后者只是有必要执行。
         *
         *
         * pred != head成立，(ws = pred.waitStatus) == Node.SIGNAL || (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))也成立，且pred.thread != null也成立，
         * 只有这里才执行if分支。说明闹钟设置成功，并且也不是个空节点，那么只需要将pred与node后继相连即可（compareAndSetNext(pred, predNext, next)）。
         */
        if (pred != head &&
            ((ws = pred.waitStatus) == Node.SIGNAL ||
             (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&
            pred.thread != null) {
            Node next = node.next;
            if (next != null && next.waitStatus <= 0)
                compareAndSetNext(pred, predNext, next);
        } else {
            unparkSuccessor(node);
        }

        node.next = node; // help GC
    }
}
```



## 6.unparkSuccessor方法逻辑

唤醒后继节点（有效的）

```java
private void unparkSuccessor(Node node) {
    /*
     * If status is negative (i.e., possibly needing signal) try
     * to clear in anticipation of signalling.  It is OK if this
     * fails or if status is changed by waiting thread.
     */
    /*
     * 一般为SIGNAL，然后将其设置为0.但允许这次CAS操作失败
     */
    int ws = node.waitStatus;
    if (ws < 0)
        compareAndSetWaitStatus(node, ws, 0);

    /*
     * Thread to unpark is held in successor, which is normally
     * just the next node.  But if cancelled or apparently null,
     * traverse backwards from tail to find the actual
     * non-cancelled successor.
     */

    /*
     * node后继一般能直接通过next找到，但只有prev是肯定有效的。
     * 所以遇到next为null，肯定需要从队尾的prev往前找。
     * 遇到next的状态为取消，也需要从队尾的prev往前找。
     */

    Node s = node.next;
    if (s == null || s.waitStatus > 0) {
        s = null;
        for (Node t = tail; t != null && t != node; t = t.prev)
            if (t.waitStatus <= 0)
                s = t;
    }
    if (s != null)
        LockSupport.unpark(s.thread);
}
```
