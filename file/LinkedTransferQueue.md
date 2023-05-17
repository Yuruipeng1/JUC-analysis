## 1.LinkedTransferQueue是什么？

LinkedTransferQueue是一种特殊的无界阻塞队列，它提供一种Transfer的功能，用以保证生产者把数据传输给消费者。其他的普通队列，生产者是不需要关心消费者是否存在的，但现在的LinkedTransferQueue却需要保证生产者把数据确实传输给了消费者，才算是一次成功的入队操作，否则算作入队失败。

LinkedTransferQueue的队列中只可能会有生产者或者消费者. 同时LinkedTransferQueue的head和tail也是弱一致性，和ConcurrentLinkedQueue实现类似，使用松弛阈值来减小CAS的次数并且还保持`head`和`tail`的相对正确性。



## 2.LinkedTransferQueue中为什么没有使用Condition阻塞队列？

虽然可以使用一个Lock+两个Condition来实现，一个用来阻塞生产者线程，一个用来阻塞消费者线程，通过hasWaiters判断是否有没有取消掉的节点，通过getWaitingThreads获得第一个等待的线程。但这种实现对抢锁的需求很大，每一个离开AQS条件队列的线程都会转移到AQS同步队列去抢锁，总之，这种悲观锁的实现方法使得并发量大大减小。

LinkedTransferQueue中是直接把自身链表当成一个“条件队列”，它在每个节点中都设置一个Thread属性，这样先来的一方就通过这个属性调用park方法进行阻塞，后来的一方就调用unpark来唤醒先来的一方,匹配的双方通过item进行数据交互。



## 3.LinkedTransferQueue中节点进入睡眠之前，自选次数的计算

如果前驱是相反模式，s大概率是在head附近，这说明前驱已经被匹配了但还没有出队，当前节点就多自选几次，因为下一个匹配的就可能是它，自选次数为128+64， 如果前驱是已匹配节点，s大概率是在head附近，不过这种情况比上一种概率低一些，自选次数为128，如果前驱节点的thread为null，说明前驱节点正在自选，当前节点也可能在head附件，自选次数为64，如果不是以上的情况，说明前驱节点正在阻塞了，这时候就没必要自选了，自选反而浪费性能，所以自选次数为0 。