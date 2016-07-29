/*
    Copyright (c) 2007-2014 Contributors as noted in the AUTHORS file

    This file is part of 0MQ.

    0MQ is free software; you can redistribute it and/or modify it under
    the terms of the GNU Lesser General Public License as published by
    the Free Software Foundation; either version 3 of the License, or
    (at your option) any later version.

    0MQ is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.zeromq;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.zeromq.ZMQ.Context;
import org.zeromq.ZMQ.Socket;

import zmq.ZError;

/**
 * 
 * ZContext 提供高等级的ZeroMQ上下文管理类
 *
 * 
 * 它用于在上下文中管理开放式sockets,在终结上下文前关闭它
 * 它提供了一种简单的方式设置sockets的超时,为I/O线程配置上下文
 * 通过设置信号处理这些过程
 *
 */

public class ZContext implements Closeable
{
    /**
     * 
     * 参考下面的Context对象
     */
    private volatile Context context;

    /**
     * 
     * ZContext管理的一些sockets
     */
    private List<Socket> sockets;

    /**
     * 指派给context的io线程数,默认为1
     * 
     */
    private int ioThreads;

    /**
     * Linger 超时设置,默认为0
     */
    private int linger;

    /**
     * HWM 超时设置,默认为1
     */
    private int hwm;

    /**
     *
     * 指示上下文是否为main线程所有(多线程中有用)
     * 
     */
    private boolean main;

    /**
     * Class Constructor
     */
    public ZContext()
    {
        this(1);
    }

    public ZContext(int ioThreads)
    {
        sockets = new CopyOnWriteArrayList<Socket>();
        this.ioThreads = ioThreads;
        linger = 0;
        main = true;
    }

    /**
     * 析构函数,通过这个优雅的终结上下文,同时关闭管理的sockets
     */
    public void destroy()
    {
        for (Socket socket : sockets) {
            try {
                socket.setLinger(linger);
            }
            catch (ZError.CtxTerminatedException e) {
            }
            socket.close();
        }
        sockets.clear();

        // 只有是main线程时,才终结上下文.
        if (isMain() && context != null) {
            context.term();
        }

        context = null;
    }

    /**
     * 
     * 创建一个新的管理socket里面保存了context对象
     * 同时将socket注册到ZContext中,用于关闭
     * @param type
     *          socket type (see ZMQ static class members)
     * @return
     *          Newly created Socket object
     */
    public Socket createSocket(int type)
    {
        // 创建和注册socket
        Socket socket = getContext().socket(type);
        sockets.add(socket);
        return socket;
    }

    /**
     * 
     * 在上下文中销毁管理的socket,同时在list中移除
     * @param s
     *          org.zeromq.Socket object to destroy
     */
    public void destroySocket(Socket s)
    {
        if (s == null) {
            return;
        }

        if (sockets.remove(s)) {
            try {
                s.setLinger(linger);
            }
            catch (ZError.CtxTerminatedException e) {
            }
            s.close();
        }
    }

    /**
     * 
     * 创建一个隐藏的context,共享相同的context对象,
     * 但是它有自己管理的sockets和io线程等.
     * 
     * @param ctx   Original ZContext to create shadow of
     * @return  New ZContext
     */
    public static ZContext shadow(ZContext ctx)
    {
        ZContext shadow = new ZContext();
        shadow.setContext(ctx.getContext());
        shadow.setMain(false);

        return shadow;
    }

    /**
     * @return the ioThreads
     */
    public int getIoThreads()
    {
        return ioThreads;
    }

    /**
     * @param ioThreads the ioThreads to set
     */
    public void setIoThreads(int ioThreads)
    {
        this.ioThreads = ioThreads;
    }

    /**
     * @return the linger
     */
    public int getLinger()
    {
        return linger;
    }

    /**
     * @param linger the linger to set
     */
    public void setLinger(int linger)
    {
        this.linger = linger;
    }

    /**
     * @return the HWM
     */
    public int getHWM()
    {
        return hwm;
    }

    /**
     * @param hwm the HWM to set
     */
    public void setHWM(int hwm)
    {
        this.hwm = hwm;
    }

    /**
     * @return the main
     */
    public boolean isMain()
    {
        return main;
    }

    /**
     * @param main the main to set
     */
    public void setMain(boolean main)
    {
        this.main = main;
    }

    /**
     * @return the context
     */
    public Context getContext()
    {
        Context result = context;
        if (result == null) {
            synchronized (this) {
                result = context;
                if (result == null) {
                    result = ZMQ.context(ioThreads);
                    context = result;
                }
            }
        }
        return result;
    }

    /**
     * @param ctx   sets the underlying org.zeromq.Context associated with this ZContext wrapper object
     */
    public void setContext(Context ctx)
    {
        this.context = ctx;
    }

    /**
     * @return the sockets
     */
    public List<Socket> getSockets()
    {
        return sockets;
    }

    @Override
    public void close()
    {
        destroy();
    }
}
