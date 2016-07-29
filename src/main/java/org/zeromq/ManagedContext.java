package org.zeromq;

import zmq.Ctx;
import zmq.SocketBase;
import zmq.ZMQ;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

// This is to avoid people trying to initialize a Context
/**
 * 该类为避免用户试图初始化一个context
 * 
 * @since 1.0.0
 * @version $Id$
 */
class ManagedContext
{
    static {
        // Release ManagedSocket resources when catching SIGINT
        // 释放managedSocket资源当捕获SIGINT
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run()
            {
                getInstance().close();
            }
        });
    }

    private final Lock lock;
    private final Ctx ctx;
    private final Set<SocketBase> sockets;

    private ManagedContext()
    {
        this.ctx = ZMQ.init(ZMQ.ZMQ_IO_THREADS_DFLT);
        this.lock = new ReentrantLock();
        this.sockets = new HashSet<SocketBase>();
    }

    static ManagedContext getInstance()
    {
        return ContextHolder.INSTANCE;
    }

    SocketBase createSocket(int type)
    {
        final SocketBase base = ctx.createSocket(type);
        lock.lock();
        try {
            sockets.add(base);
        }
        finally {
            lock.unlock();
        }
        return base;
    }

    void destroy(SocketBase socketBase)
    {
        try {
            socketBase.setSocketOpt(ZMQ.ZMQ_LINGER, 0);
            socketBase.close();
        }
        catch (Exception e) {
        }
        lock.lock();
        try {
            sockets.remove(socketBase);
        }
        finally {
            lock.unlock();
        }
    }

    /*
     * This should only be called when SIGINT is received
     * 该方法执行为接收到SIGINT消息
     */
    private void close()
    {
        lock.lock();
        try {
            for (SocketBase s : sockets) {
                try {
                    s.setSocketOpt(ZMQ.ZMQ_LINGER, 0);
                    s.close();
                }
                catch (Exception ignore) {
                }
            }
            sockets.clear();
        }
        finally {
            lock.unlock();
        }
    }

    // Lazy singleton pattern to avoid double lock checking
    // lazy单例模式避免双重检查锁定
    private static class ContextHolder
    {
        private static final ManagedContext INSTANCE = new ManagedContext();
    }
}
