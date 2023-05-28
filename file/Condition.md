## 1.为什么ConditionObject 中的firstWaiter和lastWaiter不用volatile修饰？

firstWaiter和lastWaiter分别代表条件队列的队头和队尾。 这是因为源码作者是考虑，使用者肯定是以获得锁的前提下来调用await() / signal()这些方法的，既然有了这个前提，那么对firstWaiter的读写肯定是无竞争的，既然没有竞争也就不需要 CAS+volatile 来实现一个乐观锁了。

