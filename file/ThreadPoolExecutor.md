## 1.为什么使用线程池？

- 线程复用，减少[线程创建](https://so.csdn.net/so/search?q=线程创建&spm=1001.2101.3001.7020)、销毁的开销，提高性能
- 提高响应速度，当任务到达时，无需等待线程创建就能立即执行。
- 提高线程的可管理性。线程是稀缺资源，如果无限制的创建，不仅会消耗系统资 源，还会降低系统的稳定性，使用线程池可以进行统一的分配，调优和监控。



## 2.线程池有哪些状态？

```
RUNNING：说明线程池正在正常工作
SHUTDOWN：当前线程池不再接受新的任务，但是task队列中的任务还得继续执行完毕
STOP： 当前线程池不再接受新的任务，并且不再处理阻塞队列中的任务，有正在执行的任务也会中断取消，task队列为空
TIDYING： 当前线程池变成TERMINATED状态的一个中间状态。
TERMINATED：当前线程池已经死亡。
```



## 3.线程池中为什么要把状态和线程数量设计到一个AtomicInteger中？

一次cas操作就能成功，如果状态和数量都分别设计一个int，那么需要多个cas来保证这两个变量的原子性，而cas是多线程竞争很浪费性能。

在这个变量中，高3位表示线程池的状态，低29位表示线程池的线程数量。



## 4.线程池提交任务的逻辑

首先获取当前线程池中线程数量，如果小于核心线程数，那么尝试把task提交给一个新线程来执行，

如果大于核心线程数或者线程池发生了变化，这时候判断线程池是否是RUNNING状态，是的话，就把当前task入队，入队成功后，还需要检查当前是否是RUNNING状态，如果不是，将task出队，然后执行拒绝策略。 接下来就说明是RUNNING,或者不是RUNNING但是出队失败了，如果当前线程池线程数量为0，有可能刚入队后，池里唯一的线程就死掉了，此时调用addWorker（null，false）新启动线程执行这个task任务。

如果当前线程池中阻塞队列满了，那么调用addWorker(command, false)方法新启动线程处理任务。



整体的逻辑：

如果线程数量小于corePoolSize，那么不入队，尝试起一个新线程来执行task。
如果线程数量大于等于了corePoolSize，那么只要队列未满，就将task入队。
如果线程数量大于等于了corePoolSize，且队列已满（上一步入队失败）。那么以maximumPoolSize作为限制，再起一个新线程。——addWorker(command, false)成功。
如果线程数量大于等于了maximumPoolSize，且队列已满。那么将执行拒绝策略。——addWorker(command, false)失败。

## 5.线程池中addWorker方法逻辑

```java
private boolean addWorker(Runnable firstTask, boolean core) {
    retry://该循环在检测线程池状态的前提下，和线程数量限制的前提下，尝试增加线程数量
    for (;;) {
        int c = ctl.get();
        int rs = runStateOf(c);

        // Check if queue empty only if necessary.
        // 如果状态已经不是running了
        if (rs >= SHUTDOWN &&
                // 三者都成立，括号内才返回true，括号外才返回false，函数才不会直接return
                // 三者只要有一个不成立，那么addWorker将直接返回false
            ! (rs == SHUTDOWN && //当前是SHUTDOWN状态
               firstTask == null &&//传入参数是null（非null说明是新task，但已经SHUTDOWN所以不能再添加）
               ! workQueue.isEmpty()))//队列非空

        //即有三种情况会造成addWorker直接false，不去新起线程了；还有一种特殊情况，addWorker会继续执行。
        //1. 如果当前是STOP以及以后的状态（肯定不需要新起线程了，因为task队列为空了）
        //2. 如果当前是SHUTDOWN，且firstTask参数不为null（非RUNNING状态线程池都不可以接受新task的）
        //3. 如果当前是SHUTDOWN，且firstTask参数为null，但队列空（既然队列空，那么也不需要新起线程）

            //如果当前是SHUTDOWN，且firstTask参数为null，且队列非空（特殊情况，需要新起线程把队列剩余task执行完）
            return false;

        //此时说明，需要新起线程（状态为RUNNING或SHUTDOWN）
        for (;;) {
            int wc = workerCountOf(c);
            //如果线程数量超过最大值
            if (wc >= CAPACITY ||
                 //如果线程数量超过特定值，根据core参数决定是哪个特定值
                wc >= (core ? corePoolSize : maximumPoolSize))
                return false;
            if (compareAndIncrementWorkerCount(c))//CAS尝试+1一次
                //如果CAS成功，说明这个外循环任务完成，退出大循环
                break retry;
            //CAS修改失败可能是：线程数量被并发修改了，或者线程池状态都变了
            c = ctl.get();  // Re-read ctl
            if (runStateOf(c) != rs)//再次检查当前线程池状态，和当初保存的线程池状态
                continue retry;//如果改变，那么continue外循环，即重新检查线程池状态（毕竟线程池状态也在这个int里）
            // else CAS failed due to workerCount change; retry inner loop
            // 如果只是 线程数量被并发修改了，那么接下来会继续内循环，再次CAS增加线程数量
        }
    }
    //此时，crl的线程数量已经成功加一，且线程池状态保持不变（相较于函数刚进入时）

    boolean workerStarted = false;//新线程是否已经开始运行
    boolean workerAdded = false;//新线程是否已经加入set
    Worker w = null;
    try {
        w = new Worker(firstTask);//构造器中利用线程工厂得到新线程对象
        final Thread t = w.thread;//获得这个新线程
        if (t != null) {//防止工厂没有创建出新线程
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();//加锁不是为了ctl，而是为了workers.add(w)
            try {
                // Recheck while holding lock.
                // Back out on ThreadFactory failure or if
                // shut down before lock acquired.
                // 需要再次检查状态
                int rs = runStateOf(ctl.get());

                //如果线程还是运行状态
                if (rs < SHUTDOWN ||
                      //如果线程是SHUTDOWN，且参数firstTask为null
                    (rs == SHUTDOWN && firstTask == null)) {
                    if (t.isAlive()) // precheck that t is startable // 新线程肯定是还没有开始运行的
                        throw new IllegalThreadStateException();
                    workers.add(w);
                    int s = workers.size();
                    if (s > largestPoolSize)
                        largestPoolSize = s;
                    workerAdded = true;
                }
            } finally {
                mainLock.unlock();
            }
            if (workerAdded) {//加入集合成功，才启动新线程
                t.start();//这里启动了新线程
                workerStarted = true;
            }
        }
    } finally {
        //只要线程工厂创建线程成功，那么workerStarted为false只能是因为线程池状态发生变化，且现在一定不是running状态了
        if (! workerStarted)
            addWorkerFailed(w);
    }
    return workerStarted;
}
```



## 6.addWorkerFailed方法逻辑

首先判断workers集合中是否存在当前要回滚的worker，如果存在则要移除它，然后循环cas将ctl的线程数量-1.

接着尝试帮助线程池Terminate，首先需要判断当前线程池是不是RUNNING，那么不需要帮助，如果已经是TIDYING,那么也不用帮助，因为谁是TIDYING，谁就负责将状态变成Terminate，如果当前为SHUTDOWN,且任务队列不为空，那么也不用去帮忙。



如果当前是STOP,或者当前是SHUTDOWN,并且任务队列为空， 这时判断如果线程数量不为0，就中断一个线程，并且这个中断状态会传播， 然后直接返回。如果线程数量为0，那么cas把线程池的状态设为TIDYING，然后执行terminated方法（默认为空，但是可以自己实现），接着cas把状态设为TERMINATED.



## 7.线程池中线程是如何启动的？

线程的启动，是通过Worker对象的thread属性，然后调用它的start方法来启动的， 那么这个thread属性是怎么创建的呢？

```
this.thread = getThreadFactory().newThread(this)
```

通过这段代码来创建，调用ThreadFactory的newThread方法，并且传入的参数是this，也就是Worker对象，这个参数很关键，因为Worker本身也是一个Runnable，所以在newThread中创建的那个线程，它start方法回调的那个方法最终会调用到Worker中的run方法，而run方法中调用runWorker(this)，所以线程启动的逻辑在runWorker中。

```java
final void runWorker(Worker w) {
    Thread wt = Thread.currentThread();//获得当前线程对象
    Runnable task = w.firstTask;//获得第一个task（这里可能为null）
    w.firstTask = null;//释放引用
    w.unlock(); // allow interrupts// 此时state为0，Worker锁此时可以被抢。且此时工作线程可以被中断了
    boolean completedAbruptly = true;//是否发生异常
    try {
        //1. 如果第一个task不为null，开始执行循环
        //2. 如果第一个task为null，但从task队列里获得的task不为null，也开始循环
        //3. 如果task队列获得到的也是null，那么结束循环
        while (task != null || (task = getTask()) != null) {
            w.lock();//执行task前，先获得锁
            // If pool is stopping, ensure thread is interrupted;
            // if not, ensure thread is not interrupted.  This
            // requires a recheck in second case to deal with
            // shutdownNow race while clearing interrupt

            // 第一个表达式 (runStateAtLeast(ctl.get(), STOP) || (Thread.interrupted() && runStateAtLeast(ctl.get(), STOP)))
            // 无论线程池状态是什么都会消耗掉当前线程的中断状态（如果当前线程确实被中断了），
            // 并且只有线程池状态是STOP的情况下，且当前线程被中断了，才会返回true。
            // 第二个表达式 !wt.isInterrupted()
            // 因为第一个表达式永远会消耗掉中断状态，所以第二个表达式肯定为true
            // 总之，重点在第一个表达式。

            if ((runStateAtLeast(ctl.get(), STOP) ||
                 (Thread.interrupted() &&
                  runStateAtLeast(ctl.get(), STOP))) &&
                !wt.isInterrupted())
                // 1. 如果线程池状态为STOP，且当前线程被中断，马上恢复中断状态
                // 2. 如果线程池状态为其他，且当前线程被中断，仅仅消耗掉这个中断状态，不进入分支
                wt.interrupt();//恢复中断状态
            try {
                //空实现的方法，如果抛出异常，completedAbruptly将为true
                beforeExecute(wt, task);
                Throwable thrown = null;
                try {
                    task.run();//执行task
                } catch (RuntimeException x) {
                    thrown = x; throw x;//这里抛出异常，completedAbruptly也将为true
                } catch (Error x) {
                    thrown = x; throw x;
                } catch (Throwable x) {
                    thrown = x; throw new Error(x);
                } finally {
                    //空实现方法
                    afterExecute(task, thrown);//task.run()抛出异常的话，至少afterExecute可以执行
                }
            } finally {//无论上面在beforeExecute或task.run()中抛出异常与否，最终都会执行这里
                task = null;
                w.completedTasks++;
                w.unlock();
            }
        }
        completedAbruptly = false;//上面循环是正常结束而没有抛出异常，这个变量才为false
    } finally {
        //无论循环正常结束(getTask返回null，completedAbruptly为false)，
        //还是循环中抛出了异常(completedAbruptly为true)，都会执行这句。
        //代表当前Worker马上要寿终正寝了
        processWorkerExit(w, completedAbruptly);
    }
}
```