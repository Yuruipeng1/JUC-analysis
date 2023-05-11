## 1.为什么要有CopyOnWriteArrayList？

ArrayList是不安全的，因为它的add方法没有加锁。而像Vector这种保证了线程安全，但是加锁力度太大，效率太低，所以需要一个效率较高且安全的容器。

## 2.CopyOnWriteArrayList的读写分离思想

CopyOnWriteArrayList是一个写时复制的容器，在往容器中添加元素的时候，不是直接往当前容器elements添加，而是将当前容器进行拷贝，复制出来一个新的容器newElements，数据添加完成后，将原容器的引用指向新的容器setArray(newElements)。
这样做的好处是可以进行并发读，而不用加锁，因为当前容器不会添加任何元素，所以CopyOnWrite是一种读写分离的思想，读和写不是同一个容器。

## 3.CopyOnWriteArrayList的缺点

CopyOnWriteArrayList允许在写操作时来读取数据，大大提高了读的性能，因此适合读多写少的应用场景，但是当写操作比较多的情况下，会进行大量数组复制操作，CopyOnWriteArrayList会比较占用内存，同时读取到的数据可能不是实时最新的数据，所以不适合实时性要求很高的场景。

