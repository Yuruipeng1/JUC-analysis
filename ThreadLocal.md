## 1.ThreadLocal是什么？

ThreadLocal用来给各个线程提供线程隔离的局部变量。使用很简单，通过调用同一个ThreadLocal对象的get/set方法来读写这个ThreadLocal对象对应的value，但是线程A set值后，不会影响到线程B之后get到的值。
ThreadLocal对象通常是static的，因为它在map里作为key使用，所以在各个线程中需要复用

## 2.Thread，ThreadLocal，ThreadLocalMap这三者是什么关系？

每一个Thread对象都有一个线程私有的ThreadLocalMap，ThreadLocalMap中存的是<ThreadLocal,value>这样的键值对。当调用ThreadLocal的获取值的方法时，是从当前线程的ThreadLocalMap中找到对应的key再获取值，所以用它来获取值是可以保证线程安全的。



## 3.简述ThreadLocal的实现原理

每个线程在运行过程中都可以通过Thread.currentThread()获得与之对应的Thread对象，而每个Thread对象都有一个ThreadLocalMap类型的成员，ThreadLocalMap是一种hashmap，它以ThreadLocal作为key。
所以，只有通过Thread对象和ThreadLocal对象二者，才可以唯一确定到一个value上去。线程隔离的关键，正是因为这种对应关系用到了Thread对象。
线程可以根据自己的需要往ThreadLocalMap里增加键值对，当线程从来没有使用到ThreadLocal时（指调用get/set方法），Thread对象不会初始化这个ThreadLocalMap类型的成员。



## 4.简述ThreadLocalMap这个Map

它是一种特殊实现的HashMap实现，它必须以ThreadLocal类型作为key。
容量必为2的幂，使得它，可通过 位与操作得到数组下标。
在解决哈希冲突时，使用开放寻址法（索引往后移动一位）和环形数组（索引移动到length时，跳转到0）。这样，只有size到达threshold时，才会resize操作。

## 5.ThreadLocal的哈希值是怎么计算的？

利用魔数0x61c88647从0递增，得到每个ThreadLocal对象的哈希值。
两个线程同时构造ThreadLocal对象，也能保证它俩的哈希值不同，因为利用了AtomicInteger。
利用魔数0x61c88647的好处在于，这样得到的哈希值再取模得到下标，下标是均匀分布的。而这又可能带了另一个好处：当哈希冲突时，大概率能更快找到可以放置的位置。

## 6.为什么Entry继承了WeakReference？

WeakReference指向的对象如果没有强引用类型引用它，当发生垃圾回收时，这个对象就会被回收。在ThreadLocal中，这个对象作为key被回收值为null了，但是value还不为null，整体Entry（这种Entry成为stale entry）还不能回收。这时候get/set操作中判断key为null进行整体Entry的回收。

所以，继承WeakReference的原因是为了能更快回收资源，但前提是：
没有强引用指向ThreadLocal对象。
且jvm执行了gc，回收了ThreadLocal对象，出现了stale entry。
且之后get/set操作的间接调用刚好清理掉了这个stale entry。

综上得知，要想通过WeakReference来获得更快回收资源的好处，其实比较难。所以，当你知道当前线程已经不会使用这个ThreadLocal对应的值时，显式调用remove将是最佳选择



## 7.Entry继承了WeakReference会导致内存泄漏吗？

首先要知道，这一点并不是WeakReference的锅。
一般情况下，ThreadLocal对象都会设置成static域，它的生命周期通常和一个类一样长。
当一个线程不再使用ThreadLocal读写值后，如果不调动remove，这个线程针对该ThreadLocal设置的value对象就已经内存泄漏了。且由于ThreadLocal对象生命周期一般很长，现在Entry对象、它的referent成员、它的value成员三者都内存泄漏。
而Entry继承了WeakReference，反而降低了内存泄漏的可能性（见上一问题）。
综上得知，内存泄漏不是因为继承了WeakReference，而且因为ThreadLocal对象生命周期一般很长，且使用完毕ThreadLocal后，线程没有主动调用remove。

在get/set方法中当发现key==null时，它是会对这个Entry进行回收，所以不会因为weakReference而导致内存泄漏。



## 8.线程池中的线程使用ThreadLocal需要注意什么？

由于ThreadLocalMap是Thread对象的成员，当对应线程运行结束销毁时，自然这个ThreadLocalMap类型的成员也会被回收。因为线程池里的线程为了复用线程，一般不会直接销毁掉完成了任务的线程，以下一次复用。

所以，线程使用完毕ThreadLocal后，应该主动调用`remove`来避免内存泄漏。

另外，线程池中的线程使用完毕ThreadLocal后，不主动调用`remove`，还可能造成：get值时，get到上一个任务set的值，直接造成程序错误。

## 9.使用ThreadLocal有什么好处？

相比synchronized使用锁从而使得多个线程可以安全的访问同一个共享变量，现在可以直接转换思路，让线程使用自己的私有变量，直接避免了并发访问的问题。
当一个数据需要传递给某个方法，而这个方法处于方法调用链的中间部分，那么如果采用加参数传递的方式，势必为影响到耦合性。而如果使用ThreadLocal来为线程保存这个数据，就避免了这个问题，而且对于这个线程，它在任何时候都可以取到这个值。
