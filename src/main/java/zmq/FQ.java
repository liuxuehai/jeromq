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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

//  Class manages a set of inbound pipes. On receive it performs fair
//  queueing so that senders gone berserk won't cause denial of
//  service for decent senders.
/**
 * 该类管理写入的pipe,这是一个公平的queue在接收数据上,
 * 不会导致
 * @since 1.0.0
 * @version $Id$
 */
class FQ
{
    //  Inbound pipes.
    private final List<Pipe> pipes;

    //  Number of active pipes. All the active pipes are located at the
    //  beginning of the pipes array.
    /**
     * active的pipe数, 
     */
    private int active;

    //  Index of the next bound pipe to read a message from.
    /**
     * 从下一个bound pipe读取消息的索引 
     */
    private int current;

    //  If true, part of a multipart message was already received, but
    //  there are following parts still waiting in the current pipe.
    /**
     * 如果为true,则部分消息已经被获取,但是还有剩余的部分还在当前pipe中
     */
    private boolean more;

    public FQ()
    {
        active = 0;
        current = 0;
        more = false;

        pipes = new ArrayList<Pipe>();
    }

    public void attach(Pipe pipe)
    {
        pipes.add(pipe);
        Collections.swap(pipes, active, pipes.size() - 1);
        active++;
    }

    public void terminated(Pipe pipe)
    {
        final int index = pipes.indexOf(pipe);

        //  Remove the pipe from the list; adjust number of active pipes
        //  accordingly.
        /**
         * 移除pipe从list,调整active pipe的数量
         */
        if (index < active) {
            active--;
            Collections.swap(pipes, index, active);
            if (current == active) {
                current = 0;
            }
        }
        pipes.remove(pipe);
    }

    public void activated(Pipe pipe)
    {
        //  Move the pipe to the list of active pipes.  移动该pipe到active pipes中
        Collections.swap(pipes, pipes.indexOf(pipe), active);
        active++;
    }

    public Msg recv(ValueReference<Integer> errno)
    {
        return recvPipe(errno, null);
    }

    public Msg recvPipe(ValueReference<Integer> errno, ValueReference<Pipe> pipe)
    {
        //  Round-robin over the pipes to get the next message.
        while (active > 0) {
            //  Try to fetch new message. If we've already read part of the message
            //  subsequent part should be immediately available.
            // 尝试去获取一个新消息,如果我们已经读取的部分消息,那么剩余的部分应该立刻可用
            Msg msg = pipes.get(current).read();
            boolean fetched = msg != null;

            //  Note that when message is not fetched, current pipe is deactivated
            //  and replaced by another active pipe. Thus we don't have to increase
            //  the 'current' pointer.
            if (fetched) {
                if (pipe != null) {
                    pipe.set(pipes.get(current));
                }
                more = msg.hasMore();
                if (!more) {
                    current = (current + 1) % active;
                }
                return msg;
            }

            //  Check the atomicity of the message.
            //  If we've already received the first part of the message
            //  we should get the remaining parts without blocking.
            assert (!more);

            active--;
            Collections.swap(pipes, current, active);
            if (current == active) {
                current = 0;
            }
        }

        //  No message is available. Initialise the output parameter
        //  to be a 0-byte message.
        errno.set(ZError.EAGAIN);
        return null;
    }

    public boolean hasIn()
    {
        //  There are subsequent parts of the partly-read message available.
        if (more) {
            return true;
        }

        //  Note that messing with current doesn't break the fairness of fair
        //  queueing algorithm. If there are no messages available current will
        //  get back to its original value. Otherwise it'll point to the first
        //  pipe holding messages, skipping only pipes with no messages available.
        while (active > 0) {
            if (pipes.get(current).checkRead()) {
                return true;
            }

            //  Deactivate the pipe.
            active--;
            Collections.swap(pipes, current, active);
            if (current == active) {
                current = 0;
            }
        }

        return false;
    }
}
