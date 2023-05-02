/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A synchronization aid that allows a set of threads to all wait for
 * each other to reach a common barrier point.  CyclicBarriers are
 * useful in programs involving a fixed sized party of threads that
 * must occasionally wait for each other. The barrier is called
 * <em>cyclic</em> because it can be re-used after the waiting threads
 * are released.
 *
 * <p>A {@code CyclicBarrier} supports an optional {@link Runnable} command
 * that is run once per barrier point, after the last thread in the party
 * arrives, but before any threads are released.
 * This <em>barrier action</em> is useful
 * for updating shared-state before any of the parties continue.
 *
 * <p><b>Sample usage:</b> Here is an example of using a barrier in a
 * parallel decomposition design:
 *
 *  <pre> {@code
 * class Solver {
 *   final int N;
 *   final float[][] data;
 *   final CyclicBarrier barrier;
 *
 *   class Worker implements Runnable {
 *     int myRow;
 *     Worker(int row) { myRow = row; }
 *     public void run() {
 *       while (!done()) {
 *         processRow(myRow);
 *
 *         try {
 *           barrier.await();
 *         } catch (InterruptedException ex) {
 *           return;
 *         } catch (BrokenBarrierException ex) {
 *           return;
 *         }
 *       }
 *     }
 *   }
 *
 *   public Solver(float[][] matrix) {
 *     data = matrix;
 *     N = matrix.length;
 *     Runnable barrierAction =
 *       new Runnable() { public void run() { mergeRows(...); }};
 *     barrier = new CyclicBarrier(N, barrierAction);
 *
 *     List<Thread> threads = new ArrayList<Thread>(N);
 *     for (int i = 0; i < N; i++) {
 *       Thread thread = new Thread(new Worker(i));
 *       threads.add(thread);
 *       thread.start();
 *     }
 *
 *     // wait until done
 *     for (Thread thread : threads)
 *       thread.join();
 *   }
 * }}</pre>
 *
 * Here, each worker thread processes a row of the matrix then waits at the
 * barrier until all rows have been processed. When all rows are processed
 * the supplied {@link Runnable} barrier action is executed and merges the
 * rows. If the merger
 * determines that a solution has been found then {@code done()} will return
 * {@code true} and each worker will terminate.
 *
 * <p>If the barrier action does not rely on the parties being suspended when
 * it is executed, then any of the threads in the party could execute that
 * action when it is released. To facilitate this, each invocation of
 * {@link #await} returns the arrival index of that thread at the barrier.
 * You can then choose which thread should execute the barrier action, for
 * example:
 *  <pre> {@code
 * if (barrier.await() == 0) {
 *   // log the completion of this iteration
 * }}</pre>
 *
 * <p>The {@code CyclicBarrier} uses an all-or-none breakage model
 * for failed synchronization attempts: If a thread leaves a barrier
 * point prematurely because of interruption, failure, or timeout, all
 * other threads waiting at that barrier point will also leave
 * abnormally via {@link BrokenBarrierException} (or
 * {@link InterruptedException} if they too were interrupted at about
 * the same time).
 *
 * <p>Memory consistency effects: Actions in a thread prior to calling
 * {@code await()}
 * <a href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * actions that are part of the barrier action, which in turn
 * <i>happen-before</i> actions following a successful return from the
 * corresponding {@code await()} in other threads.
 *
 * @since 1.5
 * @see CountDownLatch
 *
 * @author Doug Lea
 */
public class CyclicBarrier {
    /**
     * Each use of the barrier is represented as a generation instance.
     * The generation changes whenever the barrier is tripped, or
     * is reset. There can be many generations associated with threads
     * using the barrier - due to the non-deterministic way the lock
     * may be allocated to waiting threads - but only one of these
     * can be active at a time (the one to which {@code count} applies)
     * and all the rest are either broken or tripped.
     * There need not be an active generation if there has been a break
     * but no subsequent reset.
     */
    private static class Generation {
        boolean broken = false;
    }

    //CyclicBarrier的线程同步是依赖于 ReentrantLock 和 Condition 的。
    // 到达barrier的线程开始都会进入condition queue后阻塞，等待signal的到来；
    // 这些线程被唤醒后，将进入sync queue中排队抢锁，抢到锁的线程才能退出CyclicBarrier#await

    /** The lock for guarding barrier entry */
    private final ReentrantLock lock = new ReentrantLock();
    /** Condition to wait on until tripped */
    private final Condition trip = lock.newCondition();
    /** The number of parties */
    //代表每次通过barrier一共需要的线程数量，它是final的，被构造器所赋值被不再改变，因为每次通过barrier后需要靠它重新复位计数器
    private final int parties;
    /* The command to run when tripped */
    //barrierCommand是每一代线程们通过barrier之前要做的事情，它虽然是一个Runnable对象，
    // 但我们执行它的run方法时根本不会新起一个线程来执行，这里你甚至可以将这句的Runnable随便变成一个具有run方法的类都可以
    private final Runnable barrierCommand;
    /** The current generation */
    //CyclicBarrier是可以重复使用的，每一组一起通过barrier的线程我们称它们为一代Generation，这就像是一种版本标记
    private Generation generation = new Generation();

    /**
     * Number of parties still waiting. Counts down from parties to 0
     * on each generation.  It is reset to parties on each new
     * generation or when broken.
     */
    //当前还需要多少个线程到达barrier，它的初始值是parties，通过barrier时它肯定变成了0。
    private int count;

    /**
     * Updates state on barrier trip and wakes up everyone.
     * Called only while holding lock.
     */
    private void nextGeneration() {
        // signal completion of last generation
        // 唤醒条件队列上的这一代线程
        trip.signalAll();
        // set up next generation
        // 恢复count值，因为需要重新计数
        count = parties;
        generation = new Generation();
    }

    /**
     * Sets current barrier generation as broken and wakes up everyone.
     * Called only while holding lock.
     */
    private void breakBarrier() {
        // 标记这一代是broken的
        generation.broken = true;
        // 恢复count值
        count = parties;
        // 唤醒条件队列上的这一代线程（数量可能不够parties个）
        trip.signalAll();
    }

    /**
     * Main barrier code, covering the various policies.
     */
    private int dowait(boolean timed, long nanos)//timed参数为true，才可能抛出TimeoutException
        throws InterruptedException, BrokenBarrierException,
               TimeoutException {
        final ReentrantLock lock = this.lock;
        lock.lock();//整个执行过程必须先获得锁
        try {
            // 这个Generation局部变量保存起来很重要，因为CyclicBarrier的generation成员在通过barrier后会替换为新对象
            // 获得的Generation对象肯定是当前线程应该在的“代”
            final Generation g = generation;

            if (g.broken) //如果当前线程在调用dowait前，当代generation就是broken的
                //之所以肯定说是调用dowait前，是因为所有操作的前提都是要先获得锁
                //抛出这个异常的意义？给开发者捕捉这个异常后，手动调用reset方法复位，要不然CyclicBarrier再也得不到复用了
                throw new BrokenBarrierException();

            //如果当前线程在调用dowait前，被中断了
            //被中断说明当前线程取消掉了，而CyclicBarrier本着“一个都不少”的原则，就不会让同一代的其他线程苟活下去
            //所以就要先breakBarrier，再抛出InterruptedException异常。

            if (Thread.interrupted()) {
                breakBarrier();
                throw new InterruptedException();
            }

            //到达barrier时，将count减一的值，这个值作为正常返回时的返回值
            int index = --count;
            //如果当前线程是通过barrier所需要的最后一个线程，即之前已经到达了parties-1个线程
            if (index == 0) {  // tripped
                boolean ranAction = false;//用来标记command.run()执行过程中是否出错
                try {
                    final Runnable command = barrierCommand;
                    //当代中最后一个线程，负责执行command.run()
                    if (command != null)
                        command.run();
                    ranAction = true;//标记command.run()执行过程中没有出错
                    nextGeneration();//唤醒条件队列的所有线程，开启下一代
                    return 0;
                } finally {
                    if (!ranAction)//command.run()执行过程中出错了，需要执行breakBarrier
                        breakBarrier();
                }
            }
            //执行到这里，说明当前线程肯定不是当代中的最后一个线程（即index不为0）
            //如果最后一个线程执行command.run()没出错，将return 0返回
            //如果最后一个线程执行command.run()出错了，执行finally块后，将返回上层函数并抛出异常

            // loop until tripped, broken, interrupted, or timed out
            for (;;) {
                try {
                    // 两种Condition的await都会阻塞，

                    // 如果是普通版本调用的dowait，那么调用Condition的await
                    if (!timed)
                        trip.await();
                    // 如果是超时版本调用的dowait，那么调用Condition的awaitNanos
                    else if (nanos > 0L)
                        nanos = trip.awaitNanos(nanos);//返回值是deadline减去当前时间的差值
                } catch (InterruptedException ie) {
                    //执行到这里，说明上面两种Condition的await，被中断而唤醒,并且比最后一个线程执行的代码要早

                    //如果局部变量和成员变量是同一个对象，且当前不是broken的，则先breakBarrier
                    if (g == generation && ! g.broken) {
                        breakBarrier();
                        throw ie;
                    } else {
                        //执行到这里有两种情况：
                        //1. g != generation,说明成员变量已经更新了，此时再去breakBarrier
                        //   只能影响到新一代的broken成员，所以不能执行breakBarrier
                        //2. 虽然g == generation,但已经是broken的了。既然barrier已经被打破
                        //   那就不用再执行breakBarrier了

                        // We're about to finish waiting even if we had not
                        // been interrupted, so this interrupt is deemed to
                        // "belong" to subsequent execution.
                        Thread.currentThread().interrupt();
                    }
                }
                //只要自己这一代是broken的，就抛出异常。只有breakBarrier函数会改变broken，这里给出执行breakBarrier的场景：
                //1. 当代的其他线程调用CyclicBarrier#await之前就已经被中断了（强调“其他”是因为，如果是这个原因，不会执行到这里）
                //2. 当代的其他线程调用Condition#await阻塞后被中断（上面的catch块）
                //3. 最后到达barrier的线程执行barrierCommand时出错了
                //4. reset方法被执行，这可以是任意线程


                if (g.broken)
                    throw new BrokenBarrierException();

                // 执行到这里，说明当代g不是broken的，且最后一个线程已经到达barrier
                //（最后一个到达了，会执行nextGeneration更新generation成员的）
                if (g != generation)
                    return index;

                //执行到这里，说明当代g不是broken的，CyclicBarrier的代也没有更新呢
                //说明最后一个线程还没来，自己就超时了
                //如果发现已经超时，就打破barrier，抛出异常
                if (timed && nanos <= 0L) {
                    breakBarrier();
                    throw new TimeoutException();
                }
            }
        } finally {
            lock.unlock();//就算抛出异常，抛出之前也会释放锁的
        }
    }

    /**
     * Creates a new {@code CyclicBarrier} that will trip when the
     * given number of parties (threads) are waiting upon it, and which
     * will execute the given barrier action when the barrier is tripped,
     * performed by the last thread entering the barrier.
     *
     * @param parties the number of threads that must invoke {@link #await}
     *        before the barrier is tripped
     * @param barrierAction the command to execute when the barrier is
     *        tripped, or {@code null} if there is no action
     * @throws IllegalArgumentException if {@code parties} is less than 1
     */
    public CyclicBarrier(int parties, Runnable barrierAction) {
        if (parties <= 0) throw new IllegalArgumentException();
        this.parties = parties;
        this.count = parties;
        this.barrierCommand = barrierAction;
    }

    /**
     * Creates a new {@code CyclicBarrier} that will trip when the
     * given number of parties (threads) are waiting upon it, and
     * does not perform a predefined action when the barrier is tripped.
     *
     * @param parties the number of threads that must invoke {@link #await}
     *        before the barrier is tripped
     * @throws IllegalArgumentException if {@code parties} is less than 1
     */
    public CyclicBarrier(int parties) {
        this(parties, null);
    }

    /**
     * Returns the number of parties required to trip this barrier.
     *
     * @return the number of parties required to trip this barrier
     */
    public int getParties() {
        return parties;
    }

    /**
     * Waits until all {@linkplain #getParties parties} have invoked
     * {@code await} on this barrier.
     *
     * <p>If the current thread is not the last to arrive then it is
     * disabled for thread scheduling purposes and lies dormant until
     * one of the following things happens:
     * <ul>
     * <li>The last thread arrives; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * one of the other waiting threads; or
     * <li>Some other thread times out while waiting for barrier; or
     * <li>Some other thread invokes {@link #reset} on this barrier.
     * </ul>
     *
     * <p>If the current thread:
     * <ul>
     * <li>has its interrupted status set on entry to this method; or
     * <li>is {@linkplain Thread#interrupt interrupted} while waiting
     * </ul>
     * then {@link InterruptedException} is thrown and the current thread's
     * interrupted status is cleared.
     *
     * <p>If the barrier is {@link #reset} while any thread is waiting,
     * or if the barrier {@linkplain #isBroken is broken} when
     * {@code await} is invoked, or while any thread is waiting, then
     * {@link BrokenBarrierException} is thrown.
     *
     * <p>If any thread is {@linkplain Thread#interrupt interrupted} while waiting,
     * then all other waiting threads will throw
     * {@link BrokenBarrierException} and the barrier is placed in the broken
     * state.
     *
     * <p>If the current thread is the last thread to arrive, and a
     * non-null barrier action was supplied in the constructor, then the
     * current thread runs the action before allowing the other threads to
     * continue.
     * If an exception occurs during the barrier action then that exception
     * will be propagated in the current thread and the barrier is placed in
     * the broken state.
     *
     * @return the arrival index of the current thread, where index
     *         {@code getParties() - 1} indicates the first
     *         to arrive and zero indicates the last to arrive
     * @throws InterruptedException if the current thread was interrupted
     *         while waiting
     * @throws BrokenBarrierException if <em>another</em> thread was
     *         interrupted or timed out while the current thread was
     *         waiting, or the barrier was reset, or the barrier was
     *         broken when {@code await} was called, or the barrier
     *         action (if present) failed due to an exception
     */
    public int await() throws InterruptedException, BrokenBarrierException {
        try {
            return dowait(false, 0L);
        } catch (TimeoutException toe) {
            throw new Error(toe); // cannot happen
        }
    }

    /**
     * Waits until all {@linkplain #getParties parties} have invoked
     * {@code await} on this barrier, or the specified waiting time elapses.
     *
     * <p>If the current thread is not the last to arrive then it is
     * disabled for thread scheduling purposes and lies dormant until
     * one of the following things happens:
     * <ul>
     * <li>The last thread arrives; or
     * <li>The specified timeout elapses; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * one of the other waiting threads; or
     * <li>Some other thread times out while waiting for barrier; or
     * <li>Some other thread invokes {@link #reset} on this barrier.
     * </ul>
     *
     * <p>If the current thread:
     * <ul>
     * <li>has its interrupted status set on entry to this method; or
     * <li>is {@linkplain Thread#interrupt interrupted} while waiting
     * </ul>
     * then {@link InterruptedException} is thrown and the current thread's
     * interrupted status is cleared.
     *
     * <p>If the specified waiting time elapses then {@link TimeoutException}
     * is thrown. If the time is less than or equal to zero, the
     * method will not wait at all.
     *
     * <p>If the barrier is {@link #reset} while any thread is waiting,
     * or if the barrier {@linkplain #isBroken is broken} when
     * {@code await} is invoked, or while any thread is waiting, then
     * {@link BrokenBarrierException} is thrown.
     *
     * <p>If any thread is {@linkplain Thread#interrupt interrupted} while
     * waiting, then all other waiting threads will throw {@link
     * BrokenBarrierException} and the barrier is placed in the broken
     * state.
     *
     * <p>If the current thread is the last thread to arrive, and a
     * non-null barrier action was supplied in the constructor, then the
     * current thread runs the action before allowing the other threads to
     * continue.
     * If an exception occurs during the barrier action then that exception
     * will be propagated in the current thread and the barrier is placed in
     * the broken state.
     *
     * @param timeout the time to wait for the barrier
     * @param unit the time unit of the timeout parameter
     * @return the arrival index of the current thread, where index
     *         {@code getParties() - 1} indicates the first
     *         to arrive and zero indicates the last to arrive
     * @throws InterruptedException if the current thread was interrupted
     *         while waiting
     * @throws TimeoutException if the specified timeout elapses.
     *         In this case the barrier will be broken.
     * @throws BrokenBarrierException if <em>another</em> thread was
     *         interrupted or timed out while the current thread was
     *         waiting, or the barrier was reset, or the barrier was broken
     *         when {@code await} was called, or the barrier action (if
     *         present) failed due to an exception
     */
    public int await(long timeout, TimeUnit unit)
        throws InterruptedException,
               BrokenBarrierException,
               TimeoutException {
        return dowait(true, unit.toNanos(timeout));
    }

    /**
     * Queries if this barrier is in a broken state.
     *
     * @return {@code true} if one or more parties broke out of this
     *         barrier due to interruption or timeout since
     *         construction or the last reset, or a barrier action
     *         failed due to an exception; {@code false} otherwise.
     */
    public boolean isBroken() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return generation.broken;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resets the barrier to its initial state.  If any parties are
     * currently waiting at the barrier, they will return with a
     * {@link BrokenBarrierException}. Note that resets <em>after</em>
     * a breakage has occurred for other reasons can be complicated to
     * carry out; threads need to re-synchronize in some other way,
     * and choose one to perform the reset.  It may be preferable to
     * instead create a new barrier for subsequent use.
     */
    public void reset() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            breakBarrier();   // break the current generation
            nextGeneration(); // start a new generation
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of parties currently waiting at the barrier.
     * This method is primarily useful for debugging and assertions.
     *
     * @return the number of parties currently blocked in {@link #await}
     */
    public int getNumberWaiting() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return parties - count;
        } finally {
            lock.unlock();
        }
    }
}
