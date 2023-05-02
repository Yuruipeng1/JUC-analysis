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
 * Written by Doug Lea, Bill Scherer, and Michael Scott with
 * assistance from members of JCP JSR-166 Expert Group and released to
 * the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.*;
import java.util.Spliterator;
import java.util.Spliterators;

/**
 * A {@linkplain BlockingQueue blocking queue} in which each insert
 * operation must wait for a corresponding remove operation by another
 * thread, and vice versa.  A synchronous queue does not have any
 * internal capacity, not even a capacity of one.  You cannot
 * {@code peek} at a synchronous queue because an element is only
 * present when you try to remove it; you cannot insert an element
 * (using any method) unless another thread is trying to remove it;
 * you cannot iterate as there is nothing to iterate.  The
 * <em>head</em> of the queue is the element that the first queued
 * inserting thread is trying to add to the queue; if there is no such
 * queued thread then no element is available for removal and
 * {@code poll()} will return {@code null}.  For purposes of other
 * {@code Collection} methods (for example {@code contains}), a
 * {@code SynchronousQueue} acts as an empty collection.  This queue
 * does not permit {@code null} elements.
 *
 * <p>Synchronous queues are similar to rendezvous channels used in
 * CSP and Ada. They are well suited for handoff designs, in which an
 * object running in one thread must sync up with an object running
 * in another thread in order to hand it some information, event, or
 * task.
 *
 * <p>This class supports an optional fairness policy for ordering
 * waiting producer and consumer threads.  By default, this ordering
 * is not guaranteed. However, a queue constructed with fairness set
 * to {@code true} grants threads access in FIFO order.
 *
 * <p>This class and its iterator implement all of the
 * <em>optional</em> methods of the {@link Collection} and {@link
 * Iterator} interfaces.
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/../technotes/guides/collections/index.html">
 * Java Collections Framework</a>.
 *
 * @since 1.5
 * @author Doug Lea and Bill Scherer and Michael Scott
 * @param <E> the type of elements held in this collection
 */
