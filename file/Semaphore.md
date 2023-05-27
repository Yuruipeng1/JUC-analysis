## 1.tryAcquireShared返回值代表的含义

tryAcquireShared方法返回的是int类型的，

1. 如果返回值大于0，说明获取共享锁成功，并且后续获取也可能获取成功。
2. 如果返回值等于0，说明获取共享锁成功，但后续获取可能不会成功。
3. 如果返回值小于0，说明获取共享锁失败。



这与独占锁的返回值不同，独占锁的加锁方法返回boolean，成功就返回true，失败就返回false。



## 2.setHeadAndPropagate方法逻辑



共享锁不仅在释放锁时会调用doReleaseShared，当加锁成功时，也会判断条件是否满足来调用doReleaseShared

```java
private void setHeadAndPropagate(Node node, int propagate) {
    Node h = head; // Record old head for check below
    setHead(node);
    /*
     * Try to signal next queued node if:
     *   Propagation was indicated by caller,
     *     or was recorded (as h.waitStatus either before
     *     or after setHead) by a previous operation
     *     (note: this uses sign-check of waitStatus because
     *      PROPAGATE status may transition to SIGNAL.)
     * and
     *   The next node is waiting in shared mode,
     *     or we don't know, because it appears null
     *
     * The conservatism in both of these checks may cause
     * unnecessary wake-ups, but only when there are multiple
     * racing acquires/releases, so most need signals now or soon
     * anyway.
     */

    /**
     * 如果propagate > 0成立的话，说明还有剩余共享锁可以获取，那么短路后面条件。
     *
     * 如果propagate > 0不成立，而h.waitStatus < 0成立。这说明旧head的status<0。但如果你看doReleaseShared的逻辑，
     * 会发现在unparkSuccessor之前就会CAS设置head的status为0的，在unparkSuccessor也会进行一次CAS尝试，
     * 因为head的status为0代表一种中间状态（head的后继代表的线程已经唤醒，但它还没有做完工作），或者代表head是tail。
     * 而这里旧head的status<0，只能是由于doReleaseShared里的compareAndSetWaitStatus(h, 0, Node.PROPAGATE)的操作，
     * 而且由于当前执行setHeadAndPropagate的线程只会在最后一句才执行doReleaseShared，所以出现这种情况，
     * 一定是因为有另一个线程在调用doReleaseShared才能造成，而这很可能是因为在中间状态时，又有人释放了共享锁。
     * propagate == 0只能代表当时tryAcquireShared后没有共享锁剩余，但之后的时刻很可能又有共享锁释放出来了。
     *
     *
     * 如果propagate > 0不成立，且h.waitStatus < 0不成立，而第二个h.waitStatus < 0成立。
     * 注意，第二个h.waitStatus < 0里的h是新head（很可能就是入参node）。第一个h.waitStatus < 0不成立很正常，
     * 因为它一般为0（考虑别的线程可能不会那么碰巧读到一个中间状态）。第二个h.waitStatus < 0成立也很正常，
     * 因为只要新head不是队尾，那么新head的status肯定是SIGNAL。这种情况可能会造成不必要的唤醒（但是仅当有多次竞争加锁/解锁情况下才不必要）。
     * 在其他情况下还是有必要进行doReleaseShared的调用
     *
     *
     */
    if (propagate > 0 || h == null || h.waitStatus < 0 ||
        (h = head) == null || h.waitStatus < 0) {
        Node s = node.next;


        if (s == null || s.isShared())
            doReleaseShared();
    }
}
```

## 3.doReleaseShared方法逻辑

```java
private void doReleaseShared() {
    /*
     * Ensure that a release propagates, even if there are other
     * in-progress acquires/releases.  This proceeds in the usual
     * way of trying to unparkSuccessor of head if it needs
     * signal. But if it does not, status is set to PROPAGATE to
     * ensure that upon release, propagation continues.
     * Additionally, we must loop in case a new node is added
     * while we are doing this. Also, unlike other uses of
     * unparkSuccessor, we need to know if CAS to reset status
     * fails, if so rechecking.
     */

    /*
     * 逻辑是一个死循环，每次循环中重新读取一次head，然后保存在局部变量h中，再配合if(h == head) break;，
     * 这样，循环检测到head没有变化时就会退出循环。注意，head变化一定是因为：acquire thread被唤醒，之后它成功获取锁，
     * 然后setHead设置了新head。而且注意，只有通过if(h == head) break;即head不变才能退出循环，不然会执行多次循环。
     *
     */
    for (;;) {
        Node h = head;
        // (h != null && h != tail)判断队列是否至少有两个node，如果队列从来没有初始化过（head为null），
        // 或者head就是tail，那么中间逻辑直接不走，直接判断head是否变化了。
        if (h != null && h != tail) {
            int ws = h.waitStatus;

            //如果状态为SIGNAL，说明h的后继是需要被通知的。通过对CAS操作结果取反，
            // 将compareAndSetWaitStatus(h, Node.SIGNAL, 0)和unparkSuccessor(h)绑定在了一起。
            // 说明了只要head成功得从SIGNAL修改为0，那么head的后继的代表线程肯定会被唤醒了。

            if (ws == Node.SIGNAL) {
                if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0))
                    continue;            // loop to recheck cases
                unparkSuccessor(h);
            }

            //如果状态为0，说明h的后继所代表的线程已经被唤醒或即将被唤醒，并且这个中间状态即将消失，结果有下面两种情况
            // 要么由于acquire thread获取锁失败再次设置head为SIGNAL并再次阻塞，
            // 要么由于acquire thread获取锁成功而将自己（head后继）设置为新head并且只要head后继不是队尾，那么新head肯定为SIGNAL。
            // 所以设置这种中间状态的head的status为PROPAGATE，让其status又变成负数，这样可能被被唤醒线程检测到。

            else if (ws == 0 &&
                     !compareAndSetWaitStatus(h, 0, Node.PROPAGATE))
                continue;                // loop on failed CAS
        }
        if (h == head)                   // loop if head changed
            break;
    }
    /**
     * head状态为0的情况
     *
     * 如果等待队列中只有一个dummy node（它的状态为0），那么head也是tail，且head的状态为0。
     * 等待队列中当前只有一个dummy node（它的状态为0），acquire thread获取锁失败了（无论独占还是共享），将当前线程包装成node放到队列中，此时队列中有两个node，但当前线程还没来得及执行shouldParkAfterFailedAcquire。
     * 此时队列中有多个node，有线程刚释放了锁，刚执行了unparkSuccessor里的if (ws < 0) compareAndSetWaitStatus(node, ws, 0);把head的状态设置为了0，然后唤醒head后继线程，head后继线程获取锁成功，直到head后继线程将自己设置为AQS的新head的这段时间里，head的状态为0。
     *
     */
}
```