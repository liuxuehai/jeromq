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

package zmq;

//  This structure defines the commands that can be sent between threads.
/**
 * 定义在线程间可以发送的command
 * 
 * @since 1.0.0
 * @version $Id$
 */
class Command
{
    //  Object to process the command.   处理命令的对象
    private final ZObject destination;
    private final Type type;  //命令的类型

    public enum Type {
        //  Sent to I/O thread to let it know that it should
        //  terminate itself.  
        /**
         * 发送给i/o线程让它terminate字节
         */
        STOP,
        //  Sent to I/O object to make it register with its I/O thread
        /**
         * 发送给i/o对象,让它注册到i/o线程
         */
        PLUG,
        //  Sent to socket to let it know about the newly created object.
        /*
         * 发送给socket让它直接最近创建的对象
         */
        OWN,
        //  Attach the engine to the session. If engine is NULL, it informs
        //  session that the connection have failed.
        /**
         * attach 该engine给session
         * 如果engine为null,则通知session连接失败了
         */
        ATTACH,
        //  Sent from session to socket to establish pipe(s) between them.
        //  Caller have used inc_seqnum beforehand sending the command.
        /**
         * 从session发送消息到socket去在他们之间建立连接pipe
         * 调用者事先使用 inc_seqnum 在发送命令之前
         */
        BIND,
        //  Sent by pipe writer to inform dormant pipe reader that there
        //  are messages in the pipe.
        /**
         * writer pipe发送给休眠的reader pipe通知他有消息在pipe中 
         */
        ACTIVATE_READ,
        //  Sent by pipe reader to inform pipe writer about how many
        //  messages it has read so far.
        /**
         * 从reader pipe 发送给writer pipe 通知他有多少消息已经read到目前为止
         */
        ACTIVATE_WRITE,
        //  Sent by pipe reader to writer after creating a new inpipe.
        //  The parameter is actually of type pipe_t::upipe_t, however,
        //  its definition is private so we'll have to do with void*.
        /**
         * 在创建了一个新的inpipe后,又reader pipe 发送给writer
         * 
         */
        HICCUP,
        //  Sent by pipe reader to pipe writer to ask it to terminate
        //  its end of the pipe.
        /**
         * reader pipe 发送给writer pipe 请求他去terminate 他的pipe
         */
        PIPE_TERM,
        //  Pipe writer acknowledges pipe_term command.
        /**
         * writer pipe 确认 pipe_term命令
         */
        PIPE_TERM_ACK,
        //  Sent by I/O object ot the socket to request the shutdown of
        //  the I/O object.
        /**
         * i/o 对象发送给socket 去请求关闭i/o对象
         */
        TERM_REQ,
        //  Sent by socket to I/O object to start its shutdown.
        /**
         * socket 发送给i/o对象去开始他的关闭
         */
        TERM,
        //  Sent by I/O object to the socket to acknowledge it has
        //  shut down.
        /**
         * i/o对象发送给socket去确认他已经关闭了
         */
        TERM_ACK,
        //  Transfers the ownership of the closed socket
        //  to the reaper thread.
        /**
         * 转换关闭的socket的所有权给reaper 线程
         */
        REAP,
        //  Closed socket notifies the reaper that it's already deallocated.
        /**
         * 关闭的socket通知reaper 他已经释放了
         */
        REAPED,
        //  Sent by reaper thread to the term thread when all the sockets
        //  are successfully deallocated.
        /**
         * 有reaper线程发送给term线程当所有的socket都成功的释放时。
         */
        DONE
    }

    Object arg;

    public Command(ZObject destination, Type type)
    {
        this(destination, type, null);
    }

    public Command(ZObject destination, Type type, Object arg)
    {
        this.destination = destination;
        this.type = type;
        this.arg = arg;
    }

    public ZObject destination()
    {
        return destination;
    }

    public Type type()
    {
        return type;
    }

    @Override
    public String toString()
    {
        return "Cmd" + "[" + destination + ", " + destination.getTid() + ", " + type + (arg == null ? "" : ", " + arg) + "]";
    }
}
