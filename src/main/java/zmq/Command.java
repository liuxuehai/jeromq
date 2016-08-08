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
        BIND,
        //  Sent by pipe writer to inform dormant pipe reader that there
        //  are messages in the pipe.
        ACTIVATE_READ,
        //  Sent by pipe reader to inform pipe writer about how many
        //  messages it has read so far.
        ACTIVATE_WRITE,
        //  Sent by pipe reader to writer after creating a new inpipe.
        //  The parameter is actually of type pipe_t::upipe_t, however,
        //  its definition is private so we'll have to do with void*.
        HICCUP,
        //  Sent by pipe reader to pipe writer to ask it to terminate
        //  its end of the pipe.
        PIPE_TERM,
        //  Pipe writer acknowledges pipe_term command.
        PIPE_TERM_ACK,
        //  Sent by I/O object ot the socket to request the shutdown of
        //  the I/O object.
        TERM_REQ,
        //  Sent by socket to I/O object to start its shutdown.
        TERM,
        //  Sent by I/O object to the socket to acknowledge it has
        //  shut down.
        TERM_ACK,
        //  Transfers the ownership of the closed socket
        //  to the reaper thread.
        REAP,
        //  Closed socket notifies the reaper that it's already deallocated.
        REAPED,
        //  Sent by reaper thread to the term thread when all the sockets
        //  are successfully deallocated.
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