public class SynchronousQueue<E> extends AbstractQueue<E>
    implements BlockingQueue<E>, java.io.Serializable {
    private static final long serialVersionUID = -3223113410248163686L;

    /*
     * This class implements extensions of the dual stack and dual
     * queue algorithms described in "Nonblocking Concurrent Objects
     * with Condition Synchronization", by W. N. Scherer III and
     * M. L. Scott.  18th Annual Conf. on Distributed Computing,
     * Oct. 2004 (see also
     * http://www.cs.rochester.edu/u/scott/synchronization/pseudocode/duals.html).
     * The (Lifo) stack is used for non-fair mode, and the (Fifo)
     * queue for fair mode. The performance of the two is generally
     * similar. Fifo usually supports higher throughput under
     * contention but Lifo maintains higher thread locality in common
     * applications.
     *
     * A dual queue (and similarly stack) is one that at any given
     * time either holds "data" -- items provided by put operations,
     * or "requests" -- slots representing take operations, or is
     * empty. A call to "fulfill" (i.e., a call requesting an item
     * from a queue holding data or vice versa) dequeues a
     * complementary node.  The most interesting feature of these
     * queues is that any operation can figure out which mode the
     * queue is in, and act accordingly without needing locks.
     *
     * Both the queue and stack extend abstract class Transferer
     * defining the single method transfer that does a put or a
     * take. These are unified into a single method because in dual
     * data structures, the put and take operations are symmetrical,
     * so nearly all code can be combined. The resulting transfer
     * methods are on the long side, but are easier to follow than
     * they would be if broken up into nearly-duplicated parts.
     *
     * The queue and stack data structures share many conceptual
     * similarities but very few concrete details. For simplicity,
     * they are kept distinct so that they can later evolve
     * separately.
     *
     * The algorithms here differ from the versions in the above paper
     * in extending them for use in synchronous queues, as well as
     * dealing with cancellation. The main differences include:
     *
     *  1. The original algorithms used bit-marked pointers, but
     *     the ones here use mode bits in nodes, leading to a number
     *     of further adaptations.
     *  2. SynchronousQueues must block threads waiting to become
     *     fulfilled.
     *  3. Support for cancellation via timeout and interrupts,
     *     including cleaning out cancelled nodes/threads
     *     from lists to avoid garbage retention and memory depletion.
     *
     * Blocking is mainly accomplished using LockSupport park/unpark,
     * except that nodes that appear to be the next ones to become
     * fulfilled first spin a bit (on multiprocessors only). On very
     * busy synchronous queues, spinning can dramatically improve
     * throughput. And on less busy ones, the amount of spinning is
     * small enough not to be noticeable.
     *
     * Cleaning is done in different ways in queues vs stacks.  For
     * queues, we can almost always remove a node immediately in O(1)
     * time (modulo retries for consistency checks) when it is
     * cancelled. But if it may be pinned as the current tail, it must
     * wait until some subsequent cancellation. For stacks, we need a
     * potentially O(n) traversal to be sure that we can remove the
     * node, but this can run concurrently with other threads
     * accessing the stack.
     *
     * While garbage collection takes care of most node reclamation
     * issues that otherwise complicate nonblocking algorithms, care
     * is taken to "forget" references to data, other nodes, and
     * threads that might be held on to long-term by blocked
     * threads. In cases where setting to null would otherwise
     * conflict with main algorithms, this is done by changing a
     * node's link to now point to the node itself. This doesn't arise
     * much for Stack nodes (because blocked threads do not hang on to
     * old head pointers), but references in Queue nodes must be
     * aggressively forgotten to avoid reachability of everything any
     * node has ever referred to since arrival.
     */

    /**
     * Shared internal API for dual stacks and queues.
     */
    abstract static class Transferer<E> {
        /**
         * Performs a put or take.
         *
         * @param e if non-null, the item to be handed to a consumer;
         *          if null, requests that transfer return an item
         *          offered by producer.
         * @param timed if this operation should timeout
         * @param nanos the timeout, in nanoseconds
         * @return if non-null, the item provided or received; if null,
         *         the operation failed due to timeout or interrupt --
         *         the caller can distinguish which of these occurred
         *         by checking Thread.interrupted.
         */
        abstract E transfer(E e, boolean timed, long nanos);
    }

    /** The number of CPUs, for spin control */
    static final int NCPUS = Runtime.getRuntime().availableProcessors();

    /**
     * The number of times to spin before blocking in timed waits.
     * The value is empirically derived -- it works well across a
     * variety of processors and OSes. Empirically, the best value
     * seems not to vary with number of CPUs (beyond 2) so is just
     * a constant.
     */
    static final int maxTimedSpins = (NCPUS < 2) ? 0 : 32;

    /**
     * The number of times to spin before blocking in untimed waits.
     * This is greater than timed value because untimed waits spin
     * faster since they don't need to check times on each spin.
     */
    static final int maxUntimedSpins = maxTimedSpins * 16;

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices.
     */
    static final long spinForTimeoutThreshold = 1000L;

    /** Dual stack */
    static final class TransferStack<E> extends Transferer<E> {
        /*
         * This extends Scherer-Scott dual stack algorithm, differing,
         * among other ways, by using "covering" nodes rather than
         * bit-marked pointers: Fulfilling operations push on marker
         * nodes (with FULFILLING bit set in mode) to reserve a spot
         * to match a waiting node.
         */

        /* Modes for SNodes, ORed together in node fields */
        /** Node represents an unfulfilled consumer */
        static final int REQUEST    = 0;
        /** Node represents an unfulfilled producer */
        static final int DATA       = 1;
        /** Node is fulfilling another unfulfilled DATA or REQUEST */
        static final int FULFILLING = 2;

        /** Returns true if m has fulfilling bit set. */
        static boolean isFulfilling(int m) { return (m & FULFILLING) != 0; }

        /** Node class for TransferStacks. */
        static final class SNode {
            volatile SNode next;        // 单链表的next指针
            volatile SNode match;       // 如果交易成功，那么交易先到的node的match成员，会指向交易后到的node
            volatile Thread waiter;     // 阻塞前保存当前线程，用来被unpark
            Object item;                // data node为非null；request node为null
            int mode;                   // 有四种情况
            // Note: item and mode fields don't need to be volatile
            // since they are always written before, and read after,
            // other volatile/atomic operations.

            SNode(Object item) {
                this.item = item;
            }

            boolean casNext(SNode cmp, SNode val) {
                return cmp == next &&
                    UNSAFE.compareAndSwapObject(this, nextOffset, cmp, val);
            }

            /**
             * Tries to match node s to this node, if so, waking up thread.
             * Fulfillers call tryMatch to identify their waiters.
             * Waiters block until they have been matched.
             *
             * @param s the node to match
             * @return true if successfully matched to s
             */
            boolean tryMatch(SNode s) {
                //判断前置，如果判断前置都过不了那么没必要cas尝试了，减少cas频率
                //如果match不为null了就没必要去匹配了，因为match不为null，代表已经匹配了或者中断、超时取消了
                if (match == null &&
                    UNSAFE.compareAndSwapObject(this, matchOffset, null, s)) {
                    Thread w = waiter;
                    if (w != null) {    // waiters need at most one unpark
                        waiter = null;
                        //把被匹配节点唤醒
                        LockSupport.unpark(w);
                    }
                    return true;
                }
                //match==s代表有其他线程帮我匹配完了
                //match!=s说明被匹配节点因中断或超时被取消了，此时match的值为this（被匹配节点）而s为匹配节点
                return match == s;
            }

            /**
             * Tries to cancel a wait by matching node to itself.
             */
            void tryCancel() {
                UNSAFE.compareAndSwapObject(this, matchOffset, null, this);
            }

            boolean isCancelled() {
                return match == this;
            }

            // Unsafe mechanics
            private static final sun.misc.Unsafe UNSAFE;
            private static final long matchOffset;
            private static final long nextOffset;

            static {
                try {
                    UNSAFE = sun.misc.Unsafe.getUnsafe();
                    Class<?> k = SNode.class;
                    matchOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("match"));
                    nextOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("next"));
                } catch (Exception e) {
                    throw new Error(e);
                }
            }
        }

        /** The head (top) of the stack */
        volatile SNode head;     // 因为是栈结构，所以只需要用一个指针来保存栈顶

        boolean casHead(SNode h, SNode nh) {
            return h == head &&
                UNSAFE.compareAndSwapObject(this, headOffset, h, nh);
        }

        /**
         * Creates or resets fields of a node. Called only from transfer
         * where the node to push on stack is lazily created and
         * reused when possible to help reduce intervals between reads
         * and CASes of head and to avoid surges of garbage when CASes
         * to push nodes fail due to contention.
         */
        static SNode snode(SNode s, Object e, SNode next, int mode) {
            if (s == null) s = new SNode(e);
            s.mode = mode;
            s.next = next;
            return s;
        }

        /**
         * Puts or takes an item.
         */
        @SuppressWarnings("unchecked")
        E transfer(E e, boolean timed, long nanos) {
            /*
             * Basic algorithm is to loop trying one of three actions:
             *
             * 1. If apparently empty or already containing nodes of same
             *    mode, try to push node on stack and wait for a match,
             *    returning it, or null if cancelled.
             *
             * 2. If apparently containing node of complementary mode,
             *    try to push a fulfilling node on to stack, match
             *    with corresponding waiting node, pop both from
             *    stack, and return matched item. The matching or
             *    unlinking might not actually be necessary because of
             *    other threads performing action 3:
             *
             * 3. If top of stack already holds another fulfilling node,
             *    help it out by doing its match and/or pop
             *    operations, and then continue. The code for helping
             *    is essentially the same as for fulfilling, except
             *    that it doesn't return the item.
             */

            SNode s = null; // constructed/reused as needed
            int mode = (e == null) ? REQUEST : DATA;

            for (;;) {
                SNode h = head;
                // 队列为空或队首模式相同（这说明队列中只有模式相同的节点）
                // 注意，h.mode == mode已经排除掉了两种fulfilling node（data的、或request的）
                if (h == null || h.mode == mode) {  // empty or same-mode
                    //如果是计时操作，且传入时间参数<=0（一次尝试）
                    if (timed && nanos <= 0) {
                        if (h != null && h.isCancelled())
                            casHead(h, h.next);     // 通过修改head出队取消节点
                        else
                            return null;
                        //1. 如果不是计时操作（同步操作）
                        //2. 如果是计时操作，且传入时间参数>0（同步带超时操作）
                    } else if (casHead(h, s = snode(s, e, h, mode))) {
                        SNode m = awaitFulfill(s, timed, nanos);//返回的是s的匹配对象
                        if (m == s) {               // 如果匹配对象指向自身，说明当前线程被取消（中断或超时）
                            clean(s);               // 尝试将s从栈中移除
                            return null;
                        }
                        // 如果匹配对象不是指向自身，说明当前线程等到了匹配对象的到来
                        if ((h = head) != null && h.next == s)
                            casHead(h, s.next);     // 将匹配的一对节点出队
                        return (E) ((mode == REQUEST) ? m.item : s.item);
                    }
                    //队首为相反模式节点、或两种fulfilling node

                    //如果不是fulfilling node，那么队首为相反模式节点
                } else if (!isFulfilling(h.mode)) { // 那么进行配对
                    if (h.isCancelled())            // 如果是取消节点
                        casHead(h, h.next);         // 移除取消节点
                    //创建一个状态位为fulfill的节点，并且把head指向这个节点
                    else if (casHead(h, s=snode(s, e, h, FULFILLING|mode))) {
                        for (;;) { // loop until matched or waiters disappear
                            SNode m = s.next;       // m is s's match

                            //被匹配节点被中断或超时，导致节点被取消了，并且
                            //被匹配节点是最后一个节点，此时栈中只有匹配节点
                            if (m == null) {        // 如果发现没有匹配对象
                                casHead(s, null);   // 清空整个栈
                                s = null;           // 清空s因为旧s的成员不对
                                break;              // 下一次循环将作为普通节点再入队
                            }
                            SNode mn = m.next; //链表结构s -> m -> mn
                            if (m.tryMatch(s)) {//匹配成功
                                casHead(s, mn);     // 将匹配的一对节点出队
                                return (E) ((mode == REQUEST) ? m.item : s.item);
                            } else                  // 如果m是个取消节点
                                s.casNext(m, mn);   // 移除这个取消节点
                        }
                    }
                    //剩下的情况都是fulfilling node
                    //此时头节点正在进行匹配，这里进行的操作就是帮助他们进行匹配
                } else {                            // 那么帮忙出队匹配的一对
                    SNode m = h.next;               // m is h's match
                    //被匹配节点被中断或超时，导致节点被取消了，并且
                    //被匹配节点是最后一个节点，此时栈中只有匹配节点
                    if (m == null)                   // 如果发现没有匹配对象
                        casHead(h, null);           // 清空整个栈
                    else {
                        SNode mn = m.next;
                        if (m.tryMatch(h))          // help match
                            casHead(h, mn);         // 将匹配的一对节点出队
                        else                        // 说明m是个取消节点
                            h.casNext(m, mn);       // 移除这个取消节点
                    }
                }
            }
        }

        /**
         * Spins/blocks until node s is matched by a fulfill operation.
         *
         * @param s the waiting node
         * @param timed true if timed wait
         * @param nanos timeout value
         * @return matched node, or s if cancelled
         */
        SNode awaitFulfill(SNode s, boolean timed, long nanos) {//s肯定是当前线程创建的节点
            /*
             * When a node/thread is about to block, it sets its waiter
             * field and then rechecks state at least one more time
             * before actually parking, thus covering race vs
             * fulfiller noticing that waiter is non-null so should be
             * woken.
             *
             * When invoked by nodes that appear at the point of call
             * to be at the head of the stack, calls to park are
             * preceded by spins to avoid blocking when producers and
             * consumers are arriving very close in time.  This can
             * happen enough to bother only on multiprocessors.
             *
             * The order of checks for returning out of main loop
             * reflects fact that interrupts have precedence over
             * normal returns, which have precedence over
             * timeouts. (So, on timeout, one last check for match is
             * done before giving up.) Except that calls from untimed
             * SynchronousQueue.{poll/offer} don't check interrupts
             * and don't wait at all, so are trapped in transfer
             * method rather than calling awaitFulfill.
             */
            final long deadline = timed ? System.nanoTime() + nanos : 0L;
            Thread w = Thread.currentThread();
            //计算出自旋次数
            int spins = (shouldSpin(s) ?//当head还是s，或head是一个fulfiller节点时，shouldSpin返回true
                         (timed ? maxTimedSpins : maxUntimedSpins) : 0);
            for (;;) {
                if (w.isInterrupted())//如果被中断，则取消自身
                    s.tryCancel();
                SNode m = s.match;
                //如果是被取消，则返回节点自身
                //如果匹配成功，返回的是匹配对方的节点
                if (m != null)
                    return m;//有可能是正常被匹配到了，也有可能因为自身被取消。
                if (timed) {// 如果是超时操作
                    nanos = deadline - System.nanoTime();
                    if (nanos <= 0L) {//如果剩余时间没有了（超时）
                        s.tryCancel();//取消自身
                        continue;//下一次循环退出函数
                    }
                }
                if (spins > 0)//如果自旋次数还有，那么减一。如果发现没必要自旋了，那么归零
                    spins = shouldSpin(s) ? (spins-1) : 0;
                else if (s.waiter == null)//自旋次数为0后，这次循环先放置当前线程
                    s.waiter = w; // establish waiter so can park next iter

                    //自旋次数为0，且当前线程已经放置好了
                else if (!timed)//如果是非超时操作，无限阻塞
                    LockSupport.park(this);
                else if (nanos > spinForTimeoutThreshold)//如果是超时操作，执行超时版本阻塞
                    LockSupport.parkNanos(this, nanos);
                //如果是超时操作，但时间太短，那么即使spins为0，也不停自旋直到时间消耗完
            }
        }

        /**
         * Returns true if node s is at head or there is an active
         * fulfiller.
         */
        boolean shouldSpin(SNode s) {
            SNode h = head;
            //h==s 说明当前线程还在栈顶，可以继续自选等待
            //中间的h == null只是严谨的防止空指针异常
            //如果h是一个fulfill节点，说明有可能是s正在和h进行匹配，可以继续自选等待
            //如果h！=s，且h也不是fulfill节点，那么就没必要等待了，因为要匹配也是先和h进行匹配
            return (h == s || h == null || isFulfilling(h.mode));
        }

        /**
         * Unlinks s from the stack.
         */
        void clean(SNode s) {//s肯定是个取消节点
            s.item = null;   // forget item
            s.waiter = null; // forget thread

            /*
             * At worst we may need to traverse entire stack to unlink
             * s. If there are multiple concurrent calls to clean, we
             * might not see s if another thread has already removed
             * it. But we can stop when we see any node known to
             * follow s. We use s.next unless it too is cancelled, in
             * which case we try the node one past. We don't check any
             * further because we don't want to doubly traverse just to
             * find sentinel.
             */

            SNode past = s.next;//找到一个s的后继
            if (past != null && past.isCancelled())//如果后继是取消节点
                past = past.next;//则获得后继的后继

            // Absorb cancelled nodes at head
            // 从head开始清除取消节点，直到遇到非取消节点或者past
            SNode p;
            while ((p = head) != null && p != past && p.isCancelled())
                casHead(p, p.next);
            //结束循环时，p可能为：
            //1. null
            //2. past（这说明past之前都是取消节点）
            //3. 第一个非取消节点

            // Unsplice embedded nodes
            // 这个循环遇到past就停止，过程中一直在移除取消节点，当遇到past说明s已经被移除了
            // 注意这个循环只能清除p以后的取消节点，p本身如果是取消节点则无能为力
            while (p != null && p != past) {
                SNode n = p.next;
                if (n != null && n.isCancelled())
                    p.casNext(n, n.next);
                else
                    p = n;
            }
        }

        // Unsafe mechanics
        private static final sun.misc.Unsafe UNSAFE;
        private static final long headOffset;
        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> k = TransferStack.class;
                headOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("head"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /** Dual Queue */
    static final class TransferQueue<E> extends Transferer<E> {
        /*
         * This extends Scherer-Scott dual queue algorithm, differing,
         * among other ways, by using modes within nodes rather than
         * marked pointers. The algorithm is a little simpler than
         * that for stacks because fulfillers do not need explicit
         * nodes, and matching is done by CAS'ing QNode.item field
         * from non-null to null (for put) or vice versa (for take).
         */

        /** Node class for TransferQueue. */
        static final class QNode {
            volatile QNode next;          // next node in queue // 单链表的next指针
            // 创建的是request node，为null；创建的是data node，为非null
            volatile Object item;         // CAS'ed to or from null
            // 阻塞前保存当前线程，用来被unpark
            volatile Thread waiter;       // to control park/unpark
            // 两种模式，request node或data node
            final boolean isData;

            QNode(Object item, boolean isData) {
                this.item = item;
                this.isData = isData;
            }

            boolean casNext(QNode cmp, QNode val) {
                return next == cmp &&
                    UNSAFE.compareAndSwapObject(this, nextOffset, cmp, val);
            }

            boolean casItem(Object cmp, Object val) {
                return item == cmp &&
                    UNSAFE.compareAndSwapObject(this, itemOffset, cmp, val);
            }

            /**
             * Tries to cancel by CAS'ing ref to this as item.
             */
            void tryCancel(Object cmp) {
                UNSAFE.compareAndSwapObject(this, itemOffset, cmp, this);
            }

            boolean isCancelled() {
                return item == this;
            }

            /**
             * Returns true if this node is known to be off the queue
             * because its next pointer has been forgotten due to
             * an advanceHead operation.
             */
            boolean isOffList() {
                return next == this;
            }

            // Unsafe mechanics
            private static final sun.misc.Unsafe UNSAFE;
            private static final long itemOffset;
            private static final long nextOffset;

            static {
                try {
                    UNSAFE = sun.misc.Unsafe.getUnsafe();
                    Class<?> k = QNode.class;
                    itemOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("item"));
                    nextOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("next"));
                } catch (Exception e) {
                    throw new Error(e);
                }
            }
        }

        /** Head of queue */
        transient volatile QNode head;
        /** Tail of queue */
        transient volatile QNode tail;
        /**
         * Reference to a cancelled node that might not yet have been
         * unlinked from queue because it was the last inserted node
         * when it was cancelled.
         */
        //clean方法中使用，用来保存 需要延后移除的节点的前驱
        transient volatile QNode cleanMe;

        TransferQueue() {
            QNode h = new QNode(null, false); // initialize to dummy node.
            head = h;
            tail = h;
        }

        /**
         * Tries to cas nh as new head; if successful, unlink
         * old head's next node to avoid garbage retention.
         */
        void advanceHead(QNode h, QNode nh) {
            if (h == head &&
                UNSAFE.compareAndSwapObject(this, headOffset, h, nh))
                h.next = h; // forget old next
        }

        /**
         * Tries to cas nt as new tail.
         */
        void advanceTail(QNode t, QNode nt) {
            if (tail == t)
                UNSAFE.compareAndSwapObject(this, tailOffset, t, nt);
        }

        /**
         * Tries to CAS cleanMe slot.
         */
        boolean casCleanMe(QNode cmp, QNode val) {
            return cleanMe == cmp &&
                UNSAFE.compareAndSwapObject(this, cleanMeOffset, cmp, val);
        }

        /**
         * Puts or takes an item.
         */
        @SuppressWarnings("unchecked")
        E transfer(E e, boolean timed, long nanos) {
            /* Basic algorithm is to loop trying to take either of
             * two actions:
             *
             * 1. If queue apparently empty or holding same-mode nodes,
             *    try to add node to queue of waiters, wait to be
             *    fulfilled (or cancelled) and return matching item.
             *
             * 2. If queue apparently contains waiting items, and this
             *    call is of complementary mode, try to fulfill by CAS'ing
             *    item field of waiting node and dequeuing it, and then
             *    returning matching item.
             *
             * In each case, along the way, check for and try to help
             * advance head and tail on behalf of other stalled/slow
             * threads.
             *
             * The loop starts off with a null check guarding against
             * seeing uninitialized head or tail values. This never
             * happens in current SynchronousQueue, but could if
             * callers held non-volatile/final ref to the
             * transferer. The check is here anyway because it places
             * null checks at top of loop, which is usually faster
             * than having them implicitly interspersed.
             */

            QNode s = null; // constructed/reused as needed
            boolean isData = (e != null);

            for (;;) {
                QNode t = tail;
                QNode h = head;
                // 如果看到了队列未初始化的值,  继续循环
                if (t == null || h == null)         // saw uninitialized value
                    continue;                       // spin

                // 因为有dummy node，所以h == t代表队列空；队尾是相同模式
                if (h == t || t.isData == isData) { // empty or same-mode
                    QNode tn = t.next;
                    // 发现tail已经更新，那么继续循环
                    if (t != tail)                  // inconsistent read
                        continue;                   // 这里如果不继续，接下来的t.casNext(null, s)也会失败的
                    // 虽然t为tail但它却有后继，说明遇到了别的线程添加节点后还没
                    if (tn != null) {               // lagging tail
                        advanceTail(t, tn);         // 更新tail的中间状态。帮忙更新tail。
                        continue;
                    }
                    // 如果是一次尝试版本，且发现队尾都是相同模式
                    if (timed && nanos <= 0)        // can't wait
                        // 说明尝试失败，返回null
                        return null;
                    // 节点只创建一次
                    if (s == null)
                        s = new QNode(e, isData);
                    // 入队失败，被别的线程抢先了
                    if (!t.casNext(null, s))        // failed to link in
                        continue;

                    //入队成功 更新tail为最新入队节点
                    advanceTail(t, s);              // swing tail and wait
                    Object x = awaitFulfill(s, e, timed, nanos);

                    // 当前线程的节点被取消而唤醒（中断或超时）
                    if (x == s) {                   // wait was cancelled
                        clean(t, s);
                        return null;
                    }

                    // 当前线程的节点是正常匹配而唤醒
                    if (!s.isOffList()) {           // not already unlinked // 如果s还没有自链接
                        advanceHead(t, s);          // unlink if head // 使head从t变成s
                        if (x != null)              // and forget fields // 如果awaitFulfill返回非null，说明匹配对方是生产者
                            s.item = s;             // s节点为消费者，但s的item已变成非null，所以要释放掉这个引用
                        s.waiter = null;             // 线程对象的引用一定要释放
                    }
                    return (x != null) ? (E)x : e;

                    // 队尾是相反模式
                } else {                            // complementary-mode
                    QNode m = h.next;               // node to fulfill // 如果队尾是相反模式，说明head后继也是相反模式
                    if (t != tail || m == null || h != head)           // 队列结构已经和刚才看到的不一样了
                        continue;                   // inconsistent read

                    Object x = m.item;
                    if (isData == (x != null) ||    // 参数isData与匹配对方节点的item域情况相同，说明对方已经被匹配
                        x == m ||                   // item域指向自身，说明匹配对方节点被取消
                        !m.casItem(x, e)) {         // 修改匹配对方节点的item域失败，说明对方已经 被匹配或取消
                        advanceHead(h, m);          // 由于真正节点在head之后，所以head移动到m相当于出队m
                        continue;                   // 以上三种情况，m节点都不能被当前线程匹配了，需要继续循环
                    }

                    //执行到这里，说明上面的m.casItem(x, e)成功了，即配对成功
                    advanceHead(h, m);              // successfully fulfilled
                    LockSupport.unpark(m.waiter);   // 唤醒交易先到的一方
                    return (x != null) ? (E)x : e;
                }
            }
        }

        /**
         * Spins/blocks until node s is fulfilled.
         *
         * @param s the waiting node
         * @param e the comparison value for checking match
         * @param timed true if timed wait
         * @param nanos timeout value
         * @return matched item, or s if cancelled
         */
        Object awaitFulfill(QNode s, E e, boolean timed, long nanos) {
            /* Same idea as TransferStack.awaitFulfill */
            final long deadline = timed ? System.nanoTime() + nanos : 0L;
            Thread w = Thread.currentThread();
            int spins = ((head.next == s) ?
                         (timed ? maxTimedSpins : maxUntimedSpins) : 0);
            for (;;) {
                if (w.isInterrupted())
                    s.tryCancel(e);//尝试把item域从初始的e变成this
                Object x = s.item;
                if (x != e)//不管是因为取消，还是因为正常匹配，这个都成立
                    //如果是正常匹配，x肯定与e相反（这里指null 和 非null 相反）
                    return x;
                if (timed) {
                    nanos = deadline - System.nanoTime();
                    if (nanos <= 0L) {
                        s.tryCancel(e);
                        continue;
                    }
                }
                if (spins > 0)//这里不用再检查head.next == s了，因为这是队列结构，后来的节点肯定在s的后面
                    --spins;
                else if (s.waiter == null)
                    s.waiter = w;
                else if (!timed)
                    LockSupport.park(this);
                else if (nanos > spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanos);
            }
        }

        /**
         * Gets rid of cancelled node s with original predecessor pred.
         */
        //此函数尝试从队列中移除s节点，但如果s是tail，此次调用函数不会执行移除动作，下次一定。
        void clean(QNode pred, QNode s) {
            s.waiter = null; // forget thread
            /*
             * At any given time, exactly one node on list cannot be
             * deleted -- the last inserted node. To accommodate this,
             * if we cannot delete s, we save its predecessor as
             * "cleanMe", deleting the previously saved version
             * first. At least one of node s or the node previously
             * saved can always be deleted, so this always terminates.
             */
            while (pred.next == s) { // Return early if already unlinked
                QNode h = head;
                QNode hn = h.next;   // 从head开始吸收后面的取消节点
                if (hn != null && hn.isCancelled()) {
                    advanceHead(h, hn);
                    continue;
                }
                QNode t = tail;      // 在使用前才读取tail，尽量保证读到最新
                if (t == h)          // 如果head == tail，那么队列为空
                    return;
                QNode tn = t.next;
                if (t != tail)       // 发现tail已经更新
                    continue;
                if (tn != null) {    // tail没有更新，但tail有后继，这是个中间状态，帮忙更新tail
                    advanceTail(t, tn);
                    continue;
                }
                if (s != t) {        // If not tail, try to unsplice
                    QNode sn = s.next;
                    // 如果s已经出队了（next指向自身）
                    // 如果将pred -> s -> sn结构修改为 pred -> sn
                    if (sn == s || pred.casNext(s, sn))
                        // 两种情况都说明s已经出队了
                        return;
                }
                //执行到这里，说明s就是tail，这次调用函数不能删除s节点了，下次一定
                QNode dp = cleanMe;
                if (dp != null) {    // 发现cleanMe不为null，先移除cleanMe的后继
                    QNode d = dp.next; // d就是需要被移除的节点
                    QNode dn;
                    //结构为dp -> d -> dn
                    if (d == null ||               // d已经被移除
                        d == dp ||                 // d已经被出队
                        !d.isCancelled() ||        // d应该是个取消节点的，但这里不是，说明已经被移除了
                        (d != t &&                  // 接下来三行检查dp -> d -> dn的结构
                         (dn = d.next) != null &&  //   has successor
                         dn != d &&                //   that is on list
                         dp.casNext(d, dn)))       // 如果符合结构，那么尝试改成dp -> dn，即移除d节点
                        casCleanMe(dp, null);   // 如果CAS成功，说明cleanMe后继移除成功

                    //一般情况下，下一次循环将执行casCleanMe(null, pred),保存好cleanMe后离开
                    if (dp == pred)     // 上面过程只是帮上一次调用擦屁股，这一次因为不能删除s节点，
                        return;         // 所以也需要保存好cleanMe再离开函数。如果发现dp就是pred，那说明cleanMe已经保存好了
                } else if (casCleanMe(null, pred))  //如果cleanMe没被占领，占领它后直接return
                    return;           // 之后再调这个函数会删除cleanMe的后继的
            }
        }

        private static final sun.misc.Unsafe UNSAFE;
        private static final long headOffset;
        private static final long tailOffset;
        private static final long cleanMeOffset;
        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> k = TransferQueue.class;
                headOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("head"));
                tailOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("tail"));
                cleanMeOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("cleanMe"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /**
     * The transferer. Set only in constructor, but cannot be declared
     * as final without further complicating serialization.  Since
     * this is accessed only at most once per public method, there
     * isn't a noticeable performance penalty for using volatile
     * instead of final here.
     */
    private transient volatile Transferer<E> transferer;

    /**
     * Creates a {@code SynchronousQueue} with nonfair access policy.
     */
    public SynchronousQueue() {
        this(false);
    }

    /**
     * Creates a {@code SynchronousQueue} with the specified fairness policy.
     *
     * @param fair if true, waiting threads contend in FIFO order for
     *        access; otherwise the order is unspecified.
     */
    public SynchronousQueue(boolean fair) {
        transferer = fair ? new TransferQueue<E>() : new TransferStack<E>();
    }

    /**
     * Adds the specified element to this queue, waiting if necessary for
     * another thread to receive it.
     *
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        if (transferer.transfer(e, false, 0) == null) {
            Thread.interrupted();
            throw new InterruptedException();
        }
    }

    /**
     * Inserts the specified element into this queue, waiting if necessary
     * up to the specified wait time for another thread to receive it.
     *
     * @return {@code true} if successful, or {@code false} if the
     *         specified waiting time elapses before a consumer appears
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean offer(E e, long timeout, TimeUnit unit)
        throws InterruptedException {
        if (e == null) throw new NullPointerException();
        if (transferer.transfer(e, true, unit.toNanos(timeout)) != null)
            return true;
        if (!Thread.interrupted())
            return false;
        throw new InterruptedException();
    }

    /**
     * Inserts the specified element into this queue, if another thread is
     * waiting to receive it.
     *
     * @param e the element to add
     * @return {@code true} if the element was added to this queue, else
     *         {@code false}
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException();
        return transferer.transfer(e, true, 0) != null;
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * for another thread to insert it.
     *
     * @return the head of this queue
     * @throws InterruptedException {@inheritDoc}
     */
    public E take() throws InterruptedException {
        E e = transferer.transfer(null, false, 0);
        if (e != null)
            return e;
        Thread.interrupted();
        throw new InterruptedException();
    }

    /**
     * Retrieves and removes the head of this queue, waiting
     * if necessary up to the specified wait time, for another thread
     * to insert it.
     *
     * @return the head of this queue, or {@code null} if the
     *         specified waiting time elapses before an element is present
     * @throws InterruptedException {@inheritDoc}
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        E e = transferer.transfer(null, true, unit.toNanos(timeout));
        if (e != null || !Thread.interrupted())
            return e;
        throw new InterruptedException();
    }

    /**
     * Retrieves and removes the head of this queue, if another thread
     * is currently making an element available.
     *
     * @return the head of this queue, or {@code null} if no
     *         element is available
     */
    public E poll() {
        return transferer.transfer(null, true, 0);
    }

    /**
     * Always returns {@code true}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @return {@code true}
     */
    public boolean isEmpty() {
        return true;
    }

    /**
     * Always returns zero.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @return zero
     */
    public int size() {
        return 0;
    }

    /**
     * Always returns zero.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @return zero
     */
    public int remainingCapacity() {
        return 0;
    }

    /**
     * Does nothing.
     * A {@code SynchronousQueue} has no internal capacity.
     */
    public void clear() {
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param o the element
     * @return {@code false}
     */
    public boolean contains(Object o) {
        return false;
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param o the element to remove
     * @return {@code false}
     */
    public boolean remove(Object o) {
        return false;
    }

    /**
     * Returns {@code false} unless the given collection is empty.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param c the collection
     * @return {@code false} unless given collection is empty
     */
    public boolean containsAll(Collection<?> c) {
        return c.isEmpty();
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param c the collection
     * @return {@code false}
     */
    public boolean removeAll(Collection<?> c) {
        return false;
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param c the collection
     * @return {@code false}
     */
    public boolean retainAll(Collection<?> c) {
        return false;
    }

    /**
     * Always returns {@code null}.
     * A {@code SynchronousQueue} does not return elements
     * unless actively waited on.
     *
     * @return {@code null}
     */
    public E peek() {
        return null;
    }

    /**
     * Returns an empty iterator in which {@code hasNext} always returns
     * {@code false}.
     *
     * @return an empty iterator
     */
    public Iterator<E> iterator() {
        return Collections.emptyIterator();
    }

    /**
     * Returns an empty spliterator in which calls to
     * {@link java.util.Spliterator#trySplit()} always return {@code null}.
     *
     * @return an empty spliterator
     * @since 1.8
     */
    public Spliterator<E> spliterator() {
        return Spliterators.emptySpliterator();
    }

    /**
     * Returns a zero-length array.
     * @return a zero-length array
     */
    public Object[] toArray() {
        return new Object[0];
    }

    /**
     * Sets the zeroeth element of the specified array to {@code null}
     * (if the array has non-zero length) and returns it.
     *
     * @param a the array
     * @return the specified array
     * @throws NullPointerException if the specified array is null
     */
    public <T> T[] toArray(T[] a) {
        if (a.length > 0)
            a[0] = null;
        return a;
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        int n = 0;
        for (E e; (e = poll()) != null;) {
            c.add(e);
            ++n;
        }
        return n;
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c, int maxElements) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        int n = 0;
        for (E e; n < maxElements && (e = poll()) != null;) {
            c.add(e);
            ++n;
        }
        return n;
    }

    /*
     * To cope with serialization strategy in the 1.5 version of
     * SynchronousQueue, we declare some unused classes and fields
     * that exist solely to enable serializability across versions.
     * These fields are never used, so are initialized only if this
     * object is ever serialized or deserialized.
     */

    @SuppressWarnings("serial")
    static class WaitQueue implements java.io.Serializable { }
    static class LifoWaitQueue extends WaitQueue {
        private static final long serialVersionUID = -3633113410248163686L;
    }
    static class FifoWaitQueue extends WaitQueue {
        private static final long serialVersionUID = -3623113410248163686L;
    }
    private ReentrantLock qlock;
    private WaitQueue waitingProducers;
    private WaitQueue waitingConsumers;

    /**
     * Saves this queue to a stream (that is, serializes it).
     * @param s the stream
     * @throws java.io.IOException if an I/O error occurs
     */
    private void writeObject(java.io.ObjectOutputStream s)
        throws java.io.IOException {
        boolean fair = transferer instanceof TransferQueue;
        if (fair) {
            qlock = new ReentrantLock(true);
            waitingProducers = new FifoWaitQueue();
            waitingConsumers = new FifoWaitQueue();
        }
        else {
            qlock = new ReentrantLock();
            waitingProducers = new LifoWaitQueue();
            waitingConsumers = new LifoWaitQueue();
        }
        s.defaultWriteObject();
    }

    /**
     * Reconstitutes this queue from a stream (that is, deserializes it).
     * @param s the stream
     * @throws ClassNotFoundException if the class of a serialized object
     *         could not be found
     * @throws java.io.IOException if an I/O error occurs
     */
    private void readObject(java.io.ObjectInputStream s)
        throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();
        if (waitingProducers instanceof FifoWaitQueue)
            transferer = new TransferQueue<E>();
        else
            transferer = new TransferStack<E>();
    }

    // Unsafe mechanics
    static long objectFieldOffset(sun.misc.Unsafe UNSAFE,
                                  String field, Class<?> klazz) {
        try {
            return UNSAFE.objectFieldOffset(klazz.getDeclaredField(field));
        } catch (NoSuchFieldException e) {
            // Convert Exception to corresponding Error
            NoSuchFieldError error = new NoSuchFieldError(field);
            error.initCause(e);
            throw error;
        }
    }

}
