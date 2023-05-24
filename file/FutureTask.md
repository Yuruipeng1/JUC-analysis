## 1.FutureTask的状态

```java
private volatile int state;
private static final int NEW          = 0;
private static final int COMPLETING   = 1;
private static final int NORMAL       = 2;
private static final int EXCEPTIONAL  = 3;
private static final int CANCELLED    = 4;
private static final int INTERRUPTING = 5;
private static final int INTERRUPTED  = 6;
```

new是新建状态，COMPLETING是一个中间状态(有重要作用) ,NORMAL是正常执行完毕后的状态，EXCEPTIONAL是出现异常后的状态，CANCELLED是被取消后的状态，INTERRUPTING是正在被中断的状态（也是一个中间状态），INTERRUPTED是中断后的状态。



* NEW -> COMPLETING -> NORMAL     

* NEW -> COMPLETING -> EXCEPTIONAL     
* NEW -> CANCELLED     
* NEW -> INTERRUPTING -> INTERRUPTED



所以会有以上状态的转换。这种状态的转换都是不可逆的，某些过程中还可能存在中间状态，但这种中间状态存在的时间很短，且马上也会变成相应的最终状态。所以可以认为，**只要状态不是NEW的话，就可以认为生产者执行task已经完毕**。

```java
public boolean isDone() {
    return state != NEW;
}
```

 

## 2.FutureTask接受的参数

FutureTask既能接受callable类型的参数，也能接收runnable类型的参数，runnable最终会被包装成一个callable（RunnableAdapter）

FutureTask本身是一个Runnable，所以当线程调用它时，会调用run方法，在它的run方法里面会直接调用callable的call方法执行task，

如果时callable类型就直接调用，如果是runnable类型，调用RunnableAdapter的call方法，在这个方法里面会调用到runnable的run方法最终完成调用。



## 3.为什么要有COMPLETING 这个中间状态

COMPLETING 这个状态赋值 存在于set 和 setException方法中，

```java
private int awaitDone(boolean timed, long nanos)
    throws InterruptedException {
    final long deadline = timed ? System.nanoTime() + nanos : 0L;
    WaitNode q = null;
    boolean queued = false;
    for (;;) {
        if (Thread.interrupted()) {
            removeWaiter(q);
            throw new InterruptedException();
        }

        int s = state;
        if (s > COMPLETING) {
            if (q != null)
                q.thread = null;
            return s;
        }
        else if (s == COMPLETING) // cannot time out yet
            Thread.yield();
        else if (q == null)
            q = new WaitNode();
        else if (!queued)
            queued = UNSAFE.compareAndSwapObject(this, waitersOffset,
                                                 q.next = waiters, q);
        else if (timed) {
            nanos = deadline - System.nanoTime();
            if (nanos <= 0L) {
                removeWaiter(q);
                return state;
            }
            LockSupport.parkNanos(this, nanos);
        }
        else
            LockSupport.park(this);
    }
}
```

```java
protected void set(V v) {
    if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
        outcome = v;
        UNSAFE.putOrderedInt(this, stateOffset, NORMAL); // final state
        finishCompletion();
    }
}
```

对于这段set逻辑，如果我们没有COMPLETING状态，直接赋值为NORMAL，然后执行outcome = v

那么在get方法中首先它去阻塞的这段逻辑就不会进，直接返回一个值，

如果get方法在outcome = v之前执行，那么它返回的就是个null，所以需要COMPLETING做过度



另外在awaitDone方法逻辑中，如果没有COMPLETING这个状态，那么我们不知道当前是否是快要拿到值了 （如果当前是这个状态，说明马上就能拿到值了，所以这时候就yield让出cpu并且等待，等下次就可能直接拿到值了，避免去操作系统阻塞），这时候我们就只能直接去操作系统阻塞，而去阻塞非常耗费性能，这就降低了效率。所以有这个状态是可以提高性能的。



## 4.为什么handlePossibleCancellationInterrupt方法中不清除中断标志位

如果清除：那么外面的线程就不知道我被中断了，代表我可能run方法都没执行，都不知道，因为中断标志被清除了，外面的线程调用isInterrupted是否被中断，但发现中断标志位不存在，代表我没被中断过，但我是已经被中断过了的,awaitDone方法中if (Thread.interrupted()) 这个判断就会失效


如果不清除： 如果是保留，当前业务中使用的是线程池的话，而线程池中是复用线程对象，减少线程对象的创建和销毁操作，

而我们这里中断标志位不清除，那么会留给下一个使用当前线程的任务，如果下一个任务有响应中断的操作，那么就会直接抛出异常
如果不是线程池，只是普通的线程，当这个线程的任务执行完就会直接销毁，那么不清除标志位也没关系

那么清除和不清楚都会有问题，该如何处理呢，Doug Lea给出的答案是不清除，把这个bug抛给线程池来处理，我们知道在线程池里执行runWorker方法时在执行task之前会清除中断标志位，这就解决了这个问题.



## 5.run方法中的一个bug

如果消费者是在生产者线程执行run方法的if (c != null && state == NEW)之前就执行了cancel函数，那么才可以终止生产者执行task。

如果消费者是在生产者线程执行run方法的if (c != null && state == NEW)之后才执行的cancel函数，那么将不能终止生产者。

如果参数是false，state从NEW修改为CANCELLED。但修改state，并不能使得生产者线程运行终止。
如果参数是true，state从NEW修改为INTERRUPTING，中断生产者线程后，再修改为INTERRUPTED。我们知道，中断一个正在运行的线程，线程运行状态不会发生变化的，只是会设置一下线程的中断状态。也就是说，这也不能使得生产者线程运行终止。除非生产者线程运行的代码（Callable.call()）时刻在检测自己的中断状态。



那这种情况既然不能真的终止生产者线程，那么这个cancel函数有什么用，其实还是有用的：

如果参数为true，那么会去中断生产者线程。但生产者线程能否检测到，取决于生产者线程运行的代码（Callable.call()）。
状态肯定会变成CANCELLED或INTERRUPTED，新来的消费者线程会直接发现，然后在get函数中不去调用awaitDone。
对于生产者线程来说，执行task期间不会影响。但最后执行set或setException，会发现这个state，然后不去设置outcome。

## 6.在FutureTask实现中会出现一些普通写

这些普通写一般存在于中间状态----->最终状态

比如cancel函数中的UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED)这里使用的普通写，其他线程可能不能马上看到，但这没关系。因为：

一来，这个状态转移是唯一的。INTERRUPTING只能变成INTERRUPTED。其他线程暂时看不到 INTERRUPTED 也没关系。（注意，暂时看不到 INTERRUPTING，会导致handlePossibleCancellationInterrupt自旋）

二来，finishCompletion中也有对其他volatile字段的CAS写操作。这样做会把之前的普通写都刷新到内存中去。


