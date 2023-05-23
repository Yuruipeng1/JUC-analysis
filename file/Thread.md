## Thread是如何启动线程和回调run方法

### 1.什么是native方法

java是一个跨平台的语言，用java编译的代码可以运行在任何安装了jvm的系统上。然而各个系统的底层实现肯定是有区别的，为了使java可以跨平台，于是jvm提供了叫java native interface（JNI）的机制。当java需要使用到一些系统方法时，由jvm帮我们去调用系统底层，而java本身只需要告知jvm需要做的事情，即调用某个native方法即可。

例如，当我们需要启动一个线程时，无论在哪个平台上，我们调用的都是start0方法，由jvm根据不同的操作系统，去调用相应系统底层方法，帮我们真正地启动一个线程。因此这就像是jvm为我们提供了一个可以操作系统底层方法的接口，即JNI，java本地接口。

JNI的基本使用顺序

1）在.java文件中定义native方法

2）生成相应的.h头文件（即接口）

3）编写相应的.c或.cpp文件（即实现）

4）将接口和实现链接到一起，生成动态链接库

5）在.java中引入该库，即可调用native方法



### 2.Thread是怎么创建一个线程的

首先thread对象调用start方法，在start方法中调用start0方法。

Thread对象对应的Thread.c文件中通过一个**registerNatives**方法动态注册了start0方法，

{"start0",           "()V",        (void *)&JVM_StartThread}   

并且注册到了jvm.h中，所以接着会调用到jvm.h中的JVM_StartThread

所以直接看jvm.h中的逻辑，在jvm.h中有一个JVM_StartThread方法定义。这时转到它的具体实现jvm.cpp中

在cpp中的这个方法会创建一个c++级别的线程new JavaThread(&thread_entry, sz);

顺势查看JavaThread的构造方法(在thread.cpp文件中),发现它调用了这段代码os::create_thread(this, thr_type, stack_sz);

这是创建系统线程，不同的操作系统有不同的实现，在Linux操作系统中就是调用pthread_create方法创建一个线程

由此也可以看出java线程和操作系统线程是一 一对应的。

### 3.Thread是如何回调run方法的

当在操作系统创建线程时会调用pthread_create方法创建，

pthread_create(&tid, &attr, (void* (*)(void*)) thread_native_entry, thread);

**thread_native_entry**就是系统线程所执行的方法，而**thread**则是传递给thread_native_entry的参数：

而在thread_native_entry方法中会调用thread->run();

来到JavaThread的run方法，它会调用thread_main_inner();

在thread_main_inner()方法中会调用到this->entry_point()(this, this)方法，entry_point是外部传入的方法

这个entry_point是通过JavaThread的构造方法参数传入的

而JavaThread的构造方法又是在**JVM_StartThread**方法里面调用的，并且传入的entry_point参数是**thread_entry**方法，

查看**thread_entry**，其中调用了**JavaCalls::call_virtual**去回调java级别的方法

最终会去回调方法名为run，方法参数为()V的java级别的方法

到这里就完成Thread回调run方法逻辑。

